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

    private static final List<String> BATCH_SIGNAL_LEASE_MARKERS = List.of(
            "requested_business_date",
            "requested_session_epoch",
            "expected_cycle_id",
            "eligible_at",
            "next_attempt_at",
            "attempt_count",
            "max_attempts",
            "claim_token",
            "lease_until",
            "failure_class",
            "idx_stock_batch_job_signal_claim",
            "idx_stock_batch_job_signal_lease",
            "DEAD_LETTER"
    );

    private static final List<String> SIMULATION_CLOCK_TABLE_MARKERS = List.of(
            "stock_simulation_clock",
            "stock_market_business_state",
            "stock_market_session_fence",
            "idx_stock_market_session_fence_state",
            "chk_stock_market_session_fence_market_type",
            "chk_stock_market_session_fence_state",
            "chk_stock_market_session_fence_epoch"
    );

    private static final List<String> ADMIN_QUERY_INDEX_MARKERS = List.of(
            "idx_stock_account_status_id",
            "idx_stock_account_status_participant_id",
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
            "stock_post_close_cycle",
            "stock_post_close_phase_attempt",
            "stock_post_close_readiness_check",
            "stock_post_close_cycle_metric",
            "released_buy_cash",
            "released_sell_quantity",
            "chk_stock_post_close_cycle_metric_releases",
            "next_retry_at",
            "uk_stock_post_close_cycle_scope",
            "uk_stock_post_close_phase_attempt",
            "idx_stock_post_close_cycle_scope_date_status",
            "idx_stock_post_close_phase_attempt_cycle_id",
            "idx_stock_post_close_cycle_metric_run",
            "stock_market_close_run",
            "stock_holding_snapshot",
            "stock_order_book_daily_snapshot",
            "stock_execution_daily_account_snapshot",
            "stock_execution_account_day_summary",
            "buy_gross_amount",
            "sell_gross_amount",
            "buy_net_amount",
            "sell_net_amount",
            "fee_amount",
            "tax_amount",
            "realized_profit",
            "stock_close_account_snapshot",
            "participant_category",
            "stock_close_price_snapshot",
            "stock_close_open_order_summary",
            "stock_close_open_order_snapshot",
            "open_price",
            "first_executed_at",
            "uk_stock_order_book_daily_snapshot_run_symbol",
            "holding_snapshot_run_id",
            "uk_stock_close_account_snapshot_cycle_account",
            "idx_stock_close_account_snapshot_cycle_target",
            "idx_stock_close_account_snapshot_cycle_reconciliation",
            "idx_stock_close_open_order_snapshot_cycle_release_order",
            "idx_stock_close_open_order_snapshot_cycle_stream",
            "source_order_status",
            "uk_stock_close_price_snapshot_cycle_symbol",
            "uk_stock_close_open_order_summary_cycle_symbol",
            "uk_stock_close_open_order_snapshot_cycle_order",
            "uk_portfolio_snapshot_cycle_account",
            "pending_subscription_asset",
            "chk_portfolio_snapshot_pending_subscription_non_negative",
            "chk_portfolio_snapshot_asset_composition",
            "input_hash",
            "calculation_version",
            "data_quality_status"
    );

    private static final List<String> CLEAR_DATA_REQUIRED_TRUNCATES = List.of(
            "TRUNCATE TABLE stock_batch_job_lock;",
            "TRUNCATE TABLE stock_batch_job_signal;",
            "TRUNCATE TABLE stock_batch_job_control;",
            "TRUNCATE TABLE stock_execution;",
            "TRUNCATE TABLE stock_account_cash_flow;",
            "TRUNCATE TABLE stock_auto_participant_cash_flow_run;",
            "TRUNCATE TABLE stock_price_tick;",
            "TRUNCATE TABLE stock_order;",
            "TRUNCATE TABLE stock_auto_participant_order_schedule;",
            "TRUNCATE TABLE portfolio_snapshot;",
            "TRUNCATE TABLE stock_close_open_order_snapshot;",
            "TRUNCATE TABLE stock_close_open_order_summary;",
            "TRUNCATE TABLE stock_close_price_snapshot;",
            "TRUNCATE TABLE stock_close_account_snapshot;",
            "TRUNCATE TABLE stock_post_close_cycle_metric;",
            "TRUNCATE TABLE stock_post_close_readiness_check;",
            "TRUNCATE TABLE stock_execution_daily_account_snapshot;",
            "TRUNCATE TABLE stock_execution_account_day_summary;",
            "TRUNCATE TABLE stock_order_book_daily_snapshot;",
            "TRUNCATE TABLE stock_market_close_run;",
            "TRUNCATE TABLE stock_listing_auto_account_config;",
            "TRUNCATE TABLE stock_post_close_phase_attempt;",
            "TRUNCATE TABLE stock_post_close_cycle;",
            "TRUNCATE TABLE stock_market_session_fence;",
            "TRUNCATE TABLE stock_market_business_state;",
            "TRUNCATE TABLE stock_simulation_clock;"
    );

    private static final List<String> CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_REQUIRED_MARKERS = List.of(
            "UPDATE stock_account",
            "SET cash_balance = 0.00",
            "TRUNCATE TABLE stock_batch_job_signal;",
            "TRUNCATE TABLE stock_corporate_action_entitlement;",
            "TRUNCATE TABLE stock_execution;",
            "TRUNCATE TABLE stock_account_cash_flow;",
            "TRUNCATE TABLE stock_auto_participant_cash_flow_run;",
            "TRUNCATE TABLE stock_holding_snapshot;",
            "TRUNCATE TABLE stock_close_open_order_snapshot;",
            "TRUNCATE TABLE stock_close_open_order_summary;",
            "TRUNCATE TABLE stock_close_price_snapshot;",
            "TRUNCATE TABLE stock_post_close_readiness_check;",
            "TRUNCATE TABLE stock_close_account_snapshot;",
            "TRUNCATE TABLE stock_post_close_cycle_metric;",
            "TRUNCATE TABLE stock_execution_daily_account_snapshot;",
            "TRUNCATE TABLE stock_execution_account_day_summary;",
            "TRUNCATE TABLE stock_order_book_daily_snapshot;",
            "TRUNCATE TABLE stock_market_close_run;",
            "TRUNCATE TABLE stock_post_close_phase_attempt;",
            "TRUNCATE TABLE stock_post_close_cycle;",
            "TRUNCATE TABLE stock_market_session_fence;",
            "TRUNCATE TABLE stock_market_business_state;",
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
            "INSERT INTO stock_market_business_state(",
            "INSERT INTO stock_market_session_fence(",
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
                "operation_mode VARCHAR(30) NOT NULL",
                "strategy_profile VARCHAR(30) NOT NULL",
                "initial_inventory_quantity BIGINT NOT NULL",
                "initial_issue_price DECIMAL(19,2) NOT NULL",
                "target_spread_ticks INT NOT NULL",
                "aggressive_order_ratio DECIMAL(8,4) NOT NULL",
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
    void eodSessionFenceAlterDdl_isFailClosedAndSyncedWithBatchServiceCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_session_fence_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_session_fence_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_market_business_state",
                "CREATE TABLE IF NOT EXISTS stock_market_session_fence",
                "PRIMARY KEY (market_type, symbol)",
                "session_epoch BIGINT NOT NULL",
                "session_state VARCHAR(20) NOT NULL",
                "'CLOSED'",
                "INSERT IGNORE INTO stock_market_session_fence"
        );
        assertThat(backDdl).doesNotContain("symbol VARCHAR(20) NULL");
    }

    @Test
    void eodCycleAlterDdl_hasLogicalUniquenessAttemptHistoryAndSyncedBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_cycle_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_cycle_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "UNIQUE KEY uk_stock_post_close_cycle_scope (business_date, scope_type, scope_key)",
                "UNIQUE KEY uk_stock_post_close_phase_attempt (cycle_id, phase, attempt_no)",
                "KEY idx_stock_post_close_cycle_scope_date_status (scope_type, scope_key, business_date, status, id)",
                "KEY idx_stock_post_close_phase_attempt_cycle_id (cycle_id, id)",
                "next_retry_at DATETIME NULL",
                "ADD COLUMN next_retry_at DATETIME NULL AFTER lease_until",
                "'FULL_MARKET'",
                "'ALL'",
                "symbol IS NULL",
                "ON DUPLICATE KEY UPDATE"
        );
    }

    @Test
    void recurringCashRunAlterDdl_isRestartableBoundedAndSyncedBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_cash_flow_run_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_cash_flow_run_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_cash_flow_run",
                "PRIMARY KEY (run_key)",
                "last_account_id BIGINT NOT NULL DEFAULT 0",
                "processed_count BIGINT NOT NULL DEFAULT 0",
                "idx_stock_auto_participant_cash_flow_run_completed"
        );
        assertThat(backDdl).doesNotContain(
                "stock_order ",
                "stock_execution "
        );
    }

    @Test
    void eodImmutableSnapshotAlterDdl_freezesSettlementInputsAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_immutable_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_immutable_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.columns",
                "information_schema.statistics",
                "information_schema.table_constraints",
                "PREPARE stock_eod_immutable_statement",
                "CREATE TABLE IF NOT EXISTS stock_close_account_snapshot",
                "participant_category VARCHAR(30) NOT NULL DEFAULT 'MANUAL_PARTICIPANT'",
                "chk_stock_close_account_snapshot_participant_category",
                "CREATE TABLE IF NOT EXISTS stock_post_close_cycle_metric",
                "released_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00",
                "released_sell_quantity BIGINT NOT NULL DEFAULT 0",
                "chk_stock_post_close_cycle_metric_releases",
                "CREATE TABLE IF NOT EXISTS stock_close_price_snapshot",
                "CREATE TABLE IF NOT EXISTS stock_close_open_order_summary",
                "CREATE TABLE IF NOT EXISTS stock_close_open_order_snapshot",
                "UNIQUE KEY uk_stock_close_account_snapshot_cycle_account (close_cycle_id, account_id)",
                "UNIQUE KEY uk_stock_close_price_snapshot_cycle_symbol (close_cycle_id, symbol)",
                "UNIQUE KEY uk_stock_close_open_order_snapshot_cycle_order (close_cycle_id, order_id)",
                "ADD UNIQUE KEY uk_portfolio_snapshot_cycle_account (close_cycle_id, account_id)",
                "ADD COLUMN input_hash VARCHAR(64) NULL",
                "ADD COLUMN calculation_version VARCHAR(40) NULL",
                "ADD COLUMN data_quality_status VARCHAR(20) NULL"
        );
        assertThat(backDdl)
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void eodReportParticipantSnapshotAlterDdl_freezesClassificationWithoutScanningHotLedgers()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_report_participant_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_report_participant_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.columns",
                "information_schema.table_constraints",
                "ADD COLUMN participant_category VARCHAR(30) NULL AFTER account_status",
                "chk_stock_close_account_snapshot_participant_category",
                "stock_auto_participant participant",
                "snapshot.user_key LIKE 'stock-listing-%'"
        );
        assertThat(backDdl).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution",
                "FROM stock_order",
                "JOIN stock_order",
                "FROM stock_execution",
                "JOIN stock_execution",
                "ADD COLUMN IF NOT EXISTS"
        );
    }

    @Test
    void accountParticipantCategoryAlterDdl_persistsCurrentRoleWithoutScanningHotLedgers()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_account_participant_category_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_account_participant_category_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "ADD COLUMN participant_category VARCHAR(30) NULL",
                "stock_auto_participant participant",
                "account.user_key LIKE 'stock-listing-%'",
                "WHERE participant_category IS NULL",
                "MODIFY COLUMN participant_category VARCHAR(30) NOT NULL DEFAULT ''MANUAL_PARTICIPANT''",
                "chk_stock_account_participant_category",
                "idx_stock_account_status_participant_id"
        );
        assertThat(backDdl).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution",
                "FROM stock_order",
                "JOIN stock_order",
                "FROM stock_execution",
                "JOIN stock_execution"
        );
    }

    @Test
    void executionProfitSummaryAlterDdl_backfillsOnlyInMaintenanceWindowAndMatchesBatchCopy()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_execution_profit_summary_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_execution_profit_summary_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "stock_execution_profit_summary_requires_backfill",
                "ALTER TABLE stock_execution_account_day_summary",
                "ADD COLUMN buy_gross_amount",
                "ADD COLUMN sell_net_amount",
                "ADD COLUMN realized_profit",
                "FROM stock_execution",
                "GROUP BY DATE(executed_at), account_id"
        );
        assertThat(backDdl)
                .doesNotContain("ALTER TABLE stock_execution ")
                .doesNotContain("CREATE INDEX")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void investorTypeCleanupAlterDdl_containsExpectedGuardsAndAvoidsHotLedgers() throws IOException {
        String ddl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_investor_type_cleanup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(ddl)
                .contains(
                        "USE STOCK_SERVICE;",
                        "information_schema.table_constraints",
                        "information_schema.columns",
                        "DROP COLUMN `investor_type`",
                        "CALL stock_drop_obsolete_investor_type('stock_account', 'chk_stock_account_investor_type')",
                        "CALL stock_drop_obsolete_investor_type('stock_auto_participant', 'chk_stock_auto_participant_investor_type')",
                        "CALL stock_drop_obsolete_investor_type('stock_close_account_snapshot', 'chk_stock_close_account_snapshot_investor_type')",
                        "stock_execution_account_day_summary",
                        "stock_execution_daily_account_snapshot",
                        "DROP PROCEDURE stock_drop_obsolete_investor_type"
                )
                .doesNotContain(
                        "ALTER TABLE stock_order ",
                        "ALTER TABLE stock_execution ",
                        "FROM stock_order ",
                        "FROM stock_execution "
                );
    }

    @Test
    void eodVolumeIndexesAlterDdl_isIdempotentAvoidsHotLedgersAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_volume_indexes_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_volume_indexes_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.statistics",
                "idx_stock_close_account_snapshot_cycle_target",
                "idx_stock_close_account_snapshot_cycle_reconciliation",
                "idx_stock_close_open_order_snapshot_cycle_release_order",
                "idx_stock_close_open_order_snapshot_cycle_stream",
                "idx_stock_account_cash_flow_account_id",
                "idx_stock_corporate_action_entitlement_account_status",
                "idx_stock_post_close_cycle_scope_status_date",
                "idx_stock_batch_job_signal_cycle_id",
                "source_order_status",
                "ALTER TABLE stock_close_account_snapshot",
                "ALTER TABLE stock_close_open_order_snapshot"
        );
        assertThat(backDdl).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution"
        );
    }

    @Test
    void portfolioPostCloseCashDataFix_isGuardedIdempotentAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "stock_portfolio_asset_fix_guard",
                "pending_subscription_asset",
                "portfolio-v2-frozen-close",
                "portfolio-v3-post-close-cash",
                "portfolio-v4-explicit-subscription-asset",
                "portfolio-v1-explicit-asset-backfill",
                "account_snapshot.post_cancel_cash",
                "entitlement.subscribed_at <= legacy.created_at",
                "chk_portfolio_snapshot_asset_composition",
                "SHA2(",
                "START TRANSACTION;",
                "COMMIT;"
        );
        assertThat(backDdl).doesNotContain(
                "FROM stock_order ",
                "JOIN stock_order ",
                "UPDATE stock_order ",
                "FROM stock_execution ",
                "JOIN stock_execution ",
                "UPDATE stock_execution "
        );
    }

    @Test
    void portfolioReturnContractAlter_usesImmutableSnapshotsWithoutHotLedgers() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_portfolio_snapshot_return_contract_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_portfolio_snapshot_return_contract_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "chk_portfolio_snapshot_return_contract",
                "UNDEFINED_ZERO_CONTRIBUTION",
                "UNDEFINED_NEGATIVE_CONTRIBUTION",
                "stock_close_account_snapshot"
        );
        assertThat(batchDdl).contains(
                "chk_portfolio_snapshot_return_contract",
                "UNDEFINED_ZERO_CONTRIBUTION",
                "UNDEFINED_NEGATIVE_CONTRIBUTION",
                "stock_close_account_snapshot"
        );
        assertThat(backDdl).doesNotContain("stock_order", "stock_execution");
        assertThat(batchDdl).doesNotContain("stock_order", "stock_execution");
    }

    @Test
    void executionDailyAccountAlterDdl_usesMysqlCompatibleMetadataGuardAndMatchesBatchCopy()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_execution_daily_account_last_executed_at_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_execution_daily_account_last_executed_at_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.columns",
                "ALTER TABLE stock_execution_daily_account_snapshot ADD COLUMN last_executed_at"
        );
        assertThat(backDdl).doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void batchJobSignalLeaseAlterDdl_hasClaimBackoffFieldsAndSyncedBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_batch_job_signal_lease_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_batch_job_signal_lease_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.columns",
                "information_schema.check_constraints",
                "PREPARE stock_batch_signal_statement",
                "requested_business_date DATE NULL",
                "requested_session_epoch BIGINT NULL",
                "expected_cycle_id BIGINT NULL",
                "eligible_at DATETIME NULL",
                "next_attempt_at DATETIME NOT NULL",
                "claim_token VARCHAR(64) NULL",
                "lease_until DATETIME NULL",
                "'DEFERRED'",
                "'DEAD_LETTER'",
                "idx_stock_batch_job_signal_claim",
                "idx_stock_batch_job_signal_lease",
                "UPDATE stock_batch_job_signal job_signal"
        );
        assertThat(backDdl)
                .doesNotContain("UPDATE stock_batch_job_signal signal")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void eodApplicationRollbackAlterDdl_isFailClosedNonDestructiveAndSyncedWithBatchCopy()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_application_rollback_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_application_rollback_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "status IN (''DEFERRED'', ''PROCESSING'', ''DEAD_LETTER'')",
                "status = ''PENDING'' AND eligible_at IS NOT NULL",
                "EOD_APPLICATION_ROLLBACK",
                "failure_class = ''APPLICATION_ROLLBACK''",
                "DROP CHECK chk_stock_batch_job_signal_status",
                "MODIFY COLUMN next_attempt_at DATETIME NULL"
        );
        assertThat(backDdl).doesNotContain(
                "DROP TABLE",
                "DROP COLUMN",
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution",
                "FROM stock_order",
                "FROM stock_execution"
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
    void portfolioSnapshotHoldingMetricsAlterDdl_isGuardedAndMatchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_portfolio_snapshot_holding_metrics_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_portfolio_snapshot_holding_metrics_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "stock_migration_required_portfolio_snapshot_holding_metrics_schema",
                "@stock_portfolio_snapshot_holding_metric_column_count = 0",
                "@stock_portfolio_snapshot_holding_metric_correct_column_count = 3",
                "holding_quantity BIGINT NULL",
                "reserved_sell_quantity BIGINT NULL",
                "holding_position_count BIGINT NULL",
                "chk_portfolio_snapshot_holding_metrics_complete",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_quantity >= 0%'",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity >= 0%'",
                "reserved_sell_quantity <= holding_quantity",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_position_count >= 0%'",
                "SET SESSION lock_wait_timeout = 15",
                "ALGORITHM=COPY",
                "LOCK=SHARED"
        );
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
    void capitalIncreaseLifecycleAlterDdl_isGuardedAndOwnedByBackService() throws IOException {
        Path backPath = Path.of(
                "src/main/resources/db/ddl/stock_capital_increase_lifecycle_hardening_alter.sql"
        );
        Path batchPath = Path.of(
                "../stock-batch-service/src/main/resources/db/ddl/stock_capital_increase_lifecycle_hardening_alter.sql"
        );
        String ddl = Files.readString(backPath, StandardCharsets.UTF_8);

        assertThat(firstExecutableSqlLine(ddl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchPath).doesNotExist();
        assertThat(ddl).contains(
                "stock_migration_required_capital_increase_lifecycle",
                "stock_migration_required_capital_increase_lifecycle_data",
                "stock_migration_required_post_close_cash_order",
                "scope_type = 'FULL_MARKET'",
                "scope_key = 'ALL'",
                "'PENDING', 'RUNNING', 'DEFERRED', 'FAILED'",
                "phase <> 'COMPLETED'",
                "record_date",
                "entitlement_close_cycle_id",
                "entitlement_close_run_id",
                "PARTIALLY_SUBSCRIBED",
                "status <> 'EXPIRED'",
                "forfeited_share_quantity",
                "corporate_action_entitlement_id",
                "effective_business_date",
                "chk_stock_corporate_action_entitlement_close_pair",
                "chk_stock_corporate_action_entitlement_finalized_share_limit"
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
    void autoMarketPressureDistributionAlterDdl_isGuardedAndMatchesBatchCopyAndNewContract() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_market_pressure_distribution_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_market_pressure_distribution_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(backDdl).isEqualTo(batchDdl);
        assertThat(backDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.columns",
                "information_schema.check_constraints",
                "stock_migration_required_auto_market_pressure_distribution_schema",
                "@stock_auto_market_pressure_legacy_column_count = 13",
                "@stock_auto_market_pressure_new_column_count = 0",
                "@stock_auto_market_pressure_legacy_check_count = 15",
                "DROP COLUMN intensity",
                "primary_price_pressure_bias",
                "secondary_execution_aggression_pressure_bias",
                "price_pressure INT NULL",
                "execution_aggression_pressure INT NULL",
                "WHEN 'SLOT_0600' THEN 1",
                "WHEN 'SLOT_0900' THEN 1",
                "WHEN 'SLOT_1200' THEN 1",
                "WHEN 'SLOT_1500' THEN 1",
                "BETWEEN -100 AND 100"
        );
        assertThat(backDdl).containsSubsequence(
                "stock_migration_required_auto_market_pressure_distribution_schema",
                "ALTER TABLE stock_auto_market_config"
        );
    }

    @Test
    void autoMarketRegimeCountWeightsDdl_matchesBatchAndCanonicalSchema() throws IOException {
        String backAlter = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_market_regime_count_weights_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchAlter = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_market_regime_count_weights_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(List.of(
                backAlter.equals(batchAlter),
                backAlter.contains("USE STOCK_SERVICE"),
                backAlter.contains("primary_regime_count_1_weight"),
                backAlter.contains("primary_regime_count_4_weight"),
                backAlter.contains("source_regime_phase"),
                backAlter.contains("chk_stock_auto_market_regime_count_weights"),
                canonicalDdl.contains("primary_regime_count_4_weight INT NOT NULL DEFAULT 100"),
                canonicalDdl.contains("source_regime_phase VARCHAR(20) NULL")
        )).doesNotContain(false);
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
                "DROP INDEX idx_stock_price_tick_symbol_time",
                "ALGORITHM=INPLACE, LOCK=NONE"
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
    void marketTurnoverNormalizationAlterDdl_onlyUpdatesLegacyDoubleCountedSnapshots() throws IOException {
        String ddl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_market_turnover_normalization_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(ddl).contains(
                "USE STOCK_SERVICE",
                "UPDATE stock_order_book_daily_snapshot",
                "execution_count = execution_count / 2",
                "execution_quantity = buy_quantity",
                "turnover_amount = ROUND(turnover_amount / 2, 2)",
                "buy_quantity = sell_quantity",
                "execution_quantity = buy_quantity + sell_quantity"
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
        assertThat(extractCreateTableBlock(stockAllDdl, "stock_batch_job_signal"))
                .contains(BATCH_SIGNAL_LEASE_MARKERS.toArray(String[]::new));
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
