package stock.back.service.database;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StockMysqlDdlContractTest {

    @Test
    void stockAllSql_containsDefaultMarketSeedForDockerInit() throws IOException {
        String ddl = readStockAllSql();

        assertThat(ddl).contains("INSERT INTO stock_instrument");
        assertThat(ddl).contains("INSERT INTO stock_price");
        assertThat(ddl).contains("KEY idx_stock_price_tick_symbol_time (symbol, price_time)");
        assertThat(ddl).contains("KEY idx_stock_order_order_book_match (symbol, side, order_type, status, limit_price, created_at)");
        assertThat(ddl).contains("ON DUPLICATE KEY UPDATE symbol = symbol");
        assertThat(ddl).contains(
                "('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP)",
                "('000660', 'SK하이닉스', 'KOSPI', TRUE, CURRENT_TIMESTAMP)",
                "('035420', 'NAVER', 'KOSPI', TRUE, CURRENT_TIMESTAMP)",
                "('035720', '카카오', 'KOSPI', TRUE, CURRENT_TIMESTAMP)",
                "('051910', 'LG화학', 'KOSPI', TRUE, CURRENT_TIMESTAMP)"
        );
        assertThat(ddl).contains(
                "('005930', 72400.00, 72400.00, CURRENT_TIMESTAMP, 'seed')",
                "('000660', 241500.00, 241500.00, CURRENT_TIMESTAMP, 'seed')",
                "('035420', 193800.00, 193800.00, CURRENT_TIMESTAMP, 'seed')",
                "('035720', 52100.00, 52100.00, CURRENT_TIMESTAMP, 'seed')",
                "('051910', 312000.00, 312000.00, CURRENT_TIMESTAMP, 'seed')"
        );
    }

    private String readStockAllSql() throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("db/ddl/stock_all.sql")) {
            assertThat(inputStream).as("db/ddl/stock_all.sql resource").isNotNull();
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
