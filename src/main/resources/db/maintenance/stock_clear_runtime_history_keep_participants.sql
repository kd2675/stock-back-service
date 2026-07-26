-- Clears runtime trading history while keeping participants, account identity rows, instruments, and market configuration.
-- Target schema: STOCK_SERVICE
-- Stop stock-back-service and stock-batch-service schedulers before running this file.
-- Use stock_clear_data.sql instead when a full stock schema reset is required.
-- This resets account cash and non-listing holdings to a clean state so net cash flow and
-- return-rate calculations do not mix preserved balances with removed cash-flow/order/execution history.
-- Enabled order-book prices are reset to initial listing prices because price ticks and executions
-- are removed. The simulation clock keeps its configured base date and day speed, but its
-- accumulated time is reset so new price history starts at the simulation base date.
-- Legacy listing accounts receive the current tradable share supply again even when quoting
-- is disabled or the market is CLOSED: trading eligibility must never decide security ownership.
-- A listing account retired by a recorded LP transition is therefore also restored as the
-- transfer source before the immutable LP seed transfer is replayed.

USE STOCK_SERVICE;

SET FOREIGN_KEY_CHECKS = 0;

UPDATE stock_account
   SET cash_balance = 0.00,
       updated_at = NOW();

TRUNCATE TABLE stock_batch_job_signal;

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
TRUNCATE TABLE stock_auto_participant_order_budget;
TRUNCATE TABLE stock_auto_participant_funding_budget;
TRUNCATE TABLE stock_liquidity_daily_state;
TRUNCATE TABLE stock_underwriting_daily_supply_state;
TRUNCATE TABLE stock_institution_order_intent;
TRUNCATE TABLE stock_institution_decision_item;
TRUNCATE TABLE stock_institution_decision_run;
TRUNCATE TABLE stock_institution_daily_budget;
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
TRUNCATE TABLE stock_auto_participant_order_schedule;

TRUNCATE TABLE stock_price_tick;
-- INITIAL_ISSUE is structural provenance for preserved instruments, underwriting
-- contracts, and allocation ledgers. Remove only later runtime events so those
-- immutable links never point at a deleted corporate-action row.
DELETE FROM stock_corporate_action
 WHERE action_type <> 'INITIAL_ISSUE';

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

INSERT INTO stock_market_business_state(
    state_id,
    active_business_date,
    preparing_business_date,
    raw_simulation_date,
    version,
    created_at,
    updated_at
)
SELECT 'DEFAULT',
       base_simulation_date,
       null,
       base_simulation_date,
       0,
       NOW(),
       NOW()
  FROM stock_simulation_clock
 WHERE clock_id = 'DEFAULT';

INSERT INTO stock_market_session_fence(
    market_type,
    symbol,
    business_date,
    session_epoch,
    session_state,
    state_changed_at,
    version,
    created_at,
    updated_at
)
SELECT 'VIRTUAL_PRICE',
       c.symbol,
       s.active_business_date,
       1,
       'CLOSED',
       NOW(),
       0,
       NOW(),
       NOW()
  FROM stock_virtual_market_config c
 CROSS JOIN stock_market_business_state s
 WHERE s.state_id = 'DEFAULT'
UNION ALL
SELECT 'ORDER_BOOK',
       c.symbol,
       s.active_business_date,
       1,
       'CLOSED',
       NOW(),
       0,
       NOW(),
       NOW()
  FROM stock_order_book_market_config c
 CROSS JOIN stock_market_business_state s
 WHERE s.state_id = 'DEFAULT';

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

-- Restore structural opening capital for independently provisioned institution portfolios. Runtime gains,
-- losses, deposits, and order reservations do not survive this reset.
UPDATE stock_account account
JOIN stock_institution_portfolio portfolio
  ON portfolio.account_id = account.id
JOIN stock_market_policy_version policy
  ON policy.policy_scope = 'INSTITUTIONAL_PORTFOLIO'
 AND policy.scope_key = portfolio.portfolio_code
 AND policy.version_no = 1
   SET account.cash_balance = CAST(
           JSON_UNQUOTE(JSON_EXTRACT(policy.config_json, '$.initialCash'))
           AS DECIMAL(19,2)
       ),
       account.updated_at = NOW()
 WHERE account.status = 'ACTIVE'
   AND JSON_EXTRACT(policy.config_json, '$.initialCash') IS NOT NULL;

INSERT INTO stock_account_cash_flow(
    account_id, flow_type, amount, reason, created_by,
    corporate_action_id, corporate_action_entitlement_id,
    effective_business_date, created_at
)
SELECT account.id,
       'DEPOSIT',
       account.cash_balance,
       'OPENING_GRANT',
       'runtime-history-reset',
       null,
       null,
       state.active_business_date,
       NOW()
  FROM stock_account account
  JOIN stock_institution_portfolio portfolio
    ON portfolio.account_id = account.id
 CROSS JOIN stock_market_business_state state
 WHERE state.state_id = 'DEFAULT'
   AND account.status = 'ACTIVE'
   AND account.cash_balance > 0;

