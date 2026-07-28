-- Clears all data in the stock business schema while keeping table structure.
-- Target schema: STOCK_SERVICE
-- This file does not clear Spring Batch metadata in STOCK_BATCH_METADATA.

USE STOCK_SERVICE;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE stock_batch_job_lock;
TRUNCATE TABLE stock_batch_job_signal;
TRUNCATE TABLE stock_batch_job_control;

TRUNCATE TABLE stock_market_session_fence;
TRUNCATE TABLE stock_market_business_state;
TRUNCATE TABLE stock_close_open_order_snapshot;
TRUNCATE TABLE stock_close_open_order_summary;
TRUNCATE TABLE stock_close_price_snapshot;
TRUNCATE TABLE stock_close_account_snapshot;
TRUNCATE TABLE stock_post_close_cycle_metric;
TRUNCATE TABLE stock_post_close_readiness_check;
TRUNCATE TABLE stock_post_close_phase_attempt;
TRUNCATE TABLE stock_post_close_cycle;

TRUNCATE TABLE stock_corporate_action_processing;
TRUNCATE TABLE stock_corporate_action_entitlement;
TRUNCATE TABLE stock_security_allocation_ledger;
TRUNCATE TABLE stock_underwriting_daily_supply_state;
TRUNCATE TABLE stock_underwriting_contract;
TRUNCATE TABLE stock_execution;
TRUNCATE TABLE stock_execution_account_day_summary;
TRUNCATE TABLE stock_account_cash_flow;
TRUNCATE TABLE stock_auto_participant_cash_flow_run;
TRUNCATE TABLE stock_auto_participant_share_return;
TRUNCATE TABLE stock_auto_participant_withdrawal;
TRUNCATE TABLE stock_auto_participant_order_budget;
TRUNCATE TABLE stock_auto_participant_funding_budget;
TRUNCATE TABLE stock_auto_participant_liquidation_plan;
TRUNCATE TABLE stock_auto_participant_daily_behavior_state;
TRUNCATE TABLE stock_liquidity_daily_state;
TRUNCATE TABLE stock_liquidity_transition;
TRUNCATE TABLE stock_liquidity_mandate;
TRUNCATE TABLE stock_institution_order_intent;
TRUNCATE TABLE stock_institution_decision_item;
TRUNCATE TABLE stock_institution_decision_run;
TRUNCATE TABLE stock_institution_daily_budget;
TRUNCATE TABLE stock_institution_symbol_mandate;
TRUNCATE TABLE stock_institution_portfolio;
TRUNCATE TABLE stock_auto_participant_position_state;
TRUNCATE TABLE stock_auto_participant_performance_state;
TRUNCATE TABLE stock_holding_snapshot;
TRUNCATE TABLE stock_execution_daily_account_snapshot;
TRUNCATE TABLE stock_order_book_daily_snapshot;
TRUNCATE TABLE stock_market_close_run;
TRUNCATE TABLE stock_holding;
TRUNCATE TABLE portfolio_snapshot;
TRUNCATE TABLE stock_order_strategy_origin;
TRUNCATE TABLE stock_order;
TRUNCATE TABLE stock_order_book_regime_modifier;
TRUNCATE TABLE stock_order_book_daily_regime;
TRUNCATE TABLE stock_auto_participant_order_schedule;

TRUNCATE TABLE stock_instrument_report_event;
TRUNCATE TABLE stock_auto_participant_symbol_config;
TRUNCATE TABLE stock_auto_market_config;
TRUNCATE TABLE stock_auto_participant_event_profile_config;
TRUNCATE TABLE stock_auto_participant_profile_config;
TRUNCATE TABLE stock_auto_participant_policy_revision;
TRUNCATE TABLE stock_auto_participant;
TRUNCATE TABLE stock_market_policy_version;
TRUNCATE TABLE stock_market_participant_account;
TRUNCATE TABLE stock_market_participant;

TRUNCATE TABLE stock_price_tick;
TRUNCATE TABLE stock_price;
TRUNCATE TABLE stock_corporate_action;
TRUNCATE TABLE stock_virtual_market_config;
TRUNCATE TABLE stock_order_book_market_config;
TRUNCATE TABLE stock_order_book_instrument;
TRUNCATE TABLE stock_instrument;
TRUNCATE TABLE stock_account;
TRUNCATE TABLE stock_simulation_clock;

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
VALUES (
    'SYSTEM_CUSTODY', '시스템 보관기관', 'SYSTEM_CUSTODY', 'ACTIVE',
    'SYSTEM_CUSTODY:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
VALUES (
    'DEFAULT_ISSUE_UNDERWRITER', '기본 인수기관', 'ISSUE_UNDERWRITER', 'ACTIVE',
    'ISSUE_UNDERWRITER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
VALUES (
    'DEFAULT_LIQUIDITY_PROVIDER', '기본 유동성공급기관',
    'LIQUIDITY_PROVIDER', 'ACTIVE',
    'LIQUIDITY_PROVIDER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO stock_account(
    user_key, account_code, status, participant_category,
    self_trade_group_id, cash_balance, created_at, updated_at
)
VALUES (
    'stock-system-custody', 'SYSTEM-CUSTODY', 'ACTIVE', 'SYSTEM_CUSTODY',
    'SYSTEM_CUSTODY:DEFAULT', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO stock_market_participant_account(
    participant_id, account_id, account_role, desk_code,
    effective_from, effective_to, status, created_at, updated_at
)
SELECT
    participant.id, account.id, 'SYSTEM_CUSTODY', 'DEFAULT',
    DATE '1970-01-01', NULL, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM stock_market_participant participant
  JOIN stock_account account
    ON account.user_key = 'stock-system-custody'
 WHERE participant.participant_code = 'SYSTEM_CUSTODY';

SET FOREIGN_KEY_CHECKS = 1;
