USE STOCK_SERVICE;

DROP PROCEDURE IF EXISTS add_stock_legacy_liquidity_retirement_column;

DELIMITER //
CREATE PROCEDURE add_stock_legacy_liquidity_retirement_column(
    IN column_name_value VARCHAR(64),
    IN alter_sql_value TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_liquidity_transition'
       AND column_name = column_name_value
  ) THEN
    SET @legacy_liquidity_column_sql = alter_sql_value;
    PREPARE legacy_liquidity_column_stmt FROM @legacy_liquidity_column_sql;
    EXECUTE legacy_liquidity_column_stmt;
    DEALLOCATE PREPARE legacy_liquidity_column_stmt;
  END IF;
END//
DELIMITER ;

CALL add_stock_legacy_liquidity_retirement_column(
    'transferred_inventory_quantity',
    'ALTER TABLE stock_liquidity_transition ADD COLUMN transferred_inventory_quantity BIGINT NOT NULL DEFAULT 0 AFTER seed_cash_amount'
);
CALL add_stock_legacy_liquidity_retirement_column(
    'transferred_cash_amount',
    'ALTER TABLE stock_liquidity_transition ADD COLUMN transferred_cash_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER transferred_inventory_quantity'
);
CALL add_stock_legacy_liquidity_retirement_column(
    'legacy_retired_at',
    'ALTER TABLE stock_liquidity_transition ADD COLUMN legacy_retired_at DATETIME NULL AFTER legacy_disabled_at'
);

DROP PROCEDURE IF EXISTS add_stock_legacy_liquidity_retirement_column;

DROP PROCEDURE IF EXISTS align_stock_legacy_liquidity_retirement_checks;