-- LP opening capital is structural transition data, not runtime trading profit.
UPDATE stock_account account
JOIN stock_liquidity_transition transition
  ON transition.liquidity_account_id = account.id
   SET account.cash_balance = transition.seed_cash_amount,
       account.updated_at = NOW()
 WHERE account.status = 'ACTIVE'
   AND transition.stage IN ('SHADOW_READY', 'LIVE_ACTIVE', 'SUSPENDED');

INSERT INTO stock_account_cash_flow(
    account_id, flow_type, amount, reason, created_by,
    corporate_action_id, corporate_action_entitlement_id,
    effective_business_date, created_at
)
SELECT account.id,
       'DEPOSIT',
       transition.seed_cash_amount,
       'OPENING_GRANT',
       'runtime-history-reset',
       null,
       null,
       state.active_business_date,
       NOW()
  FROM stock_liquidity_transition transition
  JOIN stock_account account
    ON account.id = transition.liquidity_account_id
 CROSS JOIN stock_market_business_state state
 WHERE state.state_id = 'DEFAULT'
   AND account.status = 'ACTIVE'
   AND transition.stage IN ('SHADOW_READY', 'LIVE_ACTIVE', 'SUSPENDED')
   AND transition.seed_cash_amount > 0;

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
 WHERE a.status = 'ACTIVE'
   AND i.tradable_shares > 0;

-- Immutable initial-allocation rows identify the role accounts. Quantity follows the preserved
-- current instrument configuration so a completed split or capital increase is not lost while
-- runtime trades and manual reallocations are reset.
INSERT INTO stock_holding(
    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
)
SELECT allocation.destination_account_id,
       allocation.symbol,
       CASE allocation.allocation_reason
           WHEN 'INITIAL_FLOAT_CUSTODY' THEN instrument.tradable_shares
           WHEN 'INITIAL_FLOAT_UNDERWRITER' THEN instrument.tradable_shares
           WHEN 'INITIAL_LOCKED_CUSTODY' THEN
               instrument.issued_shares - instrument.tradable_shares
       END,
       0,
       instrument.initial_price,
       NOW()
  FROM stock_security_allocation_ledger allocation
  JOIN stock_account account
    ON account.id = allocation.destination_account_id
   AND account.status = 'ACTIVE'
  JOIN stock_order_book_instrument instrument
    ON instrument.symbol = allocation.symbol
   AND instrument.enabled = true
 WHERE allocation.event_type = 'INITIAL_ISSUE'
   AND allocation.source_account_id IS NULL
   AND allocation.allocation_reason IN (
       'INITIAL_FLOAT_CUSTODY', 'INITIAL_FLOAT_UNDERWRITER',
       'INITIAL_LOCKED_CUSTODY'
   )
   AND CASE allocation.allocation_reason
           WHEN 'INITIAL_FLOAT_CUSTODY' THEN instrument.tradable_shares
           WHEN 'INITIAL_FLOAT_UNDERWRITER' THEN instrument.tradable_shares
           WHEN 'INITIAL_LOCKED_CUSTODY' THEN
               instrument.issued_shares - instrument.tradable_shares
       END > 0
ON DUPLICATE KEY UPDATE
       quantity = VALUES(quantity),
       reserved_quantity = 0,
       average_price = VALUES(average_price),
       updated_at = VALUES(updated_at);

-- Materialize only fully reconcilable LP seed transfers. The CHECK guard intentionally aborts
-- the maintenance script before any seed is moved if a source holding, destination account, or
-- immutable allocation row is missing. This prevents a partial reset from minting LP shares.
DROP TEMPORARY TABLE IF EXISTS tmp_stock_lp_seed_replay;
CREATE TEMPORARY TABLE tmp_stock_lp_seed_replay (
    transition_id BIGINT NOT NULL,
    source_account_id BIGINT NOT NULL,
    liquidity_account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    seed_inventory_quantity BIGINT NOT NULL,
    source_quantity_before BIGINT NOT NULL,
    unit_price DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (transition_id)
);

