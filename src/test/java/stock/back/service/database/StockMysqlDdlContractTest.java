package stock.back.service.database;

import org.junit.jupiter.api.Test;
import stock.back.service.database.entity.StockCorporateActionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StockMysqlDdlContractTest {

    private static final List<String> INITIAL_CORPORATE_ACTION_SCOPE = List.of(
            "INITIAL_ISSUE",
            "PAID_IN_CAPITAL_INCREASE",
            "STOCK_SPLIT",
            "CASH_DIVIDEND",
            "BONUS_ISSUE",
            "STOCK_DIVIDEND",
            "DELISTING"
    );

    private static final List<String> DEFERRED_CORPORATE_ACTION_SCOPE = List.of(
            "SPECIAL_DIVIDEND",
            "CAPITAL_REDUCTION",
            "REVERSE_SPLIT",
            "RIGHTS_OFFERING",
            "MERGER",
            "SPIN_OFF"
    );

    private static final List<String> REQUIRED_CORPORATE_ACTION_CONSTRAINTS = List.of(
            "chk_stock_corporate_action_type_valid",
            "chk_stock_corporate_action_status_valid",
            "chk_stock_corporate_action_share_quantity",
            "chk_stock_corporate_action_issue_price",
            "chk_stock_corporate_action_dividend_amount",
            "chk_stock_corporate_action_delisting_treatment",
            "chk_stock_corporate_action_base_price",
            "chk_stock_corporate_action_ex_rights_price",
            "chk_stock_corporate_action_paid_dates",
            "chk_stock_corporate_action_listing_dates",
            "chk_stock_corporate_action_subscription_dates",
            "chk_stock_corporate_action_paid_date_order",
            "chk_stock_corporate_action_split_from",
            "chk_stock_corporate_action_split_to",
            "chk_stock_corporate_action_issue_required",
            "chk_stock_corporate_action_paid_schedule_required",
            "chk_stock_corporate_action_split_required",
            "chk_stock_corporate_action_dividend_required",
            "chk_stock_corporate_action_free_share_required",
            "chk_stock_corporate_action_delisting_required",
            "chk_stock_corporate_action_field_scope",
            "chk_stock_corporate_action_initial_listed",
            "chk_stock_corporate_action_entitlement_subscribed_share_limit"
    );

    private static final List<String> AUTO_PARTICIPANT_PROFILE_TYPES = List.of(
            "NEWS_REACTIVE", "MOMENTUM_FOLLOWER", "CONTRARIAN", "LOSS_AVERSE",
            "OVERCONFIDENT", "HERD_FOLLOWER", "MARKET_MAKER", "NOISE_TRADER",
            "VALUE_ANCHOR", "SCALPER", "DAY_TRADER", "SWING_TRADER",
            "LONG_TERM_HOLDER", "PAYDAY_ACCUMULATOR", "DIVIDEND_REINVESTOR",
            "LIMIT_DOWN_TRAPPED", "AVERAGE_DOWN_BUYER", "STOP_LOSS_TRADER",
            "FOMO_BUYER", "PANIC_SELLER", "DIP_BUYER", "PROFIT_LOCKER",
            "LIQUIDITY_AVOIDANT", "CASH_DEFENSIVE", "WHALE", "SMALL_DIVERSIFIER", "OBSERVER"
    );

    private static final List<String> CORPORATE_ACTION_DDL_RESOURCES = List.of(
            "db/ddl/stock_all.sql"
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

    private static final List<String> BATCH_OPERATION_TABLE_MARKERS = List.of(
            "stock_batch_job_control",
            "stock_batch_job_lock",
            "stock_batch_job_signal"
    );

    private static final List<String> SIMULATION_CLOCK_TABLE_MARKERS = List.of(
            "stock_simulation_clock"
    );

    private static final List<String> ADMIN_QUERY_INDEX_MARKERS = List.of(
            "idx_stock_account_status_id",
            "idx_stock_account_cash_flow_account_reason_creator_time",
            "idx_stock_account_cash_flow_time",
            "idx_stock_order_market_status_side",
            "idx_stock_order_market_status_account_time",
            "idx_stock_order_market_account_time",
            "idx_stock_order_market_created_status",
            "idx_stock_execution_time_account",
            "idx_stock_execution_source_account_time",
            "idx_stock_execution_source_time_account",
            "idx_stock_execution_source_symbol_time",
            "idx_stock_execution_source_time",
            "idx_stock_holding_symbol_account",
            "idx_stock_auto_participant_active",
            "idx_stock_auto_participant_profile_active",
            "idx_stock_auto_participant_symbol_lookup",
            "idx_stock_auto_order_schedule_due",
            "idx_stock_corporate_action_created",
            "idx_stock_corporate_action_type_created",
            "idx_stock_corporate_action_status_symbol"
    );

    private static final List<String> MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS = List.of(
            "stock_market_close_run",
            "stock_holding_snapshot",
            "stock_order_book_daily_snapshot",
            "uk_stock_order_book_daily_snapshot_run_symbol",
            "holding_snapshot_run_id"
    );

    private static final List<String> CLEAR_DATA_REQUIRED_TRUNCATES = List.of(
            "TRUNCATE TABLE stock_batch_job_lock;",
            "TRUNCATE TABLE stock_batch_job_signal;",
            "TRUNCATE TABLE stock_batch_job_control;",
            "TRUNCATE TABLE stock_execution;",
            "TRUNCATE TABLE stock_account_cash_flow;",
            "TRUNCATE TABLE stock_price_tick;",
            "TRUNCATE TABLE stock_order;",
            "TRUNCATE TABLE stock_auto_participant_order_schedule;",
            "TRUNCATE TABLE portfolio_snapshot;",
            "TRUNCATE TABLE stock_order_book_daily_snapshot;",
            "TRUNCATE TABLE stock_market_close_run;",
            "TRUNCATE TABLE stock_listing_auto_account_config;",
            "TRUNCATE TABLE stock_simulation_clock;"
    );

    private static final List<String> CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_REQUIRED_MARKERS = List.of(
            "UPDATE stock_account",
            "SET cash_balance = 0.00",
            "TRUNCATE TABLE stock_batch_job_signal;",
            "TRUNCATE TABLE stock_corporate_action_entitlement;",
            "TRUNCATE TABLE stock_execution;",
            "TRUNCATE TABLE stock_account_cash_flow;",
            "TRUNCATE TABLE stock_holding_snapshot;",
            "TRUNCATE TABLE stock_order_book_daily_snapshot;",
            "TRUNCATE TABLE stock_market_close_run;",
            "TRUNCATE TABLE stock_holding;",
            "TRUNCATE TABLE portfolio_snapshot;",
            "TRUNCATE TABLE stock_order;",
            "TRUNCATE TABLE stock_auto_participant_order_schedule;",
            "TRUNCATE TABLE stock_price_tick;",
            "TRUNCATE TABLE stock_corporate_action;",
            "INSERT INTO stock_simulation_clock(",
            "ON DUPLICATE KEY UPDATE",
            "accumulated_real_seconds = 0",
            "running = false",
            "last_started_at = null",
            "last_heartbeat_at = null",
            "INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)",
            "CAST(base_simulation_date AS DATETIME)",
            "'runtime-history-reset'",
            "current_price = VALUES(current_price)",
            "INSERT INTO stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)",
            "FROM stock_listing_auto_account_config c",
            "JOIN stock_order_book_market_config m",
            "m.market_status = 'OPEN'",
            "JOIN stock_price p",
            "i.tradable_shares",
            "c.position_side = 'SELL_ONLY'"
    );

    private static final List<String> CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_FORBIDDEN_TRUNCATES = List.of(
            "TRUNCATE TABLE stock_batch_job_lock;",
            "TRUNCATE TABLE stock_batch_job_control;",
            "TRUNCATE TABLE stock_account;",
            "TRUNCATE TABLE stock_auto_participant;",
            "TRUNCATE TABLE stock_auto_participant_profile_config;",
            "TRUNCATE TABLE stock_auto_participant_symbol_config;",
            "TRUNCATE TABLE stock_auto_market_config;",
            "TRUNCATE TABLE stock_listing_auto_account_config;",
            "TRUNCATE TABLE stock_price;",
            "TRUNCATE TABLE stock_simulation_clock;",
            "TRUNCATE TABLE stock_virtual_market_config;",
            "TRUNCATE TABLE stock_order_book_market_config;",
            "TRUNCATE TABLE stock_order_book_instrument;",
            "TRUNCATE TABLE stock_instrument;",
            "WHERE status = 'ACTIVE';",
            "TIMESTAMP(CURDATE())"
    );

    @Test
    void stockAllSql_createsSchemaWithoutDefaultMarketSeed() throws IOException {
        String ddl = readStockAllSql();

        assertThat(ddl).contains("KEY idx_stock_price_tick_symbol_time_id (symbol, price_time, id)");
        assertThat(ddl).contains("KEY idx_stock_order_account_market_created (account_id, market_type, created_at)");
        assertThat(ddl).contains("KEY idx_stock_execution_account_source_time (account_id, source, executed_at)");
        assertThat(ddl).contains("KEY idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount)");
        assertThat(ddl).contains("KEY idx_stock_order_order_book_match (market_type, symbol, side, status, order_type, limit_price, created_at, id)");
        assertThat(ddl).contains("KEY idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id)");
        assertThat(ddl).contains(ADMIN_QUERY_INDEX_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(BATCH_OPERATION_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(SIMULATION_CLOCK_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(
                "target_buy_quantity BIGINT NOT NULL",
                "target_sell_quantity BIGINT NOT NULL",
                "target_holding_quantity BIGINT NOT NULL",
                "inventory_band_quantity BIGINT NOT NULL",
                "buy_price_offset_direction VARCHAR(10) NOT NULL",
                "sell_price_offset_direction VARCHAR(10) NOT NULL",
                "WHEN 'TWO_SIDED' THEN 1",
                "chk_stock_listing_auto_account_target_buy",
                "chk_stock_listing_auto_account_target_sell",
                "chk_stock_listing_auto_account_target_holding",
                "chk_stock_listing_auto_account_inventory_band"
        );
        assertThat(ddl).doesNotContain(
                DEFAULT_SEED_MARKERS.toArray(String[]::new)
        );
    }

    @Test
    void stockAllSql_isSoleCanonicalMysqlBusinessDdl() {
        Path canonicalDdl = Path.of("src/main/resources/db/ddl/stock_all.sql");
        Path batchDuplicate = Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_all.sql");

        assertThat(canonicalDdl).isRegularFile();
        assertThat(batchDuplicate).doesNotExist();
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
        }
    }

    @Test
    void corporateActionDdlResources_createSchemaWithoutDefaultMarketSeed() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).doesNotContain(DEFAULT_SEED_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void alterDdlFiles_selectStockServiceSchemaBeforeChanges() throws IOException {
        List<Path> alterFiles = listAlterDdlFiles();

        assertThat(alterFiles).isNotEmpty();
        for (Path alterFile : alterFiles) {
            String ddl = Files.readString(alterFile, StandardCharsets.UTF_8);

            assertThat(firstExecutableSqlLine(ddl)).as(alterFile.toString()).isEqualTo("USE STOCK_SERVICE;");
        }
    }

    @Test
    void capitalIncreaseAlterDdl_guardsLegacyRowsAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_capital_increase_subscription_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_capital_increase_subscription_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "stock_legacy_paid_in_unsafe_count",
                "status NOT IN ('ANNOUNCED', 'LISTED')",
                "stock_migration_required_legacy_paid_in_entitlements",
                "DATE_ADD(ex_rights_date, INTERVAL 1 DAY)",
                "DATE_SUB(payment_date, INTERVAL 1 DAY)",
                "stock_paid_in_incomplete_contract_count",
                "chk_stock_corporate_action_paid_date_order",
                "chk_stock_corporate_action_entitlement_subscribed_share_limit"
        );
    }

    @Test
    void capitalIncreaseHardeningAlterDdl_isSeparateAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_capital_increase_contract_hardening_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_capital_increase_contract_hardening_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "stock_paid_in_invalid_schedule_count",
                "payment_date <= subscription_end_date",
                "information_schema.statistics",
                "idx_stock_corporate_action_created",
                "idx_stock_corporate_action_type_created",
                "stock_migration_required_paid_in_schedule",
                "stock_auto_event_profile_invalid_type_count",
                "stock_migration_required_event_profile_type",
                "chk_stock_auto_event_profile_type",
                "stock_entitlement_subscribed_share_limit_violation_count",
                "stock_migration_required_entitlement_share_limit",
                "chk_stock_corporate_action_entitlement_subscribed_share_limit"
        );
    }

    @Test
    void schemaContractAlignmentAlterDdl_isGuardedAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_schema_contract_alignment_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_schema_contract_alignment_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "stock_migration_required_schema_contract_alignment",
                "ALTER COLUMN market_enabled DROP DEFAULT",
                "ALTER COLUMN regime_phase DROP DEFAULT",
                "chk_stock_order_book_daily_snapshot_flow",
                "chk_stock_order_book_daily_snapshot_open_order",
                "chk_stock_order_book_daily_snapshot_holding",
                "chk_stock_order_book_daily_regime_phase",
                "chk_stock_order_book_daily_regime_execution_aggression",
                "action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')"
        ).doesNotContain("ADDITIONAL_ISSUE", "SIGNAL SQLSTATE");
    }

    @Test
    void priceTickLatestLookupAlterDdl_isIdempotentAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_price_tick_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_price_tick_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.statistics",
                "ADD INDEX idx_stock_price_tick_symbol_time_id (symbol, price_time, id)",
                "DROP INDEX idx_stock_price_tick_symbol_time"
        );
    }

    @Test
    void activityLatestLookupAlterDdl_isIdempotentAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_activity_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_activity_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(batchDdl)).isEqualTo(normalizeSqlBlock(backDdl));
        assertThat(backDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.statistics",
                "ADD INDEX idx_stock_order_account_market_created (account_id, market_type, created_at)",
                "ADD INDEX idx_stock_execution_account_source_time (account_id, source, executed_at)",
                "ADD INDEX idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount)",
                "SELECT 1"
        );
    }

    @Test
    void eventProfileDdlResources_allowOnlyKnownAutoParticipantProfiles() throws IOException {
        List<String> ddlResources = List.of(
                readDdlResource("db/ddl/stock_all.sql"),
                readDdlResource("db/ddl/stock_auto_participant_event_profile_config_alter.sql"),
                readDdlResource("db/ddl/stock_capital_increase_contract_hardening_alter.sql")
        );

        for (String ddl : ddlResources) {
            String eventProfileBlock = ddl.substring(ddl.indexOf("chk_stock_auto_event_profile_type"));
            assertThat(eventProfileBlock).contains(AUTO_PARTICIPANT_PROFILE_TYPES.toArray(String[]::new));
        }
    }

    @Test
    void initialSchemaDdl_containsAdminQueryPerformanceIndexNames() throws IOException {
        String stockAllDdl = readStockAllSql();

        assertThat(stockAllDdl).contains(ADMIN_QUERY_INDEX_MARKERS.toArray(String[]::new));
    }

    @Test
    void initialSchemaDdl_containsBatchOperationTableDefinitions() throws IOException {
        String stockAllDdl = readStockAllSql();

        for (String tableName : BATCH_OPERATION_TABLE_MARKERS) {
            assertThat(normalizeSqlBlock(extractCreateTableBlock(stockAllDdl, tableName)))
                    .as(tableName)
                    .isNotBlank();
        }
    }

    @Test
    void stockClearDataMaintenanceSql_clearsSimulationTradeHistoryAndClock() throws IOException {
        String stockAllDdl = readStockAllSql();
        String maintenanceSql = Files.readString(
                Path.of("src/main/resources/db/maintenance/stock_clear_data.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(maintenanceSql)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(maintenanceSql).contains(CLEAR_DATA_REQUIRED_TRUNCATES.toArray(String[]::new));
        assertThat(extractCreateTableNames(stockAllDdl))
                .allSatisfy(tableName -> assertThat(maintenanceSql)
                        .as(tableName)
                        .contains("TRUNCATE TABLE " + tableName + ";"));
    }

    @Test
    void stockClearRuntimeHistoryKeepParticipantsSql_preservesParticipantsAndMarketConfiguration() throws IOException {
        String maintenanceSql = Files.readString(
                Path.of("src/main/resources/db/maintenance/stock_clear_runtime_history_keep_participants.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(maintenanceSql)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(maintenanceSql).contains(CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_REQUIRED_MARKERS.toArray(String[]::new));
        assertThat(maintenanceSql).doesNotContain(CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_FORBIDDEN_TRUNCATES.toArray(String[]::new));
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

    private List<Path> listAlterDdlFiles() throws IOException {
        try (var paths = Files.list(Path.of("src/main/resources/db/ddl"))) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith("_alter.sql"))
                    .sorted()
                    .toList();
        }
    }

    private String firstExecutableSqlLine(String ddl) {
        return ddl.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("--"))
                .findFirst()
                .orElse("");
    }

    private String extractCreateTableBlock(String ddl, String tableName) {
        String marker = "CREATE TABLE IF NOT EXISTS " + tableName + " (";
        int startIndex = ddl.indexOf(marker);
        assertThat(startIndex).as(tableName + " create table marker").isGreaterThanOrEqualTo(0);

        int endIndex = ddl.indexOf(";", startIndex);
        assertThat(endIndex).as(tableName + " create table terminator").isGreaterThan(startIndex);

        return ddl.substring(startIndex, endIndex + 1);
    }

    private String normalizeSqlBlock(String sql) {
        return sql.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private List<String> extractCreateTableNames(String ddl) {
        return Pattern.compile("^CREATE TABLE IF NOT EXISTS\\s+([a-zA-Z0-9_]+)\\s*\\(", Pattern.MULTILINE)
                .matcher(ddl)
                .results()
                .map(match -> match.group(1))
                .toList();
    }
}
