USE STOCK_SERVICE;

SET @stock_lifecycle_record_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND column_name = 'record_date'
);

SET @stock_lifecycle_existing_paid_in_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action
   WHERE action_type = 'PAID_IN_CAPITAL_INCREASE'
);

SET @stock_lifecycle_paid_in_guard_sql := IF(
  @stock_lifecycle_record_column_exists = 0 AND @stock_lifecycle_existing_paid_in_count > 0,
  'SELECT 1 FROM stock_migration_required_capital_increase_lifecycle',
  'SELECT 1'
);
PREPARE stock_lifecycle_paid_in_guard_stmt FROM @stock_lifecycle_paid_in_guard_sql;
EXECUTE stock_lifecycle_paid_in_guard_stmt;
DEALLOCATE PREPARE stock_lifecycle_paid_in_guard_stmt;

SET @stock_lifecycle_unsafe_cycle_count := (
  SELECT COUNT(*)
    FROM stock_post_close_cycle
   WHERE scope_type = 'FULL_MARKET'
     AND scope_key = 'ALL'
     AND status IN ('PENDING', 'RUNNING', 'DEFERRED', 'FAILED')
     AND phase <> 'COMPLETED'
);

SET @stock_lifecycle_cycle_guard_sql := IF(
  @stock_lifecycle_unsafe_cycle_count > 0,
  'SELECT 1 FROM stock_migration_required_post_close_cash_order',
  'SELECT 1'
);
PREPARE stock_lifecycle_cycle_guard_stmt FROM @stock_lifecycle_cycle_guard_sql;
EXECUTE stock_lifecycle_cycle_guard_stmt;
DEALLOCATE PREPARE stock_lifecycle_cycle_guard_stmt;

SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_record_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN record_date DATE NULL AFTER ex_rights_date',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_cycle_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_corporate_action'
     AND column_name = 'entitlement_close_cycle_id'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_cycle_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN entitlement_close_cycle_id BIGINT NULL AFTER record_date',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_run_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_corporate_action'
     AND column_name = 'entitlement_close_run_id'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_run_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN entitlement_close_run_id BIGINT NULL AFTER entitlement_close_cycle_id',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_forfeited_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_corporate_action_entitlement'
     AND column_name = 'forfeited_share_quantity'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_forfeited_column_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN forfeited_share_quantity BIGINT NOT NULL DEFAULT 0 AFTER subscribed_cash_amount',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_invalid_action_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action
   WHERE action_type = 'PAID_IN_CAPITAL_INCREASE'
     AND (
       offering_type IS NULL
       OR subscription_start_date IS NULL
       OR subscription_end_date IS NULL
       OR payment_date IS NULL
       OR listing_date IS NULL
       OR subscription_end_date < subscription_start_date
       OR payment_date <= subscription_end_date
       OR listing_date <= payment_date
       OR (
         offering_type = 'SHAREHOLDER_ALLOCATION'
         AND (
           base_price IS NULL
           OR theoretical_ex_rights_price IS NULL
           OR ex_rights_date IS NULL
           OR record_date IS NULL
           OR record_date <= ex_rights_date
           OR subscription_start_date < record_date
         )
       )
       OR offering_type NOT IN ('SHAREHOLDER_ALLOCATION', 'PUBLIC_OFFERING')
       OR (
         offering_type = 'PUBLIC_OFFERING'
         AND (ex_rights_date IS NOT NULL OR record_date IS NOT NULL)
       )
       OR (
         (entitlement_close_cycle_id IS NULL) <> (entitlement_close_run_id IS NULL)
       )
     )
);

SET @stock_lifecycle_invalid_entitlement_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action_entitlement entitlement
    JOIN stock_corporate_action action ON action.id = entitlement.action_id
   WHERE (
       action.action_type = 'PAID_IN_CAPITAL_INCREASE'
       AND entitlement.status IN ('SUBSCRIBED', 'PAID')
       AND entitlement.subscribed_at IS NOT NULL
       AND (
         entitlement.share_quantity IS NULL
         OR entitlement.subscribed_share_quantity IS NULL
         OR entitlement.subscribed_cash_amount IS NULL
         OR entitlement.subscribed_share_quantity + entitlement.forfeited_share_quantity
              <> entitlement.share_quantity
       )
     )
     OR (
       entitlement.status = 'EXPIRED'
       AND entitlement.share_quantity IS NOT NULL
       AND coalesce(entitlement.subscribed_share_quantity, 0) + entitlement.forfeited_share_quantity
            <> entitlement.share_quantity
     )
);

SET @stock_lifecycle_data_guard_sql := IF(
  @stock_lifecycle_invalid_action_count > 0 OR @stock_lifecycle_invalid_entitlement_count > 0,
  'SELECT 1 FROM stock_migration_required_capital_increase_lifecycle_data',
  'SELECT 1'
);
PREPARE stock_lifecycle_data_guard_stmt FROM @stock_lifecycle_data_guard_sql;
EXECUTE stock_lifecycle_data_guard_stmt;
DEALLOCATE PREPARE stock_lifecycle_data_guard_stmt;

