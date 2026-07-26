package stock.back.service.market.biz;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.common.exception.StockException;

/**
 * Serializes low-frequency account and cash-ledger mutations with the market-close freeze.
 * Live order and execution paths deliberately do not use this guard; they continue to lock only
 * the per-symbol session fence. A mutation that started first holds a shared singleton-state lock,
 * so close waits and includes it. Once the full-market cycle is waiting to freeze, new mutations
 * fail before locking an account and can be retried after LEDGER_FROZEN.
 */
@Service
public class MarketLedgerFreezeGuard {

    private static final String DEFAULT_STATE_ID = "DEFAULT";

    private final JdbcClient jdbcClient;
    private final String sharedLockClause;

    @PersistenceContext(unitName = "pubEntityManager")
    private EntityManager entityManager;

    public MarketLedgerFreezeGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.sharedLockClause = isMySql(jdbcTemplate) ? "for share" : "for update";
    }

    @Transactional(
            transactionManager = "pubTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public LocalDate acquireMutationPermit(String operation) {
        MarketBusinessState businessState = lockBusinessStateInCurrentJpaTransaction();
        LocalDate activeBusinessDate = businessState.activeBusinessDate();
        requireFreezeCompletedInCurrentJpaTransaction(activeBusinessDate, operation);
        return activeBusinessDate;
    }

    @Transactional(
            transactionManager = "pubTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public LocalDate acquirePreOpenMutationPermit(String operation) {
        MarketBusinessState businessState = lockBusinessStateInCurrentJpaTransaction();
        requireFreezeCompletedInCurrentJpaTransaction(
                businessState.activeBusinessDate(),
                operation
        );
        return resolvePreOpenBusinessDate(businessState, operation);
    }

    @Transactional(
            transactionManager = "pubJdbcTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public LocalDate acquireJdbcMutationPermit(String operation) {
        MarketBusinessState businessState = lockBusinessState();
        LocalDate activeBusinessDate = businessState.activeBusinessDate();
        requireFreezeCompleted(activeBusinessDate, operation);
        return activeBusinessDate;
    }

    @Transactional(
            transactionManager = "pubJdbcTransactionManager",
            propagation = Propagation.MANDATORY
    )
    public LocalDate acquireJdbcPreOpenMutationPermit(String operation) {
        MarketBusinessState businessState = lockBusinessState();
        requireFreezeCompleted(businessState.activeBusinessDate(), operation);
        return resolvePreOpenBusinessDate(businessState, operation);
    }

    private LocalDate resolvePreOpenBusinessDate(
            MarketBusinessState businessState,
            String operation
    ) {
        LocalDate preparingBusinessDate = businessState.preparingBusinessDate();
        if (preparingBusinessDate == null) {
            return businessState.activeBusinessDate();
        }
        if (!preparingBusinessDate.equals(businessState.activeBusinessDate().plusDays(1))) {
            throw StockException.conflict(
                    "Prepared market business date is not the next active date: "
                            + normalizeOperation(operation)
            );
        }
        return preparingBusinessDate;
    }

    private MarketBusinessState lockBusinessStateInCurrentJpaTransaction() {
        if (entityManager == null) {
            return lockBusinessState();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        """
                        select active_business_date,
                               preparing_business_date
                          from stock_market_business_state
                         where state_id = :stateId
                         for update
                        """
                )
                .setParameter("stateId", DEFAULT_STATE_ID)
                .getResultList();
        if (rows.size() != 1) {
            throw StockException.conflict(
                    "Active market business date is not initialized"
            );
        }
        Object[] row = rows.getFirst();
        return new MarketBusinessState(
                toLocalDate(row[0]),
                toLocalDate(row[1])
        );
    }

    private void requireFreezeCompletedInCurrentJpaTransaction(
            LocalDate activeBusinessDate,
            String operation
    ) {
        if (entityManager == null) {
            requireFreezeCompleted(activeBusinessDate, operation);
            return;
        }
        Number freezePending = (Number) entityManager.createNativeQuery(
                        """
                        select count(*)
                          from stock_post_close_cycle
                         where business_date = :activeBusinessDate
                           and scope_type = 'FULL_MARKET'
                           and scope_key = 'ALL'
                           and phase in (
                               'OPEN', 'CLOSE_REQUESTED',
                               'ORDER_ENTRY_CLOSED', 'EXECUTION_DRAINED'
                           )
                        """
                )
                .setParameter("activeBusinessDate", activeBusinessDate)
                .getSingleResult();
        if (freezePending != null && freezePending.longValue() > 0L) {
            throw StockException.conflict(
                    "Market close ledger freeze is in progress; retry after the ledger is frozen: "
                            + normalizeOperation(operation)
            );
        }
    }

    private void requireFreezeCompleted(LocalDate activeBusinessDate, String operation) {
        Boolean freezePending = jdbcClient.sql(
                        """
                        select exists (
                            select 1
                              from stock_post_close_cycle
                             where business_date = ?
                               and scope_type = 'FULL_MARKET'
                               and scope_key = 'ALL'
                               and phase in (
                                   'OPEN', 'CLOSE_REQUESTED',
                                   'ORDER_ENTRY_CLOSED', 'EXECUTION_DRAINED'
                               )
                        )
                        """
                )
                .param(activeBusinessDate)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(freezePending)) {
            throw StockException.conflict(
                    "Market close ledger freeze is in progress; retry after the ledger is frozen: "
                            + normalizeOperation(operation)
            );
        }
    }

    private MarketBusinessState lockBusinessState() {
        return jdbcClient.sql(
                        """
                        select active_business_date,
                               preparing_business_date
                          from stock_market_business_state
                         where state_id = ?
                        %s
                        """.formatted(sharedLockClause)
                )
                .param(DEFAULT_STATE_ID)
                .query((rs, rowNum) -> new MarketBusinessState(
                        rs.getObject("active_business_date", LocalDate.class),
                        rs.getObject("preparing_business_date", LocalDate.class)
                ))
                .optional()
                .orElseThrow(() -> StockException.conflict(
                        "Active market business date is not initialized"
                ));
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

    private String normalizeOperation(String operation) {
        return operation == null || operation.isBlank() ? "account-ledger mutation" : operation.trim();
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        throw new IllegalStateException(
                "Unsupported market business date value: " + value.getClass().getName()
        );
    }

    private record MarketBusinessState(
            LocalDate activeBusinessDate,
            LocalDate preparingBusinessDate
    ) {
    }
}
