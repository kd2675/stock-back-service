CREATE DATABASE IF NOT EXISTS STOCK_SERVICE
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_batch_job_control (
  job_name VARCHAR(100) NOT NULL,
  runtime_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  scheduler_configured BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  CONSTRAINT chk_stock_batch_job_control_name CHECK (job_name <> '')
);

CREATE TABLE IF NOT EXISTS stock_batch_job_lock (
  job_name VARCHAR(100) NOT NULL,
  lock_owner VARCHAR(128) NOT NULL,
  locked_until DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  KEY idx_stock_batch_job_lock_until (locked_until),
  CONSTRAINT chk_stock_batch_job_lock_name CHECK (job_name <> ''),
  CONSTRAINT chk_stock_batch_job_lock_owner CHECK (lock_owner <> '')
);

CREATE TABLE IF NOT EXISTS stock_batch_job_signal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  signal_type VARCHAR(60) NOT NULL,
  job_name VARCHAR(100) NOT NULL,
  execution_mode VARCHAR(120) NOT NULL,
  symbol VARCHAR(20) NULL,
  payload_json TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  requested_by VARCHAR(64) NULL,
  requested_at DATETIME NOT NULL,
  requested_business_date DATE NULL,
  requested_session_epoch BIGINT NULL,
  expected_cycle_id BIGINT NULL,
  eligible_at DATETIME NULL,
  next_attempt_at DATETIME NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 8,
  claim_token VARCHAR(64) NULL,
  lease_until DATETIME NULL,
  failure_class VARCHAR(40) NULL,
  picked_at DATETIME NULL,
  completed_at DATETIME NULL,
  processed_count INT NULL,
  message VARCHAR(500) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_batch_job_signal_status_time (status, requested_at, id),
  KEY idx_stock_batch_job_signal_job_status (job_name, status, requested_at),
  KEY idx_stock_batch_job_signal_claim (status, next_attempt_at, eligible_at, id),
  KEY idx_stock_batch_job_signal_lease (status, lease_until, id),
  KEY idx_stock_batch_job_signal_cycle (expected_cycle_id, status, id),
  KEY idx_stock_batch_job_signal_cycle_id (expected_cycle_id, id),
  CONSTRAINT chk_stock_batch_job_signal_type CHECK (signal_type <> ''),
  CONSTRAINT chk_stock_batch_job_signal_job CHECK (job_name <> ''),
  CONSTRAINT chk_stock_batch_job_signal_mode CHECK (execution_mode <> ''),
  CONSTRAINT chk_stock_batch_job_signal_status CHECK (
    CASE `status`
      WHEN 'PENDING' THEN 1
      WHEN 'DEFERRED' THEN 1
      WHEN 'PROCESSING' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'FAILED' THEN 1
      WHEN 'DEAD_LETTER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_batch_job_signal_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT chk_stock_batch_job_signal_max_attempts CHECK (max_attempts > 0),
  CONSTRAINT chk_stock_batch_job_signal_epoch CHECK (requested_session_epoch IS NULL OR requested_session_epoch > 0)
);

