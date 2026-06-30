package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.StockListingAutoAccountConfig;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ListingAutoAccountLedgerQueryService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    Map<String, ListingAutoAccountLedger> findLedgersBySymbol() {
        return jdbcTemplate.query(
                """
                select c.symbol,
                       a.id as account_id,
                       coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(p.current_price, 0) as current_price
                  from stock_listing_auto_account_config c
                  left join stock_account a on a.user_key = c.user_key
                  left join stock_holding h on h.account_id = a.id and h.symbol = c.symbol
                  left join stock_price p on p.symbol = c.symbol
                 order by c.symbol asc
                """,
                (rs, rowNum) -> new ListingAutoAccountLedgerRow(
                        rs.getString("symbol"),
                        ListingAutoAccountLedger.of(
                                rs.getObject("account_id", Long.class),
                                nonNullMoney(rs.getBigDecimal("cash_balance")),
                                Math.max(0L, rs.getLong("holding_quantity")),
                                Math.max(0L, rs.getLong("reserved_quantity")),
                                nonNullMoney(rs.getBigDecimal("average_price")),
                                nonNullMoney(rs.getBigDecimal("current_price"))
                        )
                )
        ).stream().collect(Collectors.toMap(
                ListingAutoAccountLedgerRow::symbol,
                ListingAutoAccountLedgerRow::ledger,
                (left, right) -> left
        ));
    }

    @Transactional(readOnly = true)
    ListingAutoAccountLedger findLedger(StockListingAutoAccountConfig config) {
        return jdbcTemplate.query(
                """
                select a.id as account_id,
                       coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(p.current_price, 0) as current_price
                from stock_account a
                left join stock_holding h on h.account_id = a.id and h.symbol = ?
                left join stock_price p on p.symbol = ?
                where a.user_key = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return ListingAutoAccountLedger.empty();
                    }
                    return ListingAutoAccountLedger.of(
                            rs.getLong("account_id"),
                            nonNullMoney(rs.getBigDecimal("cash_balance")),
                            Math.max(0L, rs.getLong("holding_quantity")),
                            Math.max(0L, rs.getLong("reserved_quantity")),
                            nonNullMoney(rs.getBigDecimal("average_price")),
                            nonNullMoney(rs.getBigDecimal("current_price"))
                    );
                },
                config.getSymbol(),
                config.getSymbol(),
                config.getUserKey()
        );
    }

    private BigDecimal nonNullMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ListingAutoAccountLedgerRow(
            String symbol,
            ListingAutoAccountLedger ledger
    ) {
    }
}
