USE STOCK_SERVICE;

SET @database_name = 'STOCK_SERVICE';
SET @table_name = 'stock_account';

SET @sql = IF(
  EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = @database_name
      AND table_name = @table_name
      AND column_name = 'user_key'
      AND is_nullable = 'NO'
  ),
  'ALTER TABLE stock_account MODIFY user_key VARCHAR(64) NULL',
  'SELECT ''stock_account.user_key already nullable'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'account_code'
  ),
  'ALTER TABLE stock_account ADD COLUMN account_code VARCHAR(32) NULL AFTER user_key',
  'SELECT ''stock_account.account_code already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'recovery_code_hash'
  ),
  'ALTER TABLE stock_account ADD COLUMN recovery_code_hash VARCHAR(128) NULL AFTER account_code',
  'SELECT ''stock_account.recovery_code_hash already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'status'
  ),
  'ALTER TABLE stock_account ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'' AFTER recovery_code_hash',
  'SELECT ''stock_account.status already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'detached_at'
  ),
  'ALTER TABLE stock_account ADD COLUMN detached_at DATETIME NULL AFTER updated_at',
  'SELECT ''stock_account.detached_at already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'reconnected_at'
  ),
  'ALTER TABLE stock_account ADD COLUMN reconnected_at DATETIME NULL AFTER detached_at',
  'SELECT ''stock_account.reconnected_at already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'recovery_expires_at'
  ),
  'ALTER TABLE stock_account ADD COLUMN recovery_expires_at DATETIME NULL AFTER reconnected_at',
  'SELECT ''stock_account.recovery_expires_at already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'purge_after'
  ),
  'ALTER TABLE stock_account ADD COLUMN purge_after DATETIME NULL AFTER recovery_expires_at',
  'SELECT ''stock_account.purge_after already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @database_name AND table_name = @table_name AND column_name = 'previous_user_key_hash'
  ),
  'ALTER TABLE stock_account ADD COLUMN previous_user_key_hash VARCHAR(128) NULL AFTER purge_after',
  'SELECT ''stock_account.previous_user_key_hash already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = @database_name AND table_name = @table_name AND index_name = 'uk_stock_account_account_code'
  ),
  'ALTER TABLE stock_account ADD UNIQUE KEY uk_stock_account_account_code (account_code)',
  'SELECT ''uk_stock_account_account_code already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = @database_name AND table_name = @table_name AND index_name = 'idx_stock_account_status_purge'
  ),
  'ALTER TABLE stock_account ADD KEY idx_stock_account_status_purge (status, purge_after)',
  'SELECT ''idx_stock_account_status_purge already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = @database_name AND table_name = @table_name AND constraint_name = 'chk_stock_account_status_valid'
  ),
  'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_status_valid CHECK (CASE `status` WHEN ''ACTIVE'' THEN 1 WHEN ''DETACHED'' THEN 1 WHEN ''CLOSED'' THEN 1 ELSE 0 END = 1)',
  'SELECT ''chk_stock_account_status_valid already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = @database_name AND table_name = @table_name AND constraint_name = 'chk_stock_account_detached_user_scope'
  ),
  'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_detached_user_scope CHECK (status <> ''DETACHED'' OR user_key IS NULL)',
  'SELECT ''chk_stock_account_detached_user_scope already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = @database_name AND table_name = @table_name AND constraint_name = 'chk_stock_account_recovery_window'
  ),
  'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_recovery_window CHECK (recovery_expires_at IS NULL OR purge_after IS NULL OR purge_after >= recovery_expires_at)',
  'SELECT ''chk_stock_account_recovery_window already exists'' AS message'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
