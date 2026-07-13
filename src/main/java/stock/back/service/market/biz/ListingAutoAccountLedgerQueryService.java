package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.database.entity.StockListingAutoAccountConfig;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ListingAutoAccountLedgerQueryService {

    private final JdbcClient jdbcClient;

    public ListingAutoAccountLedgerQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional(readOnly = true)
    Map<String, ListingAutoAccountLedger> findLedgersBySymbol() {
        return jdbcClient.sql(
                """
                select c.symbol,
                       a.id as account_id,
                       coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(p.current_price, 0) as current_price,
                       coalesce((select sum(o.quantity - o.filled_quantity)
                                   from stock_order o
                                  where o.account_id = a.id and o.symbol = c.symbol and o.side = 'BUY'
                                    and o.market_type = 'ORDER_BOOK'
                                    and o.status in ('PENDING', 'PARTIALLY_FILLED')), 0) as open_buy_quantity,
                       coalesce((select sum(o.quantity - o.filled_quantity)
                                   from stock_order o
                                  where o.account_id = a.id and o.symbol = c.symbol and o.side = 'SELL'
                                    and o.market_type = 'ORDER_BOOK'
                                    and o.status in ('PENDING', 'PARTIALLY_FILLED')), 0) as open_sell_quantity
                  from stock_listing_auto_account_config c
                  left join stock_account a on a.user_key = c.user_key
                  left join stock_holding h on h.account_id = a.id and h.symbol = c.symbol
                  left join stock_price p on p.symbol = c.symbol
                 order by c.symbol asc
                """
        )
                .query((rs, rowNum) -> new ListingAutoAccountLedgerRow(
                        rs.getString("symbol"),
                        toLedger(rs)
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
                        ListingAutoAccountLedgerRow::symbol,
                        ListingAutoAccountLedgerRow::ledger,
                        (left, right) -> left
                ));
    }

    @Transactional(readOnly = true)
    ListingAutoAccountLedger findLedger(StockListingAutoAccountConfig config) {
        return jdbcClient.sql(
                """
                select a.id as account_id,
                       coalesce(a.cash_balance, 0) as cash_balance,
                       coalesce(h.quantity, 0) as holding_quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(p.current_price, 0) as current_price,
                       coalesce((select sum(o.quantity - o.filled_quantity)
                                   from stock_order o
                                  where o.account_id = a.id and o.symbol = ? and o.side = 'BUY'
                                    and o.market_type = 'ORDER_BOOK'
                                    and o.status in ('PENDING', 'PARTIALLY_FILLED')), 0) as open_buy_quantity,
                       coalesce((select sum(o.quantity - o.filled_quantity)
                                   from stock_order o
                                  where o.account_id = a.id and o.symbol = ? and o.side = 'SELL'
                                    and o.market_type = 'ORDER_BOOK'
                                    and o.status in ('PENDING', 'PARTIALLY_FILLED')), 0) as open_sell_quantity
                from stock_account a
                left join stock_holding h on h.account_id = a.id and h.symbol = ?
                left join stock_price p on p.symbol = ?
                where a.user_key = ?
                """
        )
                .params(
                        config.getSymbol(),
                        config.getSymbol(),
                        config.getSymbol(),
                        config.getSymbol(),
                        config.getUserKey()
                )
                .query(rs -> {
                    if (!rs.next()) {
                        return ListingAutoAccountLedger.empty();
                    }
                    return toLedger(rs);
                });
    }

    private ListingAutoAccountLedger toLedger(ResultSet rs) throws SQLException {
        return ListingAutoAccountLedger.of(
                rs.getObject("account_id", Long.class),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("cash_balance")),
                Math.max(0L, rs.getLong("holding_quantity")),
                Math.max(0L, rs.getLong("reserved_quantity")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("average_price")),
                MarketQuerySupport.zeroIfNull(rs.getBigDecimal("current_price")),
                Math.max(0L, rs.getLong("open_buy_quantity")),
                Math.max(0L, rs.getLong("open_sell_quantity"))
        );
    }

    private record ListingAutoAccountLedgerRow(
            String symbol,
            ListingAutoAccountLedger ledger
    ) {
    }
}
