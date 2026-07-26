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
            "TRUNCATE TABLE stock_auto_participant_share_return;",
            "TRUNCATE TABLE stock_auto_participant_withdrawal;",
            "TRUNCATE TABLE stock_auto_participant_order_budget;",
            "TRUNCATE TABLE stock_auto_participant_funding_budget;",
            "TRUNCATE TABLE stock_underwriting_daily_supply_state;",
            "TRUNCATE TABLE stock_liquidity_daily_state;",
            "TRUNCATE TABLE stock_liquidity_mandate;",
            "TRUNCATE TABLE stock_institution_decision_item;",
            "TRUNCATE TABLE stock_institution_decision_run;",
            "TRUNCATE TABLE stock_institution_daily_budget;",
            "TRUNCATE TABLE stock_institution_symbol_mandate;",
            "TRUNCATE TABLE stock_institution_portfolio;",
            "TRUNCATE TABLE stock_auto_participant_position_state;",
            "TRUNCATE TABLE stock_auto_participant_performance_state;",
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
            "TRUNCATE TABLE stock_auto_participant_order_budget;",
            "TRUNCATE TABLE stock_auto_participant_funding_budget;",
            "TRUNCATE TABLE stock_underwriting_daily_supply_state;",
            "TRUNCATE TABLE stock_liquidity_daily_state;",
            "TRUNCATE TABLE stock_institution_decision_item;",
            "TRUNCATE TABLE stock_institution_decision_run;",
            "TRUNCATE TABLE stock_institution_daily_budget;",
            "TRUNCATE TABLE stock_auto_participant_position_state;",
            "TRUNCATE TABLE stock_auto_participant_performance_state;",
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
            "DELETE FROM stock_corporate_action",
            "WHERE action_type <> 'INITIAL_ISSUE';",
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
            "INSERT INTO stock_market_business_state(",
            "INSERT INTO stock_market_session_fence(",
            "JOIN stock_institution_portfolio portfolio",
            "JSON_EXTRACT(policy.config_json, '$.initialCash')",
            "JOIN stock_liquidity_transition transition",
            "transition.seed_cash_amount + transition.transferred_cash_amount",
            "transition.stage IN ('LIVE_ACTIVE', 'SUSPENDED')",
            "CREATE TEMPORARY TABLE tmp_stock_lp_seed_replay",
            "CREATE TEMPORARY TABLE tmp_stock_lp_seed_replay_guard",
            "CHECK (violation_count = 0)",
            "allocation.allocation_reason = 'LIQUIDITY_SEED_TRANSFER'",
            "allocation.idempotency_key = CONCAT('LP-SEED:', transition.symbol)",
            "replay.source_quantity_before - replay.seed_inventory_quantity",
            "transition.legacy_retired_at IS NOT NULL",
            "instrument.tradable_shares",
            "CREATE TEMPORARY TABLE tmp_stock_reset_share_guard",
            "COALESCE(holding_sum.holding_quantity, 0) <> instrument.issued_shares"
    );

    private static final List<String> CLEAR_RUNTIME_HISTORY_KEEP_PARTICIPANTS_FORBIDDEN_TRUNCATES = List.of(
            "TRUNCATE TABLE stock_batch_job_lock;",
            "TRUNCATE TABLE stock_batch_job_control;",
            "TRUNCATE TABLE stock_account;",
            "TRUNCATE TABLE stock_auto_participant;",
            "TRUNCATE TABLE stock_auto_participant_profile_config;",
            "TRUNCATE TABLE stock_auto_participant_symbol_config;",
            "TRUNCATE TABLE stock_auto_market_config;",
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
        assertThat(ddl).doesNotContain("KEY idx_stock_order_auto_reprice");
        assertThat(ddl).contains(ADMIN_QUERY_INDEX_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(BATCH_OPERATION_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(SIMULATION_CLOCK_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).contains(MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS.toArray(String[]::new));
        assertThat(ddl).doesNotContain("stock_listing_auto_account_config");
        assertThat(ddl).contains(
                "decision_frequency_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000",
                "orders_per_decision_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000",
                "pricing_mode VARCHAR(30) NOT NULL DEFAULT 'DIRECTIONAL'",
                "exit_mode VARCHAR(30) NOT NULL DEFAULT 'SIGNAL_DRIVEN'",
                "inventory_mode VARCHAR(30) NOT NULL DEFAULT 'SIGNAL_DRIVEN'"
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
    void eodRuntimeContractAlterDdl_separatesSchemaRevisionAndRestartContract() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_runtime_contract_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_eod_runtime_contract_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "ADD COLUMN eod_contract_version VARCHAR(100) NULL AFTER schema_version",
                "THEN 'EOD_V1'",
                "WHEN status = 'COMPLETED' THEN 'LEGACY_COMPLETED'",
                "ELSE 'UNDECLARED'",
                "DEFAULT ''UNDECLARED''",
                "chk_stock_post_close_cycle_eod_contract",
                "chk_stock_post_close_phase_attempt_eod_contract"
        );
        assertThat(backDdl).doesNotContain(
                "stock_order",
                "stock_execution",
                "stock_holding"
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
    void autoParticipantBehaviorStateAlter_matchesBatchCopyAndBackfillsOnlyCurrentHoldings() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_behavior_state_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_behavior_state_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_position_state",
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_performance_state",
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_funding_budget",
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_order_budget",
                "INSERT IGNORE INTO stock_auto_participant_position_state",
                "FROM stock_holding h",
                "JOIN stock_account a",
                "JOIN stock_auto_participant p",
                "LEFT JOIN stock_market_business_state bs",
                "ON bs.state_id = 'DEFAULT'",
                "WHERE h.quantity > 0"
        );
        assertThat(backDdl).doesNotContain(
                "ON bs.state_id = 'GLOBAL'",
                "UPDATE stock_order ",
                "FROM stock_order ",
                "JOIN stock_order ",
                "UPDATE stock_execution ",
                "FROM stock_execution ",
                "JOIN stock_execution "
        );
    }

    @Test
    void autoParticipantWithdrawalSettlementAlter_isCreateOnlyAndMatchesCanonicalDdl() throws IOException {
        String alterDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_withdrawal_settlement_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(alterDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(alterDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_withdrawal",
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_share_return",
                "uk_stock_auto_participant_withdrawal_user",
                "idx_stock_auto_share_return_underwriter",
                "idx_stock_auto_share_return_receiver",
                "returned_cash_amount",
                "returned_share_quantity",
                "source_average_price",
                "receiver_account_id",
                "receiver_role",
                "transfer_reason"
        );
        assertThat(canonicalDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_withdrawal",
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_share_return"
        );
        for (String tableName : List.of(
                "stock_auto_participant_withdrawal",
                "stock_auto_participant_share_return"
        )) {
            assertThat(normalizeSqlBlock(extractCreateTableBlock(alterDdl, tableName)))
                    .isEqualTo(normalizeSqlBlock(extractCreateTableBlock(canonicalDdl, tableName)));
        }
        assertThat(alterDdl).doesNotContain("ALTER TABLE", "UPDATE ", "DELETE FROM");
    }

    @Test
    void systemCustodyWithdrawalAlter_matchesBatchCopyAndPreservesLegacyReceiver() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_system_custody_withdrawal_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_system_custody_withdrawal_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "receiver_account_id",
                "receiver_role",
                "transfer_reason",
                "LEGACY_UNDERWRITER_RETURN",
                "AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY",
                "stock-system-custody",
                "SYSTEM_CUSTODY:DEFAULT",
                "idx_stock_auto_share_return_receiver"
        );
        assertThat(backDdl).doesNotContain(
                "DELETE FROM stock_auto_participant_share_return",
                "TRUNCATE TABLE stock_auto_participant_share_return"
        );
    }

    @Test
    void institutionEngineAlter_matchesCanonicalAndBatchCopies() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_institution_engine_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_institution_engine_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        for (String tableName : List.of(
                "stock_institution_portfolio",
                "stock_institution_symbol_mandate",
                "stock_institution_decision_run",
                "stock_institution_decision_item",
                "stock_institution_daily_budget"
        )) {
            assertThat(normalizeSqlBlock(extractCreateTableBlock(backDdl, tableName)))
                    .as(tableName)
                    .isEqualTo(normalizeSqlBlock(extractCreateTableBlock(canonicalDdl, tableName)));
        }
        assertThat(backDdl).contains(
                "execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE'",
                "CHECK (`execution_mode` = 'LIVE')",
                "primary_regime_weight",
                "reference_daily_volume",
                "remaining_daily_quantity_budget",
                "planned_buy_quantity",
                "uk_stock_institution_decision_slot"
        );
        assertThat(backDdl).doesNotContain(
                "INSERT INTO stock_order",
                "UPDATE stock_order",
                "DELETE FROM stock_order"
        );
    }

    @Test
    void institutionLiveOnlyAlter_matchesBatchCopyAndRetiresPendingLegacyModes()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_institution_live_only_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_institution_live_only_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(backDdl).contains(
                "LEGACY_NON_LIVE_MODE_RETIRED",
                "SET execution_mode = 'LIVE'",
                "execution_mode SET DEFAULT 'LIVE'",
                "table_name = 'stock_institution_decision_run'",
                "chk_stock_institution_decision_run_mode",
                "CHECK (`execution_mode` = 'LIVE')"
        ).doesNotContain(
                "UPDATE stock_account",
                "UPDATE stock_holding",
                "UPDATE stock_order",
                "UPDATE stock_execution"
        );
    }

    @Test
    void liquidityProviderEngineAlter_matchesCanonicalAndBatchCopies() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_liquidity_provider_engine_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_liquidity_provider_engine_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        for (String tableName : List.of(
                "stock_liquidity_mandate",
                "stock_liquidity_daily_state"
        )) {
            assertThat(normalizeSqlBlock(extractCreateTableBlock(backDdl, tableName)))
                    .as(tableName)
                    .isEqualTo(normalizeSqlBlock(extractCreateTableBlock(canonicalDdl, tableName)));
        }
        assertThat(backDdl).contains(
                "execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE'",
                "passive_only BOOLEAN NOT NULL DEFAULT TRUE",
                "reference_daily_volume",
                "daily_execution_participation_rate",
                "minimum_quote_lifetime_seconds",
                "submitted_buy_amount",
                "executed_sell_amount",
                "uk_stock_liquidity_mandate_symbol"
        );
        assertThat(backDdl).doesNotContain(
                "INSERT INTO stock_order",
                "UPDATE stock_order",
                "DELETE FROM stock_order"
        );
    }

    @Test
    void liquidityTransitionAlter_matchesCanonicalAndBatchCopiesWithoutMutatingHotLedgers()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_liquidity_transition_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_liquidity_transition_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(normalizeSqlBlock(
                extractCreateTableBlock(backDdl, "stock_liquidity_transition")
        )).isEqualTo(normalizeSqlBlock(
                extractCreateTableBlock(canonicalDdl, "stock_liquidity_transition")
        ));
        assertThat(backDdl).contains(
                "DEFAULT_LIQUIDITY_PROVIDER",
                "LIQUIDITY_PROVIDER:DEFAULT",
                "LIQUIDITY_SEED_TRANSFER",
                "uk_stock_liquidity_transition_symbol",
                "chk_stock_liquidity_transition_activation"
        ).doesNotContain(
                "UPDATE stock_holding",
                "DELETE FROM stock_holding",
                "UPDATE stock_order",
                "DELETE FROM stock_order"
        );
    }

    @Test
    void legacyLiquidityRetirementAlter_transfersWholeAccountsAndIsBackOwned()
            throws IOException {
        Path backPath = Path.of(
                "src/main/resources/db/ddl/stock_legacy_liquidity_retirement_alter.sql"
        );
        Path batchPath = Path.of(
                "../stock-batch-service/src/main/resources/db/ddl/"
                        + "stock_legacy_liquidity_retirement_alter.sql"
        );
        String ddl = Files.readString(backPath, StandardCharsets.UTF_8);

        assertThat(firstExecutableSqlLine(ddl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchPath).doesNotExist();
        assertThat(ddl).contains(
                "Pause the simulation clock before legacy liquidity retirement",
                "Every legacy liquidity config must map to exactly one LP transition",
                "Legacy and LP open orders must be fully cancelled before retirement",
                "'LIQUIDITY_ACCOUNT_TRANSFER'",
                "'MARKET_ROLE_TRANSFER'",
                "transfer.liquidity_cash + transfer.legacy_cash",
                "transfer.legacy_quantity",
                "Previously retired legacy liquidity accounts must be closed and empty",
                "mandate.target_inventory_quantity = transfer.combined_quantity",
                "daily_state.opening_net_asset_value",
                "legacy_account.status = 'CLOSED'",
                "legacy_account.cash_balance <> 0",
                "Issued-share reconciliation failed after legacy liquidity retirement",
                "DROP TABLE IF EXISTS stock_listing_auto_account_config"
        ).doesNotContain(
                "legacy_holding.average_price = 0.00",
                "UPDATE stock_order",
                "DELETE FROM stock_order",
                "INSERT INTO stock_order",
                "UPDATE stock_execution",
                "DELETE FROM stock_execution",
                "INSERT INTO stock_execution"
        );
    }

    @Test
    void liquidityLiveOnlyAlter_matchesBatchCopyAndOnlyChangesDefaults()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_liquidity_live_only_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_liquidity_live_only_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(backDdl).contains(
                "execution_mode SET DEFAULT 'LIVE'",
                "state_status SET DEFAULT 'QUOTING'",
                "stage SET DEFAULT 'LIVE_ACTIVE'"
        ).doesNotContain(
                "stock_order",
                "stock_execution",
                "UPDATE stock_account",
                "UPDATE stock_holding"
        );
    }

    @Test
    void issuanceUnderwritingAlter_matchesCanonicalAndBatchCopiesWithoutRewritingExistingHoldings() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_issuance_underwriting_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_issuance_underwriting_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        for (String tableName : List.of(
                "stock_underwriting_contract",
                "stock_security_allocation_ledger"
        )) {
            assertThat(normalizeSqlBlock(extractCreateTableBlock(backDdl, tableName)))
                    .as(tableName)
                    .isEqualTo(normalizeSqlBlock(extractCreateTableBlock(canonicalDdl, tableName)));
        }
        assertThat(backDdl).contains(
                "DEFAULT_ISSUE_UNDERWRITER",
                "ISSUE_UNDERWRITER:DEFAULT",
                "tradable_allocation_quantity + locked_allocation_quantity = total_issue_quantity",
                "external_allocation_quantity + underwritten_quantity = tradable_allocation_quantity",
                "uk_stock_security_allocation_idempotency",
                "tradability_status VARCHAR(20) NOT NULL"
        );
        assertThat(backDdl).doesNotContain(
                "UPDATE stock_holding",
                "DELETE FROM stock_holding",
                "UPDATE stock_order",
                "DELETE FROM stock_order"
        );
    }

    @Test
    void independentProvisioningAlter_matchesBatchAndAvoidsHotLedgers()
            throws IOException {
        String backDdl = Files.readString(
                Path.of(
                        "src/main/resources/db/ddl/"
                                + "stock_market_role_independent_provisioning_alter.sql"
                ),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of(
                        "../stock-batch-service/src/main/resources/db/ddl/"
                                + "stock_market_role_independent_provisioning_alter.sql"
                ),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(backDdl).contains(
                "INITIAL_FLOAT_CUSTODY",
                "chk_stock_security_allocation_reason",
                "stock_security_allocation_ledger"
        ).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution",
                "UPDATE stock_order",
                "UPDATE stock_execution"
        );
    }

    @Test
    void underwriterScaledSupplyAlter_matchesCanonicalAndBatchCopiesWithoutHotLedgerMutation()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_underwriter_scaled_supply_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_underwriter_scaled_supply_alter.sql"),
                StandardCharsets.UTF_8
        );
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(normalizeSqlBlock(extractCreateTableBlock(
                backDdl,
                "stock_underwriting_daily_supply_state"
        ))).isEqualTo(normalizeSqlBlock(extractCreateTableBlock(
                canonicalDdl,
                "stock_underwriting_daily_supply_state"
        )));
        assertThat(backDdl).contains(
                "submitted_quantity <= submission_quantity_limit",
                "submitted_amount <= submission_amount_limit",
                "idx_stock_underwriting_supply_contract",
                "idx_stock_underwriting_supply_status"
        ).doesNotContain(
                "INSERT INTO stock_order",
                "UPDATE stock_order",
                "DELETE FROM stock_order",
                "UPDATE stock_holding",
                "DELETE FROM stock_holding"
        );
    }

    @Test
    void autoParticipantRealizedPerformanceAlter_matchesBatchCopyAndAvoidsHotLedgers() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_realized_performance_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_realized_performance_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_performance_state",
                "recent_profitable_trading_days",
                "recent_closed_trading_days",
                "chk_stock_auto_performance_recent_days"
        );
        assertThat(backDdl).doesNotContain(
                "stock_order ",
                "stock_execution "
        );
    }

    @Test
    void autoParticipantProfileExecutionPolicyAlter_matchesBatchCopy() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_profile_execution_policy_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_profile_execution_policy_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "decision_frequency_multiplier",
                "orders_per_decision_multiplier",
                "pricing_mode",
                "exit_mode",
                "inventory_mode"
        );
        assertThat(backDdl).doesNotContain(
                "stock_order ",
                "stock_execution "
        );
    }

    @Test
    void autoParticipantShadowCleanupAlter_matchesBatchCopyAndRemovesLegacyContractSafely() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_shadow_cleanup_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_shadow_cleanup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "DROP CHECK chk_stock_auto_participant_funding_shadow",
                "DROP CHECK chk_stock_auto_participant_behavior_rollout_pair",
                "DROP CHECK chk_stock_auto_participant_behavior_evaluation",
                "SET behavior_model_version = ''V1'' WHERE behavior_evaluation_mode = ''SHADOW''",
                "DROP COLUMN behavior_evaluation_mode",
                "DROP TABLE IF EXISTS stock_auto_profile_decision_day_summary"
        );
        assertThat(backDdl.indexOf("DROP CHECK chk_stock_auto_participant_behavior_rollout_pair"))
                .isLessThan(backDdl.indexOf("SET behavior_model_version = ''V1''"));
        assertThat(backDdl).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution"
        );
    }

    @Test
    void autoOrderPolicySnapshotAlter_matchesBatchCopyAndDoesNotAddHotLedgerIndex() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_order_policy_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_order_policy_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "expires_at", "auto_profile_type", "auto_behavior_model_version",
                "ALGORITHM=INSTANT"
        );
        assertThat(backDdl).doesNotContain("ALGORITHM=INSTANT, LOCK=NONE");
        assertThat(backDdl.toLowerCase()).doesNotContain("add index", "add key", "create index");
    }

    @Test
    void autoMarketRepriceIndexAlter_matchesBatchCopyAndRemovesWriteAmplifyingIndex() throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_market_reprice_index_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_market_reprice_index_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "information_schema.statistics",
                "idx_stock_order_auto_reprice",
                "DROP INDEX idx_stock_order_auto_reprice",
                "ALGORITHM=INPLACE",
                "LOCK=NONE"
        );
        assertThat(backDdl.toLowerCase()).doesNotContain("add index idx_stock_order_auto_reprice");
        assertThat(backDdl).doesNotContain("stock_execution");
    }

    @Test
    void closeAccountProfileSnapshotAlter_freezesFutureProfilesWithoutReclassifyingHistory()
            throws IOException {
        String backDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_close_account_profile_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_close_account_profile_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(backDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(backDdl).contains(
                "ADD COLUMN participant_profile_type VARCHAR(40) NULL",
                "chk_stock_close_account_snapshot_profile_type",
                "Historical rows are",
                "intentionally left NULL"
        );
        assertThat(backDdl).doesNotContain(
                "UPDATE stock_close_account_snapshot",
                "FROM stock_order",
                "JOIN stock_order",
                "FROM stock_execution",
                "JOIN stock_execution"
        );
    }

    @Test
    void autoParticipantBehaviorModel_isOwnedByProfileAndDefaultsToV2() throws IOException {
        String canonicalDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_all.sql"),
                StandardCharsets.UTF_8
        );
        String alterDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_profile_behavior_model_alter.sql"),
                StandardCharsets.UTF_8
        );
        String batchAlterDdl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_auto_participant_profile_behavior_model_alter.sql"),
                StandardCharsets.UTF_8
        );
        String h2Ddl = Files.readString(
                Path.of("../stock-batch-service/src/main/resources/db/ddl/stock_h2.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(extractCreateTableBlock(canonicalDdl, "stock_auto_participant"))
                .contains("behavior_seed BIGINT NULL")
                .doesNotContain("behavior_model_version");
        assertThat(extractCreateTableBlock(canonicalDdl, "stock_auto_participant_profile_config"))
                .contains(
                        "behavior_model_version VARCHAR(20) NOT NULL DEFAULT 'V2'",
                        "CONSTRAINT chk_stock_auto_profile_behavior_model CHECK"
                );
        assertThat(extractCreateTableBlock(h2Ddl, "stock_auto_participant"))
                .contains("behavior_seed BIGINT")
                .doesNotContain("behavior_model_version");
        assertThat(extractCreateTableBlock(h2Ddl, "stock_auto_participant_profile_config"))
                .contains(
                        "behavior_model_version VARCHAR(20) NOT NULL DEFAULT 'V2'",
                        "CONSTRAINT chk_stock_auto_profile_behavior_model CHECK"
                );
        assertThat(alterDdl).contains(
                "ADD COLUMN behavior_model_version VARCHAR(20) NOT NULL DEFAULT ''V2''",
                "UPDATE stock_auto_participant_profile_config",
                "SET behavior_model_version = 'V2'",
                "ALTER TABLE stock_auto_participant DROP COLUMN behavior_model_version"
        );
        assertThat(normalizeSqlBlock(alterDdl)).isEqualTo(normalizeSqlBlock(batchAlterDdl));
        assertThat(canonicalDdl).doesNotContain(
                "behavior_evaluation_mode",
                "chk_stock_auto_participant_behavior_rollout_pair",
                "chk_stock_auto_participant_funding_shadow"
        );
        assertThat(h2Ddl).doesNotContain(
                "behavior_evaluation_mode",
                "chk_stock_auto_participant_behavior_rollout_pair",
                "chk_stock_auto_participant_funding_shadow"
        );
        assertThat(alterDdl).doesNotContain(
                "behavior_evaluation_mode",
                "chk_stock_auto_participant_behavior_rollout_pair",
                "chk_stock_auto_participant_funding_shadow"
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
        assertThat(maintenanceSql).doesNotContain(
                "m.market_status = 'OPEN'",
                "c.position_side = 'SELL_ONLY'",
                "source_holding.quantity - transition.seed_inventory_quantity"
        );
    }

    @Test
    void autoParticipantV2ValidationReport_isReadOnlyAndUsesBoundedLedgerRanges() throws IOException {
        String reportSql = Files.readString(
                Path.of("src/main/resources/db/maintenance/stock_auto_participant_profile_v2_validation_report.sql"),
                StandardCharsets.UTF_8
        );
        String executableSql = reportSql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");

        assertThat(firstExecutableSqlLine(reportSql)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(reportSql).contains(AUTO_PARTICIPANT_PROFILE_TYPES.toArray(String[]::new));
        assertThat(reportSql).contains(
                "stock_order.created_at >=",
                "stock_order.created_at <",
                "PROFILE_CONTRACT",
                "PARTICIPANT_MODEL_EXPORT",
                "ORDER_CANARY",
                "FUNDING_RECONCILIATION",
                "behavior_model_version"
        );
        assertThat(reportSql).doesNotContain(
                "behavior_evaluation_mode",
                "stock_auto_profile_decision_day_summary"
        );
        assertThat(Pattern.compile(
                "(?im)^\\s*(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|CALL)\\b"
        ).matcher(executableSql).find()).isFalse();
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
