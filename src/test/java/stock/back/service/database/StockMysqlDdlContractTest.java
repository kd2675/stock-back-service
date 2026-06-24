package stock.back.service.database;

import org.junit.jupiter.api.Test;
import stock.back.service.database.entity.StockCorporateActionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockMysqlDdlContractTest {

    private static final List<String> INITIAL_CORPORATE_ACTION_SCOPE = List.of(
            "INITIAL_ISSUE",
            "PAID_IN_CAPITAL_INCREASE",
            "ADDITIONAL_ISSUE",
            "STOCK_SPLIT",
            "CASH_DIVIDEND",
            "BONUS_ISSUE",
            "STOCK_DIVIDEND"
    );

    private static final List<String> DEFERRED_CORPORATE_ACTION_SCOPE = List.of(
            "SPECIAL_DIVIDEND",
            "CAPITAL_REDUCTION",
            "REVERSE_SPLIT",
            "RIGHTS_OFFERING",
            "MERGER",
            "SPIN_OFF",
            "DELISTING"
    );

    private static final List<String> REQUIRED_CORPORATE_ACTION_CONSTRAINTS = List.of(
            "chk_stock_corporate_action_type_valid",
            "chk_stock_corporate_action_status_valid",
            "chk_stock_corporate_action_share_quantity",
            "chk_stock_corporate_action_issue_price",
            "chk_stock_corporate_action_dividend_amount",
            "chk_stock_corporate_action_base_price",
            "chk_stock_corporate_action_ex_rights_price",
            "chk_stock_corporate_action_paid_dates",
            "chk_stock_corporate_action_listing_dates",
            "chk_stock_corporate_action_split_from",
            "chk_stock_corporate_action_split_to",
            "chk_stock_corporate_action_issue_required",
            "chk_stock_corporate_action_paid_schedule_required",
            "chk_stock_corporate_action_additional_listing_required",
            "chk_stock_corporate_action_split_required",
            "chk_stock_corporate_action_dividend_required",
            "chk_stock_corporate_action_free_share_required",
            "chk_stock_corporate_action_field_scope",
            "chk_stock_corporate_action_initial_listed"
    );

    private static final List<String> CORPORATE_ACTION_DDL_RESOURCES = List.of(
            "db/ddl/stock_all.sql",
            "db/ddl/stock_market_execution_split_alter.sql"
    );

    private static final List<String> REQUIRED_CORPORATE_ACTION_ALTER_MARKERS = List.of(
            "UPDATE stock_corporate_action",
            "listed_at = COALESCE(listed_at, created_at)",
            "applied_at = NULL",
            "paid_at = NULL",
            "action_type = 'INITIAL_ISSUE'"
    );

    private static final List<String> DEFAULT_SEED_MARKERS = List.of(
            "INSERT INTO stock_instrument",
            "INSERT INTO stock_price",
            "INSERT INTO stock_virtual_market_config",
            "INSERT INTO stock_auto_participant",
            "삼성전자",
            "'seed'",
            "stock-auto-001"
    );

    @Test
    void stockAllSql_createsSchemaWithoutDefaultMarketSeed() throws IOException {
        String ddl = readStockAllSql();

        assertThat(ddl).contains("KEY idx_stock_price_tick_symbol_time (symbol, price_time)");
        assertThat(ddl).contains("KEY idx_stock_order_order_book_match (symbol, side, order_type, status, limit_price, created_at)");
        assertThat(ddl).doesNotContain(
                DEFAULT_SEED_MARKERS.toArray(String[]::new)
        );
    }

    @Test
    void corporateActionTypes_matchInitialProjectScope() throws IOException {
        List<String> actualTypes = Arrays.stream(StockCorporateActionType.values())
                .map(Enum::name)
                .toList();

        assertThat(actualTypes).containsExactlyElementsOf(INITIAL_CORPORATE_ACTION_SCOPE);
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(INITIAL_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).contains(REQUIRED_CORPORATE_ACTION_CONSTRAINTS.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).doesNotContain(DEFERRED_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
            if (resourcePath.contains("alter")) {
                assertThat(ddl).as(resourcePath).contains(REQUIRED_CORPORATE_ACTION_ALTER_MARKERS.toArray(String[]::new));
            }
        }
    }

    @Test
    void corporateActionDdlResources_createSchemaWithoutDefaultMarketSeed() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).doesNotContain(DEFAULT_SEED_MARKERS.toArray(String[]::new));
        }
    }

    private String readStockAllSql() throws IOException {
        return readDdlResource("db/ddl/stock_all.sql");
    }

    private String readDdlResource(String resourcePath) throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(inputStream).as(resourcePath + " resource").isNotNull();
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
