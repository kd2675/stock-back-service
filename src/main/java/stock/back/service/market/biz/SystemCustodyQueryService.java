package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.SystemCustodyOverviewResponse;

@Service
public class SystemCustodyQueryService {

    private final JdbcClient jdbcClient;

    public SystemCustodyQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public SystemCustodyOverviewResponse getOverview() {
        List<AccountRow> accountRows = jdbcClient.sql(
                        """
                        select account.id as account_id,
                               account.account_code,
                               account.user_key,
                               account.status as account_status,
                               account.self_trade_group_id,
                               mapping.desk_code,
                               mapping.status as mapping_status,
                               account.cash_balance
                          from stock_account account
                          join stock_market_participant_account mapping
                            on mapping.account_id = account.id
                           and mapping.account_role = 'SYSTEM_CUSTODY'
                         where account.participant_category = 'SYSTEM_CUSTODY'
                         order by mapping.desk_code, account.id
                        """
                )
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("account_id"),
                        rs.getString("account_code"),
                        rs.getString("user_key"),
                        rs.getString("account_status"),
                        rs.getString("self_trade_group_id"),
                        rs.getString("desk_code"),
                        rs.getString("mapping_status"),
                        rs.getBigDecimal("cash_balance")
                ))
                .list();
        Map<Long, List<SystemCustodyOverviewResponse.Holding>> holdingsByAccount =
                new LinkedHashMap<>();
        jdbcClient.sql(
                        """
                        select holding.account_id,
                               holding.symbol,
                               holding.quantity,
                               holding.reserved_quantity,
                               holding.average_price,
                               coalesce(price.current_price, 0) as current_price
                          from stock_holding holding
                          join stock_account account
                            on account.id = holding.account_id
                           and account.participant_category = 'SYSTEM_CUSTODY'
                          left join stock_price price
                            on price.symbol = holding.symbol
                         where holding.quantity > 0
                            or holding.reserved_quantity > 0
                         order by holding.account_id, holding.symbol
                        """
                )
                .query((rs, rowNum) -> {
                    BigDecimal currentPrice = rs.getBigDecimal("current_price");
                    long quantity = rs.getLong("quantity");
                    return new HoldingRow(
                            rs.getLong("account_id"),
                            new SystemCustodyOverviewResponse.Holding(
                                    rs.getString("symbol"),
                                    quantity,
                                    rs.getLong("reserved_quantity"),
                                    rs.getBigDecimal("average_price"),
                                    currentPrice,
                                    currentPrice.multiply(BigDecimal.valueOf(quantity))
                                            .setScale(2, RoundingMode.HALF_UP)
                            )
                    );
                })
                .list()
                .forEach(row -> holdingsByAccount
                        .computeIfAbsent(row.accountId(), ignored -> new ArrayList<>())
                        .add(row.holding()));
        List<SystemCustodyOverviewResponse.Account> accounts = accountRows.stream()
                .map(account -> new SystemCustodyOverviewResponse.Account(
                        account.accountId(),
                        account.accountCode(),
                        account.userKey(),
                        account.accountStatus(),
                        account.selfTradeGroupId(),
                        account.deskCode(),
                        account.mappingStatus(),
                        account.cashBalance(),
                        holdingsByAccount.getOrDefault(account.accountId(), List.of())
                ))
                .toList();
        long withdrawalCount = accountRows.stream()
                .filter(account -> "DEFAULT".equals(account.deskCode()))
                .count();
        long issuanceCount = accountRows.stream()
                .filter(account -> account.deskCode().startsWith("ISSUANCE_FLOAT:")
                        || account.deskCode().startsWith("ISSUANCE_LOCKUP:"))
                .count();
        long issueSymbolCount = jdbcClient.sql(
                        """
                        select count(distinct symbol)
                          from stock_security_allocation_ledger
                         where event_type = 'INITIAL_ISSUE'
                           and allocation_reason = 'INITIAL_LOCKED_CUSTODY'
                        """
                )
                .query(Long.class)
                .single();
        return new SystemCustodyOverviewResponse(
                1,
                withdrawalCount,
                2,
                issueSymbolCount,
                Math.multiplyExact(issueSymbolCount, 2L),
                issuanceCount,
                accounts
        );
    }

    private record AccountRow(
            long accountId,
            String accountCode,
            String userKey,
            String accountStatus,
            String selfTradeGroupId,
            String deskCode,
            String mappingStatus,
            BigDecimal cashBalance
    ) {
    }

    private record HoldingRow(
            long accountId,
            SystemCustodyOverviewResponse.Holding holding
    ) {
    }
}
