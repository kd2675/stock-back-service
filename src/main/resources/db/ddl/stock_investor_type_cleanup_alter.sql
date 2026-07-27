USE STOCK_SERVICE;

-- Removes the obsolete real-market investor classification that was briefly added
-- to simulation accounts and compact flow snapshots. Role-based flow is derived as
-- USER / AUTO_PARTICIPANT / ISSUE_UNDERWRITER without touching hot ledgers.
SET SESSION lock_wait_timeout = 15;

DROP PROCEDURE IF EXISTS stock_drop_obsolete_investor_type;

DELIMITER $$

CREATE PROCEDURE stock_drop_obsolete_investor_type(
  IN p_table_name VARCHAR(64),
  IN p_constraint_name VARCHAR(64)
)
BEGIN
  SET @stock_investor_type_alter_clauses := '';

  IF EXISTS (
    SELECT 1
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND constraint_name = p_constraint_name
       AND constraint_type = 'CHECK'
  ) THEN
    SET @stock_investor_type_alter_clauses := CONCAT(
      'DROP CHECK `', REPLACE(p_constraint_name, '`', '``'), '`'
    );
  END IF;

  IF EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND column_name = 'investor_type'
  ) THEN
    SET @stock_investor_type_alter_clauses := CONCAT(
      @stock_investor_type_alter_clauses,
      IF(@stock_investor_type_alter_clauses = '', '', ', '),
      'DROP COLUMN `investor_type`'
    );
  END IF;

  IF @stock_investor_type_alter_clauses <> '' THEN
    SET @stock_investor_type_alter_sql := CONCAT(
      'ALTER TABLE `', REPLACE(p_table_name, '`', '``'), '` ',
      @stock_investor_type_alter_clauses
    );
    PREPARE stock_investor_type_alter_stmt FROM @stock_investor_type_alter_sql;
    EXECUTE stock_investor_type_alter_stmt;
    DEALLOCATE PREPARE stock_investor_type_alter_stmt;
  END IF;
END$$

DELIMITER ;

CALL stock_drop_obsolete_investor_type('stock_account', 'chk_stock_account_investor_type');
CALL stock_drop_obsolete_investor_type('stock_auto_participant', 'chk_stock_auto_participant_investor_type');
CALL stock_drop_obsolete_investor_type('stock_close_account_snapshot', 'chk_stock_close_account_snapshot_investor_type');
CALL stock_drop_obsolete_investor_type('stock_execution_account_day_summary', 'chk_stock_execution_account_day_investor_type');
CALL stock_drop_obsolete_investor_type('stock_execution_daily_account_snapshot', 'chk_stock_execution_daily_account_investor_type');

DROP PROCEDURE stock_drop_obsolete_investor_type;
