USE STOCK_SERVICE;

-- Align the live STOCK_SERVICE schema to db/ddl/stock_all.sql as of 2026-07-07.
-- Review before execution. This script removes tables that are not present in stock_all.sql.

-- 1. Drop live-only tables that are not present in stock_all.sql.
DROP TABLE IF EXISTS stock_order_book_open;
DROP TABLE IF EXISTS stock_order_book_symbol_work_lease;

-- 2. Rename stock_auto_participant_order_schedule check constraints to match stock_all.sql.
SET @drop_auto_schedule_interval_check_sql := (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE stock_auto_participant_order_schedule DROP CHECK chk_stock_auto_order_schedule_participant_migration_interval',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_auto_order_schedule_participant_migration_interval'
);
PREPARE drop_auto_schedule_interval_check_stmt FROM @drop_auto_schedule_interval_check_sql;
EXECUTE drop_auto_schedule_interval_check_stmt;
DEALLOCATE PREPARE drop_auto_schedule_interval_check_stmt;

SET @drop_auto_schedule_priority_check_sql := (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE stock_auto_participant_order_schedule DROP CHECK chk_stock_auto_order_schedule_participant_migration_priority',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_auto_order_schedule_participant_migration_priority'
);
PREPARE drop_auto_schedule_priority_check_stmt FROM @drop_auto_schedule_priority_check_sql;
EXECUTE drop_auto_schedule_priority_check_stmt;
DEALLOCATE PREPARE drop_auto_schedule_priority_check_stmt;

SET @add_auto_schedule_interval_check_sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_order_schedule ADD CONSTRAINT chk_stock_auto_order_schedule_interval CHECK (run_interval_seconds > 0)',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_auto_order_schedule_interval'
);
PREPARE add_auto_schedule_interval_check_stmt FROM @add_auto_schedule_interval_check_sql;
EXECUTE add_auto_schedule_interval_check_stmt;
DEALLOCATE PREPARE add_auto_schedule_interval_check_stmt;

SET @add_auto_schedule_priority_check_sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_auto_participant_order_schedule ADD CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (priority between 1 and 100)',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_auto_order_schedule_priority'
);
PREPARE add_auto_schedule_priority_check_stmt FROM @add_auto_schedule_priority_check_sql;
EXECUTE add_auto_schedule_priority_check_stmt;
DEALLOCATE PREPARE add_auto_schedule_priority_check_stmt;

-- 3. Recreate market_status checks so they allow CIRCUIT_BREAKER, matching stock_all.sql.
SET @drop_order_book_market_status_check_sql := (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE stock_order_book_market_config DROP CHECK chk_stock_order_book_market_status',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_market_status'
);
PREPARE drop_order_book_market_status_check_stmt FROM @drop_order_book_market_status_check_sql;
EXECUTE drop_order_book_market_status_check_stmt;
DEALLOCATE PREPARE drop_order_book_market_status_check_stmt;

ALTER TABLE stock_order_book_market_config
  ADD CONSTRAINT chk_stock_order_book_market_status
  CHECK (
      CASE `market_status`
          WHEN 'OPEN' THEN 1
          WHEN 'CLOSED' THEN 1
          WHEN 'HALTED' THEN 1
          WHEN 'CIRCUIT_BREAKER' THEN 1
          ELSE 0
      END = 1
  );

SET @drop_virtual_market_status_check_sql := (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE stock_virtual_market_config DROP CHECK chk_stock_virtual_market_status',
        'SELECT 1'
    )
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_virtual_market_status'
);
PREPARE drop_virtual_market_status_check_stmt FROM @drop_virtual_market_status_check_sql;
EXECUTE drop_virtual_market_status_check_stmt;
DEALLOCATE PREPARE drop_virtual_market_status_check_stmt;

ALTER TABLE stock_virtual_market_config
  ADD CONSTRAINT chk_stock_virtual_market_status
  CHECK (
      CASE `market_status`
          WHEN 'OPEN' THEN 1
          WHEN 'CLOSED' THEN 1
          WHEN 'HALTED' THEN 1
          WHEN 'CIRCUIT_BREAKER' THEN 1
          ELSE 0
      END = 1
  );
