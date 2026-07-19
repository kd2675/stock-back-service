package stock.back.service.market.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketSessionStatus;
import stock.back.service.database.entity.MarketType;
import web.common.core.simulation.SimulationMarketSession;

/**
 * Serializes an administrative market-state change with in-flight order and execution
 * transactions. It is intentionally used only by the low-frequency admin command path; live
 * order entry continues to lock only the per-symbol fence row once per transaction.
 */
@Service
@RequiredArgsConstructor
public class MarketSessionFenceCommandService {

    private static final String DEFAULT_STATE_ID = "DEFAULT";

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronize(
            MarketType marketType,
            String symbol,
            boolean enabled,
            MarketSessionStatus marketStatus,
            LocalDateTime changedAt
    ) {
        MarketBusinessState businessState = lockBusinessState();
        LocalDate activeBusinessDate = businessState.activeBusinessDate();
        boolean shouldOpen = enabled && marketStatus == MarketSessionStatus.OPEN;
        if (shouldOpen && (simulationMarketSessionService.currentSession() != SimulationMarketSession.REGULAR
                || !activeBusinessDate.equals(changedAt.toLocalDate())
                || !activeBusinessDate.equals(businessState.rawSimulationDate())
                || businessState.preparingBusinessDate() != null)) {
            throw StockException.conflict("Market cannot open before the active trading day is ready: " + symbol);
        }
        String targetFenceState = shouldOpen ? "OPEN" : "CLOSED";
        FenceRow current = lockFence(marketType, symbol);
        if (current == null) {
            insertFence(marketType, symbol, activeBusinessDate, targetFenceState, changedAt);
            return;
        }
        if (activeBusinessDate.equals(current.businessDate())
                && targetFenceState.equals(current.sessionState())) {
            return;
        }
        jdbcClient.sql(
                        """
                        update stock_market_session_fence
                           set business_date = :businessDate,
                               session_epoch = session_epoch + 1,
                               session_state = :sessionState,
                               state_changed_at = :changedAt,
                               version = version + 1,
                               updated_at = :changedAt
                         where market_type = :marketType
                           and symbol = :symbol
                        """
                )
                .param("businessDate", activeBusinessDate)
                .param("sessionState", targetFenceState)
                .param("changedAt", changedAt)
                .param("marketType", marketType.name())
                .param("symbol", symbol)
                .update();
    }

    private MarketBusinessState lockBusinessState() {
        return jdbcClient.sql(
                        """
                        select active_business_date,
                               preparing_business_date,
                               raw_simulation_date
                          from stock_market_business_state
                         where state_id = :stateId
                         for update
                        """
                )
                .param("stateId", DEFAULT_STATE_ID)
                .query((rs, rowNum) -> new MarketBusinessState(
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("raw_simulation_date", LocalDate.class)
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict("Active market business date is not initialized"));
    }

    private FenceRow lockFence(MarketType marketType, String symbol) {
        return jdbcClient.sql(
                        """
                        select business_date, session_state
                          from stock_market_session_fence
                         where market_type = :marketType
                           and symbol = :symbol
                         for update
                        """
                )
                .param("marketType", marketType.name())
                .param("symbol", symbol)
                .query((rs, rowNum) -> new FenceRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getString("session_state")
                ))
                .optional()
                .orElse(null);
    }

    private void insertFence(
            MarketType marketType,
            String symbol,
            LocalDate businessDate,
            String sessionState,
            LocalDateTime changedAt
    ) {
        try {
            jdbcClient.sql(
                            """
                            insert into stock_market_session_fence(
                                market_type, symbol, business_date, session_epoch, session_state,
                                state_changed_at, version, created_at, updated_at
                            )
                            values (
                                :marketType, :symbol, :businessDate, 1, :sessionState,
                                :changedAt, 0, :changedAt, :changedAt
                            )
                            """
                    )
                    .param("marketType", marketType.name())
                    .param("symbol", symbol)
                    .param("businessDate", businessDate)
                    .param("sessionState", sessionState)
                    .param("changedAt", changedAt)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("Market session fence changed concurrently: " + symbol, ex);
        }
    }

    private record FenceRow(LocalDate businessDate, String sessionState) {
    }

    private record MarketBusinessState(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate
    ) {
    }
}