DELIMITER //
CREATE PROCEDURE align_stock_legacy_liquidity_retirement_checks()
BEGIN
  DECLARE transfer_check_drops TEXT;
  DECLARE cash_flow_check_drops TEXT;
  DECLARE allocation_check_drops TEXT;

  SELECT GROUP_CONCAT(
             CONCAT(
                 'DROP CHECK `',
                 REPLACE(table_constraint.constraint_name, '`', '``'),
                 '`'
             )
             ORDER BY table_constraint.constraint_name
             SEPARATOR ', '
         )
    INTO transfer_check_drops
    FROM information_schema.table_constraints table_constraint
    JOIN information_schema.check_constraints check_constraint
      ON check_constraint.constraint_schema = table_constraint.constraint_schema
     AND check_constraint.constraint_name = table_constraint.constraint_name
   WHERE table_constraint.constraint_schema = DATABASE()
     AND table_constraint.table_name = 'stock_liquidity_transition'
     AND table_constraint.constraint_type = 'CHECK'
     AND (
         LOWER(check_constraint.check_clause) LIKE '%transferred_inventory_quantity%'
         OR LOWER(check_constraint.check_clause) LIKE '%transferred_cash_amount%'
     );
  IF transfer_check_drops IS NOT NULL THEN
    SET @legacy_liquidity_check_sql = CONCAT(
        'ALTER TABLE stock_liquidity_transition ',
        transfer_check_drops
    );
    PREPARE legacy_liquidity_check_stmt FROM @legacy_liquidity_check_sql;
    EXECUTE legacy_liquidity_check_stmt;
    DEALLOCATE PREPARE legacy_liquidity_check_stmt;
  END IF;

  ALTER TABLE stock_liquidity_transition
    ADD CONSTRAINT chk_stock_liquidity_transition_transfer CHECK (
      transferred_inventory_quantity >= 0
      AND transferred_cash_amount >= 0
    );

  SELECT GROUP_CONCAT(
             CONCAT(
                 'DROP CHECK `',
                 REPLACE(table_constraint.constraint_name, '`', '``'),
                 '`'
             )
             ORDER BY table_constraint.constraint_name
             SEPARATOR ', '
         )
    INTO cash_flow_check_drops
    FROM information_schema.table_constraints table_constraint
    JOIN information_schema.check_constraints check_constraint
      ON check_constraint.constraint_schema = table_constraint.constraint_schema
     AND check_constraint.constraint_name = table_constraint.constraint_name
   WHERE table_constraint.constraint_schema = DATABASE()
     AND table_constraint.table_name = 'stock_account_cash_flow'
     AND table_constraint.constraint_type = 'CHECK'
     AND LOWER(check_constraint.check_clause) LIKE '%`reason`%';
  IF cash_flow_check_drops IS NOT NULL THEN
    SET @legacy_liquidity_check_sql = CONCAT(
        'ALTER TABLE stock_account_cash_flow ',
        cash_flow_check_drops
    );
    PREPARE legacy_liquidity_check_stmt FROM @legacy_liquidity_check_sql;
    EXECUTE legacy_liquidity_check_stmt;
    DEALLOCATE PREPARE legacy_liquidity_check_stmt;
  END IF;

  ALTER TABLE stock_account_cash_flow
    ADD CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
      CASE reason
        WHEN 'OPENING_GRANT' THEN 1
        WHEN 'ADMIN_DEPOSIT' THEN 1
        WHEN 'ADMIN_WITHDRAW' THEN 1
        WHEN 'DIVIDEND_PAYMENT' THEN 1
        WHEN 'CAPITAL_INCREASE_SUBSCRIPTION' THEN 1
        WHEN 'AUTO_PROFILE_RECURRING_DEPOSIT' THEN 1
        WHEN 'AUTO_PARTICIPANT_RECURRING_DEPOSIT' THEN 1
        WHEN 'MARKET_ROLE_TRANSFER' THEN 1
        ELSE 0
      END = 1
    );

  SELECT GROUP_CONCAT(
             CONCAT(
                 'DROP CHECK `',
                 REPLACE(table_constraint.constraint_name, '`', '``'),
                 '`'
             )
             ORDER BY table_constraint.constraint_name
             SEPARATOR ', '
         )
    INTO allocation_check_drops
    FROM information_schema.table_constraints table_constraint
    JOIN information_schema.check_constraints check_constraint
      ON check_constraint.constraint_schema = table_constraint.constraint_schema
     AND check_constraint.constraint_name = table_constraint.constraint_name
   WHERE table_constraint.constraint_schema = DATABASE()
     AND table_constraint.table_name = 'stock_security_allocation_ledger'
     AND table_constraint.constraint_type = 'CHECK'
     AND LOWER(check_constraint.check_clause) LIKE '%allocation_reason%';
  IF allocation_check_drops IS NOT NULL THEN
    SET @legacy_liquidity_check_sql = CONCAT(
        'ALTER TABLE stock_security_allocation_ledger ',
        allocation_check_drops
    );
    PREPARE legacy_liquidity_check_stmt FROM @legacy_liquidity_check_sql;
    EXECUTE legacy_liquidity_check_stmt;
    DEALLOCATE PREPARE legacy_liquidity_check_stmt;
  END IF;

  ALTER TABLE stock_security_allocation_ledger
    ADD CONSTRAINT chk_stock_security_allocation_reason CHECK (
      CASE allocation_reason
        WHEN 'INITIAL_FLOAT_CUSTODY' THEN 1
        WHEN 'INITIAL_FLOAT_UNDERWRITER' THEN 1
        WHEN 'INITIAL_LOCKED_CUSTODY' THEN 1
        WHEN 'PUBLIC_ALLOCATION' THEN 1
        WHEN 'UNSOLD_UNDERWRITING' THEN 1
        WHEN 'CORPORATE_ACTION_ALLOCATION' THEN 1
        WHEN 'LOCK_RELEASE' THEN 1
        WHEN 'LIQUIDITY_SEED_TRANSFER' THEN 1
        WHEN 'LIQUIDITY_ACCOUNT_TRANSFER' THEN 1
        ELSE 0
      END = 1
    );
END//
DELIMITER ;

CALL align_stock_legacy_liquidity_retirement_checks();

DROP PROCEDURE IF EXISTS align_stock_legacy_liquidity_retirement_checks;

