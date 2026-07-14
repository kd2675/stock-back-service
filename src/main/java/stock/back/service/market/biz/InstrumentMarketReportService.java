package stock.back.service.market.biz;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import stock.back.service.market.vo.InstrumentMarketReportResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class InstrumentMarketReportService {

    private final ConcurrentHashMap<ReportCacheKey, InstrumentMarketReportResponse> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ValidatedVersion> validatedVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<InstrumentMarketReportResponse>> loads =
            new ConcurrentHashMap<>();
    private final InstrumentMarketReportQueryService queryService;
    private final InstrumentMarketReportVersionQueryService versionQueryService;
    private final MeterRegistry meterRegistry;
    private final long versionTtlNanos;

    @Autowired
    public InstrumentMarketReportService(
            InstrumentMarketReportQueryService queryService,
            InstrumentMarketReportVersionQueryService versionQueryService,
            MeterRegistry meterRegistry,
            @Value("${stock.market.report.cache.version-ttl-ms:1000}") long versionTtlMs
    ) {
        this.queryService = queryService;
        this.versionQueryService = versionQueryService;
        this.meterRegistry = meterRegistry;
        this.versionTtlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(versionTtlMs, 0L));
    }

    InstrumentMarketReportService(
            InstrumentMarketReportQueryService queryService,
            InstrumentMarketReportVersionQueryService versionQueryService,
            MeterRegistry meterRegistry
    ) {
        this(queryService, versionQueryService, meterRegistry, 1000L);
    }

    public InstrumentMarketReportResponse getInstrumentMarketReport(String symbol) {
        String normalizedSymbol = MarketTextNormalizer.symbol(symbol);
        if (normalizedSymbol.isBlank()) {
            return queryService.getInstrumentMarketReport(symbol);
        }

        long now = System.nanoTime();
        ValidatedVersion validatedVersion = validatedVersions.get(normalizedSymbol);
        if (isFresh(validatedVersion, now)) {
            InstrumentMarketReportResponse cached = cache.get(validatedVersion.key());
            if (cached != null) {
                meterRegistry.counter("stock.market.report.cache.requests", "result", "hit").increment();
                return cached;
            }
        }

        CompletableFuture<InstrumentMarketReportResponse> loader = new CompletableFuture<>();
        CompletableFuture<InstrumentMarketReportResponse> existing = loads.putIfAbsent(normalizedSymbol, loader);
        if (existing != null) {
            meterRegistry.counter("stock.market.report.cache.requests", "result", "single-flight").increment();
            return await(existing);
        }

        try {
            Long closeRunId = versionQueryService.findLatestCloseRunId(normalizedSymbol);
            if (closeRunId != null) {
                ReportCacheKey key = new ReportCacheKey(normalizedSymbol, closeRunId);
                InstrumentMarketReportResponse cached = cache.get(key);
                if (cached != null) {
                    validatedVersions.put(normalizedSymbol, new ValidatedVersion(key, System.nanoTime()));
                    meterRegistry.counter("stock.market.report.cache.requests", "result", "validated-hit").increment();
                    loader.complete(cached);
                    return cached;
                }
            }

            meterRegistry.counter(
                    "stock.market.report.cache.requests",
                    "result",
                    closeRunId == null ? "bypass" : "miss"
            ).increment();
            InstrumentMarketReportResponse report = queryService.getInstrumentMarketReport(normalizedSymbol);
            if (report.closeRunId() != null) {
                ReportCacheKey actualKey = new ReportCacheKey(normalizedSymbol, report.closeRunId());
                cache.put(actualKey, report);
                validatedVersions.put(normalizedSymbol, new ValidatedVersion(actualKey, System.nanoTime()));
                removeObsoleteEntries(normalizedSymbol, report.closeRunId());
            }
            loader.complete(report);
            return report;
        } catch (RuntimeException ex) {
            loader.completeExceptionally(ex);
            throw ex;
        } finally {
            loads.remove(normalizedSymbol, loader);
        }
    }

    private boolean isFresh(ValidatedVersion version, long now) {
        return version != null && now - version.validatedAtNanos() < versionTtlNanos;
    }

    private InstrumentMarketReportResponse await(
            CompletableFuture<InstrumentMarketReportResponse> future
    ) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private void removeObsoleteEntries(String symbol, Long currentCloseRunId) {
        if (currentCloseRunId == null) {
            return;
        }
        cache.keySet().removeIf(key -> key.symbol().equals(symbol) && key.closeRunId() != currentCloseRunId);
    }

    private record ReportCacheKey(String symbol, long closeRunId) {
    }

    private record ValidatedVersion(ReportCacheKey key, long validatedAtNanos) {
    }
}
