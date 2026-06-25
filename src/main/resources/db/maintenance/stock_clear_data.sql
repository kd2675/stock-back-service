-- Clears all data in the stock business schema while keeping table structure.
-- Target schema: STOCK_SERVICE
-- This file does not clear Spring Batch metadata in STOCK_BATCH_METADATA.

USE STOCK_SERVICE;

SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE stock_corporate_action_entitlement;
TRUNCATE TABLE stock_execution;
TRUNCATE TABLE stock_holding;
TRUNCATE TABLE portfolio_snapshot;
TRUNCATE TABLE stock_order;

TRUNCATE TABLE stock_instrument_report_event;
TRUNCATE TABLE stock_auto_participant_symbol_config;
TRUNCATE TABLE stock_auto_market_config;
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

SET FOREIGN_KEY_CHECKS = 1;