DROP PROCEDURE IF EXISTS retire_stock_legacy_liquidity_accounts;

DELIMITER //
CREATE PROCEDURE retire_stock_legacy_liquidity_accounts()
main: BEGIN
  DECLARE violation_count BIGINT DEFAULT 0;
  DECLARE legacy_config_count BIGINT DEFAULT 0;
  DECLARE mapped_config_count BIGINT DEFAULT 0;
  DECLARE pending_config_count BIGINT DEFAULT 0;
  DECLARE transfer_count BIGINT DEFAULT 0;
  DECLARE migration_business_date DATE;
  DECLARE migration_at DATETIME;
  DECLARE simulation_clock_running BOOLEAN;

  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_lp_transfer;
    RESIGNAL;
  END;

  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_listing_auto_account_config'
  ) THEN
    LEAVE main;
  END IF;

  SELECT active_business_date
    INTO migration_business_date
    FROM stock_market_business_state
   WHERE state_id = 'DEFAULT';

  SELECT DATE_ADD(
             CAST(base_simulation_date AS DATETIME),
             INTERVAL FLOOR(
                 accumulated_real_seconds * 86400 / real_seconds_per_simulation_day
             ) SECOND
         ),
         running
    INTO migration_at,
         simulation_clock_running
    FROM stock_simulation_clock
   WHERE clock_id = 'DEFAULT';

  IF migration_business_date IS NULL
      OR migration_at IS NULL
      OR simulation_clock_running IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy liquidity retirement requires initialized simulation state';
  END IF;

  IF simulation_clock_running THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Pause the simulation clock before legacy liquidity retirement';
  END IF;

  DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_lp_transfer;
  CREATE TEMPORARY TABLE tmp_stock_legacy_lp_transfer (
    transition_id BIGINT NOT NULL,
    mandate_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    legacy_account_id BIGINT NOT NULL,
    liquidity_account_id BIGINT NOT NULL,
    legacy_cash DECIMAL(19,2) NOT NULL,
    legacy_quantity BIGINT NOT NULL,
    legacy_average_price DECIMAL(19,2) NOT NULL,
    liquidity_cash DECIMAL(19,2) NOT NULL,
    liquidity_quantity BIGINT NOT NULL,
    liquidity_average_price DECIMAL(19,2) NOT NULL,
    combined_quantity BIGINT NOT NULL,
    combined_average_price DECIMAL(19,2) NOT NULL,
    current_price DECIMAL(19,2) NOT NULL,
    tradable_shares BIGINT NOT NULL,
    inventory_band_quantity BIGINT NOT NULL,
    old_policy_version BIGINT NOT NULL,
    PRIMARY KEY (transition_id),
    UNIQUE KEY uk_tmp_stock_legacy_lp_symbol (symbol),
    UNIQUE KEY uk_tmp_stock_legacy_lp_legacy_account (legacy_account_id),
    UNIQUE KEY uk_tmp_stock_legacy_lp_account (liquidity_account_id)
  );

  START TRANSACTION;

  INSERT INTO tmp_stock_legacy_lp_transfer(
      transition_id, mandate_id, symbol,
      legacy_account_id, liquidity_account_id,
      legacy_cash, legacy_quantity, legacy_average_price,
      liquidity_cash, liquidity_quantity, liquidity_average_price,
      combined_quantity, combined_average_price,
      current_price, tradable_shares, inventory_band_quantity,
      old_policy_version
  )
  SELECT transition.id,
         mandate.id,
         transition.symbol,
         legacy_account.id,
         liquidity_account.id,
         legacy_account.cash_balance,
         legacy_holding.quantity,
         legacy_holding.average_price,
         liquidity_account.cash_balance,
         liquidity_holding.quantity,
         liquidity_holding.average_price,
         legacy_holding.quantity + liquidity_holding.quantity,
         CASE
           WHEN legacy_holding.quantity + liquidity_holding.quantity = 0
           THEN 0.00
           ELSE ROUND(
               (
                   legacy_holding.average_price * legacy_holding.quantity
                   + liquidity_holding.average_price * liquidity_holding.quantity
               ) / (legacy_holding.quantity + liquidity_holding.quantity),
               2
           )
         END,
         price.current_price,
         instrument.tradable_shares,
         mandate.inventory_band_quantity,
         mandate.policy_version
    FROM stock_listing_auto_account_config legacy_config
    JOIN stock_liquidity_transition transition
      ON transition.symbol = legacy_config.symbol
     AND transition.legacy_retired_at IS NULL
    JOIN stock_liquidity_mandate mandate
      ON mandate.id = transition.mandate_id
     AND mandate.symbol = transition.symbol
     AND mandate.account_id = transition.liquidity_account_id
    JOIN stock_account legacy_account
      ON legacy_account.id = transition.legacy_account_id
     AND legacy_account.user_key = legacy_config.user_key
     AND legacy_account.status = 'ACTIVE'
     AND legacy_account.participant_category = 'LISTING_UNDERWRITER'
    JOIN stock_holding legacy_holding
      ON legacy_holding.account_id = legacy_account.id
     AND legacy_holding.symbol = transition.symbol
    JOIN stock_account liquidity_account
      ON liquidity_account.id = transition.liquidity_account_id
     AND liquidity_account.status = 'ACTIVE'
     AND liquidity_account.participant_category = 'LIQUIDITY_PROVIDER'
    JOIN stock_holding liquidity_holding
      ON liquidity_holding.account_id = liquidity_account.id
     AND liquidity_holding.symbol = transition.symbol
    JOIN stock_order_book_instrument instrument
      ON instrument.symbol = transition.symbol
     AND instrument.enabled = TRUE
    JOIN stock_price price
      ON price.symbol = transition.symbol
     AND price.current_price > 0
   WHERE legacy_config.enabled = FALSE;

  SELECT COUNT(*)
    INTO legacy_config_count
    FROM stock_listing_auto_account_config;
  SELECT COUNT(*)
    INTO mapped_config_count
    FROM stock_listing_auto_account_config legacy_config
    JOIN stock_liquidity_transition transition
      ON transition.symbol = legacy_config.symbol;
  SELECT COUNT(*)
    INTO pending_config_count
    FROM stock_listing_auto_account_config legacy_config
    JOIN stock_liquidity_transition transition
      ON transition.symbol = legacy_config.symbol
   WHERE transition.legacy_retired_at IS NULL;
  SELECT COUNT(*)
    INTO transfer_count
    FROM tmp_stock_legacy_lp_transfer;

  IF legacy_config_count <> mapped_config_count THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Every legacy liquidity config must map to exactly one LP transition';
  END IF;

  IF pending_config_count <> transfer_count THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Every legacy liquidity config must map to one active LP transfer';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_listing_auto_account_config legacy_config
    JOIN stock_liquidity_transition transition
      ON transition.symbol = legacy_config.symbol
     AND transition.legacy_retired_at IS NOT NULL
    JOIN stock_account legacy_account
      ON legacy_account.id = transition.legacy_account_id
     AND legacy_account.user_key = legacy_config.user_key
    LEFT JOIN stock_holding legacy_holding
      ON legacy_holding.account_id = legacy_account.id
     AND legacy_holding.symbol = transition.symbol
   WHERE legacy_account.status <> 'CLOSED'
      OR legacy_account.cash_balance <> 0
      OR COALESCE(legacy_holding.quantity, 0) <> 0
      OR COALESCE(legacy_holding.reserved_quantity, 0) <> 0;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Previously retired legacy liquidity accounts must be closed and empty';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_listing_auto_account_config
   WHERE enabled = TRUE;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Disable every legacy liquidity config before retirement';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_lp_transfer transfer
    JOIN stock_holding legacy_holding
      ON legacy_holding.account_id = transfer.legacy_account_id
     AND legacy_holding.symbol = transfer.symbol
    JOIN stock_holding liquidity_holding
      ON liquidity_holding.account_id = transfer.liquidity_account_id
     AND liquidity_holding.symbol = transfer.symbol
   WHERE legacy_holding.reserved_quantity <> 0
      OR liquidity_holding.reserved_quantity <> 0
      OR transfer.combined_quantity + transfer.inventory_band_quantity
           > transfer.tradable_shares;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy or LP inventory is reserved or outside the LP inventory band';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_order open_order
    JOIN tmp_stock_legacy_lp_transfer transfer
      ON transfer.legacy_account_id = open_order.account_id
      OR transfer.liquidity_account_id = open_order.account_id
   WHERE open_order.status IN ('PENDING', 'PARTIALLY_FILLED')
     AND open_order.quantity > open_order.filled_quantity;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy and LP open orders must be fully cancelled before retirement';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_holding unmanaged_holding
    JOIN tmp_stock_legacy_lp_transfer transfer
      ON transfer.legacy_account_id = unmanaged_holding.account_id
   WHERE unmanaged_holding.symbol <> transfer.symbol
     AND (
         unmanaged_holding.quantity <> 0
         OR unmanaged_holding.reserved_quantity <> 0
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A legacy liquidity account contains an unmanaged symbol';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_order_book_instrument instrument
    LEFT JOIN (
        SELECT symbol, SUM(quantity) AS holding_quantity
          FROM stock_holding
         GROUP BY symbol
    ) holding_sum
      ON holding_sum.symbol = instrument.symbol
   WHERE instrument.enabled = TRUE
     AND COALESCE(holding_sum.holding_quantity, 0) <> instrument.issued_shares;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Issued-share reconciliation failed before legacy liquidity retirement';
  END IF;

  INSERT INTO stock_security_allocation_ledger(
      idempotency_key, event_type, corporate_action_id,
      underwriting_contract_id, source_account_id,
      destination_account_id, symbol, quantity, unit_price,
      allocation_reason, tradability_status,
      effective_business_date, unlock_business_date, created_at
  )
  SELECT CONCAT('LP-FULL-ACCOUNT-TRANSFER:', transfer.symbol),
         'MANUAL_REALLOCATION',
         NULL,
         NULL,
         transfer.legacy_account_id,
         transfer.liquidity_account_id,
         transfer.symbol,
         transfer.legacy_quantity,
         transfer.legacy_average_price,
         'LIQUIDITY_ACCOUNT_TRANSFER',
         'TRADABLE',
         migration_business_date,
         NULL,
         migration_at
    FROM tmp_stock_legacy_lp_transfer transfer
   WHERE transfer.legacy_quantity > 0;

  INSERT INTO stock_account_cash_flow(
      account_id, flow_type, amount, reason, created_by,
      corporate_action_id, corporate_action_entitlement_id,
      effective_business_date, created_at
  )
  SELECT transfer.legacy_account_id,
         'WITHDRAW',
         transfer.legacy_cash,
         'MARKET_ROLE_TRANSFER',
         'legacy-liquidity-retirement',
         NULL,
         NULL,
         migration_business_date,
         migration_at
    FROM tmp_stock_legacy_lp_transfer transfer
   WHERE transfer.legacy_cash > 0;

  INSERT INTO stock_account_cash_flow(
      account_id, flow_type, amount, reason, created_by,
      corporate_action_id, corporate_action_entitlement_id,
      effective_business_date, created_at
  )
  SELECT transfer.liquidity_account_id,
         'DEPOSIT',
         transfer.legacy_cash,
         'MARKET_ROLE_TRANSFER',
         'legacy-liquidity-retirement',
         NULL,
         NULL,
         migration_business_date,
         migration_at
    FROM tmp_stock_legacy_lp_transfer transfer
   WHERE transfer.legacy_cash > 0;

  UPDATE stock_holding liquidity_holding
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.liquidity_account_id = liquidity_holding.account_id
   AND transfer.symbol = liquidity_holding.symbol
     SET liquidity_holding.quantity = transfer.combined_quantity,
         liquidity_holding.reserved_quantity = 0,
         liquidity_holding.average_price = transfer.combined_average_price,
         liquidity_holding.updated_at = migration_at;

  UPDATE stock_holding legacy_holding
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.legacy_account_id = legacy_holding.account_id
   AND transfer.symbol = legacy_holding.symbol
     SET legacy_holding.quantity = 0,
         legacy_holding.reserved_quantity = 0,
         legacy_holding.updated_at = migration_at;

  UPDATE stock_account liquidity_account
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.liquidity_account_id = liquidity_account.id
     SET liquidity_account.cash_balance =
             transfer.liquidity_cash + transfer.legacy_cash,
         liquidity_account.updated_at = migration_at;

  UPDATE stock_account legacy_account
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.legacy_account_id = legacy_account.id
     SET legacy_account.cash_balance = 0.00,
         legacy_account.status = 'CLOSED',
         legacy_account.updated_at = migration_at;

  UPDATE stock_liquidity_mandate mandate
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.mandate_id = mandate.id
     SET mandate.target_inventory_quantity = transfer.combined_quantity,
         mandate.daily_loss_limit_amount = GREATEST(
             1.00,
             ROUND(
                 (
                     transfer.liquidity_cash
                     + transfer.legacy_cash
                     + transfer.current_price * transfer.combined_quantity
                 ) * 0.010000,
                 2
             )
         ),
         mandate.policy_version = transfer.old_policy_version + 1,
         mandate.updated_at = migration_at;

  UPDATE stock_liquidity_daily_state daily_state
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.mandate_id = daily_state.mandate_id
   AND daily_state.simulation_trade_date = migration_business_date
     SET daily_state.opening_net_asset_value =
             daily_state.opening_net_asset_value
             + transfer.legacy_cash
             + transfer.current_price * transfer.legacy_quantity,
         daily_state.current_net_asset_value =
             daily_state.current_net_asset_value
             + transfer.legacy_cash
             + transfer.current_price * transfer.legacy_quantity,
         daily_state.last_inventory_quantity =
             daily_state.last_inventory_quantity + transfer.legacy_quantity,
         daily_state.last_projected_inventory_quantity =
             daily_state.last_projected_inventory_quantity
             + transfer.legacy_quantity,
         daily_state.policy_version = transfer.old_policy_version + 1,
         daily_state.version = daily_state.version + 1,
         daily_state.updated_at = migration_at;

  UPDATE stock_market_policy_version policy
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON policy.policy_scope = 'LIQUIDITY_MANDATE'
   AND policy.scope_key = transfer.symbol
     SET policy.status = 'RETIRED',
         policy.updated_at = migration_at
   WHERE policy.status IN ('SCHEDULED', 'ACTIVE');

  INSERT INTO stock_market_policy_version(
      policy_scope, scope_key, version_no,
      effective_business_date, status, config_json,
      change_reason, changed_by, created_at, updated_at
  )
  SELECT 'LIQUIDITY_MANDATE',
         mandate.symbol,
         mandate.policy_version,
         migration_business_date,
         'ACTIVE',
         JSON_OBJECT(
             'preset', 'INDEPENDENT_LIQUIDITY_PROVIDER_V2',
             'migration', 'LEGACY_FULL_ACCOUNT_TRANSFER',
             'symbol', mandate.symbol,
             'executionMode', mandate.execution_mode,
             'status', mandate.status,
             'targetSpreadTicks', mandate.target_spread_ticks,
             'maxSpreadTicks', mandate.max_spread_ticks,
             'maxOrderQuantity', mandate.max_order_quantity,
             'referenceDailyVolume', mandate.reference_daily_volume,
             'targetOpenParticipationRate', mandate.target_open_participation_rate,
             'maxOpenParticipationRate', mandate.max_open_participation_rate,
             'maxSingleOrderParticipationRate',
                 mandate.max_single_order_participation_rate,
             'externalDepthLevels', mandate.external_depth_levels,
             'maxExternalDepthParticipationRate',
                 mandate.max_external_depth_participation_rate,
             'dailyExecutionParticipationRate',
                 mandate.daily_execution_participation_rate,
             'dailySubmissionMultiplier', mandate.daily_submission_multiplier,
             'targetInventoryQuantity', mandate.target_inventory_quantity,
             'inventoryBandQuantity', mandate.inventory_band_quantity,
             'inventorySkewTicks', mandate.inventory_skew_ticks,
             'primaryRegimeWeight', mandate.primary_regime_weight,
             'liquiditySizeSensitivity', mandate.liquidity_size_sensitivity,
             'volatilitySpreadMaxTicks', mandate.volatility_spread_max_ticks,
             'priceRegimeMaxSkewTicks', mandate.price_regime_max_skew_ticks,
             'passiveOnly', mandate.passive_only,
             'minimumQuoteLifetimeSeconds',
                 mandate.minimum_quote_lifetime_seconds,
             'repriceThresholdTicks', mandate.reprice_threshold_ticks,
             'orderTtlSeconds', mandate.order_ttl_seconds,
             'quoteIntervalSeconds', mandate.quote_interval_seconds,
             'dailyLossLimitAmount', mandate.daily_loss_limit_amount
         ),
         'Transfer all residual legacy-liquidity assets and retire the old account',
         'legacy-liquidity-retirement',
         migration_at,
         migration_at
    FROM stock_liquidity_mandate mandate
    JOIN tmp_stock_legacy_lp_transfer transfer
      ON transfer.mandate_id = mandate.id;

  UPDATE stock_liquidity_transition transition
  JOIN tmp_stock_legacy_lp_transfer transfer
    ON transfer.transition_id = transition.id
     SET transition.transferred_inventory_quantity =
             transfer.legacy_quantity,
         transition.transferred_cash_amount = transfer.legacy_cash,
         transition.legacy_retired_at = migration_at,
         transition.policy_version = transfer.old_policy_version + 1,
         transition.change_reason = CONCAT(
             LEFT(transition.change_reason, 350),
             '; full legacy account transferred and retired'
         ),
         transition.updated_at = migration_at;

  DELETE batch_lock
    FROM stock_batch_job_lock batch_lock
   WHERE batch_lock.job_name = 'listing-auto-market';
  DELETE batch_control
    FROM stock_batch_job_control batch_control
   WHERE batch_control.job_name = 'listing-auto-market';

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_lp_transfer transfer
    JOIN stock_account legacy_account
      ON legacy_account.id = transfer.legacy_account_id
    JOIN stock_account liquidity_account
      ON liquidity_account.id = transfer.liquidity_account_id
    JOIN stock_holding legacy_holding
      ON legacy_holding.account_id = transfer.legacy_account_id
     AND legacy_holding.symbol = transfer.symbol
    JOIN stock_holding liquidity_holding
      ON liquidity_holding.account_id = transfer.liquidity_account_id
     AND liquidity_holding.symbol = transfer.symbol
    JOIN stock_liquidity_transition transition
      ON transition.id = transfer.transition_id
   WHERE legacy_account.status <> 'CLOSED'
      OR legacy_account.cash_balance <> 0
      OR legacy_holding.quantity <> 0
      OR legacy_holding.reserved_quantity <> 0
      OR liquidity_account.cash_balance
           <> transfer.liquidity_cash + transfer.legacy_cash
      OR liquidity_holding.quantity <> transfer.combined_quantity
      OR liquidity_holding.reserved_quantity <> 0
      OR transition.legacy_retired_at IS NULL;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy liquidity retirement post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_order_book_instrument instrument
    LEFT JOIN (
        SELECT symbol, SUM(quantity) AS holding_quantity
          FROM stock_holding
         GROUP BY symbol
    ) holding_sum
      ON holding_sum.symbol = instrument.symbol
   WHERE instrument.enabled = TRUE
     AND COALESCE(holding_sum.holding_quantity, 0) <> instrument.issued_shares;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Issued-share reconciliation failed after legacy liquidity retirement';
  END IF;

  COMMIT;
  DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_lp_transfer;
END//
DELIMITER ;

CALL retire_stock_legacy_liquidity_accounts();

DROP PROCEDURE IF EXISTS retire_stock_legacy_liquidity_accounts;

DROP TABLE IF EXISTS stock_listing_auto_account_config;
