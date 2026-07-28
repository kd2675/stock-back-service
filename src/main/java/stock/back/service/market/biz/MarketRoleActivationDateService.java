package stock.back.service.market.biz;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import stock.back.service.common.exception.StockException;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class MarketRoleActivationDateService {

    private static final String DEFAULT_STATE_ID = "DEFAULT";

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService marketSessionService;

    public MarketRoleActivationDateService(
            JdbcClient jdbcClient,
            SimulationMarketSessionService marketSessionService
    ) {
        this.jdbcClient = jdbcClient;
        this.marketSessionService = marketSessionService;
    }

    public LocalDate resolveNextOpeningDate(
            SimulationClockSnapshot clock,
            LocalDate activeBusinessDate
    ) {
        if (clock == null || clock.simulationDate() == null || activeBusinessDate == null) {
            throw StockException.conflict(
                    "Simulation and active business dates are required to schedule a market role"
            );
        }
        if (marketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            return activeBusinessDate.plusDays(1);
        }

        LocalDate simulationDate = clock.simulationDate();
        if (simulationDate.equals(activeBusinessDate)) {
            return simulationDate;
        }
        LocalDate preparingBusinessDate = findPreparingBusinessDate();
        if (!simulationDate.equals(preparingBusinessDate)
                || !simulationDate.equals(activeBusinessDate.plusDays(1))) {
            return activeBusinessDate.plusDays(1);
        }
        FullMarketCycle cycle = findFullMarketCycle(activeBusinessDate);
        if (cycle == null || activationPhasePassed(cycle)) {
            return simulationDate.plusDays(1);
        }
        return simulationDate;
    }

    private LocalDate findPreparingBusinessDate() {
        return jdbcClient.sql(
                        """
                        select preparing_business_date
                          from stock_market_business_state
                         where state_id = ?
                        """
                )
                .param(DEFAULT_STATE_ID)
                .query(LocalDate.class)
                .optional()
                .orElse(null);
    }

    private FullMarketCycle findFullMarketCycle(LocalDate activeBusinessDate) {
        return jdbcClient.sql(
                        """
                        select phase, status
                          from stock_post_close_cycle
                         where business_date = ?
                           and scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                         for update
                        """
                )
                .param(activeBusinessDate)
                .query((rs, rowNum) -> new FullMarketCycle(
                        rs.getString("phase"),
                        rs.getString("status")
                ))
                .optional()
                .orElse(null);
    }

    private boolean activationPhasePassed(FullMarketCycle cycle) {
        if ("MARKET_DATA_PREPARED".equals(cycle.phase())
                && "RUNNING".equals(cycle.status())) {
            return true;
        }
        return switch (cycle.phase()) {
            case "AUTO_MARKET_PREPARED", "READY_TO_OPEN", "COMPLETED" -> true;
            default -> false;
        };
    }

    private record FullMarketCycle(String phase, String status) {
    }
}
