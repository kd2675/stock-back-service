package stock.back.service.market.biz;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;

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

    public MarketLedgerFreezeGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.sharedLockClause = isMySql(jdbcTemplate) ? "for share" : "for update";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LocalDate acquireMutationPermit(String operation) {
        LocalDate activeBusinessDate = lockActiveBusinessDate();
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
        return activeBusinessDate;
    }

    private LocalDate lockActiveBusinessDate() {
        return jdbcClient.sql(
                        """
                        select active_business_date
                          from stock_market_business_state
                         where state_id = ?
                        %s
                        """.formatted(sharedLockClause)
                )
                .param(DEFAULT_STATE_ID)
                .query(LocalDate.class)
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
}
