USE STOCK_SERVICE;

DROP PROCEDURE IF EXISTS preflight_stock_participant_role_cleanup;

DELIMITER //
CREATE PROCEDURE preflight_stock_participant_role_cleanup()
BEGIN
  DECLARE violation_count BIGINT DEFAULT 0;
  DECLARE simulation_clock_running BOOLEAN;

  SELECT running
    INTO simulation_clock_running
    FROM stock_simulation_clock
   WHERE clock_id = 'DEFAULT';

  IF simulation_clock_running IS NULL OR simulation_clock_running THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Pause the simulation clock before participant-role cleanup';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_account account
   WHERE account.participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   )
     AND NOT EXISTS (
         SELECT 1
           FROM stock_liquidity_transition transition
          WHERE transition.legacy_account_id = account.id
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Unsupported account roles exist outside known liquidity transitions';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_close_account_snapshot snapshot
   WHERE snapshot.participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   )
     AND NOT EXISTS (
         SELECT 1
           FROM stock_liquidity_transition transition
          WHERE transition.legacy_account_id = snapshot.account_id
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Unsupported close-snapshot roles exist outside known liquidity transitions';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_execution_daily_account_snapshot snapshot
   WHERE snapshot.participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   )
     AND NOT EXISTS (
         SELECT 1
           FROM stock_liquidity_transition transition
          WHERE transition.legacy_account_id = snapshot.account_id
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Unsupported execution-snapshot roles exist outside known liquidity transitions';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_auto_participant_share_return share_return
    LEFT JOIN stock_account receiver
      ON receiver.id = share_return.receiver_account_id
   WHERE share_return.receiver_role NOT IN (
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   )
     AND (
         receiver.id IS NULL
         OR receiver.participant_category <> 'ISSUE_UNDERWRITER'
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Unsupported share-return roles do not map to issue-underwriter accounts';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_order order_row
   WHERE order_row.origin_type IS NOT NULL
     AND order_row.origin_type NOT IN (
         'MANUAL_PARTICIPANT',
         'AUTO_PARTICIPANT',
         'INSTITUTIONAL_INVESTOR',
         'LIQUIDITY_PROVIDER',
         'ISSUE_UNDERWRITER'
     )
     AND NOT EXISTS (
         SELECT 1
           FROM stock_liquidity_transition transition
          WHERE transition.legacy_account_id = order_row.account_id
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Unsupported order origins exist outside known liquidity transitions';
  END IF;
END//
DELIMITER ;

CALL preflight_stock_participant_role_cleanup();

DROP PROCEDURE IF EXISTS preflight_stock_participant_role_cleanup;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_account DROP CHECK chk_stock_account_participant_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_account'
       AND constraint_name = 'chk_stock_account_participant_category'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_order DROP CHECK chk_stock_order_origin_type',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_origin_type'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_close_account_snapshot DROP CHECK chk_stock_close_account_snapshot_participant_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_close_account_snapshot'
       AND constraint_name = 'chk_stock_close_account_snapshot_participant_category'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_execution_daily_account_snapshot DROP CHECK chk_stock_execution_daily_account_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_execution_daily_account_snapshot'
       AND constraint_name = 'chk_stock_execution_daily_account_category'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return DROP CHECK chk_stock_auto_share_return_receiver_role',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND constraint_name = 'chk_stock_auto_share_return_receiver_role'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return DROP CHECK chk_stock_auto_share_return_reason',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND constraint_name = 'chk_stock_auto_share_return_reason'
       AND constraint_type = 'CHECK'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

DROP PROCEDURE IF EXISTS migrate_stock_participant_roles;

DELIMITER //
CREATE PROCEDURE migrate_stock_participant_roles()
BEGIN
  DECLARE violation_count BIGINT DEFAULT 0;

  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  START TRANSACTION;

  UPDATE stock_account account
  JOIN stock_liquidity_transition transition
    ON transition.legacy_account_id = account.id
     SET account.participant_category = 'ISSUE_UNDERWRITER',
         account.updated_at = GREATEST(account.updated_at, CURRENT_TIMESTAMP)
   WHERE account.participant_category <> 'ISSUE_UNDERWRITER';

  UPDATE stock_close_account_snapshot snapshot
  JOIN stock_liquidity_transition transition
    ON transition.legacy_account_id = snapshot.account_id
     SET snapshot.participant_category = 'ISSUE_UNDERWRITER'
   WHERE snapshot.participant_category <> 'ISSUE_UNDERWRITER';

  UPDATE stock_execution_daily_account_snapshot snapshot
  JOIN stock_liquidity_transition transition
    ON transition.legacy_account_id = snapshot.account_id
     SET snapshot.participant_category = 'ISSUE_UNDERWRITER'
   WHERE snapshot.participant_category <> 'ISSUE_UNDERWRITER';

  UPDATE stock_auto_participant_share_return share_return
  JOIN stock_account receiver
    ON receiver.id = share_return.receiver_account_id
   AND receiver.participant_category = 'ISSUE_UNDERWRITER'
     SET share_return.receiver_role = 'ISSUE_UNDERWRITER',
         share_return.transfer_reason = 'ISSUE_UNDERWRITER_RETURN'
   WHERE share_return.receiver_role <> 'ISSUE_UNDERWRITER'
      OR share_return.transfer_reason <> 'ISSUE_UNDERWRITER_RETURN';

  UPDATE stock_liquidity_transition transition
  STRAIGHT_JOIN stock_order order_row FORCE INDEX (idx_stock_order_account_created)
    ON order_row.account_id = transition.legacy_account_id
     SET order_row.origin_type = 'LIQUIDITY_PROVIDER'
   WHERE order_row.origin_type IS NOT NULL
     AND order_row.origin_type NOT IN (
         'MANUAL_PARTICIPANT',
         'AUTO_PARTICIPANT',
         'INSTITUTIONAL_INVESTOR',
         'LIQUIDITY_PROVIDER',
         'ISSUE_UNDERWRITER'
     );

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_account
   WHERE participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Account role cleanup post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_close_account_snapshot
   WHERE participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Close-snapshot role cleanup post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_execution_daily_account_snapshot
   WHERE participant_category NOT IN (
       'MANUAL_PARTICIPANT',
       'AUTO_PARTICIPANT',
       'INSTITUTIONAL_INVESTOR',
       'LIQUIDITY_PROVIDER',
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Execution-snapshot role cleanup post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_auto_participant_share_return
   WHERE receiver_role NOT IN (
       'ISSUE_UNDERWRITER',
       'SYSTEM_CUSTODY'
   )
      OR (
          receiver_role = 'ISSUE_UNDERWRITER'
          AND transfer_reason <> 'ISSUE_UNDERWRITER_RETURN'
      )
      OR (
          receiver_role = 'SYSTEM_CUSTODY'
          AND transfer_reason <> 'AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY'
      );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Share-return role cleanup post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_order
   WHERE origin_type IS NOT NULL
     AND origin_type NOT IN (
         'MANUAL_PARTICIPANT',
         'AUTO_PARTICIPANT',
         'INSTITUTIONAL_INVESTOR',
         'LIQUIDITY_PROVIDER',
         'ISSUE_UNDERWRITER'
     );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Order-origin cleanup post-condition failed';
  END IF;

  COMMIT;
END//
DELIMITER ;

CALL migrate_stock_participant_roles();

DROP PROCEDURE IF EXISTS migrate_stock_participant_roles;

ALTER TABLE stock_account
  ADD CONSTRAINT chk_stock_account_participant_category CHECK (
    CASE participant_category
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  );

ALTER TABLE stock_close_account_snapshot
  ADD CONSTRAINT chk_stock_close_account_snapshot_participant_category CHECK (
    CASE participant_category
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  );

ALTER TABLE stock_execution_daily_account_snapshot
  ADD CONSTRAINT chk_stock_execution_daily_account_category CHECK (
    CASE participant_category
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  );

ALTER TABLE stock_order
  ADD CONSTRAINT chk_stock_order_origin_type CHECK (
    origin_type IS NULL OR CASE origin_type
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      ELSE 0
    END = 1
  );

ALTER TABLE stock_auto_participant_share_return
  ADD CONSTRAINT chk_stock_auto_share_return_receiver_role CHECK (
    CASE receiver_role
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_auto_share_return_reason CHECK (
    CASE transfer_reason
      WHEN 'ISSUE_UNDERWRITER_RETURN' THEN 1
      WHEN 'AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE stock_auto_participant_share_return DROP INDEX idx_stock_auto_share_return_underwriter',
        'SELECT 1'
    )
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND index_name = 'idx_stock_auto_share_return_underwriter'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;

SET @stock_role_cleanup_sql = (
    SELECT IF(
        COUNT(*) = 1,
        'ALTER TABLE stock_auto_participant_share_return DROP COLUMN underwriter_account_id',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_share_return'
       AND column_name = 'underwriter_account_id'
);
PREPARE stock_role_cleanup_statement FROM @stock_role_cleanup_sql;
EXECUTE stock_role_cleanup_statement;
DEALLOCATE PREPARE stock_role_cleanup_statement;
