package stock.back.service.trading.biz;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderSide;
import stock.back.service.database.entity.OrderStatus;
import stock.back.service.market.biz.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationClockSnapshots;
import web.common.core.simulation.SimulationMarketSession;
import web.common.core.simulation.SimulationMarketSessions;

@Service
public class TradingSessionFenceService {

    private static final String DEFAULT_STATE_ID = "DEFAULT";
    private static final String DEFAULT_CLOCK_ID = "DEFAULT";

    private final JdbcClient jdbcClient;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final String lockClause;
    private final long staleAfterSeconds;
    private final Timer newOrderFenceTimer;
    private final Timer ownedEntryFenceTimer;
    private final Timer ownedMutationFenceTimer;

    public TradingSessionFenceService(
            JdbcTemplate jdbcTemplate,
            SimulationMarketSessionService simulationMarketSessionService,
            MeterRegistry meterRegistry,
            @Value("${stock.simulation-clock.stale-after-seconds:30}") long staleAfterSeconds
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.lockClause = isMySql(jdbcTemplate) ? "for share of f" : "for update";
        this.staleAfterSeconds = staleAfterSeconds;
        this.newOrderFenceTimer = fenceTimer(meterRegistry, "new-order");
        this.ownedEntryFenceTimer = fenceTimer(meterRegistry, "owned-entry");
        this.ownedMutationFenceTimer = fenceTimer(meterRegistry, "owned-mutation");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TradingSessionApproval acquireOpenSession(String symbol, MarketType marketType) {
        return newOrderFenceTimer.record(() -> acquireOpenSessionInternal(symbol, marketType));
    }

    private TradingSessionApproval acquireOpenSessionInternal(String symbol, MarketType marketType) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarketType normalizedMarketType = marketType == null ? MarketType.VIRTUAL_PRICE : marketType;
        MarketSessionGateRow gate = lockOpenFence(normalizedSymbol, normalizedMarketType);
        if (gate == null) {
            throw StockException.conflict("Market is not open: " + normalizedSymbol);
        }
        SimulationClockSnapshot clock = toClockSnapshot(gate);
        SimulationMarketSession session = SimulationMarketSessions.resolve(
                clock.simulationDateTime(),
                simulationMarketSessionService.openTime(),
                simulationMarketSessionService.closeTime()
        );
        if (session != SimulationMarketSession.REGULAR
                || !gate.activeBusinessDate().equals(clock.simulationDate())
                || !gate.activeBusinessDate().equals(gate.rawSimulationDate())
                || gate.preparingBusinessDate() != null
                || !gate.activeBusinessDate().equals(gate.businessDate())
                || !"OPEN".equals(gate.sessionState())) {
            throw StockException.conflict("Market session is closed for new orders: " + normalizedSymbol);
        }
        return new TradingSessionApproval(
                gate.activeBusinessDate(),
                gate.sessionEpoch(),
                clock.simulationDateTime()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OwnedOrderSessionApproval acquireOwnedOpenOrderEntrySession(String userKey, Long orderId) {
        return ownedEntryFenceTimer.record(() -> acquireOwnedOpenOrderSession(
                userKey,
                orderId,
                true,
                "Only pending orders can be changed"
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OwnedOrderSessionApproval acquireOwnedOpenOrderMutationSession(
            String userKey,
            Long orderId,
            String unavailableOrderMessage
    ) {
        return ownedMutationFenceTimer.record(() ->
                acquireOwnedOpenOrderSession(userKey, orderId, false, unavailableOrderMessage)
        );
    }

    private OwnedOrderSessionApproval acquireOwnedOpenOrderSession(
            String userKey,
            Long orderId,
            boolean requireRegularOrderEntry,
            String unavailableOrderMessage
    ) {
        if (userKey == null || userKey.isBlank() || orderId == null) {
            throw StockException.notFound("Order not found");
        }
        OwnedOrderGateRow gate = lockOwnedOrderFence(userKey.trim(), orderId);
        if (gate == null) {
            throw StockException.notFound("Order not found");
        }
        if (gate.orderStatus() != OrderStatus.PENDING
                && gate.orderStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw StockException.conflict(unavailableOrderMessage);
        }

        SimulationClockSnapshot clock = toClockSnapshot(gate.marketSessionGate());
        SimulationMarketSession session = SimulationMarketSessions.resolve(
                clock.simulationDateTime(),
                simulationMarketSessionService.openTime(),
                simulationMarketSessionService.closeTime()
        );
        boolean sessionIdentityMatches = gate.activeBusinessDate().equals(clock.simulationDate())
                && gate.activeBusinessDate().equals(gate.rawSimulationDate())
                && gate.preparingBusinessDate() == null
                && gate.activeBusinessDate().equals(gate.businessDate())
                && "OPEN".equals(gate.sessionState());
        boolean marketConfigOpen = Boolean.TRUE.equals(gate.marketEnabled())
                && "OPEN".equals(gate.marketStatus());
        if (!sessionIdentityMatches
                || (requireRegularOrderEntry
                && (session != SimulationMarketSession.REGULAR || !marketConfigOpen))) {
            String message = requireRegularOrderEntry
                    ? "Market session is closed for order changes: " + gate.symbol()
                    : "Market ledger is closed for order changes: " + gate.symbol();
            throw StockException.conflict(message);
        }
        return new OwnedOrderSessionApproval(
                gate.symbol(),
                gate.marketType(),
                gate.orderSide(),
                gate.activeBusinessDate(),
                gate.sessionEpoch(),
                clock.simulationDateTime()
        );
    }

    private OwnedOrderGateRow lockOwnedOrderFence(String userKey, long orderId) {
        return jdbcClient.sql(
                        """
                        select o.symbol,
                               o.market_type,
                               o.side as order_side,
                               o.status as order_status,
                               f.business_date,
                               f.session_epoch,
                               f.session_state,
                               b.active_business_date,
                               b.preparing_business_date,
                               b.raw_simulation_date,
                               sc.base_simulation_date,
                               sc.real_seconds_per_simulation_day,
                               sc.accumulated_real_seconds,
                               sc.running,
                               sc.last_started_at,
                               sc.last_heartbeat_at,
                               case
                                   when o.market_type = 'ORDER_BOOK' then ob.enabled
                                   else vm.enabled
                               end as market_enabled,
                               case
                                   when o.market_type = 'ORDER_BOOK' then ob.market_status
                                   else vm.market_status
                               end as market_status
                          from stock_order o
                          join stock_account a
                            on a.id = o.account_id
                           and a.user_key = :userKey
                           and a.status = 'ACTIVE'
                          join stock_market_session_fence f
                            on f.market_type = o.market_type
                           and f.symbol = o.symbol
                          left join stock_order_book_market_config ob
                            on ob.symbol = o.symbol
                           and o.market_type = 'ORDER_BOOK'
                          left join stock_virtual_market_config vm
                            on vm.symbol = o.symbol
                           and o.market_type = 'VIRTUAL_PRICE'
                          join stock_market_business_state b
                            on b.state_id = '%s'
                          join stock_simulation_clock sc
                            on sc.clock_id = '%s'
                         where o.id = :orderId
                        %s
                        """.formatted(DEFAULT_STATE_ID, DEFAULT_CLOCK_ID, lockClause)
                )
                .param("userKey", userKey)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OwnedOrderGateRow(
                        rs.getString("symbol"),
                        MarketType.valueOf(rs.getString("market_type")),
                        OrderSide.valueOf(rs.getString("order_side")),
                        OrderStatus.valueOf(rs.getString("order_status")),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("session_epoch"),
                        rs.getString("session_state"),
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("raw_simulation_date", LocalDate.class),
                        rs.getObject("base_simulation_date", LocalDate.class),
                        rs.getInt("real_seconds_per_simulation_day"),
                        rs.getLong("accumulated_real_seconds"),
                        rs.getBoolean("running"),
                        rs.getObject("last_started_at", LocalDateTime.class),
                        rs.getObject("last_heartbeat_at", LocalDateTime.class),
                        rs.getObject("market_enabled", Boolean.class),
                        rs.getString("market_status")
                ))
                .optional()
                .orElse(null);
    }

    private MarketSessionGateRow lockOpenFence(String symbol, MarketType marketType) {
        String configTable = marketType == MarketType.ORDER_BOOK
                ? "stock_order_book_market_config"
                : "stock_virtual_market_config";
        return jdbcClient.sql(
                        """
                        select f.business_date,
                               f.session_epoch,
                               f.session_state,
                               b.active_business_date,
                               b.preparing_business_date,
                               b.raw_simulation_date,
                               sc.base_simulation_date,
                               sc.real_seconds_per_simulation_day,
                               sc.accumulated_real_seconds,
                               sc.running,
                               sc.last_started_at,
                               sc.last_heartbeat_at
                          from stock_market_session_fence f
                          join %s c
                            on c.symbol = f.symbol
                           and c.enabled = true
                           and c.market_status = 'OPEN'
                          join stock_market_business_state b
                            on b.state_id = '%s'
                          join stock_simulation_clock sc
                            on sc.clock_id = '%s'
                         where f.market_type = :marketType
                           and f.symbol = :symbol
                        %s
                        """.formatted(
                                configTable,
                                DEFAULT_STATE_ID,
                                DEFAULT_CLOCK_ID,
                                lockClause
                        )
                )
                .param("marketType", marketType.name())
                .param("symbol", symbol)
                .query((rs, rowNum) -> new MarketSessionGateRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("session_epoch"),
                        rs.getString("session_state"),
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class),
                        rs.getObject("raw_simulation_date", LocalDate.class),
                        rs.getObject("base_simulation_date", LocalDate.class),
                        rs.getInt("real_seconds_per_simulation_day"),
                        rs.getLong("accumulated_real_seconds"),
                        rs.getBoolean("running"),
                        rs.getObject("last_started_at", LocalDateTime.class),
                        rs.getObject("last_heartbeat_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private SimulationClockSnapshot toClockSnapshot(MarketSessionGateRow gate) {
        if (gate == null) {
            return null;
        }
        return SimulationClockSnapshots.calculate(
                gate.baseSimulationDate(),
                gate.realSecondsPerSimulationDay(),
                gate.accumulatedRealSeconds(),
                gate.running(),
                gate.lastStartedAt(),
                gate.lastHeartbeatAt(),
                staleAfterSeconds,
                LocalDateTime.now()
        );
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) this::databaseProductName
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    private String databaseProductName(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    private Timer fenceTimer(MeterRegistry meterRegistry, String operation) {
        return Timer.builder("stock.trading.session.fence.duration")
                .description("Time spent validating and acquiring the stock trading session fence")
                .tag("operation", operation)
                .register(meterRegistry);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw StockException.badRequest("Stock symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private record MarketSessionGateRow(
            LocalDate businessDate,
            long sessionEpoch,
            String sessionState,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            LocalDate baseSimulationDate,
            int realSecondsPerSimulationDay,
            long accumulatedRealSeconds,
            boolean running,
            LocalDateTime lastStartedAt,
            LocalDateTime lastHeartbeatAt
    ) {
    }

    private record OwnedOrderGateRow(
            String symbol,
            MarketType marketType,
            OrderSide orderSide,
            OrderStatus orderStatus,
            LocalDate businessDate,
            long sessionEpoch,
            String sessionState,
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate,
            LocalDate rawSimulationDate,
            LocalDate baseSimulationDate,
            int realSecondsPerSimulationDay,
            long accumulatedRealSeconds,
            boolean running,
            LocalDateTime lastStartedAt,
            LocalDateTime lastHeartbeatAt,
            Boolean marketEnabled,
            String marketStatus
    ) {
        private MarketSessionGateRow marketSessionGate() {
            return new MarketSessionGateRow(
                    businessDate,
                    sessionEpoch,
                    sessionState,
                    activeBusinessDate,
                    preparingBusinessDate,
                    rawSimulationDate,
                    baseSimulationDate,
                    realSecondsPerSimulationDay,
                    accumulatedRealSeconds,
                    running,
                    lastStartedAt,
                    lastHeartbeatAt
            );
        }
    }

    public record TradingSessionApproval(
            LocalDate businessDate,
            long sessionEpoch,
            LocalDateTime businessEffectiveAt
    ) {
    }

    public record OwnedOrderSessionApproval(
            String symbol,
            MarketType marketType,
            OrderSide orderSide,
            LocalDate businessDate,
            long sessionEpoch,
            LocalDateTime businessEffectiveAt
    ) {
    }
}
