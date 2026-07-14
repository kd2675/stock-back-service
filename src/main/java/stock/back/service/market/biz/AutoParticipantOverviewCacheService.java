package stock.back.service.market.biz;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import stock.back.service.market.vo.AutoParticipantActivityScope;
import stock.back.service.market.vo.AutoParticipantOverviewResponse;
import stock.back.service.market.vo.AutoParticipantProfileOverviewResponse;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class AutoParticipantOverviewCacheService {

    private final ConcurrentHashMap<ParticipantCacheKey, CacheEntry<List<AutoParticipantOverviewResponse>>>
            participantCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ProfileCacheKey, CacheEntry<List<AutoParticipantProfileOverviewResponse>>>
            profileCache = new ConcurrentHashMap<>();
    private final AutoParticipantOverviewQueryService participantQueryService;
    private final AutoParticipantProfileOverviewQueryService profileQueryService;
    private final MeterRegistry meterRegistry;
    private final long ttlNanos;

    public AutoParticipantOverviewCacheService(
            AutoParticipantOverviewQueryService participantQueryService,
            AutoParticipantProfileOverviewQueryService profileQueryService,
            MeterRegistry meterRegistry,
            @Value("${stock.back.auto-participant-overview-cache-ttl-ms:5000}") long ttlMillis
    ) {
        this.participantQueryService = participantQueryService;
        this.profileQueryService = profileQueryService;
        this.meterRegistry = meterRegistry;
        this.ttlNanos = Math.max(ttlMillis, 0L) * 1_000_000L;
    }

    public List<AutoParticipantOverviewResponse> getAutoParticipantOverviews(
            boolean includeHoldings,
            List<String> userKeys,
            AutoParticipantActivityScope activityScope
    ) {
        ParticipantCacheKey key = new ParticipantCacheKey(
                includeHoldings,
                normalizedValues(userKeys, false),
                effectiveScope(activityScope)
        );
        return getOrLoad(
                participantCache,
                key,
                "participant",
                () -> participantQueryService.getAutoParticipantOverviews(
                        includeHoldings,
                        key.userKeys(),
                        key.activityScope()
                )
        );
    }

    public List<AutoParticipantProfileOverviewResponse> getAutoParticipantProfileOverviews(
            AutoParticipantActivityScope activityScope,
            List<String> profileTypes
    ) {
        ProfileCacheKey key = new ProfileCacheKey(
                effectiveScope(activityScope),
                normalizedValues(profileTypes, true)
        );
        return getOrLoad(
                profileCache,
                key,
                "profile",
                () -> profileQueryService.getAutoParticipantProfileOverviews(
                        key.activityScope(),
                        key.profileTypes()
                )
        );
    }

    private <K, V> V getOrLoad(
            ConcurrentHashMap<K, CacheEntry<V>> cache,
            K key,
            String view,
            Supplier<V> loader
    ) {
        long now = System.nanoTime();
        CacheEntry<V> existing = cache.get(key);
        if (existing != null && existing.isUsable(now)) {
            record(view, existing.future().isDone() ? "hit" : "single-flight");
            return await(existing.future());
        }
        if (existing != null) {
            cache.remove(key, existing);
        }

        CacheEntry<V> created = CacheEntry.loading();
        existing = cache.putIfAbsent(key, created);
        if (existing != null) {
            record(view, existing.future().isDone() ? "hit" : "single-flight");
            return await(existing.future());
        }

        record(view, "miss");
        try {
            V value = loader.get();
            created.complete(value, ttlNanos);
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(System.nanoTime()));
            return value;
        } catch (RuntimeException ex) {
            created.future().completeExceptionally(ex);
            cache.remove(key, created);
            throw ex;
        }
    }

    private <V> V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private void record(String view, String result) {
        meterRegistry.counter(
                "stock.auto.participant.overview.cache.requests",
                "view",
                view,
                "result",
                result
        ).increment();
    }

    private static AutoParticipantActivityScope effectiveScope(AutoParticipantActivityScope activityScope) {
        return activityScope == null ? AutoParticipantActivityScope.RECENT_SIMULATION_DAY : activityScope;
    }

    private static List<String> normalizedValues(List<String> values, boolean upperCase) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> upperCase ? value.toUpperCase(Locale.ROOT) : value)
                .distinct()
                .sorted()
                .toList();
    }

    private record ParticipantCacheKey(
            boolean includeHoldings,
            List<String> userKeys,
            AutoParticipantActivityScope activityScope
    ) {
    }

    private record ProfileCacheKey(
            AutoParticipantActivityScope activityScope,
            List<String> profileTypes
    ) {
    }

    private static final class CacheEntry<V> {

        private final CompletableFuture<V> future = new CompletableFuture<>();
        private volatile long expiresAtNanos = Long.MAX_VALUE;

        private static <V> CacheEntry<V> loading() {
            return new CacheEntry<>();
        }

        private CompletableFuture<V> future() {
            return future;
        }

        private void complete(V value, long ttlNanos) {
            expiresAtNanos = System.nanoTime() + ttlNanos;
            future.complete(value);
        }

        private boolean isUsable(long now) {
            return !future.isDone() || now < expiresAtNanos;
        }

        private boolean isExpired(long now) {
            return future.isDone() && now >= expiresAtNanos;
        }
    }
}
