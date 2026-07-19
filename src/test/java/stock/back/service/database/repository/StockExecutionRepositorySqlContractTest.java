package stock.back.service.database.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockExecutionRepositorySqlContractTest {

    private static final Path REPOSITORY_SOURCE = Path.of(
            "src/main/java/stock/back/service/database/repository/StockExecutionRepository.java"
    );

    @Test
    void profitSummary_usesCompactAsyncDaySummaryInsteadOfCumulativeExecutionLedgerScan() throws Exception {
        String source = Files.readString(REPOSITORY_SOURCE, StandardCharsets.UTF_8);
        String query = source.substring(
                source.indexOf("select\n              coalesce(sum(buy_gross_amount)"),
                source.indexOf("ProfitSummaryProjection summarizeProfitByAccountId")
        );

        assertThat(query)
                .contains("from stock_execution_account_day_summary")
                .contains("where account_id = :accountId")
                .contains("sum(execution_count)")
                .doesNotContain("from stock_execution\n")
                .doesNotContain("date(")
                .doesNotContain("time(");
    }
}