CREATE TABLE IF NOT EXISTS stock_simulation_clock (
  clock_id VARCHAR(40) NOT NULL,
  base_simulation_date DATE NOT NULL,
  real_seconds_per_simulation_day INT NOT NULL,
  accumulated_real_seconds BIGINT NOT NULL DEFAULT 0,
  running BOOLEAN NOT NULL DEFAULT FALSE,
  last_started_at DATETIME NULL,
  last_heartbeat_at DATETIME NULL,
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Seoul',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (clock_id),
  CONSTRAINT chk_stock_simulation_clock_id CHECK (clock_id <> ''),
  CONSTRAINT chk_stock_simulation_clock_day_seconds CHECK (real_seconds_per_simulation_day > 0),
  CONSTRAINT chk_stock_simulation_clock_accumulated CHECK (accumulated_real_seconds >= 0),
  CONSTRAINT chk_stock_simulation_clock_running_dates CHECK (
    running = FALSE OR (last_started_at IS NOT NULL AND last_heartbeat_at IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_key VARCHAR(64) NULL,
  account_code VARCHAR(32) NULL,
  recovery_code_hash VARCHAR(128) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  participant_category VARCHAR(30) NOT NULL DEFAULT 'MANUAL_PARTICIPANT',
  self_trade_group_id VARCHAR(80) NULL,
  cash_balance DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  detached_at DATETIME NULL,
  reconnected_at DATETIME NULL,
  recovery_expires_at DATETIME NULL,
  purge_after DATETIME NULL,
  previous_user_key_hash VARCHAR(128) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_account_user_key (user_key),
  UNIQUE KEY uk_stock_account_account_code (account_code),
  KEY idx_stock_account_status_purge (status, purge_after),
  KEY idx_stock_account_status_id (status, id),
  KEY idx_stock_account_status_participant_id (status, participant_category, id),
  CONSTRAINT chk_stock_account_cash_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_stock_account_status_valid CHECK (CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'DETACHED' THEN 1 WHEN 'CLOSED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_account_participant_category CHECK (
    CASE `participant_category`
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_account_self_trade_group CHECK (
    self_trade_group_id IS NULL OR self_trade_group_id <> ''
  ),
  CONSTRAINT chk_stock_account_detached_user_scope CHECK (status <> 'DETACHED' OR user_key IS NULL),
  CONSTRAINT chk_stock_account_recovery_window CHECK (
    recovery_expires_at IS NULL OR purge_after IS NULL OR purge_after >= recovery_expires_at
  )
);

CREATE TABLE IF NOT EXISTS stock_market_participant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  participant_type VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  self_trade_group_id VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_participant_code (participant_code),
  UNIQUE KEY uk_stock_market_participant_self_trade_group (self_trade_group_id),
  KEY idx_stock_market_participant_type_status (participant_type, status, id),
  CONSTRAINT chk_stock_market_participant_code CHECK (participant_code <> ''),
  CONSTRAINT chk_stock_market_participant_name CHECK (display_name <> ''),
  CONSTRAINT chk_stock_market_participant_type CHECK (
    CASE `participant_type`
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_participant_status CHECK (
    CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'RETIRED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_participant_self_trade_group CHECK (self_trade_group_id <> '')
);

CREATE TABLE IF NOT EXISTS stock_market_participant_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  account_role VARCHAR(40) NOT NULL,
  desk_code VARCHAR(64) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_participant_account (account_id),
  UNIQUE KEY uk_stock_market_participant_role_desk (
    participant_id, account_role, desk_code
  ),
  KEY idx_stock_market_participant_account_lookup (
    participant_id, status, account_role, account_id
  ),
  CONSTRAINT chk_stock_market_participant_account_role CHECK (
    CASE `account_role`
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_participant_account_status CHECK (
    CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'CLOSED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_participant_account_dates CHECK (
    effective_to IS NULL OR effective_to >= effective_from
  ),
  CONSTRAINT chk_stock_market_participant_account_desk CHECK (desk_code <> '')
);

CREATE TABLE IF NOT EXISTS stock_market_policy_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  policy_scope VARCHAR(40) NOT NULL,
  scope_key VARCHAR(80) NOT NULL,
  version_no BIGINT NOT NULL,
  effective_business_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  config_json JSON NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  changed_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_policy_version (policy_scope, scope_key, version_no),
  KEY idx_stock_market_policy_effective (
    status, effective_business_date, policy_scope, scope_key, version_no
  ),
  CONSTRAINT chk_stock_market_policy_scope CHECK (
    CASE `policy_scope`
      WHEN 'GLOBAL_RISK' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_PORTFOLIO' THEN 1
      WHEN 'LIQUIDITY_MANDATE' THEN 1
      WHEN 'UNDERWRITING_CONTRACT' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_policy_status CHECK (
    CASE `status`
      WHEN 'DRAFT' THEN 1
      WHEN 'SCHEDULED' THEN 1
      WHEN 'ACTIVE' THEN 1
      WHEN 'RETIRED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_policy_scope_key CHECK (scope_key <> ''),
  CONSTRAINT chk_stock_market_policy_version_no CHECK (version_no > 0),
  CONSTRAINT chk_stock_market_policy_reason CHECK (change_reason <> '')
);

CREATE TABLE IF NOT EXISTS stock_account_cash_flow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  flow_type VARCHAR(20) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  reason VARCHAR(40) NOT NULL,
  created_by VARCHAR(64) NULL,
  corporate_action_id BIGINT NULL,
  corporate_action_entitlement_id BIGINT NULL,
  effective_business_date DATE NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_account_cash_flow_account_id (account_id, id),
  KEY idx_stock_account_cash_flow_account_time (account_id, created_at, id),
  KEY idx_stock_account_cash_flow_account_reason_creator_time (account_id, reason, created_by, created_at, id),
  KEY idx_stock_account_cash_flow_account_type_reason_time (account_id, flow_type, reason, created_at, id),
  KEY idx_stock_account_cash_flow_time (created_at, id),
  KEY idx_stock_account_cash_flow_corporate_action (corporate_action_id, effective_business_date, account_id, id),
  CONSTRAINT chk_stock_account_cash_flow_type CHECK (CASE `flow_type` WHEN 'DEPOSIT' THEN 1 WHEN 'WITHDRAW' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_account_cash_flow_amount CHECK (amount > 0),
  CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
    CASE `reason`
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
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_cash_flow_run (
  run_key VARCHAR(160) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  last_account_id BIGINT NOT NULL DEFAULT 0,
  processed_count BIGINT NOT NULL DEFAULT 0,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_key),
  KEY idx_stock_auto_participant_cash_flow_run_completed (completed_at, run_key),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_operation CHECK (
    CASE `operation` WHEN 'SCHEDULED' THEN 1 WHEN 'MANUAL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_cursor CHECK (last_account_id >= 0),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_count CHECK (processed_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (symbol)
);

CREATE TABLE IF NOT EXISTS stock_order_book_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  issued_shares BIGINT NOT NULL,
  tradable_shares BIGINT NOT NULL,
  tick_size DECIMAL(19,2) NOT NULL DEFAULT 1.00,
  price_limit_rate DECIMAL(5,2) NOT NULL DEFAULT 30.00,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_instrument_enabled (enabled, symbol),
  CONSTRAINT chk_stock_order_book_instrument_initial_price CHECK (initial_price > 0),
  CONSTRAINT chk_stock_order_book_instrument_issued_shares CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_instrument_tradable_shares CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_instrument_tick_size CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_instrument_price_limit_rate CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

CREATE TABLE IF NOT EXISTS stock_corporate_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  share_quantity BIGINT NULL,
  issue_price DECIMAL(19,2) NULL,
  dividend_amount DECIMAL(19,2) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'ANNOUNCED',
  base_price DECIMAL(19,2) NULL,
  theoretical_ex_rights_price DECIMAL(19,2) NULL,
  ex_rights_date DATE NULL,
  record_date DATE NULL,
  entitlement_close_cycle_id BIGINT NULL,
  entitlement_close_run_id BIGINT NULL,
  payment_date DATE NULL,
  listing_date DATE NULL,
  delisting_date DATE NULL,
  offering_type VARCHAR(40) NULL,
  subscription_start_date DATE NULL,
  subscription_end_date DATE NULL,
  delisting_treatment VARCHAR(30) NULL,
  applied_at DATETIME NULL,
  paid_at DATETIME NULL,
  listed_at DATETIME NULL,
  split_from INT NULL,
  split_to INT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_corporate_action_symbol_created (symbol, created_at),
  KEY idx_stock_corporate_action_created (created_at, id),
  KEY idx_stock_corporate_action_type_created (action_type, created_at, id),
  KEY idx_stock_corporate_action_status_dates (status, ex_rights_date, payment_date, listing_date, delisting_date),
  KEY idx_stock_corporate_action_status_symbol (status, symbol),
  KEY idx_stock_corporate_action_entitlement_close (entitlement_close_cycle_id, entitlement_close_run_id),
  CONSTRAINT chk_stock_corporate_action_type_valid CHECK (CASE `action_type` WHEN 'INITIAL_ISSUE' THEN 1 WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1 WHEN 'STOCK_SPLIT' THEN 1 WHEN 'CASH_DIVIDEND' THEN 1 WHEN 'BONUS_ISSUE' THEN 1 WHEN 'STOCK_DIVIDEND' THEN 1 WHEN 'DELISTING' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_status_valid CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'EX_RIGHTS_APPLIED' THEN 1 WHEN 'PAID' THEN 1 WHEN 'LISTED' THEN 1 WHEN 'DELISTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_delisting_treatment CHECK (delisting_treatment IS NULL OR delisting_treatment = 'ZERO_VALUE'),
  CONSTRAINT chk_stock_corporate_action_offering_type CHECK (offering_type IS NULL OR CASE `offering_type` WHEN 'SHAREHOLDER_ALLOCATION' THEN 1 WHEN 'PUBLIC_OFFERING' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_share_quantity CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_issue_price CHECK (issue_price IS NULL OR issue_price > 0),
  CONSTRAINT chk_stock_corporate_action_dividend_amount CHECK (dividend_amount IS NULL OR dividend_amount > 0),
  CONSTRAINT chk_stock_corporate_action_base_price CHECK (base_price IS NULL OR base_price > 0),
  CONSTRAINT chk_stock_corporate_action_ex_rights_price CHECK (theoretical_ex_rights_price IS NULL OR theoretical_ex_rights_price > 0),
  CONSTRAINT chk_stock_corporate_action_paid_dates CHECK (ex_rights_date IS NULL OR payment_date IS NULL OR payment_date >= ex_rights_date),
  CONSTRAINT chk_stock_corporate_action_listing_dates CHECK (payment_date IS NULL OR listing_date IS NULL OR listing_date >= payment_date),
  CONSTRAINT chk_stock_corporate_action_subscription_dates CHECK (subscription_start_date IS NULL OR subscription_end_date IS NULL OR subscription_end_date >= subscription_start_date),
  CONSTRAINT chk_stock_corporate_action_paid_date_order CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      subscription_end_date >= subscription_start_date
      AND payment_date > subscription_end_date
      AND listing_date > payment_date
      AND (
        offering_type <> 'SHAREHOLDER_ALLOCATION'
        OR (
          record_date > ex_rights_date
          AND subscription_start_date >= record_date
        )
      )
    )
  ),
  CONSTRAINT chk_stock_corporate_action_split_from CHECK (split_from IS NULL OR split_from > 0),
  CONSTRAINT chk_stock_corporate_action_split_to CHECK (split_to IS NULL OR split_to > 0),
  CONSTRAINT chk_stock_corporate_action_issue_required CHECK (
    action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')
    OR (share_quantity IS NOT NULL AND issue_price IS NOT NULL)
  ),
  CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (
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
  ),
  CONSTRAINT chk_stock_corporate_action_split_required CHECK (
    action_type <> 'STOCK_SPLIT'
    OR (
      split_from IS NOT NULL
      AND split_to IS NOT NULL
      AND split_to > split_from
      AND MOD(split_to, split_from) = 0
    )
  ),
  CONSTRAINT chk_stock_corporate_action_dividend_required CHECK (
    action_type <> 'CASH_DIVIDEND'
    OR (
      dividend_amount IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND payment_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_free_share_required CHECK (
    action_type NOT IN ('BONUS_ISSUE', 'STOCK_DIVIDEND')
    OR (
      share_quantity IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND listing_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_delisting_required CHECK (
    action_type <> 'DELISTING'
    OR (
      delisting_date IS NOT NULL
      AND delisting_treatment = 'ZERO_VALUE'
    )
  ),
  CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
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
  ),
  CONSTRAINT chk_stock_corporate_action_entitlement_close_pair CHECK (
    (entitlement_close_cycle_id IS NULL AND entitlement_close_run_id IS NULL)
    OR (entitlement_close_cycle_id IS NOT NULL AND entitlement_close_run_id IS NOT NULL)
  ),
  CONSTRAINT chk_stock_corporate_action_initial_listed CHECK (
    action_type <> 'INITIAL_ISSUE'
    OR (
      status = 'LISTED'
      AND listed_at IS NOT NULL
      AND applied_at IS NULL
      AND paid_at IS NULL
    )
  )
);

CREATE TABLE IF NOT EXISTS stock_corporate_action_entitlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  share_quantity BIGINT NULL,
  cash_amount DECIMAL(19,2) NULL,
  subscribed_share_quantity BIGINT NULL,
  subscribed_cash_amount DECIMAL(19,2) NULL,
  forfeited_share_quantity BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ANNOUNCED',
  holding_snapshot_run_id BIGINT NULL,
  created_at DATETIME NOT NULL,
  subscribed_at DATETIME NULL,
  paid_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_corporate_action_entitlement_action_account (action_id, account_id),
  KEY idx_stock_corporate_action_entitlement_account_status (account_id, status),
  KEY idx_stock_corporate_action_entitlement_account_created (account_id, created_at),
  KEY idx_stock_corporate_action_entitlement_status (status, action_id),
  KEY idx_stock_corporate_action_entitlement_action_status_id (action_id, status, id),
  KEY idx_stock_corporate_action_entitlement_snapshot_run (holding_snapshot_run_id),
  CONSTRAINT chk_stock_corporate_action_entitlement_quantity CHECK (quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_share CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_cash CHECK (cash_amount IS NULL OR cash_amount > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_share CHECK (subscribed_share_quantity IS NULL OR subscribed_share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_share_limit CHECK (
    subscribed_share_quantity IS NULL
    OR share_quantity IS NULL
    OR subscribed_share_quantity <= share_quantity
  ),
  CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_cash CHECK (subscribed_cash_amount IS NULL OR subscribed_cash_amount > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_forfeited_share CHECK (forfeited_share_quantity >= 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_finalized_share_limit CHECK (
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
  ),
  CONSTRAINT chk_stock_corporate_action_entitlement_subscription_complete CHECK (
    status NOT IN ('PARTIALLY_SUBSCRIBED', 'SUBSCRIBED')
    OR (subscribed_share_quantity IS NOT NULL AND subscribed_cash_amount IS NOT NULL AND subscribed_at IS NOT NULL)
  ),
  CONSTRAINT chk_stock_corporate_action_entitlement_value CHECK (cash_amount IS NOT NULL OR share_quantity IS NOT NULL),
  CONSTRAINT chk_stock_corporate_action_entitlement_status CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'PARTIALLY_SUBSCRIBED' THEN 1 WHEN 'SUBSCRIBED' THEN 1 WHEN 'EXPIRED' THEN 1 WHEN 'PAID' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_corporate_action_processing (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_id BIGINT NOT NULL,
  account_scope_key VARCHAR(40) NOT NULL DEFAULT 'ALL',
  action_phase VARCHAR(80) NOT NULL,
  effective_business_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 1,
  processed_count INT NOT NULL DEFAULT 0,
  amount DECIMAL(19,2) NULL,
  quantity BIGINT NULL,
  ledger_reference_id VARCHAR(100) NULL,
  processed_at DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_corporate_action_processing_unit (
    action_id, account_scope_key, action_phase, effective_business_date
  ),
  KEY idx_stock_corporate_action_processing_status_date (
    status, effective_business_date, action_phase, action_id
  ),
  CONSTRAINT chk_stock_corporate_action_processing_scope CHECK (account_scope_key <> ''),
  CONSTRAINT chk_stock_corporate_action_processing_status CHECK (
    CASE `status` WHEN 'PENDING' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_corporate_action_processing_attempt CHECK (attempt_count > 0),
  CONSTRAINT chk_stock_corporate_action_processing_count CHECK (processed_count >= 0),
  CONSTRAINT chk_stock_corporate_action_processing_amount CHECK (amount IS NULL OR amount >= 0),
  CONSTRAINT chk_stock_corporate_action_processing_quantity CHECK (quantity IS NULL OR quantity >= 0)
);

CREATE TABLE IF NOT EXISTS stock_price (
  symbol VARCHAR(20) NOT NULL,
  current_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  price_time DATETIME NOT NULL,
  provider VARCHAR(40) NOT NULL,
  PRIMARY KEY (symbol),
  CONSTRAINT chk_stock_price_current_non_negative CHECK (current_price >= 0),
  CONSTRAINT chk_stock_price_previous_close_non_negative CHECK (previous_close >= 0)
);

CREATE TABLE IF NOT EXISTS stock_price_tick (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  price_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_price_tick_symbol_time_id (symbol, price_time, id),
  CONSTRAINT chk_stock_price_tick_price_non_negative CHECK (price >= 0)
);

CREATE TABLE IF NOT EXISTS stock_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  client_order_id VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  origin_type VARCHAR(40) NULL,
  self_trade_group_id VARCHAR(80) NULL,
  symbol VARCHAR(20) NOT NULL,
  market_type VARCHAR(30) NOT NULL DEFAULT 'VIRTUAL_PRICE',
  side VARCHAR(10) NOT NULL,
  order_type VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  limit_price DECIMAL(19,2) NULL,
  quantity BIGINT NOT NULL,
  filled_quantity BIGINT NOT NULL,
  average_fill_price DECIMAL(19,2) NULL,
  reserved_cash DECIMAL(19,2) NOT NULL,
  funding_budget_type VARCHAR(20) NULL,
  expires_at DATETIME NULL,
  auto_profile_type VARCHAR(40) NULL,
  auto_behavior_model_version VARCHAR(20) NULL,
  auto_policy_version BIGINT NULL,
  auto_behavior_event_sequence BIGINT NULL,
  decision_urgency VARCHAR(30) NULL,
  cancel_reason VARCHAR(40) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_client_order_id (client_order_id),
  KEY idx_stock_order_account_created (account_id, created_at),
  KEY idx_stock_order_account_market_created (account_id, market_type, created_at),
  KEY idx_stock_order_account_status_created (account_id, status, created_at),
  KEY idx_stock_order_account_symbol_created (account_id, symbol, created_at),
  KEY idx_stock_order_market_status_symbol (market_type, status, symbol),
  KEY idx_stock_order_market_status_side (market_type, status, side),
  KEY idx_stock_order_market_status_account_time (market_type, status, account_id, created_at),
  KEY idx_stock_order_market_account_time (market_type, account_id, created_at),
  KEY idx_stock_order_market_account_symbol_time (market_type, account_id, symbol, created_at),
  KEY idx_stock_order_market_created_status (market_type, created_at, status),
  KEY idx_stock_order_side_status_account (side, status, account_id),
  KEY idx_stock_order_execution_scan (status, order_type, created_at, symbol),
  KEY idx_stock_order_order_book_match (market_type, symbol, side, status, order_type, limit_price, created_at, id),
  KEY idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id),
  CONSTRAINT chk_stock_order_market_type_valid CHECK (CASE `market_type` WHEN 'VIRTUAL_PRICE' THEN 1 WHEN 'ORDER_BOOK' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_origin_type CHECK (
    origin_type IS NULL OR CASE `origin_type`
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_order_self_trade_group CHECK (
    self_trade_group_id IS NULL OR self_trade_group_id <> ''
  ),
  CONSTRAINT chk_stock_order_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_type_valid CHECK (CASE `order_type` WHEN 'LIMIT' THEN 1 WHEN 'MARKET' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_status_valid CHECK (CASE `status` WHEN 'PENDING' THEN 1 WHEN 'PARTIALLY_FILLED' THEN 1 WHEN 'FILLED' THEN 1 WHEN 'CANCELLED' THEN 1 WHEN 'REJECTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_limit_price_positive CHECK (limit_price IS NULL OR limit_price > 0),
  CONSTRAINT chk_stock_order_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_order_filled_quantity_valid CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
  CONSTRAINT chk_stock_order_average_fill_price_positive CHECK (average_fill_price IS NULL OR average_fill_price > 0),
  CONSTRAINT chk_stock_order_reserved_cash_non_negative CHECK (reserved_cash >= 0),
  CONSTRAINT chk_stock_order_funding_budget_type CHECK (funding_budget_type IS NULL OR funding_budget_type IN ('PAYDAY', 'DIVIDEND')),
  CONSTRAINT chk_stock_order_auto_behavior_model CHECK (
    auto_behavior_model_version IS NULL OR auto_behavior_model_version = 'V3'
  ),
  CONSTRAINT chk_stock_order_auto_policy_version CHECK (
    auto_policy_version IS NULL OR auto_policy_version > 0
  ),
  CONSTRAINT chk_stock_order_auto_event_sequence CHECK (
    auto_behavior_event_sequence IS NULL OR auto_behavior_event_sequence >= 0
  ),
  CONSTRAINT chk_stock_order_decision_urgency CHECK (
    decision_urgency IS NULL OR CASE `decision_urgency`
      WHEN 'VOLUNTARY' THEN 1
      WHEN 'RISK_REDUCTION' THEN 1
      WHEN 'MANDATORY_CLOSE' THEN 1
      WHEN 'OPERATIONAL_QUOTE' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_order_cancel_reason CHECK (
    cancel_reason IS NULL OR CASE `cancel_reason`
      WHEN 'TTL_EXPIRED' THEN 1
      WHEN 'REPRICE' THEN 1
      WHEN 'ADMIN_CANCELLED' THEN 1
      WHEN 'PARTICIPANT_WITHDRAWAL' THEN 1
      WHEN 'SESSION_CLOSE' THEN 1
      WHEN 'POLICY_CHANGE' THEN 1
      WHEN 'RISK_REDUCTION' THEN 1
      WHEN 'ROLE_SUSPENDED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_order_auto_profile_type CHECK (
    auto_profile_type IS NULL OR auto_profile_type IN (
      'NEWS_REACTIVE', 'MOMENTUM_FOLLOWER', 'CONTRARIAN', 'LOSS_AVERSE', 'OVERCONFIDENT',
      'HERD_FOLLOWER', 'PASSIVE_LIMIT_TRADER', 'NOISE_TRADER', 'VALUE_ANCHOR', 'SCALPER',
      'DAY_TRADER', 'SWING_TRADER', 'LONG_TERM_HOLDER', 'PAYDAY_ACCUMULATOR',
      'DIVIDEND_REINVESTOR', 'LIMIT_DOWN_TRAPPED', 'AVERAGE_DOWN_BUYER', 'STOP_LOSS_TRADER',
      'FOMO_BUYER', 'PANIC_SELLER', 'DIP_BUYER', 'PROFIT_LOCKER', 'LIQUIDITY_AVOIDANT',
      'CASH_DEFENSIVE', 'WHALE', 'SMALL_DIVERSIFIER', 'OBSERVER'
    )
  ),
  CONSTRAINT chk_stock_order_terminal_reserved_cash_zero CHECK ((status <> 'FILLED' AND status <> 'CANCELLED' AND status <> 'REJECTED') OR reserved_cash = 0)
);

CREATE TABLE IF NOT EXISTS stock_order_strategy_origin (
  order_id BIGINT NOT NULL,
  origin_type VARCHAR(40) NOT NULL,
  participant_id BIGINT NOT NULL,
  portfolio_id BIGINT NULL,
  decision_run_id BIGINT NULL,
  liquidity_mandate_id BIGINT NULL,
  underwriting_contract_id BIGINT NULL,
  policy_version BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (order_id),
  KEY idx_stock_order_strategy_participant (
    participant_id, origin_type, order_id
  ),
  KEY idx_stock_order_strategy_decision (
    decision_run_id, order_id
  ),
  KEY idx_stock_order_strategy_liquidity (
    liquidity_mandate_id, order_id
  ),
  KEY idx_stock_order_strategy_underwriting (
    underwriting_contract_id, order_id
  ),
  CONSTRAINT chk_stock_order_strategy_origin_type CHECK (
    CASE origin_type
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_order_strategy_owner CHECK (
    (
      origin_type = 'INSTITUTIONAL_INVESTOR'
      AND portfolio_id IS NOT NULL
      AND decision_run_id IS NOT NULL
      AND liquidity_mandate_id IS NULL
      AND underwriting_contract_id IS NULL
    )
    OR (
      origin_type = 'LIQUIDITY_PROVIDER'
      AND portfolio_id IS NULL
      AND decision_run_id IS NULL
      AND liquidity_mandate_id IS NOT NULL
      AND underwriting_contract_id IS NULL
    )
    OR (
      origin_type = 'ISSUE_UNDERWRITER'
      AND portfolio_id IS NULL
      AND decision_run_id IS NULL
      AND liquidity_mandate_id IS NULL
      AND underwriting_contract_id IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_order_strategy_policy_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_execution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  quantity BIGINT NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  gross_amount DECIMAL(19,2) NOT NULL,
  fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  net_amount DECIMAL(19,2) NOT NULL,
  realized_profit DECIMAL(19,2) NULL,
  source VARCHAR(30) NOT NULL,
  executed_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_execution_account_time (account_id, executed_at),
  KEY idx_stock_execution_account_source_time (account_id, source, executed_at),
  KEY idx_stock_execution_account_symbol_time (account_id, symbol, executed_at),
  KEY idx_stock_execution_time_account (executed_at, account_id),
  KEY idx_stock_execution_source_account_time (source, account_id, executed_at),
  KEY idx_stock_execution_source_account_symbol_time (source, account_id, symbol, executed_at),
  KEY idx_stock_execution_source_time_account (source, executed_at, account_id),
  KEY idx_stock_execution_source_symbol_time (source, symbol, executed_at),
  KEY idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount),
  KEY idx_stock_execution_market_report_flow (source, symbol, executed_at, account_id, side, quantity, gross_amount, net_amount),
  KEY idx_stock_execution_source_time (source, executed_at),
  KEY idx_stock_execution_order (order_id),
  CONSTRAINT chk_stock_execution_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_source_valid CHECK (CASE `source` WHEN 'VIRTUAL_MARKET_PRICE' THEN 1 WHEN 'INTERNAL_ORDER_BOOK' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_execution_price_positive CHECK (price > 0),
  CONSTRAINT chk_stock_execution_gross_non_negative CHECK (gross_amount >= 0),
  CONSTRAINT chk_stock_execution_fee_non_negative CHECK (fee_amount >= 0),
  CONSTRAINT chk_stock_execution_tax_non_negative CHECK (tax_amount >= 0),
  CONSTRAINT chk_stock_execution_net_non_negative CHECK (net_amount >= 0)
);

CREATE TABLE IF NOT EXISTS stock_holding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_account_symbol (account_id, symbol),
  KEY idx_stock_holding_symbol_account (symbol, account_id),
  KEY idx_stock_holding_empty_cleanup (quantity, reserved_quantity, updated_at),
  CONSTRAINT chk_stock_holding_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_reserved_quantity_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_average_price_positive CHECK (average_price > 0)
);

CREATE TABLE IF NOT EXISTS stock_post_close_cycle (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_date DATE NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_key VARCHAR(40) NOT NULL,
  cycle_kind VARCHAR(20) NOT NULL DEFAULT 'TRADING',
  skip_reason VARCHAR(500) NULL,
  phase VARCHAR(60) NOT NULL DEFAULT 'OPEN',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  phase_revision INT NOT NULL DEFAULT 1,
  version BIGINT NOT NULL DEFAULT 0,
  owner_id VARCHAR(128) NULL,
  lease_until DATETIME NULL,
  next_retry_at DATETIME NULL,
  close_run_id BIGINT NULL,
  settlement_eligible_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  last_error_code VARCHAR(80) NULL,
  last_error_message VARCHAR(1000) NULL,
  build_version VARCHAR(100) NULL,
  schema_version VARCHAR(100) NULL,
  eod_contract_version VARCHAR(100) NOT NULL DEFAULT 'UNDECLARED',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_post_close_cycle_scope (business_date, scope_type, scope_key),
  KEY idx_stock_post_close_cycle_scope_date_status (scope_type, scope_key, business_date, status, id),
  KEY idx_stock_post_close_cycle_scope_status_date (scope_type, scope_key, status, business_date, id),
  KEY idx_stock_post_close_cycle_phase_status (phase, status, business_date, id),
  KEY idx_stock_post_close_cycle_lease (status, lease_until, business_date, id),
  KEY idx_stock_post_close_cycle_close_run (close_run_id),
  CONSTRAINT chk_stock_post_close_cycle_scope_type CHECK (
    CASE `scope_type` WHEN 'FULL_MARKET' THEN 1 WHEN 'SYMBOL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_scope_key CHECK (scope_key <> ''),
  CONSTRAINT chk_stock_post_close_cycle_kind CHECK (
    CASE `cycle_kind` WHEN 'TRADING' THEN 1 WHEN 'SKIPPED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_skip_reason CHECK (
    cycle_kind <> 'SKIPPED' OR skip_reason IS NOT NULL
  ),
  CONSTRAINT chk_stock_post_close_cycle_phase CHECK (
    CASE `phase`
      WHEN 'OPEN' THEN 1 WHEN 'CLOSE_REQUESTED' THEN 1 WHEN 'ORDER_ENTRY_CLOSED' THEN 1
      WHEN 'EXECUTION_DRAINED' THEN 1 WHEN 'LEDGER_FROZEN' THEN 1 WHEN 'PORTFOLIO_SETTLED' THEN 1
      WHEN 'OVERNIGHT_CASH_APPLIED' THEN 1 WHEN 'CORPORATE_CASH_APPLIED' THEN 1
      WHEN 'REPORTS_AGGREGATED' THEN 1 WHEN 'PREOPEN_SECURITY_TRANSFORMS_APPLIED' THEN 1
      WHEN 'MARKET_DATA_PREPARED' THEN 1 WHEN 'AUTO_MARKET_PREPARED' THEN 1
      WHEN 'READY_TO_OPEN' THEN 1 WHEN 'COMPLETED' THEN 1 ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_status CHECK (
    CASE `status` WHEN 'PENDING' THEN 1 WHEN 'RUNNING' THEN 1 WHEN 'DEFERRED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'COMPLETED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_revision CHECK (phase_revision > 0),
  CONSTRAINT chk_stock_post_close_cycle_version CHECK (version >= 0),
  CONSTRAINT chk_stock_post_close_cycle_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT chk_stock_post_close_cycle_eod_contract CHECK (eod_contract_version <> '')
);

CREATE TABLE IF NOT EXISTS stock_post_close_phase_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cycle_id BIGINT NOT NULL,
  phase VARCHAR(60) NOT NULL,
  attempt_no INT NOT NULL,
  batch_job_execution_id BIGINT NULL,
  owner_id VARCHAR(128) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  started_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(1000) NULL,
  build_version VARCHAR(100) NULL,
  schema_version VARCHAR(100) NULL,
  eod_contract_version VARCHAR(100) NOT NULL DEFAULT 'UNDECLARED',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_post_close_phase_attempt (cycle_id, phase, attempt_no),
  KEY idx_stock_post_close_phase_attempt_cycle_id (cycle_id, id),
  KEY idx_stock_post_close_phase_attempt_status (status, started_at, id),
  KEY idx_stock_post_close_phase_attempt_job (batch_job_execution_id),
  CONSTRAINT chk_stock_post_close_phase_attempt_no CHECK (attempt_no > 0),
  CONSTRAINT chk_stock_post_close_phase_attempt_owner CHECK (owner_id <> ''),
  CONSTRAINT chk_stock_post_close_phase_attempt_status CHECK (
    CASE `status` WHEN 'RUNNING' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'ABANDONED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_phase_attempt_eod_contract CHECK (eod_contract_version <> '')
);

CREATE TABLE IF NOT EXISTS stock_post_close_readiness_check (
  close_cycle_id BIGINT NOT NULL,
  check_code VARCHAR(60) NOT NULL,
  display_order INT NOT NULL,
  check_status VARCHAR(20) NOT NULL,
  failure_count BIGINT NOT NULL DEFAULT 0,
  message VARCHAR(500) NULL,
  checked_at DATETIME NOT NULL,
  PRIMARY KEY (close_cycle_id, check_code),
  UNIQUE KEY uk_stock_post_close_readiness_order (close_cycle_id, display_order),
  CONSTRAINT chk_stock_post_close_readiness_order CHECK (display_order > 0),
  CONSTRAINT chk_stock_post_close_readiness_status CHECK (
    CASE `check_status` WHEN 'PASSED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_readiness_failure_count CHECK (failure_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_post_close_cycle_metric (
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NULL,
  captured_open_order_count BIGINT NOT NULL DEFAULT 0,
  cancelled_order_count BIGINT NOT NULL DEFAULT 0,
  released_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  released_sell_quantity BIGINT NOT NULL DEFAULT 0,
  settlement_target_account_count BIGINT NOT NULL DEFAULT 0,
  account_snapshot_count BIGINT NOT NULL DEFAULT 0,
  holding_snapshot_count BIGINT NOT NULL DEFAULT 0,
  price_snapshot_count BIGINT NOT NULL DEFAULT 0,
  open_order_summary_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_mismatch_count BIGINT NOT NULL DEFAULT 0,
  settled_account_count BIGINT NOT NULL DEFAULT 0,
  settlement_missing_account_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (close_cycle_id),
  KEY idx_stock_post_close_cycle_metric_run (close_run_id),
  CONSTRAINT chk_stock_post_close_cycle_metric_counts CHECK (
    captured_open_order_count >= 0
    AND cancelled_order_count >= 0
    AND settlement_target_account_count >= 0
    AND account_snapshot_count >= 0
    AND holding_snapshot_count >= 0
    AND price_snapshot_count >= 0
    AND open_order_summary_count >= 0
    AND reconciliation_mismatch_count >= 0
    AND settled_account_count >= 0
    AND settlement_missing_account_count >= 0
  ),
  CONSTRAINT chk_stock_post_close_cycle_metric_releases CHECK (
    released_buy_cash >= 0
    AND released_sell_quantity >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_market_close_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NULL,
  business_date DATE NOT NULL,
  closed_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  cancelled_order_count INT NOT NULL DEFAULT 0,
  holding_snapshot_count INT NOT NULL DEFAULT 0,
  price_rollover_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  PRIMARY KEY (id),
  KEY idx_stock_market_close_run_symbol_time (symbol, closed_at, id),
  KEY idx_stock_market_close_run_date_time (business_date, closed_at, id),
  CONSTRAINT chk_stock_market_close_run_status CHECK (CASE `status` WHEN 'STARTED' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_market_close_run_cancelled_non_negative CHECK (cancelled_order_count >= 0),
  CONSTRAINT chk_stock_market_close_run_snapshot_non_negative CHECK (holding_snapshot_count >= 0),
  CONSTRAINT chk_stock_market_close_run_price_non_negative CHECK (price_rollover_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_holding_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NULL,
  close_run_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  evaluation_price DECIMAL(19,2) NULL,
  snapshot_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_snapshot_run_account_symbol (close_run_id, account_id, symbol),
  KEY idx_stock_holding_snapshot_cycle_account (close_cycle_id, account_id, symbol),
  KEY idx_stock_holding_snapshot_symbol_run (symbol, close_run_id, account_id),
  KEY idx_stock_holding_snapshot_account_time (account_id, snapshot_at, id),
  CONSTRAINT chk_stock_holding_snapshot_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_snapshot_reserved_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_snapshot_average_price_positive CHECK (average_price > 0),
  CONSTRAINT chk_stock_holding_snapshot_evaluation_price CHECK (evaluation_price IS NULL OR evaluation_price >= 0)
);

CREATE TABLE IF NOT EXISTS stock_close_account_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NULL,
  account_status VARCHAR(20) NOT NULL,
  participant_category VARCHAR(30) NOT NULL DEFAULT 'MANUAL_PARTICIPANT',
  participant_profile_type VARCHAR(40) NULL,
  settlement_target BOOLEAN NOT NULL,
  pre_cancel_cash DECIMAL(19,2) NOT NULL,
  pre_cancel_order_reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  subscription_reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  post_cancel_cash DECIMAL(19,2) NULL,
  external_net_cash_flow DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  cash_flow_watermark_id BIGINT NOT NULL DEFAULT 0,
  holding_market_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  holding_quantity BIGINT NOT NULL DEFAULT 0,
  reserved_sell_quantity BIGINT NOT NULL DEFAULT 0,
  holding_position_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_account_snapshot_cycle_account (close_cycle_id, account_id),
  KEY idx_stock_close_account_snapshot_cycle_target (close_cycle_id, settlement_target, account_id),
  KEY idx_stock_close_account_snapshot_cycle_reconciliation (close_cycle_id, reconciliation_status, account_id),
  KEY idx_stock_close_account_snapshot_run_target (close_run_id, settlement_target, account_id),
  KEY idx_stock_close_account_snapshot_account_cycle (account_id, close_cycle_id),
  CONSTRAINT chk_stock_close_account_snapshot_cash CHECK (
    pre_cancel_cash >= 0
    AND pre_cancel_order_reserved_cash >= 0
    AND subscription_reserved_cash >= 0
    AND (post_cancel_cash IS NULL OR post_cancel_cash >= 0)
  ),
  CONSTRAINT chk_stock_close_account_snapshot_watermark CHECK (cash_flow_watermark_id >= 0),
  CONSTRAINT chk_stock_close_account_snapshot_holding_summary CHECK (
    holding_market_value >= 0
    AND holding_quantity >= 0
    AND reserved_sell_quantity >= 0
    AND reserved_sell_quantity <= holding_quantity
    AND holding_position_count >= 0
  ),
  CONSTRAINT chk_stock_close_account_snapshot_reconciliation CHECK (
    CASE `reconciliation_status` WHEN 'PENDING' THEN 1 WHEN 'MATCHED' THEN 1 WHEN 'MISMATCHED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_close_account_snapshot_participant_category CHECK (
    CASE `participant_category`
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_close_account_snapshot_profile_type CHECK (
    participant_profile_type IS NULL OR participant_profile_type IN (
      'NEWS_REACTIVE', 'MOMENTUM_FOLLOWER', 'CONTRARIAN', 'LOSS_AVERSE', 'OVERCONFIDENT',
      'HERD_FOLLOWER', 'PASSIVE_LIMIT_TRADER', 'NOISE_TRADER', 'VALUE_ANCHOR', 'SCALPER',
      'DAY_TRADER', 'SWING_TRADER', 'LONG_TERM_HOLDER', 'PAYDAY_ACCUMULATOR',
      'DIVIDEND_REINVESTOR', 'LIMIT_DOWN_TRAPPED', 'AVERAGE_DOWN_BUYER', 'STOP_LOSS_TRADER',
      'FOMO_BUYER', 'PANIC_SELLER', 'DIP_BUYER', 'PROFIT_LOCKER', 'LIQUIDITY_AVOIDANT',
      'CASH_DEFENSIVE', 'WHALE', 'SMALL_DIVERSIFIER', 'OBSERVER'
    )
  )
);

CREATE TABLE IF NOT EXISTS stock_close_price_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  close_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  price_time DATETIME NULL,
  price_provider VARCHAR(40) NULL,
  last_execution_id BIGINT NULL,
  order_book_symbol BOOLEAN NOT NULL,
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_price_snapshot_cycle_symbol (close_cycle_id, symbol),
  KEY idx_stock_close_price_snapshot_run_symbol (close_run_id, symbol),
  CONSTRAINT chk_stock_close_price_snapshot_price CHECK (close_price >= 0 AND previous_close >= 0),
  CONSTRAINT chk_stock_close_price_snapshot_execution CHECK (last_execution_id IS NULL OR last_execution_id > 0)
);

CREATE TABLE IF NOT EXISTS stock_close_open_order_summary (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  pre_cancel_open_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_buy_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_sell_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_remaining_buy_quantity BIGINT NOT NULL DEFAULT 0,
  pre_cancel_remaining_sell_quantity BIGINT NOT NULL DEFAULT 0,
  pre_cancel_reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  pre_cancel_reserved_sell_quantity BIGINT NOT NULL DEFAULT 0,
  post_cancel_open_order_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_open_order_summary_cycle_symbol (close_cycle_id, symbol),
  KEY idx_stock_close_open_order_summary_run (close_run_id, symbol),
  CONSTRAINT chk_stock_close_open_order_summary_counts CHECK (
    pre_cancel_open_order_count >= 0
    AND pre_cancel_buy_order_count >= 0
    AND pre_cancel_sell_order_count >= 0
    AND pre_cancel_remaining_buy_quantity >= 0
    AND pre_cancel_remaining_sell_quantity >= 0
    AND pre_cancel_reserved_buy_cash >= 0
    AND pre_cancel_reserved_sell_quantity >= 0
    AND post_cancel_open_order_count >= 0
  ),
  CONSTRAINT chk_stock_close_open_order_summary_reconciliation CHECK (
    CASE `reconciliation_status` WHEN 'PENDING' THEN 1 WHEN 'MATCHED' THEN 1 WHEN 'MISMATCHED' THEN 1 ELSE 0 END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_close_open_order_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  source_order_status VARCHAR(20) NOT NULL,
  remaining_quantity BIGINT NOT NULL,
  reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  captured_at DATETIME NOT NULL,
  released_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_open_order_snapshot_cycle_order (close_cycle_id, order_id),
  KEY idx_stock_close_open_order_snapshot_run_order (close_run_id, order_id),
  KEY idx_stock_close_open_order_snapshot_cycle_release_order (close_cycle_id, released_at, order_id),
  KEY idx_stock_close_open_order_snapshot_cycle_account (close_cycle_id, account_id, side),
  KEY idx_stock_close_open_order_snapshot_cycle_holding (close_cycle_id, symbol, account_id, side),
  KEY idx_stock_close_open_order_snapshot_cycle_stream (close_cycle_id, symbol, source_order_status, order_id),
  CONSTRAINT chk_stock_close_open_order_snapshot_side CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_close_open_order_snapshot_status CHECK (
    CASE `source_order_status` WHEN 'PENDING' THEN 1 WHEN 'PARTIALLY_FILLED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_close_open_order_snapshot_quantity CHECK (remaining_quantity > 0),
  CONSTRAINT chk_stock_close_open_order_snapshot_cash CHECK (reserved_cash >= 0)
);

CREATE TABLE IF NOT EXISTS stock_order_book_daily_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  snapshot_at DATETIME NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL,
  issued_shares BIGINT NOT NULL,
  tradable_shares BIGINT NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  tick_size DECIMAL(19,2) NOT NULL,
  price_limit_rate DECIMAL(5,2) NOT NULL,
  close_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  change_rate DECIMAL(9,4) NOT NULL DEFAULT 0.0000,
  price_time DATETIME NULL,
  price_provider VARCHAR(40) NULL,
  execution_count BIGINT NOT NULL DEFAULT 0,
  execution_quantity BIGINT NOT NULL DEFAULT 0,
  turnover_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  open_price DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  high_price DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  low_price DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  last_execution_price DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  buy_quantity BIGINT NOT NULL DEFAULT 0,
  sell_quantity BIGINT NOT NULL DEFAULT 0,
  buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  open_order_count BIGINT NOT NULL DEFAULT 0,
  open_buy_order_count BIGINT NOT NULL DEFAULT 0,
  open_sell_order_count BIGINT NOT NULL DEFAULT 0,
  reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  holder_count BIGINT NOT NULL DEFAULT 0,
  holding_quantity BIGINT NOT NULL DEFAULT 0,
  pending_corporate_action_count BIGINT NOT NULL DEFAULT 0,
  first_executed_at DATETIME NULL,
  last_executed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_book_daily_snapshot_run_symbol (close_run_id, symbol),
  KEY idx_stock_order_book_daily_snapshot_date_symbol (simulation_trade_date, symbol, close_run_id),
  KEY idx_stock_order_book_daily_snapshot_symbol_date (symbol, simulation_trade_date, close_run_id),
  CONSTRAINT chk_stock_order_book_daily_snapshot_issued CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tradable CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_daily_snapshot_price CHECK (close_price >= 0 AND previous_close >= 0 AND initial_price > 0 AND open_price >= 0 AND high_price >= 0 AND low_price >= 0 AND last_execution_price >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_flow CHECK (execution_count >= 0 AND execution_quantity >= 0 AND turnover_amount >= 0 AND buy_quantity >= 0 AND sell_quantity >= 0 AND buy_net_amount >= 0 AND sell_net_amount >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_open_order CHECK (open_order_count >= 0 AND open_buy_order_count >= 0 AND open_sell_order_count >= 0 AND reserved_buy_cash >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_holding CHECK (holder_count >= 0 AND holding_quantity >= 0 AND pending_corporate_action_count >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tick CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_limit CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

CREATE TABLE IF NOT EXISTS stock_execution_daily_account_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  participant_category VARCHAR(30) NOT NULL,
  execution_count BIGINT NOT NULL DEFAULT 0,
  buy_quantity BIGINT NOT NULL DEFAULT 0,
  sell_quantity BIGINT NOT NULL DEFAULT 0,
  buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  net_cash_flow DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  execution_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  last_executed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_execution_daily_account_run_symbol_account (close_run_id, symbol, account_id),
  KEY idx_stock_execution_daily_account_symbol_date (symbol, simulation_trade_date, close_run_id, account_id),
  KEY idx_stock_execution_daily_account_account_date (account_id, simulation_trade_date, close_run_id),
  CONSTRAINT chk_stock_execution_daily_account_category CHECK (
    CASE `participant_category`
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_execution_daily_account_quantity CHECK (execution_count >= 0 AND buy_quantity >= 0 AND sell_quantity >= 0),
  CONSTRAINT chk_stock_execution_daily_account_amount CHECK (buy_amount >= 0 AND sell_amount >= 0 AND execution_amount >= 0)
);

CREATE TABLE IF NOT EXISTS stock_execution_account_day_summary (
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  execution_count BIGINT NOT NULL DEFAULT 0,
  buy_quantity BIGINT NOT NULL DEFAULT 0,
  sell_quantity BIGINT NOT NULL DEFAULT 0,
  gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  buy_gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  realized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  last_executed_at DATETIME NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, account_id),
  KEY idx_stock_execution_account_day_account_date (account_id, simulation_trade_date),
  CONSTRAINT chk_stock_execution_account_day_quantity CHECK (execution_count >= 0 AND buy_quantity >= 0 AND sell_quantity >= 0),
  CONSTRAINT chk_stock_execution_account_day_amount CHECK (
    gross_amount >= 0
    AND buy_gross_amount >= 0
    AND sell_gross_amount >= 0
    AND buy_net_amount >= 0
    AND sell_net_amount >= 0
    AND fee_amount >= 0
    AND tax_amount >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant (
  user_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  enabled BIT NOT NULL,
  profile_type VARCHAR(40) NOT NULL DEFAULT 'NOISE_TRADER',
  behavior_seed BIGINT NULL,
  recurring_cash_amount DECIMAL(19,2) NULL,
  recurring_cash_interval_value DECIMAL(12,4) NULL,
  recurring_cash_interval_unit VARCHAR(20) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  withdrawn_at DATETIME NULL,
  PRIMARY KEY (user_key),
  KEY idx_stock_auto_participant_active (withdrawn_at, enabled, user_key),
  KEY idx_stock_auto_participant_profile_active (withdrawn_at, profile_type, enabled, user_key),
  CONSTRAINT chk_stock_auto_participant_profile_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'PASSIVE_LIMIT_TRADER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_amount CHECK (recurring_cash_amount IS NULL OR recurring_cash_amount >= 0),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_interval CHECK (recurring_cash_interval_value IS NULL OR (recurring_cash_interval_value >= 0 AND recurring_cash_interval_value <= 1000)),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_unit CHECK (
    recurring_cash_interval_unit IS NULL OR
    CASE `recurring_cash_interval_unit`
      WHEN 'SECOND' THEN 1
      WHEN 'MINUTE' THEN 1
      WHEN 'HOUR' THEN 1
      WHEN 'DAY' THEN 1
      WHEN 'MONTH' THEN 1
      WHEN 'YEAR' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_complete CHECK (
    recurring_cash_amount IS NULL
    OR recurring_cash_amount = 0
    OR (recurring_cash_interval_value IS NOT NULL AND recurring_cash_interval_value > 0 AND recurring_cash_interval_unit IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_withdrawal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_user_key VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  returned_cash_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  returned_share_quantity BIGINT NOT NULL DEFAULT 0,
  returned_symbol_count INT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_auto_participant_withdrawal_user (participant_user_key),
  KEY idx_stock_auto_participant_withdrawal_account (account_id, id),
  KEY idx_stock_auto_participant_withdrawal_created (created_at, id),
  CONSTRAINT chk_stock_auto_participant_withdrawal_cash CHECK (returned_cash_amount >= 0),
  CONSTRAINT chk_stock_auto_participant_withdrawal_shares CHECK (returned_share_quantity >= 0),
  CONSTRAINT chk_stock_auto_participant_withdrawal_symbols CHECK (returned_symbol_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_share_return (
  withdrawal_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  receiver_account_id BIGINT NOT NULL,
  receiver_role VARCHAR(40) NOT NULL,
  transfer_reason VARCHAR(50) NOT NULL,
  quantity BIGINT NOT NULL,
  source_average_price DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (withdrawal_id, symbol),
  KEY idx_stock_auto_share_return_receiver (receiver_account_id, symbol, withdrawal_id),
  CONSTRAINT chk_stock_auto_share_return_quantity CHECK (quantity > 0),
  CONSTRAINT chk_stock_auto_share_return_average_price CHECK (source_average_price >= 0),
  CONSTRAINT chk_stock_auto_share_return_receiver_role CHECK (
    CASE `receiver_role`
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_share_return_reason CHECK (
    CASE `transfer_reason`
      WHEN 'ISSUE_UNDERWRITER_RETURN' THEN 1
      WHEN 'AUTO_PARTICIPANT_WITHDRAWAL_CUSTODY' THEN 1
      ELSE 0
    END = 1
  )
);

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'SYSTEM_CUSTODY', '시스템 보관기관', 'SYSTEM_CUSTODY', 'ACTIVE',
    'SYSTEM_CUSTODY:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'SYSTEM_CUSTODY'
);

INSERT INTO stock_account(
    user_key, account_code, status, participant_category,
    self_trade_group_id, cash_balance, created_at, updated_at
)
SELECT
    'stock-system-custody', 'SYSTEM-CUSTODY', 'ACTIVE', 'SYSTEM_CUSTODY',
    'SYSTEM_CUSTODY:DEFAULT', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_account
     WHERE user_key = 'stock-system-custody'
);

DELETE FROM stock_market_participant_account
 WHERE account_role = 'SYSTEM_CUSTODY'
   AND desk_code = 'DEFAULT'
   AND participant_id IN (
       SELECT id
         FROM stock_market_participant
        WHERE participant_code = 'SYSTEM_CUSTODY'
   )
   AND NOT EXISTS (
       SELECT 1
         FROM stock_account
        WHERE stock_account.id = stock_market_participant_account.account_id
   );

INSERT INTO stock_market_participant_account(
    participant_id, account_id, account_role, desk_code,
    effective_from, effective_to, status, created_at, updated_at
)
SELECT
    participant.id, account.id, 'SYSTEM_CUSTODY', 'DEFAULT',
    DATE '1970-01-01', NULL, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM stock_market_participant participant
  JOIN stock_account account
    ON account.user_key = 'stock-system-custody'
 WHERE participant.participant_code = 'SYSTEM_CUSTODY'
   AND NOT EXISTS (
       SELECT 1
         FROM stock_market_participant_account existing
        WHERE existing.account_id = account.id
   );

CREATE TABLE IF NOT EXISTS stock_auto_participant_position_state (
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  position_opened_business_date DATE NOT NULL,
  holding_trading_days INT NOT NULL,
  average_down_rounds INT NOT NULL DEFAULT 0,
  last_average_down_business_date DATE NULL,
  peak_close_price DECIMAL(19,2) NOT NULL,
  last_seen_business_date DATE NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id, symbol),
  KEY idx_stock_auto_position_symbol_account (symbol, account_id),
  KEY idx_stock_auto_position_last_seen (last_seen_business_date, account_id, symbol),
  CONSTRAINT chk_stock_auto_position_holding_days CHECK (holding_trading_days > 0),
  CONSTRAINT chk_stock_auto_position_average_down_rounds CHECK (average_down_rounds >= 0),
  CONSTRAINT chk_stock_auto_position_peak_price CHECK (peak_close_price >= 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_performance_state (
  account_id BIGINT NOT NULL,
  recent_profitable_trading_days INT NOT NULL DEFAULT 0,
  recent_closed_trading_days INT NOT NULL DEFAULT 0,
  last_seen_business_date DATE NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id),
  KEY idx_stock_auto_performance_last_seen (last_seen_business_date, account_id),
  CONSTRAINT chk_stock_auto_performance_recent_days CHECK (
    recent_profitable_trading_days >= 0
    AND recent_closed_trading_days >= 0
    AND recent_closed_trading_days <= 20
    AND recent_profitable_trading_days <= recent_closed_trading_days
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_funding_budget (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  budget_type VARCHAR(20) NOT NULL,
  source_key VARCHAR(160) NOT NULL,
  source_symbol VARCHAR(20) NULL,
  corporate_action_id BIGINT NULL,
  corporate_action_entitlement_id BIGINT NULL,
  granted_amount DECIMAL(19,2) NOT NULL,
  available_amount DECIMAL(19,2) NOT NULL,
  reserved_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  spent_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  expires_business_date DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_auto_funding_budget_source (account_id, budget_type, source_key),
  KEY idx_stock_auto_funding_budget_eligible (account_id, budget_type, status, expires_business_date, id),
  KEY idx_stock_auto_funding_budget_symbol (account_id, budget_type, source_symbol, status, id),
  KEY idx_stock_auto_funding_budget_action (corporate_action_id, corporate_action_entitlement_id),
  CONSTRAINT chk_stock_auto_funding_budget_type CHECK (budget_type IN ('PAYDAY', 'DIVIDEND')),
  CONSTRAINT chk_stock_auto_funding_budget_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED')),
  CONSTRAINT chk_stock_auto_funding_budget_amounts CHECK (
    granted_amount > 0
    AND available_amount >= 0
    AND reserved_amount >= 0
    AND spent_amount >= 0
    AND granted_amount = available_amount + reserved_amount + spent_amount
  ),
  CONSTRAINT chk_stock_auto_funding_budget_source CHECK (
    (budget_type = 'PAYDAY' AND corporate_action_id IS NULL AND corporate_action_entitlement_id IS NULL)
    OR (budget_type = 'DIVIDEND' AND source_symbol IS NOT NULL AND corporate_action_id IS NOT NULL AND corporate_action_entitlement_id IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_budget (
  order_id BIGINT NOT NULL,
  budget_id BIGINT NOT NULL,
  allocated_amount DECIMAL(19,2) NOT NULL,
  remaining_reserved_amount DECIMAL(19,2) NOT NULL,
  spent_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  released_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (order_id, budget_id),
  KEY idx_stock_auto_order_budget_budget (budget_id, order_id),
  CONSTRAINT chk_stock_auto_order_budget_amounts CHECK (
    allocated_amount > 0
    AND remaining_reserved_amount >= 0
    AND spent_amount >= 0
    AND released_amount >= 0
    AND allocated_amount = remaining_reserved_amount + spent_amount + released_amount
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  behavior_model_version VARCHAR(20) NOT NULL DEFAULT 'V3',
  news_weight DECIMAL(8,4) DEFAULT NULL,
  momentum_weight DECIMAL(8,4) DEFAULT NULL,
  contrarian_weight DECIMAL(8,4) DEFAULT NULL,
  loss_aversion_weight DECIMAL(8,4) DEFAULT NULL,
  herding_weight DECIMAL(8,4) DEFAULT NULL,
  market_making_weight DECIMAL(8,4) DEFAULT NULL,
  overconfidence_weight DECIMAL(8,4) DEFAULT NULL,
  noise_weight DECIMAL(8,4) DEFAULT NULL,
  panic_sell_weight DECIMAL(8,4) DEFAULT NULL,
  dip_buy_weight DECIMAL(8,4) DEFAULT NULL,
  order_multiplier DECIMAL(8,4) NOT NULL,
  decision_frequency_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
  orders_per_decision_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
  aggression_multiplier DECIMAL(8,4) NOT NULL,
  price_pressure_sensitivity DECIMAL(8,4) NOT NULL,
  order_ttl_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
  quantity_multiplier DECIMAL(8,4) NOT NULL,
  holding_patience_weight DECIMAL(8,4) NOT NULL,
  deep_loss_hold_weight DECIMAL(8,4) NOT NULL,
  profit_taking_weight DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
  pricing_mode VARCHAR(30) NOT NULL DEFAULT 'DIRECTIONAL',
  exit_mode VARCHAR(30) NOT NULL DEFAULT 'SIGNAL_DRIVEN',
  inventory_mode VARCHAR(30) NOT NULL DEFAULT 'SIGNAL_DRIVEN',
  recurring_deposit_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  recurring_deposit_interval_days INT NOT NULL DEFAULT 30,
  recurring_deposit_interval_value DECIMAL(12,4) NOT NULL DEFAULT 30.0000,
  recurring_deposit_interval_unit VARCHAR(20) NOT NULL DEFAULT 'DAY',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_profile_config_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'PASSIVE_LIMIT_TRADER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_behavior_model CHECK (
    behavior_model_version = 'V3'
  ),
  CONSTRAINT chk_stock_auto_profile_news_weight CHECK (news_weight IS NULL OR (news_weight >= 0 AND news_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_momentum_weight CHECK (momentum_weight IS NULL OR (momentum_weight >= 0 AND momentum_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_contrarian_weight CHECK (contrarian_weight IS NULL OR (contrarian_weight >= 0 AND contrarian_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_loss_aversion_weight CHECK (loss_aversion_weight IS NULL OR (loss_aversion_weight >= 0 AND loss_aversion_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_herding_weight CHECK (herding_weight IS NULL OR (herding_weight >= 0 AND herding_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_market_making_weight CHECK (market_making_weight IS NULL OR (market_making_weight >= 0 AND market_making_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_overconfidence_weight CHECK (overconfidence_weight IS NULL OR (overconfidence_weight >= 0 AND overconfidence_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_noise_weight CHECK (noise_weight IS NULL OR (noise_weight >= 0 AND noise_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_panic_sell_weight CHECK (panic_sell_weight IS NULL OR (panic_sell_weight >= 0 AND panic_sell_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_dip_buy_weight CHECK (dip_buy_weight IS NULL OR (dip_buy_weight >= 0 AND dip_buy_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_order_multiplier CHECK (order_multiplier >= 0 AND order_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_decision_frequency CHECK (decision_frequency_multiplier IS NULL OR (decision_frequency_multiplier >= 0 AND decision_frequency_multiplier <= 20)),
  CONSTRAINT chk_stock_auto_profile_orders_per_decision CHECK (orders_per_decision_multiplier IS NULL OR (orders_per_decision_multiplier >= 0 AND orders_per_decision_multiplier <= 5)),
  CONSTRAINT chk_stock_auto_profile_aggression_multiplier CHECK (aggression_multiplier >= 0 AND aggression_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_price_pressure_sensitivity CHECK (price_pressure_sensitivity >= 0 AND price_pressure_sensitivity <= 2),
  CONSTRAINT chk_stock_auto_profile_order_ttl_multiplier CHECK (order_ttl_multiplier >= 0.1 AND order_ttl_multiplier <= 10),
  CONSTRAINT chk_stock_auto_profile_quantity_multiplier CHECK (quantity_multiplier >= 0 AND quantity_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_holding_patience CHECK (holding_patience_weight >= 0 AND holding_patience_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_deep_loss_hold CHECK (deep_loss_hold_weight >= 0 AND deep_loss_hold_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_profit_taking CHECK (profit_taking_weight >= 0 AND profit_taking_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_pricing_mode CHECK (pricing_mode IS NULL OR pricing_mode = 'DIRECTIONAL'),
  CONSTRAINT chk_stock_auto_profile_exit_mode CHECK (
    exit_mode IS NULL OR CASE `exit_mode` WHEN 'SIGNAL_DRIVEN' THEN 1 WHEN 'TAKE_PROFIT_FIRST' THEN 1 WHEN 'HOLD_LOSSES' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_inventory_mode CHECK (
    inventory_mode IS NULL OR inventory_mode = 'SIGNAL_DRIVEN'
  ),
  CONSTRAINT chk_stock_auto_profile_recurring_deposit CHECK (recurring_deposit_amount >= 0),
  CONSTRAINT chk_stock_auto_profile_recurring_interval CHECK (recurring_deposit_interval_days >= 1),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_value CHECK (recurring_deposit_interval_value >= 0 AND recurring_deposit_interval_value <= 1000),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_unit CHECK (
    CASE `recurring_deposit_interval_unit`
      WHEN 'SECOND' THEN 1
      WHEN 'MINUTE' THEN 1
      WHEN 'HOUR' THEN 1
      WHEN 'DAY' THEN 1
      WHEN 'MONTH' THEN 1
      WHEN 'YEAR' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_complete CHECK (
    recurring_deposit_amount = 0
    OR (recurring_deposit_interval_value > 0 AND recurring_deposit_interval_unit IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_event_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  shareholder_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.4500,
  public_offering_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  max_cash_allocation_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_event_profile_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'PASSIVE_LIMIT_TRADER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_event_profile_shareholder_rate CHECK (shareholder_subscription_rate >= 0 AND shareholder_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_public_rate CHECK (public_offering_subscription_rate >= 0 AND public_offering_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_cash_rate CHECK (max_cash_allocation_rate >= 0 AND max_cash_allocation_rate <= 1)
);

CREATE TABLE IF NOT EXISTS stock_virtual_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_virtual_market_enabled (enabled, market_status, symbol),
  CONSTRAINT chk_stock_virtual_market_status CHECK (CASE `market_status` WHEN 'OPEN' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'HALTED' THEN 1 WHEN 'CIRCUIT_BREAKER' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_order_book_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_market_enabled (enabled, market_status, symbol),
  CONSTRAINT chk_stock_order_book_market_status CHECK (CASE `market_status` WHEN 'OPEN' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'HALTED' THEN 1 WHEN 'CIRCUIT_BREAKER' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_market_business_state (
  state_id VARCHAR(20) NOT NULL,
  active_business_date DATE NOT NULL,
  preparing_business_date DATE NULL,
  raw_simulation_date DATE NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (state_id),
  CONSTRAINT chk_stock_market_business_state_version CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS stock_market_session_fence (
  market_type VARCHAR(20) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  business_date DATE NOT NULL,
  session_epoch BIGINT NOT NULL,
  session_state VARCHAR(20) NOT NULL,
  state_changed_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (market_type, symbol),
  KEY idx_stock_market_session_fence_state (business_date, session_state, market_type, symbol),
  CONSTRAINT chk_stock_market_session_fence_market_type CHECK (
    CASE `market_type` WHEN 'VIRTUAL_PRICE' THEN 1 WHEN 'ORDER_BOOK' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_session_fence_state CHECK (
    CASE `session_state` WHEN 'OPEN' THEN 1 WHEN 'CLOSING' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'PREPARING' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_session_fence_epoch CHECK (session_epoch > 0),
  CONSTRAINT chk_stock_market_session_fence_version CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  primary_regime_count_1_weight INT NOT NULL DEFAULT 0,
  primary_regime_count_2_weight INT NOT NULL DEFAULT 0,
  primary_regime_count_3_weight INT NOT NULL DEFAULT 0,
  primary_regime_count_4_weight INT NOT NULL DEFAULT 100,
  primary_price_pressure_bias INT NOT NULL DEFAULT 0,
  primary_asset_preference_pressure_bias INT NOT NULL DEFAULT 0,
  primary_volatility_pressure_bias INT NOT NULL DEFAULT 0,
  primary_liquidity_pressure_bias INT NOT NULL DEFAULT 0,
  primary_execution_aggression_pressure_bias INT NOT NULL DEFAULT 0,
  secondary_price_pressure_bias INT NOT NULL DEFAULT 0,
  secondary_asset_preference_pressure_bias INT NOT NULL DEFAULT 0,
  secondary_volatility_pressure_bias INT NOT NULL DEFAULT 0,
  secondary_liquidity_pressure_bias INT NOT NULL DEFAULT 0,
  secondary_execution_aggression_pressure_bias INT NOT NULL DEFAULT 0,
  max_order_quantity INT NOT NULL,
  order_ttl_seconds INT NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_auto_market_enabled (enabled, symbol),
  CONSTRAINT chk_stock_auto_market_regime_count_weights CHECK (
    primary_regime_count_1_weight between 0 and 100
    and primary_regime_count_2_weight between 0 and 100
    and primary_regime_count_3_weight between 0 and 100
    and primary_regime_count_4_weight between 0 and 100
    and primary_regime_count_1_weight + primary_regime_count_2_weight
      + primary_regime_count_3_weight + primary_regime_count_4_weight > 0
  ),
  CONSTRAINT chk_stock_auto_market_primary_price_bias CHECK (primary_price_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_primary_asset_bias CHECK (primary_asset_preference_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_primary_volatility_bias CHECK (primary_volatility_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_primary_liquidity_bias CHECK (primary_liquidity_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_primary_aggression_bias CHECK (primary_execution_aggression_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_secondary_price_bias CHECK (secondary_price_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_secondary_asset_bias CHECK (secondary_asset_preference_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_secondary_volatility_bias CHECK (secondary_volatility_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_secondary_liquidity_bias CHECK (secondary_liquidity_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_secondary_aggression_bias CHECK (secondary_execution_aggression_pressure_bias between -100 and 100),
  CONSTRAINT chk_stock_auto_market_max_order_quantity CHECK (max_order_quantity > 0),
  CONSTRAINT chk_stock_auto_market_order_ttl_seconds CHECK (order_ttl_seconds > 0)
);

CREATE TABLE IF NOT EXISTS stock_institution_portfolio (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  portfolio_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  investment_style VARCHAR(40) NOT NULL,
  execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  base_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  min_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  max_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  primary_regime_weight DECIMAL(8,6) NOT NULL DEFAULT 0.700000,
  asset_preference_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  volatility_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  entry_threshold_rate DECIMAL(8,6) NOT NULL DEFAULT 0.005000,
  exit_threshold_rate DECIMAL(8,6) NOT NULL DEFAULT 0.002000,
  daily_turnover_limit_rate DECIMAL(8,6) NOT NULL DEFAULT 0.050000,
  max_decision_turnover_rate DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  decision_interval_minutes INT NOT NULL DEFAULT 60,
  next_decision_at DATETIME NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_portfolio_code (portfolio_code),
  UNIQUE KEY uk_stock_institution_portfolio_account (account_id),
  KEY idx_stock_institution_portfolio_due (status, execution_mode, next_decision_at, id),
  KEY idx_stock_institution_portfolio_participant (participant_id, status, id),
  CONSTRAINT chk_stock_institution_portfolio_style CHECK (
    CASE `investment_style`
      WHEN 'BALANCED_LONG_TERM' THEN 1
      WHEN 'VALUE_CONTRARIAN' THEN 1
      WHEN 'MOMENTUM' THEN 1
      WHEN 'ACTIVE_SHORT_TERM' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_mode CHECK (`execution_mode` = 'LIVE'),
  CONSTRAINT chk_stock_institution_portfolio_status CHECK (
    CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'RETIRED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_allocation CHECK (
    min_stock_allocation_rate >= 0
    AND min_stock_allocation_rate <= base_stock_allocation_rate
    AND base_stock_allocation_rate <= max_stock_allocation_rate
    AND max_stock_allocation_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_regime_weight CHECK (
    primary_regime_weight >= 0 AND primary_regime_weight <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_sensitivity CHECK (
    asset_preference_sensitivity >= 0
    AND asset_preference_sensitivity <= 1
    AND volatility_sensitivity >= 0
    AND volatility_sensitivity <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_threshold CHECK (
    exit_threshold_rate >= 0
    AND exit_threshold_rate <= entry_threshold_rate
    AND entry_threshold_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_turnover CHECK (
    daily_turnover_limit_rate > 0
    AND daily_turnover_limit_rate <= 1
    AND max_decision_turnover_rate > 0
    AND max_decision_turnover_rate <= daily_turnover_limit_rate
  ),
  CONSTRAINT chk_stock_institution_portfolio_interval CHECK (
    decision_interval_minutes BETWEEN 5 AND 1440
  ),
  CONSTRAINT chk_stock_institution_portfolio_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_institution_symbol_mandate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  portfolio_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  base_symbol_weight DECIMAL(8,6) NOT NULL,
  min_portfolio_allocation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  max_portfolio_allocation_rate DECIMAL(8,6) NOT NULL,
  price_pressure_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  momentum_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  value_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  report_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  reference_daily_volume BIGINT NOT NULL,
  daily_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.050000,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_symbol_mandate (portfolio_id, symbol),
  KEY idx_stock_institution_symbol_mandate_symbol (symbol, enabled, portfolio_id),
  CONSTRAINT chk_stock_institution_mandate_base_weight CHECK (
    base_symbol_weight > 0 AND base_symbol_weight <= 1
  ),
  CONSTRAINT chk_stock_institution_mandate_allocation CHECK (
    min_portfolio_allocation_rate >= 0
    AND min_portfolio_allocation_rate <= max_portfolio_allocation_rate
    AND max_portfolio_allocation_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_mandate_sensitivity CHECK (
    price_pressure_sensitivity BETWEEN -1 AND 1
    AND momentum_sensitivity BETWEEN -1 AND 1
    AND value_sensitivity BETWEEN -1 AND 1
    AND report_sensitivity BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_mandate_reference CHECK (reference_daily_volume > 0),
  CONSTRAINT chk_stock_institution_mandate_participation CHECK (
    daily_participation_rate > 0 AND daily_participation_rate <= 0.200000
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_decision_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  decision_slot DATETIME NOT NULL,
  simulation_trade_date DATE NOT NULL,
  portfolio_id BIGINT NOT NULL,
  execution_mode VARCHAR(20) NOT NULL,
  policy_version BIGINT NOT NULL,
  deterministic_seed BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CLAIMED',
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_decision_slot (portfolio_id, decision_slot),
  KEY idx_stock_institution_decision_run_date (simulation_trade_date, portfolio_id, decision_slot),
  KEY idx_stock_institution_decision_run_status (status, decision_slot, id),
  CONSTRAINT chk_stock_institution_decision_run_mode CHECK (`execution_mode` = 'LIVE'),
  CONSTRAINT chk_stock_institution_decision_run_status CHECK (
    CASE `status` WHEN 'CLAIMED' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_decision_run_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_institution_decision_item (
  decision_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  primary_price_pressure INT NOT NULL,
  primary_asset_preference_pressure INT NOT NULL,
  primary_volatility_pressure INT NOT NULL,
  primary_liquidity_pressure INT NOT NULL,
  primary_execution_aggression_pressure INT NOT NULL,
  secondary_price_pressure INT NOT NULL,
  secondary_asset_preference_pressure INT NOT NULL,
  secondary_volatility_pressure INT NOT NULL,
  secondary_liquidity_pressure INT NOT NULL,
  secondary_execution_aggression_pressure INT NOT NULL,
  blended_price_pressure DECIMAL(8,6) NOT NULL,
  blended_asset_preference_pressure DECIMAL(8,6) NOT NULL,
  blended_volatility_pressure DECIMAL(8,6) NOT NULL,
  blended_liquidity_pressure DECIMAL(8,6) NOT NULL,
  blended_execution_aggression_pressure DECIMAL(8,6) NOT NULL,
  return_5_day DECIMAL(12,8) NOT NULL,
  return_20_day DECIMAL(12,8) NOT NULL,
  report_pressure DECIMAL(8,6) NOT NULL,
  current_price DECIMAL(19,2) NOT NULL,
  liquid_asset_amount DECIMAL(19,2) NOT NULL,
  actual_quantity BIGINT NOT NULL,
  open_buy_quantity BIGINT NOT NULL,
  open_sell_quantity BIGINT NOT NULL,
  projected_quantity BIGINT NOT NULL,
  actual_allocation_rate DECIMAL(12,8) NOT NULL,
  projected_allocation_rate DECIMAL(12,8) NOT NULL,
  base_allocation_rate DECIMAL(12,8) NOT NULL,
  target_stock_allocation_rate DECIMAL(12,8) NOT NULL,
  target_allocation_rate DECIMAL(12,8) NOT NULL,
  target_amount DECIMAL(19,2) NOT NULL,
  raw_trade_amount DECIMAL(19,2) NOT NULL,
  gated_trade_amount DECIMAL(19,2) NOT NULL,
  gated_quantity BIGINT NOT NULL,
  action VARCHAR(10) NOT NULL,
  decision_reason VARCHAR(50) NOT NULL,
  gate_reason VARCHAR(100) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  remaining_daily_quantity_budget BIGINT NOT NULL,
  remaining_daily_notional_budget DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (decision_run_id, symbol),
  KEY idx_stock_institution_decision_item_symbol (symbol, decision_run_id),
  KEY idx_stock_institution_decision_item_action (action, decision_run_id, symbol),
  CONSTRAINT chk_stock_institution_decision_item_pressure CHECK (
    primary_price_pressure BETWEEN -100 AND 100
    AND primary_asset_preference_pressure BETWEEN -100 AND 100
    AND primary_volatility_pressure BETWEEN -100 AND 100
    AND primary_liquidity_pressure BETWEEN -100 AND 100
    AND primary_execution_aggression_pressure BETWEEN -100 AND 100
    AND secondary_price_pressure BETWEEN -100 AND 100
    AND secondary_asset_preference_pressure BETWEEN -100 AND 100
    AND secondary_volatility_pressure BETWEEN -100 AND 100
    AND secondary_liquidity_pressure BETWEEN -100 AND 100
    AND secondary_execution_aggression_pressure BETWEEN -100 AND 100
  ),
  CONSTRAINT chk_stock_institution_decision_item_blended CHECK (
    blended_price_pressure BETWEEN -1 AND 1
    AND blended_asset_preference_pressure BETWEEN -1 AND 1
    AND blended_volatility_pressure BETWEEN -1 AND 1
    AND blended_liquidity_pressure BETWEEN -1 AND 1
    AND blended_execution_aggression_pressure BETWEEN -1 AND 1
    AND report_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_decision_item_asset CHECK (
    current_price >= 0
    AND liquid_asset_amount >= 0
    AND actual_quantity >= 0
    AND open_buy_quantity >= 0
    AND open_sell_quantity >= 0
    AND projected_quantity >= 0
  ),
  CONSTRAINT chk_stock_institution_decision_item_rate CHECK (
    actual_allocation_rate >= 0
    AND projected_allocation_rate >= 0
    AND base_allocation_rate >= 0
    AND target_stock_allocation_rate BETWEEN 0 AND 1
    AND target_allocation_rate BETWEEN 0 AND 1
  ),
  CONSTRAINT chk_stock_institution_decision_item_trade CHECK (
    target_amount >= 0
    AND raw_trade_amount >= 0
    AND gated_trade_amount >= 0
    AND gated_trade_amount <= raw_trade_amount
    AND gated_quantity >= 0
    AND reference_daily_volume > 0
    AND remaining_daily_quantity_budget >= 0
    AND remaining_daily_notional_budget >= 0
  ),
  CONSTRAINT chk_stock_institution_decision_item_action CHECK (
    CASE `action` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 WHEN 'HOLD' THEN 1 ELSE 0 END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_daily_budget (
  simulation_trade_date DATE NOT NULL,
  portfolio_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  gross_quantity_limit BIGINT NOT NULL,
  gross_notional_limit DECIMAL(19,2) NOT NULL,
  planned_buy_quantity BIGINT NOT NULL DEFAULT 0,
  planned_sell_quantity BIGINT NOT NULL DEFAULT 0,
  planned_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  planned_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, portfolio_id, symbol),
  KEY idx_stock_institution_daily_budget_portfolio (
    portfolio_id, simulation_trade_date, symbol
  ),
  KEY idx_stock_institution_daily_budget_symbol (
    simulation_trade_date, symbol, portfolio_id
  ),
  CONSTRAINT chk_stock_institution_daily_budget_limit CHECK (
    reference_daily_volume > 0
    AND gross_quantity_limit > 0
    AND gross_notional_limit > 0
  ),
  CONSTRAINT chk_stock_institution_daily_budget_usage CHECK (
    planned_buy_quantity >= 0
    AND planned_sell_quantity >= 0
    AND planned_buy_quantity + planned_sell_quantity <= gross_quantity_limit
    AND planned_buy_amount >= 0
    AND planned_sell_amount >= 0
    AND planned_buy_amount + planned_sell_amount <= gross_notional_limit
    AND submitted_buy_amount >= 0
    AND submitted_sell_amount >= 0
    AND executed_buy_amount >= 0
    AND executed_sell_amount >= 0
  ),
  CONSTRAINT chk_stock_institution_daily_budget_version CHECK (
    policy_version > 0 AND version >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_order_intent (
  decision_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  portfolio_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  side VARCHAR(10) NOT NULL,
  requested_quantity BIGINT NOT NULL,
  planned_amount DECIMAL(19,2) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  execution_aggression_pressure DECIMAL(8,6) NOT NULL,
  policy_version BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  submitted_order_id BIGINT NULL,
  submitted_price DECIMAL(19,2) NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  submission_reason VARCHAR(200) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  submitted_at DATETIME NULL,
  PRIMARY KEY (decision_run_id, symbol),
  UNIQUE KEY uk_stock_institution_order_intent_order (submitted_order_id),
  KEY idx_stock_institution_order_intent_pending (
    status, created_at, decision_run_id, symbol
  ),
  KEY idx_stock_institution_order_intent_portfolio (
    portfolio_id, status, decision_run_id, symbol
  ),
  CONSTRAINT chk_stock_institution_order_intent_side CHECK (
    CASE side WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_quantity CHECK (
    requested_quantity > 0
    AND planned_amount > 0
    AND reference_daily_volume > 0
    AND submitted_quantity >= 0
    AND submitted_quantity <= requested_quantity
  ),
  CONSTRAINT chk_stock_institution_order_intent_pressure CHECK (
    execution_aggression_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_status CHECK (
    CASE status
      WHEN 'PENDING' THEN 1
      WHEN 'SUBMITTED' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'CANCELLED' THEN 1
      WHEN 'REJECTED' THEN 1
      WHEN 'FAILED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_submission CHECK (
    (
      status IN ('SUBMITTED', 'COMPLETED', 'CANCELLED')
      AND submitted_order_id IS NOT NULL
      AND submitted_price > 0
      AND submitted_quantity > 0
      AND submitted_at IS NOT NULL
    )
    OR (
      status NOT IN ('SUBMITTED', 'COMPLETED', 'CANCELLED')
      AND submitted_order_id IS NULL
      AND submitted_price IS NULL
      AND submitted_quantity = 0
      AND submitted_at IS NULL
    )
  ),
  CONSTRAINT chk_stock_institution_order_intent_version CHECK (policy_version > 0),
  CONSTRAINT chk_stock_institution_order_intent_attempt CHECK (
    attempt_count BETWEEN 0 AND 3
  )
);

CREATE TABLE IF NOT EXISTS stock_liquidity_mandate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  mandate_code VARCHAR(80) NOT NULL,
  execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  contract_start_date DATE NOT NULL,
  contract_end_date DATE NULL,
  target_spread_ticks INT NOT NULL DEFAULT 4,
  max_spread_ticks INT NOT NULL DEFAULT 12,
  max_order_quantity BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  target_open_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.050000,
  max_open_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.080000,
  max_single_order_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.010000,
  external_depth_levels INT NOT NULL DEFAULT 5,
  max_external_depth_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.100000,
  daily_execution_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.100000,
  daily_submission_multiplier DECIMAL(8,4) NOT NULL DEFAULT 2.0000,
  target_inventory_quantity BIGINT NOT NULL,
  inventory_band_quantity BIGINT NOT NULL,
  inventory_skew_ticks INT NOT NULL DEFAULT 3,
  primary_regime_weight DECIMAL(8,6) NOT NULL DEFAULT 0.700000,
  liquidity_size_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.250000,
  volatility_spread_max_ticks INT NOT NULL DEFAULT 4,
  price_regime_max_skew_ticks INT NOT NULL DEFAULT 1,
  passive_only BOOLEAN NOT NULL DEFAULT TRUE,
  minimum_quote_lifetime_seconds INT NOT NULL DEFAULT 30,
  reprice_threshold_ticks INT NOT NULL DEFAULT 2,
  order_ttl_seconds INT NOT NULL DEFAULT 300,
  quote_interval_seconds INT NOT NULL DEFAULT 30,
  daily_loss_limit_amount DECIMAL(19,2) NOT NULL,
  next_quote_at DATETIME NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_liquidity_mandate_code (mandate_code),
  UNIQUE KEY uk_stock_liquidity_mandate_account (account_id),
  UNIQUE KEY uk_stock_liquidity_mandate_symbol (symbol),
  KEY idx_stock_liquidity_mandate_due (
    status, execution_mode, next_quote_at, id
  ),
  KEY idx_stock_liquidity_mandate_participant (
    participant_id, status, id
  ),
  CONSTRAINT chk_stock_liquidity_mandate_mode CHECK (
    `execution_mode` = 'LIVE'
  ),
  CONSTRAINT chk_stock_liquidity_mandate_status CHECK (
    CASE `status`
      WHEN 'ACTIVE' THEN 1
      WHEN 'SUSPENDED' THEN 1
      WHEN 'EXPIRED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_mandate_contract CHECK (
    contract_end_date IS NULL OR contract_end_date >= contract_start_date
  ),
  CONSTRAINT chk_stock_liquidity_mandate_spread CHECK (
    target_spread_ticks BETWEEN 1 AND 50
    AND max_spread_ticks BETWEEN target_spread_ticks AND 100
  ),
  CONSTRAINT chk_stock_liquidity_mandate_volume CHECK (
    max_order_quantity > 0
    AND reference_daily_volume > 0
    AND target_open_participation_rate > 0
    AND target_open_participation_rate <= 0.100000
    AND max_open_participation_rate >= target_open_participation_rate
    AND max_open_participation_rate <= 0.200000
    AND max_single_order_participation_rate > 0
    AND max_single_order_participation_rate <= target_open_participation_rate
    AND external_depth_levels BETWEEN 1 AND 10
    AND max_external_depth_participation_rate > 0
    AND max_external_depth_participation_rate <= 0.250000
    AND daily_execution_participation_rate > 0
    AND daily_execution_participation_rate <= 0.300000
    AND daily_submission_multiplier BETWEEN 1 AND 10
  ),
  CONSTRAINT chk_stock_liquidity_mandate_inventory CHECK (
    target_inventory_quantity >= 0
    AND inventory_band_quantity > 0
    AND inventory_skew_ticks BETWEEN 0 AND 50
  ),
  CONSTRAINT chk_stock_liquidity_mandate_regime CHECK (
    primary_regime_weight BETWEEN 0 AND 1
    AND liquidity_size_sensitivity BETWEEN 0 AND 1
    AND volatility_spread_max_ticks BETWEEN 0 AND 50
    AND price_regime_max_skew_ticks BETWEEN 0 AND 5
  ),
  CONSTRAINT chk_stock_liquidity_mandate_timing CHECK (
    minimum_quote_lifetime_seconds BETWEEN 10 AND 1800
    AND reprice_threshold_ticks BETWEEN 1 AND 20
    AND order_ttl_seconds >= minimum_quote_lifetime_seconds
    AND order_ttl_seconds <= 7200
    AND quote_interval_seconds BETWEEN 10 AND 600
  ),
  CONSTRAINT chk_stock_liquidity_mandate_loss CHECK (
    daily_loss_limit_amount > 0
  ),
  CONSTRAINT chk_stock_liquidity_mandate_version CHECK (
    policy_version > 0
  )
);

CREATE TABLE IF NOT EXISTS stock_liquidity_daily_state (
  simulation_trade_date DATE NOT NULL,
  mandate_id BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  execution_quantity_limit BIGINT NOT NULL,
  submission_quantity_limit BIGINT NOT NULL,
  submitted_buy_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_sell_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  cancelled_buy_quantity BIGINT NOT NULL DEFAULT 0,
  cancelled_sell_quantity BIGINT NOT NULL DEFAULT 0,
  executed_buy_quantity BIGINT NOT NULL DEFAULT 0,
  executed_sell_quantity BIGINT NOT NULL DEFAULT 0,
  executed_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  realized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  unrealized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  opening_net_asset_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  current_net_asset_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  risk_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  target_buy_open_quantity BIGINT NOT NULL DEFAULT 0,
  target_sell_open_quantity BIGINT NOT NULL DEFAULT 0,
  last_open_buy_quantity BIGINT NOT NULL DEFAULT 0,
  last_open_sell_quantity BIGINT NOT NULL DEFAULT 0,
  external_buy_depth_quantity BIGINT NOT NULL DEFAULT 0,
  external_sell_depth_quantity BIGINT NOT NULL DEFAULT 0,
  last_bid_price DECIMAL(19,2) NULL,
  last_ask_price DECIMAL(19,2) NULL,
  last_inventory_quantity BIGINT NOT NULL DEFAULT 0,
  last_projected_inventory_quantity BIGINT NOT NULL DEFAULT 0,
  blended_price_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  blended_volatility_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  blended_liquidity_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  state_status VARCHAR(20) NOT NULL DEFAULT 'QUOTING',
  gate_reason VARCHAR(120) NOT NULL DEFAULT 'NOT_RUN',
  quote_run_count BIGINT NOT NULL DEFAULT 0,
  limit_breached BOOLEAN NOT NULL DEFAULT FALSE,
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, mandate_id),
  KEY idx_stock_liquidity_daily_state_mandate (
    mandate_id, simulation_trade_date
  ),
  KEY idx_stock_liquidity_daily_state_status (
    simulation_trade_date, state_status, mandate_id
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_limit CHECK (
    reference_daily_volume > 0
    AND execution_quantity_limit > 0
    AND submission_quantity_limit >= execution_quantity_limit
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_quantity CHECK (
    submitted_buy_quantity >= 0
    AND submitted_sell_quantity >= 0
    AND submitted_buy_amount >= 0
    AND submitted_sell_amount >= 0
    AND cancelled_buy_quantity >= 0
    AND cancelled_sell_quantity >= 0
    AND executed_buy_quantity >= 0
    AND executed_sell_quantity >= 0
    AND executed_buy_amount >= 0
    AND executed_sell_amount >= 0
    AND opening_net_asset_value >= 0
    AND current_net_asset_value >= 0
    AND target_buy_open_quantity >= 0
    AND target_sell_open_quantity >= 0
    AND last_open_buy_quantity >= 0
    AND last_open_sell_quantity >= 0
    AND external_buy_depth_quantity >= 0
    AND external_sell_depth_quantity >= 0
    AND last_inventory_quantity >= 0
    AND last_projected_inventory_quantity >= 0
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_price CHECK (
    (last_bid_price IS NULL OR last_bid_price > 0)
    AND (last_ask_price IS NULL OR last_ask_price > 0)
    AND (
      last_bid_price IS NULL
      OR last_ask_price IS NULL
      OR last_bid_price < last_ask_price
    )
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_pressure CHECK (
    blended_price_pressure BETWEEN -1 AND 1
    AND blended_volatility_pressure BETWEEN -1 AND 1
    AND blended_liquidity_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_status CHECK (
    CASE `state_status`
      WHEN 'QUOTING' THEN 1
      WHEN 'EXEMPT' THEN 1
      WHEN 'HALTED' THEN 1
      WHEN 'ERROR' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_version CHECK (
    quote_run_count >= 0 AND policy_version > 0 AND version >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_liquidity_transition (
  id BIGINT NOT NULL AUTO_INCREMENT,
  transition_key VARCHAR(120) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  mandate_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  liquidity_account_id BIGINT NOT NULL,
  source_account_id BIGINT NOT NULL,
  legacy_account_id BIGINT NULL,
  stage VARCHAR(30) NOT NULL DEFAULT 'LIVE_ACTIVE',
  reference_daily_volume BIGINT NOT NULL,
  seed_inventory_quantity BIGINT NOT NULL,
  seed_cash_amount DECIMAL(19,2) NOT NULL,
  transferred_inventory_quantity BIGINT NOT NULL DEFAULT 0,
  transferred_cash_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  effective_business_date DATE NOT NULL,
  legacy_disabled_at DATETIME NULL,
  legacy_retired_at DATETIME NULL,
  activated_at DATETIME NULL,
  requested_by VARCHAR(64) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_liquidity_transition_key (transition_key),
  UNIQUE KEY uk_stock_liquidity_transition_symbol (symbol),
  UNIQUE KEY uk_stock_liquidity_transition_mandate (mandate_id),
  UNIQUE KEY uk_stock_liquidity_transition_account (liquidity_account_id),
  KEY idx_stock_liquidity_transition_stage (
    stage, effective_business_date, symbol
  ),
  KEY idx_stock_liquidity_transition_source (
    source_account_id, symbol, id
  ),
  CONSTRAINT chk_stock_liquidity_transition_stage CHECK (
    CASE stage
      WHEN 'LIVE_ACTIVE' THEN 1
      WHEN 'SUSPENDED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_transition_seed CHECK (
    reference_daily_volume > 0
    AND seed_inventory_quantity > 0
    AND seed_cash_amount > 0
  ),
  CONSTRAINT chk_stock_liquidity_transition_transfer CHECK (
    transferred_inventory_quantity >= 0
    AND transferred_cash_amount >= 0
  ),
  CONSTRAINT chk_stock_liquidity_transition_activation CHECK (
    stage IN ('LIVE_ACTIVE', 'SUSPENDED')
    AND activated_at IS NOT NULL
  ),
  CONSTRAINT chk_stock_liquidity_transition_audit CHECK (
    transition_key <> ''
    AND requested_by <> ''
    AND change_reason <> ''
    AND policy_version > 0
  )
);

CREATE TABLE IF NOT EXISTS stock_order_book_daily_regime (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  source_regime_phase VARCHAR(20) NULL,
  price_pressure INT NOT NULL,
  asset_preference_pressure INT NOT NULL,
  volatility_pressure INT NOT NULL,
  liquidity_pressure INT NOT NULL,
  execution_aggression_pressure INT NOT NULL,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase),
  KEY idx_stock_order_book_daily_regime_date (simulation_trade_date, regime_phase, symbol),
  CONSTRAINT chk_stock_order_book_daily_regime_phase CHECK (CASE `regime_phase` WHEN 'SLOT_0600' THEN 1 WHEN 'SLOT_0900' THEN 1 WHEN 'SLOT_1200' THEN 1 WHEN 'SLOT_1500' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_source_phase CHECK (
    source_regime_phase is null
    or CASE `source_regime_phase` WHEN 'SLOT_0600' THEN 1 WHEN 'SLOT_0900' THEN 1 WHEN 'SLOT_1200' THEN 1 WHEN 'SLOT_1500' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_order_book_daily_regime_price CHECK (price_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_daily_regime_asset CHECK (asset_preference_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_daily_regime_volatility CHECK (volatility_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_daily_regime_liquidity CHECK (liquidity_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_daily_regime_aggression CHECK (execution_aggression_pressure between -100 and 100)
);

CREATE TABLE IF NOT EXISTS stock_order_book_regime_modifier (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  modifier_window_start_at DATETIME NOT NULL,
  price_pressure INT NOT NULL,
  asset_preference_pressure INT NOT NULL,
  volatility_pressure INT NOT NULL,
  liquidity_pressure INT NOT NULL,
  execution_aggression_pressure INT NOT NULL,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase, modifier_window_start_at),
  KEY idx_stock_order_book_regime_modifier_window (simulation_trade_date, regime_phase, modifier_window_start_at, symbol),
  CONSTRAINT chk_stock_order_book_regime_modifier_phase CHECK (CASE `regime_phase` WHEN 'SLOT_0600' THEN 1 WHEN 'SLOT_0900' THEN 1 WHEN 'SLOT_1200' THEN 1 WHEN 'SLOT_1500' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_regime_modifier_price CHECK (price_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_regime_modifier_asset CHECK (asset_preference_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_regime_modifier_volatility CHECK (volatility_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_regime_modifier_liquidity CHECK (liquidity_pressure between -100 and 100),
  CONSTRAINT chk_stock_order_book_regime_modifier_aggression CHECK (execution_aggression_pressure between -100 and 100)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_symbol_config (
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  intensity INT NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key, symbol),
  KEY idx_stock_auto_participant_symbol_enabled (enabled, symbol, user_key),
  KEY idx_stock_auto_participant_symbol_lookup (symbol, user_key),
  KEY idx_stock_auto_participant_symbol_user_enabled (user_key, enabled, symbol),
  CONSTRAINT chk_stock_auto_participant_symbol_intensity CHECK (intensity between 1 and 10)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_policy_revision (
  policy_version BIGINT NOT NULL AUTO_INCREMENT,
  status VARCHAR(20) NOT NULL,
  effective_trade_date DATE NULL,
  runtime_enabled BIT NOT NULL DEFAULT b'1',
  runtime_change_reason VARCHAR(200) NULL,
  runtime_changed_by VARCHAR(64) NULL,
  runtime_changed_at DATETIME NULL,
  policy_json LONGTEXT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  activated_at DATETIME NULL,
  retired_at DATETIME NULL,
  PRIMARY KEY (policy_version),
  KEY idx_stock_auto_policy_status_effective (status, effective_trade_date, policy_version),
  CONSTRAINT chk_stock_auto_policy_status CHECK (
    CASE `status`
      WHEN 'DRAFT' THEN 1
      WHEN 'SCHEDULED' THEN 1
      WHEN 'ACTIVE' THEN 1
      WHEN 'RETIRED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_policy_effective_date CHECK (
    (status = 'DRAFT' AND effective_trade_date IS NULL)
    OR (status <> 'DRAFT' AND effective_trade_date IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_activation_time CHECK (
    (status IN ('DRAFT', 'SCHEDULED') AND activated_at IS NULL)
    OR (status IN ('ACTIVE', 'RETIRED') AND activated_at IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_retired_time CHECK (
    (status <> 'RETIRED' AND retired_at IS NULL)
    OR (status = 'RETIRED' AND retired_at IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_json CHECK (JSON_VALID(policy_json)),
  CONSTRAINT chk_stock_auto_policy_runtime_audit CHECK (
    (
      runtime_change_reason IS NULL
      AND runtime_changed_by IS NULL
      AND runtime_changed_at IS NULL
    )
    OR (
      runtime_change_reason <> ''
      AND runtime_changed_by <> ''
      AND runtime_changed_at IS NOT NULL
    )
  )
);

INSERT INTO stock_auto_participant_policy_revision(
    status, effective_trade_date, runtime_enabled,
    policy_json, created_by, created_at, activated_at, retired_at
)
SELECT
    'ACTIVE', '1970-01-01', b'1',
    '{"model":"V3","executionIntercept":-0.35,"signalSensitivity":1.70,"fatigueSensitivity":1.15,"fatigueHalfLifeSeconds":2700,"reentryTauSeconds":180,"ordinaryQuantityGamma":3.0,"rareLargeOrderProbability":0.025}',
    'SYSTEM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_auto_participant_policy_revision
     WHERE status = 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_daily_behavior_state (
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  policy_version BIGINT NOT NULL,
  participant_config_version BIGINT NOT NULL,
  activity_state VARCHAR(20) NOT NULL,
  activity_session VARCHAR(20) NOT NULL,
  daily_seed BIGINT NOT NULL,
  event_sequence BIGINT NOT NULL DEFAULT 0,
  fatigue_score DECIMAL(12,6) NOT NULL DEFAULT 0.000000,
  fatigue_updated_at DATETIME NOT NULL,
  submitted_order_count BIGINT NOT NULL DEFAULT 0,
  submitted_notional DECIMAL(24,2) NOT NULL DEFAULT 0.00,
  observed_execution_count BIGINT NOT NULL DEFAULT 0,
  observed_execution_notional DECIMAL(24,2) NOT NULL DEFAULT 0.00,
  observed_cancel_count BIGINT NOT NULL DEFAULT 0,
  last_attention_at DATETIME NULL,
  last_decision_at DATETIME NULL,
  last_order_at DATETIME NULL,
  last_result_reason VARCHAR(50) NULL,
  last_hold_reason VARCHAR(50) NULL,
  recovery_factor DECIMAL(12,8) NOT NULL DEFAULT 0.00000000,
  optimistic_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, account_id),
  KEY idx_stock_auto_behavior_account_date (account_id, simulation_trade_date),
  KEY idx_stock_auto_behavior_policy_date (policy_version, simulation_trade_date, account_id),
  KEY idx_stock_auto_behavior_state_date (activity_state, simulation_trade_date, account_id),
  CONSTRAINT chk_stock_auto_behavior_policy_version CHECK (policy_version > 0),
  CONSTRAINT chk_stock_auto_behavior_config_version CHECK (participant_config_version > 0),
  CONSTRAINT chk_stock_auto_behavior_activity_state CHECK (
    CASE `activity_state`
      WHEN 'OFFLINE' THEN 1
      WHEN 'LOW' THEN 1
      WHEN 'NORMAL' THEN 1
      WHEN 'HIGH' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_behavior_session CHECK (
    CASE `activity_session`
      WHEN 'OPENING' THEN 1
      WHEN 'MIDDAY' THEN 1
      WHEN 'CLOSING' THEN 1
      WHEN 'RANDOM' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_behavior_event_sequence CHECK (event_sequence >= 0),
  CONSTRAINT chk_stock_auto_behavior_fatigue CHECK (fatigue_score >= 0),
  CONSTRAINT chk_stock_auto_behavior_submission CHECK (
    submitted_order_count >= 0 AND submitted_notional >= 0
  ),
  CONSTRAINT chk_stock_auto_behavior_observed CHECK (
    observed_execution_count >= 0
    AND observed_execution_notional >= 0
    AND observed_cancel_count >= 0
  ),
  CONSTRAINT chk_stock_auto_behavior_recovery CHECK (
    recovery_factor >= 0 AND recovery_factor <= 1
  ),
  CONSTRAINT chk_stock_auto_behavior_optimistic_version CHECK (optimistic_version >= 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule (
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  behavior_model_version VARCHAR(20) NOT NULL DEFAULT 'V3',
  simulation_trade_date DATE NOT NULL,
  next_attention_at DATETIME NULL,
  next_guard_at DATETIME NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_stock_auto_order_schedule_user (user_key),
  KEY idx_stock_auto_order_schedule_due (next_run_at, lease_until, priority, account_id),
  KEY idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, account_id),
  KEY idx_stock_auto_order_schedule_trade_date (simulation_trade_date, next_run_at, account_id),
  CONSTRAINT chk_stock_auto_order_schedule_model CHECK (behavior_model_version = 'V3'),
  CONSTRAINT chk_stock_auto_order_schedule_next_run CHECK (
    next_run_at = LEAST(COALESCE(next_attention_at, next_guard_at), next_guard_at)
  ),
  CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (priority between 1 and 100)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_liquidation_plan (
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  urgency VARCHAR(30) NOT NULL,
  trigger_reason VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL,
  target_quantity BIGINT NOT NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  remaining_quantity BIGINT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, account_id, symbol, urgency),
  KEY idx_stock_auto_liquidation_retry (status, next_retry_at, account_id, symbol),
  CONSTRAINT chk_stock_auto_liquidation_urgency CHECK (
    urgency IN ('RISK_REDUCTION', 'MANDATORY_CLOSE')
  ),
  CONSTRAINT chk_stock_auto_liquidation_status CHECK (
    status IN ('PENDING', 'SUBMITTED', 'COMPLETED', 'INCOMPLETE')
  ),
  CONSTRAINT chk_stock_auto_liquidation_quantity CHECK (
    target_quantity > 0
    AND submitted_quantity >= 0
    AND remaining_quantity >= 0
  ),
  CONSTRAINT chk_stock_auto_liquidation_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_instrument_report_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  title VARCHAR(120) NULL,
  summary VARCHAR(1000) NULL,
  score INT NULL,
  rise_reason VARCHAR(500) NULL,
  fall_reason VARCHAR(500) NULL,
  delete_reason VARCHAR(255) NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_report_symbol_time (symbol, created_at, id),
  CONSTRAINT chk_stock_report_event_type CHECK (CASE `event_type` WHEN 'PUBLISH' THEN 1 WHEN 'UPDATE' THEN 1 WHEN 'DELETE' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_report_score CHECK (score IS NULL OR score between 1 and 10),
  CONSTRAINT chk_stock_report_content_scope CHECK ((event_type = 'DELETE' AND title IS NULL AND summary IS NULL AND score IS NULL AND rise_reason IS NULL AND fall_reason IS NULL) OR (event_type IN ('PUBLISH', 'UPDATE') AND title IS NOT NULL AND summary IS NOT NULL AND score IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS portfolio_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NULL,
  close_run_id BIGINT NULL,
  account_id BIGINT NOT NULL,
  snapshot_date DATE NOT NULL,
  total_asset DECIMAL(19,2) NOT NULL,
  cash_balance DECIMAL(19,2) NOT NULL,
  pending_subscription_asset DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  market_value DECIMAL(19,2) NOT NULL,
  holding_quantity BIGINT NULL,
  reserved_sell_quantity BIGINT NULL,
  holding_position_count BIGINT NULL,
  net_contribution DECIMAL(19,2) NULL,
  total_profit DECIMAL(19,2) NULL,
  return_rate DECIMAL(19,8) NULL,
  return_rate_status VARCHAR(40) NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
  input_hash VARCHAR(64) NULL,
  calculation_version VARCHAR(40) NULL,
  data_quality_status VARCHAR(20) NULL,
  source_build_version VARCHAR(100) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_portfolio_snapshot_account_date (account_id, snapshot_date),
  UNIQUE KEY uk_portfolio_snapshot_cycle_account (close_cycle_id, account_id),
  KEY idx_portfolio_snapshot_date_return (snapshot_date, return_rate),
  KEY idx_portfolio_snapshot_close_run (close_run_id, account_id),
  CONSTRAINT chk_portfolio_snapshot_total_asset_non_negative CHECK (total_asset >= 0),
  CONSTRAINT chk_portfolio_snapshot_cash_balance_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_portfolio_snapshot_pending_subscription_non_negative CHECK (
    pending_subscription_asset >= 0
  ),
  CONSTRAINT chk_portfolio_snapshot_market_value_non_negative CHECK (market_value >= 0),
  CONSTRAINT chk_portfolio_snapshot_asset_composition CHECK (
    total_asset = cash_balance + pending_subscription_asset + market_value
  ),
  CONSTRAINT chk_portfolio_snapshot_return_contract CHECK (
    (return_rate_status = 'LEGACY_UNVERIFIED' AND net_contribution IS NULL AND total_profit IS NULL)
    OR (
      net_contribution IS NOT NULL
      AND total_profit IS NOT NULL
      AND total_profit = total_asset - net_contribution
      AND (
        (return_rate_status = 'DEFINED' AND net_contribution > 0 AND return_rate IS NOT NULL)
        OR (
          return_rate_status = 'UNDEFINED_ZERO_CONTRIBUTION'
          AND net_contribution = 0
          AND return_rate IS NULL
        )
        OR (
          return_rate_status = 'UNDEFINED_NEGATIVE_CONTRIBUTION'
          AND net_contribution < 0
          AND return_rate IS NULL
        )
      )
    )
  ),
  CONSTRAINT chk_portfolio_snapshot_holding_metrics_complete CHECK (
    (holding_quantity IS NULL AND reserved_sell_quantity IS NULL AND holding_position_count IS NULL)
    OR (
      holding_quantity IS NOT NULL
      AND reserved_sell_quantity IS NOT NULL
      AND holding_position_count IS NOT NULL
      AND holding_quantity >= 0
      AND reserved_sell_quantity >= 0
      AND reserved_sell_quantity <= holding_quantity
      AND holding_position_count >= 0
    )
  ),
  CONSTRAINT chk_portfolio_snapshot_input_hash CHECK (input_hash IS NULL OR char_length(input_hash) = 64),
  CONSTRAINT chk_portfolio_snapshot_data_quality CHECK (
    data_quality_status IS NULL
    OR CASE `data_quality_status` WHEN 'VERIFIED' THEN 1 WHEN 'WARNING' THEN 1 WHEN 'INVALID' THEN 1 ELSE 0 END = 1
  )
);

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'DEFAULT_ISSUE_UNDERWRITER', '기본 인수기관', 'ISSUE_UNDERWRITER', 'ACTIVE',
    'ISSUE_UNDERWRITER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
);

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'DEFAULT_LIQUIDITY_PROVIDER', '기본 유동성공급기관',
    'LIQUIDITY_PROVIDER', 'ACTIVE',
    'LIQUIDITY_PROVIDER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'DEFAULT_LIQUIDITY_PROVIDER'
);

CREATE TABLE IF NOT EXISTS stock_underwriting_contract (
  id BIGINT NOT NULL AUTO_INCREMENT,
  contract_code VARCHAR(80) NOT NULL,
  corporate_action_id BIGINT NULL,
  symbol VARCHAR(20) NOT NULL,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  total_issue_quantity BIGINT NOT NULL,
  tradable_allocation_quantity BIGINT NOT NULL,
  locked_allocation_quantity BIGINT NOT NULL,
  external_allocation_quantity BIGINT NOT NULL DEFAULT 0,
  underwritten_quantity BIGINT NOT NULL,
  issue_price DECIMAL(19,2) NOT NULL,
  underwriting_type VARCHAR(30) NOT NULL DEFAULT 'FIRM_COMMITMENT',
  stabilization_start_date DATE NULL,
  stabilization_end_date DATE NULL,
  stabilization_quantity_limit BIGINT NOT NULL DEFAULT 0,
  stabilization_amount_limit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  status VARCHAR(20) NOT NULL DEFAULT 'ALLOCATED',
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_underwriting_contract_code (contract_code),
  UNIQUE KEY uk_stock_underwriting_contract_action (corporate_action_id),
  KEY idx_stock_underwriting_contract_symbol (symbol, status, id),
  KEY idx_stock_underwriting_contract_participant (
    participant_id, status, symbol, id
  ),
  KEY idx_stock_underwriting_contract_account (account_id, status, symbol, id),
  CONSTRAINT chk_stock_underwriting_contract_quantity CHECK (
    total_issue_quantity > 0
    AND tradable_allocation_quantity > 0
    AND locked_allocation_quantity >= 0
    AND external_allocation_quantity >= 0
    AND underwritten_quantity >= 0
    AND tradable_allocation_quantity + locked_allocation_quantity = total_issue_quantity
    AND external_allocation_quantity + underwritten_quantity = tradable_allocation_quantity
  ),
  CONSTRAINT chk_stock_underwriting_contract_price CHECK (issue_price > 0),
  CONSTRAINT chk_stock_underwriting_contract_type CHECK (
    CASE underwriting_type
      WHEN 'FIRM_COMMITMENT' THEN 1
      WHEN 'BEST_EFFORTS' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_contract_stabilization CHECK (
    stabilization_quantity_limit >= 0
    AND stabilization_amount_limit >= 0
    AND (
      stabilization_start_date IS NULL
      OR stabilization_end_date IS NULL
      OR stabilization_end_date >= stabilization_start_date
    )
  ),
  CONSTRAINT chk_stock_underwriting_contract_status CHECK (
    CASE status
      WHEN 'ALLOCATED' THEN 1
      WHEN 'STABILIZING' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'CANCELLED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_contract_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_underwriting_daily_supply_state (
  simulation_trade_date DATE NOT NULL,
  underwriting_contract_id BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  submission_quantity_limit BIGINT NOT NULL,
  submission_amount_limit DECIMAL(19,2) NOT NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  generated_order_count BIGINT NOT NULL DEFAULT 0,
  cancelled_order_count BIGINT NOT NULL DEFAULT 0,
  last_order_price DECIMAL(19,2) NULL,
  state_status VARCHAR(20) NOT NULL DEFAULT 'GATED',
  gate_reason VARCHAR(80) NOT NULL DEFAULT 'NOT_RUN',
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, underwriting_contract_id),
  KEY idx_stock_underwriting_supply_contract (
    underwriting_contract_id, simulation_trade_date
  ),
  KEY idx_stock_underwriting_supply_status (
    simulation_trade_date, state_status, underwriting_contract_id
  ),
  CONSTRAINT chk_stock_underwriting_supply_limits CHECK (
    reference_daily_volume >= 0
    AND submission_quantity_limit >= 0
    AND submission_amount_limit >= 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_usage CHECK (
    submitted_quantity >= 0
    AND submitted_amount >= 0
    AND submitted_quantity <= submission_quantity_limit
    AND submitted_amount <= submission_amount_limit
    AND generated_order_count >= 0
    AND cancelled_order_count >= 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_price CHECK (
    last_order_price IS NULL OR last_order_price > 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_status CHECK (
    CASE state_status
      WHEN 'ACTIVE' THEN 1
      WHEN 'GATED' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'SUSPENDED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_supply_version CHECK (
    policy_version > 0 AND version >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_security_allocation_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  idempotency_key VARCHAR(120) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  corporate_action_id BIGINT NULL,
  underwriting_contract_id BIGINT NULL,
  source_account_id BIGINT NULL,
  destination_account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(19,2) NOT NULL,
  allocation_reason VARCHAR(50) NOT NULL,
  tradability_status VARCHAR(20) NOT NULL,
  effective_business_date DATE NOT NULL,
  unlock_business_date DATE NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_security_allocation_idempotency (idempotency_key),
  KEY idx_stock_security_allocation_symbol (
    symbol, effective_business_date, id
  ),
  KEY idx_stock_security_allocation_destination (
    destination_account_id, symbol, effective_business_date, id
  ),
  KEY idx_stock_security_allocation_contract (
    underwriting_contract_id, id
  ),
  CONSTRAINT chk_stock_security_allocation_event CHECK (
    CASE event_type
      WHEN 'INITIAL_ISSUE' THEN 1
      WHEN 'CAPITAL_INCREASE' THEN 1
      WHEN 'LOCK_RELEASE' THEN 1
      WHEN 'MANUAL_REALLOCATION' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_security_allocation_amount CHECK (
    quantity > 0 AND unit_price >= 0
  ),
  CONSTRAINT chk_stock_security_allocation_reason CHECK (
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
  ),
  CONSTRAINT chk_stock_security_allocation_tradability CHECK (
    CASE tradability_status
      WHEN 'TRADABLE' THEN 1
      WHEN 'LOCKED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_security_allocation_unlock CHECK (
    unlock_business_date IS NULL
    OR unlock_business_date >= effective_business_date
  )
);
