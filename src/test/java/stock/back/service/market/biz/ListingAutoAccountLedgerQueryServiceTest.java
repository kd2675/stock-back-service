package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListingAutoAccountLedgerQueryServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ListingAutoAccountLedgerQueryService queryService = new ListingAutoAccountLedgerQueryService(jdbcTemplate);

    @Test
    void findLedgersBySymbol_mapsAvailableQuantityAndMarketValue() throws Exception {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("from stock_listing_auto_account_config c"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                aryEq(new Object[0])
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("symbol")).thenReturn("ZQ001");
            when(resultSet.getObject("account_id", Long.class)).thenReturn(77L);
            when(resultSet.getBigDecimal("cash_balance")).thenReturn(new BigDecimal("350000.00"));
            when(resultSet.getLong("holding_quantity")).thenReturn(100000L);
            when(resultSet.getLong("reserved_quantity")).thenReturn(1200L);
            when(resultSet.getBigDecimal("average_price")).thenReturn(new BigDecimal("70000.00"));
            when(resultSet.getBigDecimal("current_price")).thenReturn(new BigDecimal("72000.00"));
            when(resultSet.getLong("open_buy_quantity")).thenReturn(3000L);
            when(resultSet.getLong("open_sell_quantity")).thenReturn(1200L);
            return List.of(rowMapper.mapRow(resultSet, 0));
        });

        var ledgers = queryService.findLedgersBySymbol();

        assertThat(ledgers).containsKey("ZQ001");
        var ledger = ledgers.get("ZQ001");
        assertThat(ledger.accountId()).isEqualTo(77L);
        assertThat(ledger.availableQuantity()).isEqualTo(98800L);
        assertThat(ledger.marketValue()).isEqualByComparingTo(new BigDecimal("7200000000.00"));
        assertThat(ledger.openBuyQuantity()).isEqualTo(3000L);
        assertThat(ledger.openSellQuantity()).isEqualTo(1200L);
    }
}