INSERT INTO tmp_stock_lp_seed_replay(
    transition_id, source_account_id, liquidity_account_id, symbol,
    seed_inventory_quantity, source_quantity_before, unit_price
)
SELECT transition.id,
       transition.source_account_id,
       transition.liquidity_account_id,
       transition.symbol,
       transition.seed_inventory_quantity,
       source_holding.quantity,
       allocation.unit_price
  FROM stock_liquidity_transition transition
  JOIN stock_holding source_holding
    ON source_holding.account_id = transition.source_account_id
   AND source_holding.symbol = transition.symbol
  JOIN stock_security_allocation_ledger allocation
    ON allocation.idempotency_key = CONCAT('LP-SEED:', transition.symbol)
   AND allocation.source_account_id = transition.source_account_id
   AND allocation.destination_account_id = transition.liquidity_account_id
   AND allocation.quantity = transition.seed_inventory_quantity
   AND allocation.allocation_reason = 'LIQUIDITY_SEED_TRANSFER'
  JOIN stock_account destination_account
    ON destination_account.id = transition.liquidity_account_id
   AND destination_account.status = 'ACTIVE'
 WHERE transition.stage IN ('SHADOW_READY', 'LIVE_ACTIVE', 'SUSPENDED')
   AND source_holding.reserved_quantity = 0
   AND source_holding.quantity >= transition.seed_inventory_quantity;

DROP TEMPORARY TABLE IF EXISTS tmp_stock_lp_seed_replay_guard;
CREATE TEMPORARY TABLE tmp_stock_lp_seed_replay_guard (
    violation_count BIGINT NOT NULL,
    CHECK (violation_count = 0)
);

INSERT INTO tmp_stock_lp_seed_replay_guard(violation_count)
SELECT COUNT(*)
  FROM stock_liquidity_transition transition
  LEFT JOIN tmp_stock_lp_seed_replay replay
    ON replay.transition_id = transition.id
 WHERE transition.stage IN ('SHADOW_READY', 'LIVE_ACTIVE', 'SUSPENDED')
   AND replay.transition_id IS NULL;

UPDATE stock_holding source_holding
JOIN tmp_stock_lp_seed_replay replay
  ON replay.source_account_id = source_holding.account_id
 AND replay.symbol = source_holding.symbol
   SET source_holding.quantity =
           replay.source_quantity_before - replay.seed_inventory_quantity,
       source_holding.reserved_quantity = 0,
       source_holding.updated_at = NOW()
 WHERE source_holding.quantity = replay.source_quantity_before
   AND source_holding.reserved_quantity = 0;

INSERT INTO tmp_stock_lp_seed_replay_guard(violation_count)
SELECT COUNT(*)
  FROM tmp_stock_lp_seed_replay replay
  LEFT JOIN stock_holding source_holding
    ON source_holding.account_id = replay.source_account_id
   AND source_holding.symbol = replay.symbol
 WHERE source_holding.account_id IS NULL
    OR source_holding.quantity
           <> replay.source_quantity_before - replay.seed_inventory_quantity
    OR source_holding.reserved_quantity <> 0;

INSERT INTO stock_holding(
    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
)
SELECT replay.liquidity_account_id,
       replay.symbol,
       replay.seed_inventory_quantity,
       0,
       replay.unit_price,
       NOW()
  FROM tmp_stock_lp_seed_replay replay
ON DUPLICATE KEY UPDATE
       quantity = VALUES(quantity),
       reserved_quantity = 0,
       average_price = VALUES(average_price),
       updated_at = VALUES(updated_at);

INSERT INTO tmp_stock_lp_seed_replay_guard(violation_count)
SELECT COUNT(*)
  FROM tmp_stock_lp_seed_replay replay
  LEFT JOIN stock_holding liquidity_holding
    ON liquidity_holding.account_id = replay.liquidity_account_id
   AND liquidity_holding.symbol = replay.symbol
 WHERE liquidity_holding.account_id IS NULL
    OR liquidity_holding.quantity <> replay.seed_inventory_quantity
    OR liquidity_holding.reserved_quantity <> 0;

-- Every enabled instrument must finish with exactly its issued quantity across all accounts.
-- A failed CHECK leaves the reset visibly incomplete instead of silently deleting or minting shares.
DROP TEMPORARY TABLE IF EXISTS tmp_stock_reset_share_guard;
CREATE TEMPORARY TABLE tmp_stock_reset_share_guard (
    violation_count BIGINT NOT NULL,
    CHECK (violation_count = 0)
);

INSERT INTO tmp_stock_reset_share_guard(violation_count)
SELECT COUNT(*)
  FROM stock_order_book_instrument instrument
  LEFT JOIN (
      SELECT symbol, SUM(quantity) AS holding_quantity
        FROM stock_holding
       GROUP BY symbol
  ) holding_sum
    ON holding_sum.symbol = instrument.symbol
 WHERE instrument.enabled = true
   AND COALESCE(holding_sum.holding_quantity, 0) <> instrument.issued_shares;

DROP TEMPORARY TABLE tmp_stock_reset_share_guard;
DROP TEMPORARY TABLE tmp_stock_lp_seed_replay_guard;
DROP TEMPORARY TABLE tmp_stock_lp_seed_replay;

SET FOREIGN_KEY_CHECKS = 1;