SET @stock_lifecycle_cash_action_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_account_cash_flow'
     AND column_name = 'corporate_action_id'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_cash_action_column_exists = 0,
  'ALTER TABLE stock_account_cash_flow ADD COLUMN corporate_action_id BIGINT NULL AFTER created_by',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_cash_entitlement_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_account_cash_flow'
     AND column_name = 'corporate_action_entitlement_id'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_cash_entitlement_column_exists = 0,
  'ALTER TABLE stock_account_cash_flow ADD COLUMN corporate_action_entitlement_id BIGINT NULL AFTER corporate_action_id',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_cash_date_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'stock_account_cash_flow'
     AND column_name = 'effective_business_date'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_cash_date_column_exists = 0,
  'ALTER TABLE stock_account_cash_flow ADD COLUMN effective_business_date DATE NULL AFTER corporate_action_entitlement_id',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_action_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'stock_corporate_action'
     AND index_name = 'idx_stock_corporate_action_entitlement_close'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_action_index_exists = 0,
  'ALTER TABLE stock_corporate_action ADD INDEX idx_stock_corporate_action_entitlement_close (entitlement_close_cycle_id, entitlement_close_run_id)',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_cash_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'stock_account_cash_flow'
     AND index_name = 'idx_stock_account_cash_flow_corporate_action'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_cash_index_exists = 0,
  'ALTER TABLE stock_account_cash_flow ADD INDEX idx_stock_account_cash_flow_corporate_action (corporate_action_id, effective_business_date, account_id, id)',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_paid_date_order'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_paid_date_order',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_paid_date_order CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      subscription_end_date >= subscription_start_date
      AND payment_date > subscription_end_date
      AND listing_date > payment_date
      AND (
        offering_type <> 'SHAREHOLDER_ALLOCATION'
        OR (record_date > ex_rights_date AND subscription_start_date >= record_date)
      )
    )
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_paid_schedule_required'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_paid_schedule_required',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      offering_type IS NOT NULL
      AND subscription_start_date IS NOT NULL
      AND subscription_end_date IS NOT NULL
      AND payment_date IS NOT NULL
      AND listing_date IS NOT NULL
      AND (
        (
          offering_type = 'SHAREHOLDER_ALLOCATION'
          AND base_price IS NOT NULL
          AND theoretical_ex_rights_price IS NOT NULL
          AND ex_rights_date IS NOT NULL
          AND record_date IS NOT NULL
        )
        OR (
          offering_type = 'PUBLIC_OFFERING'
          AND ex_rights_date IS NULL
          AND record_date IS NULL
          AND theoretical_ex_rights_price IS NULL
        )
      )
    )
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_field_scope'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_field_scope',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
    (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR share_quantity IS NULL)
    AND (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE') OR issue_price IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR offering_type IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR subscription_start_date IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR subscription_end_date IS NULL)
    AND (action_type = 'CASH_DIVIDEND' OR dividend_amount IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR base_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR theoretical_ex_rights_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR ex_rights_date IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR record_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR entitlement_close_cycle_id IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR entitlement_close_run_id IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND') OR payment_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR listing_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_treatment IS NULL)
    AND (action_type = 'STOCK_SPLIT' OR (split_from IS NULL AND split_to IS NULL))
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_entitlement_close_pair'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_entitlement_close_pair',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_close_pair CHECK (
    (entitlement_close_cycle_id IS NULL AND entitlement_close_run_id IS NULL)
    OR (entitlement_close_cycle_id IS NOT NULL AND entitlement_close_run_id IS NOT NULL)
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_entitlement_subscription_complete'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_subscription_complete',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscription_complete CHECK (
    status NOT IN ('PARTIALLY_SUBSCRIBED', 'SUBSCRIBED')
    OR (subscribed_share_quantity IS NOT NULL AND subscribed_cash_amount IS NOT NULL AND subscribed_at IS NOT NULL)
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_entitlement_status'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_status',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_status CHECK (
    CASE `status`
      WHEN 'ANNOUNCED' THEN 1 WHEN 'PARTIALLY_SUBSCRIBED' THEN 1
      WHEN 'SUBSCRIBED' THEN 1 WHEN 'EXPIRED' THEN 1 WHEN 'PAID' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_entitlement_forfeited_share'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_forfeited_share',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_forfeited_share CHECK (forfeited_share_quantity >= 0);

SET @stock_lifecycle_check_exists := (
  SELECT COUNT(*) FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_corporate_action_entitlement_finalized_share_limit'
);
SET @stock_lifecycle_sql := IF(
  @stock_lifecycle_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_finalized_share_limit',
  'SELECT 1'
);
PREPARE stock_lifecycle_stmt FROM @stock_lifecycle_sql;
EXECUTE stock_lifecycle_stmt;
DEALLOCATE PREPARE stock_lifecycle_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_finalized_share_limit CHECK (
    (
      share_quantity IS NULL
      OR coalesce(subscribed_share_quantity, 0) + forfeited_share_quantity <= share_quantity
    )
    AND (
      status NOT IN ('SUBSCRIBED', 'PAID')
      OR subscribed_at IS NULL
      OR share_quantity IS NULL
      OR coalesce(subscribed_share_quantity, 0) + forfeited_share_quantity = share_quantity
    )
    AND (
      status <> 'EXPIRED'
      OR share_quantity IS NULL
      OR coalesce(subscribed_share_quantity, 0) + forfeited_share_quantity = share_quantity
    )
  );
