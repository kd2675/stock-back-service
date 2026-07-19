package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import stock.back.service.common.exception.StockException;

@Service
@RequiredArgsConstructor
public class BatchJobSignalContextService {

    private static final String DEFAULT_STATE_ID = "DEFAULT";

    private final JdbcClient jdbcClient;
    private final SimulationClockService simulationClockService;

    @Transactional(readOnly = true)
    public BatchJobSignalContext resolveFullMarket() {
        return resolve("FULL_MARKET", "ALL", null);
    }

    @Transactional(readOnly = true)
    public BatchJobSignalContext resolveSymbol(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        return resolve("SYMBOL", normalizedSymbol, normalizedSymbol);
    }

    private BatchJobSignalContext resolve(String scopeType, String scopeKey, String symbol) {
        LocalDateTime simulationNow = simulationClockService.currentMarketDateTime();
        BatchJobSignalContext context = jdbcClient.sql(
                        """
                        select state.active_business_date,
                               fence.session_epoch as requested_session_epoch,
                               cycle.id as expected_cycle_id
                          from stock_market_business_state state
                          left join stock_market_session_fence fence
                            on fence.market_type = 'ORDER_BOOK'
                           and fence.symbol = :symbol
                          left join stock_post_close_cycle cycle
                            on cycle.business_date = state.active_business_date
                           and cycle.scope_type = :scopeType
                           and cycle.scope_key = :scopeKey
                         where state.state_id = :stateId
                        """
                )
                .param("symbol", symbol == null ? "" : symbol)
                .param("scopeType", scopeType)
                .param("scopeKey", scopeKey)
                .param("stateId", DEFAULT_STATE_ID)
                .query((rs, rowNum) -> new BatchJobSignalContext(
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("requested_session_epoch", Long.class),
                        rs.getObject("expected_cycle_id", Long.class),
                        simulationNow
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict("Active market business date is not initialized"));
        if (symbol != null && context.requestedSessionEpoch() == null) {
            throw StockException.conflict("Market session fence is not initialized for symbol: " + symbol);
        }
        return context;
    }

    private String normalizeSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    public record BatchJobSignalContext(
            LocalDate requestedBusinessDate,
            Long requestedSessionEpoch,
            Long expectedCycleId,
            LocalDateTime simulationNow
    ) {
    }
}
