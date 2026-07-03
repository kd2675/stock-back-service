-- Clears runtime trading history while keeping participants, account identity rows, instruments, and market configuration.
-- Target schema: STOCK_SERVICE
-- Stop stock-back-service and stock-batch-service schedulers before running this file.
-- Use stock_clear_data.sql instead when a full stock schema reset is required.
-- This resets account cash and non-listing holdings to a clean state so net cash flow and
-- return-rate calculations do not mix preserved balances with removed cash-flow/order/execution history.
-- Enabled order-book prices are reset to initial listing prices because price ticks and executions
-- are removed. The simulation clock keeps its configured base date and day speed, but its
-- accumulated time is reset so new price history starts at the simulation base date.
-- SELL_ONLY listing auto accounts receive the current tradable share supply again only for
-- instruments that the listing-auto batch can actually trade.

USE STOCK_SERVICE;

SET FOREIGN_KEY_CHECKS = 0;

UPDATE stock_account
   SET cash_balance = 0.00,
       updated_at = NOW();

TRUNCATE TABLE stock_batch_job_signal;

TRUNCATE TABLE stock_corporate_action_entitlement;
TRUNCATE TABLE stock_execution;
TRUNCATE TABLE stock_account_cash_flow;
TRUNCATE TABLE stock_holding_snapshot;
TRUNCATE TABLE stock_market_close_run;
TRUNCATE TABLE stock_holding;
TRUNCATE TABLE portfolio_snapshot;
TRUNCATE TABLE stock_order;
TRUNCATE TABLE stock_auto_participant_order_schedule;

TRUNCATE TABLE stock_price_tick;
TRUNCATE TABLE stock_corporate_action;

INSERT INTO stock_simulation_clock(
    clock_id,
    base_simulation_date,
    real_seconds_per_simulation_day,
    accumulated_real_seconds,
    running,
    last_started_at,
    last_heartbeat_at,
    timezone,
    created_at,
    updated_at
)
VALUES (
    'DEFAULT',
    CURDATE(),
    7200,
    0,
    false,
    null,
    null,
    'Asia/Seoul',
    NOW(),
    NOW()
)
ON DUPLICATE KEY UPDATE
       accumulated_real_seconds = 0,
       running = false,
       last_started_at = null,
       last_heartbeat_at = null,
       updated_at = VALUES(updated_at);

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
SELECT i.symbol,
       i.initial_price,
       i.initial_price,
       (
           SELECT CAST(base_simulation_date AS DATETIME)
             FROM stock_simulation_clock
            WHERE clock_id = 'DEFAULT'
       ),
       'runtime-history-reset'
  FROM stock_order_book_instrument i
 WHERE i.enabled = true
ON DUPLICATE KEY UPDATE
       current_price = VALUES(current_price),
       previous_close = VALUES(previous_close),
       price_time = VALUES(price_time),
       provider = VALUES(provider);

INSERT INTO stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
SELECT a.id,
       i.symbol,
       i.tradable_shares,
       0,
       i.initial_price,
       NOW()
  FROM stock_listing_auto_account_config c
  JOIN stock_account a ON a.user_key = c.user_key
  JOIN stock_order_book_instrument i ON i.symbol = c.symbol AND i.enabled = true
  JOIN stock_order_book_market_config m ON m.symbol = c.symbol AND m.enabled = true AND m.market_status = 'OPEN'
  JOIN stock_price p ON p.symbol = c.symbol
 WHERE c.enabled = true
   AND c.position_side = 'SELL_ONLY'
   AND a.status = 'ACTIVE'
   AND i.tradable_shares > 0;

SET FOREIGN_KEY_CHECKS = 1;
