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
TRUNCATE TABLE stock_execution;
TRUNCATE TABLE stock_execution_account_day_summary;
TRUNCATE TABLE stock_account_cash_flow;
TRUNCATE TABLE stock_auto_participant_cash_flow_run;
TRUNCATE TABLE stock_holding_snapshot;
TRUNCATE TABLE stock_execution_daily_account_snapshot;
TRUNCATE TABLE stock_order_book_daily_snapshot;
TRUNCATE TABLE stock_market_close_run;
TRUNCATE TABLE stock_holding;
TRUNCATE TABLE portfolio_snapshot;
TRUNCATE TABLE stock_order;
TRUNCATE TABLE stock_order_book_regime_modifier;
TRUNCATE TABLE stock_order_book_daily_regime;
TRUNCATE TABLE stock_auto_participant_order_schedule;

TRUNCATE TABLE stock_instrument_report_event;
TRUNCATE TABLE stock_auto_participant_symbol_config;
TRUNCATE TABLE stock_auto_market_config;
TRUNCATE TABLE stock_listing_auto_account_config;
TRUNCATE TABLE stock_auto_participant_event_profile_config;
TRUNCATE TABLE stock_auto_participant_profile_config;
TRUNCATE TABLE stock_auto_participant;

TRUNCATE TABLE stock_price_tick;
TRUNCATE TABLE stock_price;
TRUNCATE TABLE stock_corporate_action;
TRUNCATE TABLE stock_virtual_market_config;
TRUNCATE TABLE stock_order_book_market_config;
TRUNCATE TABLE stock_order_book_instrument;
TRUNCATE TABLE stock_instrument;
TRUNCATE TABLE stock_account;
TRUNCATE TABLE stock_simulation_clock;

SET FOREIGN_KEY_CHECKS = 1;
