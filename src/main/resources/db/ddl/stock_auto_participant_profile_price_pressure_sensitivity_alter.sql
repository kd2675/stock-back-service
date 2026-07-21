USE STOCK_SERVICE;

SET @profile_price_sensitivity_column_existed := EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'price_pressure_sensitivity'
);

SET @profile_price_sensitivity_add_sql := IF(
    @profile_price_sensitivity_column_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN price_pressure_sensitivity DECIMAL(8,4) NOT NULL DEFAULT 1.0000 AFTER aggression_multiplier'
);
PREPARE profile_price_sensitivity_add_stmt FROM @profile_price_sensitivity_add_sql;
EXECUTE profile_price_sensitivity_add_stmt;
DEALLOCATE PREPARE profile_price_sensitivity_add_stmt;

SET @profile_price_sensitivity_backfill_sql := IF(
    @profile_price_sensitivity_column_existed,
    'SELECT 1',
    'UPDATE stock_auto_participant_profile_config
        SET price_pressure_sensitivity = CASE profile_type
          WHEN ''NEWS_REACTIVE'' THEN 1.3000
          WHEN ''FOMO_BUYER'' THEN 1.3000
          WHEN ''PANIC_SELLER'' THEN 1.3000
          WHEN ''MOMENTUM_FOLLOWER'' THEN 1.2000
          WHEN ''HERD_FOLLOWER'' THEN 1.2000
          WHEN ''STOP_LOSS_TRADER'' THEN 1.2000
          WHEN ''OVERCONFIDENT'' THEN 1.1000
          WHEN ''DAY_TRADER'' THEN 1.1000
          WHEN ''SCALPER'' THEN 1.0500
          WHEN ''SWING_TRADER'' THEN 1.0000
          WHEN ''PROFIT_LOCKER'' THEN 1.0000
          WHEN ''CONTRARIAN'' THEN 0.9500
          WHEN ''DIP_BUYER'' THEN 0.9000
          WHEN ''AVERAGE_DOWN_BUYER'' THEN 0.8500
          WHEN ''WHALE'' THEN 0.8500
          WHEN ''LOSS_AVERSE'' THEN 0.8000
          WHEN ''VALUE_ANCHOR'' THEN 0.7000
          WHEN ''SMALL_DIVERSIFIER'' THEN 0.6500
          WHEN ''NOISE_TRADER'' THEN 0.6000
          WHEN ''PAYDAY_ACCUMULATOR'' THEN 0.6000
          WHEN ''DIVIDEND_REINVESTOR'' THEN 0.6000
          WHEN ''LONG_TERM_HOLDER'' THEN 0.5500
          WHEN ''LIQUIDITY_AVOIDANT'' THEN 0.4500
          WHEN ''CASH_DEFENSIVE'' THEN 0.4500
          WHEN ''LIMIT_DOWN_TRAPPED'' THEN 0.4000
          WHEN ''MARKET_MAKER'' THEN 0.3000
          WHEN ''OBSERVER'' THEN 0.3000
          ELSE 1.0000
        END'
);
PREPARE profile_price_sensitivity_backfill_stmt FROM @profile_price_sensitivity_backfill_sql;
EXECUTE profile_price_sensitivity_backfill_stmt;
DEALLOCATE PREPARE profile_price_sensitivity_backfill_stmt;

SET @profile_price_sensitivity_drop_default_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_profile_config'
           AND column_name = 'price_pressure_sensitivity'
           AND column_default IS NOT NULL
    ),
    'ALTER TABLE stock_auto_participant_profile_config ALTER COLUMN price_pressure_sensitivity DROP DEFAULT',
    'SELECT 1'
);
PREPARE profile_price_sensitivity_drop_default_stmt FROM @profile_price_sensitivity_drop_default_sql;
EXECUTE profile_price_sensitivity_drop_default_stmt;
DEALLOCATE PREPARE profile_price_sensitivity_drop_default_stmt;

SET @profile_price_sensitivity_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant_profile_config'
           AND constraint_name = 'chk_stock_auto_profile_price_pressure_sensitivity'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_price_pressure_sensitivity CHECK (price_pressure_sensitivity >= 0 AND price_pressure_sensitivity <= 2)'
);
PREPARE profile_price_sensitivity_check_stmt FROM @profile_price_sensitivity_check_sql;
EXECUTE profile_price_sensitivity_check_stmt;
DEALLOCATE PREPARE profile_price_sensitivity_check_stmt;
