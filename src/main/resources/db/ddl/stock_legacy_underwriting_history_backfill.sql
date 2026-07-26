USE STOCK_SERVICE;

-- Reconstructs the completed issue-underwriting history for legacy listing
-- accounts after their residual assets have already moved to the dedicated LP
-- accounts. This migration changes only role metadata and low-volume audit
-- tables. It never rewrites holdings, cash, orders, executions, or EOD
-- snapshots.

DROP PROCEDURE IF EXISTS backfill_stock_legacy_underwriting_history;

DELIMITER //
CREATE PROCEDURE backfill_stock_legacy_underwriting_history()
main: BEGIN
  DECLARE expected_transition_count BIGINT DEFAULT 0;
  DECLARE candidate_count BIGINT DEFAULT 0;
  DECLARE participant_count BIGINT DEFAULT 0;
  DECLARE underwriter_participant_id BIGINT;
  DECLARE violation_count BIGINT DEFAULT 0;
  DECLARE simulation_clock_running BOOLEAN;

  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_underwriting_history;
    RESIGNAL;
  END;

  SELECT running
    INTO simulation_clock_running
    FROM stock_simulation_clock
   WHERE clock_id = 'DEFAULT';
  IF simulation_clock_running IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy underwriting history backfill requires an initialized simulation clock';
  END IF;
  IF simulation_clock_running THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Pause the simulation clock before legacy underwriting history backfill';
  END IF;

  SELECT COUNT(*), MIN(id)
    INTO participant_count, underwriter_participant_id
    FROM stock_market_participant
   WHERE participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
     AND participant_type = 'ISSUE_UNDERWRITER'
     AND status = 'ACTIVE'
     AND self_trade_group_id = 'ISSUE_UNDERWRITER:DEFAULT';
  IF participant_count <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Exactly one active default issue-underwriter participant is required';
  END IF;

  DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_underwriting_history;
  CREATE TEMPORARY TABLE tmp_stock_legacy_underwriting_history (
    transition_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    legacy_account_id BIGINT NOT NULL,
    liquidity_account_id BIGINT NOT NULL,
    issued_shares BIGINT NOT NULL,
    tradable_shares BIGINT NOT NULL,
    issue_price DECIMAL(19,2) NOT NULL,
    contract_code VARCHAR(80) NOT NULL,
    account_code VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    created_at DATETIME NOT NULL,
    completed_at DATETIME NOT NULL,
    PRIMARY KEY (transition_id),
    UNIQUE KEY uk_tmp_legacy_underwriting_symbol (symbol),
    UNIQUE KEY uk_tmp_legacy_underwriting_account (legacy_account_id)
  );

  START TRANSACTION;

  INSERT INTO tmp_stock_legacy_underwriting_history(
      transition_id, symbol, legacy_account_id, liquidity_account_id,
      issued_shares, tradable_shares, issue_price,
      contract_code, account_code,
      effective_from, effective_to, created_at, completed_at
  )
  SELECT transition.id,
         transition.symbol,
         transition.legacy_account_id,
         transition.liquidity_account_id,
         instrument.issued_shares,
         instrument.tradable_shares,
         instrument.initial_price,
         CONCAT('INITIAL-ISSUE:', transition.symbol),
         CONCAT('UW-', transition.symbol),
         DATE(LEAST(instrument.created_at, legacy_account.created_at)),
         GREATEST(
             DATE(LEAST(instrument.created_at, legacy_account.created_at)),
             transition.effective_business_date
         ),
         LEAST(instrument.created_at, legacy_account.created_at),
         transition.legacy_retired_at
    FROM stock_liquidity_transition transition
    JOIN stock_liquidity_mandate mandate
      ON mandate.id = transition.mandate_id
     AND mandate.symbol = transition.symbol
     AND mandate.account_id = transition.liquidity_account_id
    JOIN stock_account liquidity_account
      ON liquidity_account.id = transition.liquidity_account_id
     AND liquidity_account.participant_category = 'LIQUIDITY_PROVIDER'
    JOIN stock_account legacy_account
      ON legacy_account.id = transition.legacy_account_id
    JOIN stock_order_book_instrument instrument
      ON instrument.symbol = transition.symbol
   WHERE transition.legacy_account_id IS NOT NULL
     AND transition.legacy_retired_at IS NOT NULL
     AND transition.source_account_id = transition.legacy_account_id
     AND transition.stage IN ('LIVE_ACTIVE', 'SUSPENDED');

  SELECT COUNT(*)
    INTO expected_transition_count
    FROM stock_liquidity_transition
   WHERE legacy_account_id IS NOT NULL
     AND legacy_retired_at IS NOT NULL;
  SELECT COUNT(*)
    INTO candidate_count
    FROM tmp_stock_legacy_underwriting_history;
  IF candidate_count <> expected_transition_count THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Every retired legacy liquidity transition must map to one underwriting history candidate';
  END IF;
  IF candidate_count = 0 THEN
    COMMIT;
    DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_underwriting_history;
    LEAVE main;
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
    JOIN stock_account legacy_account
      ON legacy_account.id = candidate.legacy_account_id
   WHERE legacy_account.status <> 'CLOSED'
      OR legacy_account.participant_category NOT IN (
          'LISTING_UNDERWRITER',
          'ISSUE_UNDERWRITER'
      )
      OR legacy_account.cash_balance <> 0
      OR (
          legacy_account.account_code IS NOT NULL
          AND legacy_account.account_code <> candidate.account_code
      )
      OR (
          legacy_account.self_trade_group_id IS NOT NULL
          AND legacy_account.self_trade_group_id <> 'ISSUE_UNDERWRITER:DEFAULT'
      )
      OR EXISTS (
          SELECT 1
            FROM stock_holding holding
           WHERE holding.account_id = candidate.legacy_account_id
             AND (
                 holding.quantity <> 0
                 OR holding.reserved_quantity <> 0
             )
      );
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Historical underwriting accounts must be closed, empty, and role-compatible';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
    STRAIGHT_JOIN stock_order open_order
      ON candidate.legacy_account_id = open_order.account_id
   WHERE open_order.status IN ('PENDING', 'PARTIALLY_FILLED')
     AND open_order.quantity > open_order.filled_quantity;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Historical underwriting accounts must not have open orders';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
   WHERE candidate.issued_shares <> candidate.tradable_shares
      OR candidate.issued_shares <= 0
      OR candidate.issue_price <= 0;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy underwriting history backfill only supports positive 100-percent-float legacy issues';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
    LEFT JOIN (
        SELECT holding.symbol, SUM(holding.quantity) AS holding_quantity
          FROM stock_holding holding
         GROUP BY holding.symbol
    ) holding_sum
      ON holding_sum.symbol = candidate.symbol
   WHERE COALESCE(holding_sum.holding_quantity, 0) <> candidate.issued_shares;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Issued-share reconciliation failed before underwriting history backfill';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_market_participant_account role_mapping
    JOIN tmp_stock_legacy_underwriting_history candidate
      ON candidate.legacy_account_id = role_mapping.account_id
   WHERE role_mapping.participant_id <> underwriter_participant_id
      OR role_mapping.account_role <> 'ISSUE_UNDERWRITER'
      OR role_mapping.desk_code <> candidate.symbol
      OR role_mapping.effective_from <> candidate.effective_from
      OR role_mapping.effective_to <> candidate.effective_to
      OR role_mapping.status <> 'CLOSED';
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'An existing legacy account-role mapping conflicts with underwriting history';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_underwriting_contract contract
    JOIN tmp_stock_legacy_underwriting_history candidate
      ON candidate.symbol = contract.symbol
   WHERE contract.contract_code <> candidate.contract_code
      OR contract.corporate_action_id IS NOT NULL
      OR contract.participant_id <> underwriter_participant_id
      OR contract.account_id <> candidate.legacy_account_id
      OR contract.total_issue_quantity <> candidate.issued_shares
      OR contract.tradable_allocation_quantity <> candidate.tradable_shares
      OR contract.locked_allocation_quantity <> 0
      OR contract.external_allocation_quantity <> 0
      OR contract.underwritten_quantity <> candidate.tradable_shares
      OR contract.issue_price <> candidate.issue_price
      OR contract.underwriting_type <> 'FIRM_COMMITMENT'
      OR contract.stabilization_start_date IS NOT NULL
      OR contract.stabilization_end_date IS NOT NULL
      OR contract.stabilization_quantity_limit <> 0
      OR contract.stabilization_amount_limit <> 0
      OR contract.status <> 'COMPLETED'
      OR contract.policy_version <> 1;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'An existing underwriting contract conflicts with reconstructed history';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM (
        SELECT contract.symbol
          FROM stock_underwriting_contract contract
          JOIN tmp_stock_legacy_underwriting_history candidate
            ON candidate.symbol = contract.symbol
         GROUP BY contract.symbol
        HAVING COUNT(*) > 1
    ) duplicate_contract;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A legacy symbol has more than one underwriting contract';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_underwriting_contract contract
    JOIN tmp_stock_legacy_underwriting_history candidate
      ON candidate.legacy_account_id = contract.account_id
   WHERE contract.symbol <> candidate.symbol;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A historical underwriting account is linked to another symbol contract';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM stock_market_policy_version policy
    JOIN tmp_stock_legacy_underwriting_history candidate
      ON policy.policy_scope = 'UNDERWRITING_CONTRACT'
     AND policy.scope_key = candidate.contract_code
     AND policy.version_no = 1
   WHERE policy.effective_business_date <> candidate.effective_from
      OR policy.status <> 'RETIRED'
      OR policy.changed_by <> 'legacy-underwriting-history-backfill';
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'An existing underwriting policy conflicts with reconstructed history';
  END IF;

  UPDATE stock_account legacy_account
  JOIN tmp_stock_legacy_underwriting_history candidate
    ON candidate.legacy_account_id = legacy_account.id
     SET legacy_account.account_code = candidate.account_code,
         legacy_account.participant_category = 'ISSUE_UNDERWRITER',
         legacy_account.self_trade_group_id = 'ISSUE_UNDERWRITER:DEFAULT',
         legacy_account.updated_at = GREATEST(
             legacy_account.updated_at,
             candidate.completed_at
         );

  INSERT INTO stock_market_participant_account(
      participant_id, account_id, account_role, desk_code,
      effective_from, effective_to, status, created_at, updated_at
  )
  SELECT underwriter_participant_id,
         candidate.legacy_account_id,
         'ISSUE_UNDERWRITER',
         candidate.symbol,
         candidate.effective_from,
         candidate.effective_to,
         'CLOSED',
         candidate.created_at,
         candidate.completed_at
    FROM tmp_stock_legacy_underwriting_history candidate
   WHERE NOT EXISTS (
       SELECT 1
         FROM stock_market_participant_account role_mapping
        WHERE role_mapping.account_id = candidate.legacy_account_id
   );

  INSERT INTO stock_underwriting_contract(
      contract_code, corporate_action_id, symbol,
      participant_id, account_id,
      total_issue_quantity, tradable_allocation_quantity,
      locked_allocation_quantity, external_allocation_quantity,
      underwritten_quantity, issue_price, underwriting_type,
      stabilization_start_date, stabilization_end_date,
      stabilization_quantity_limit, stabilization_amount_limit,
      status, policy_version, created_at, updated_at
  )
  SELECT candidate.contract_code,
         NULL,
         candidate.symbol,
         underwriter_participant_id,
         candidate.legacy_account_id,
         candidate.issued_shares,
         candidate.tradable_shares,
         0,
         0,
         candidate.tradable_shares,
         candidate.issue_price,
         'FIRM_COMMITMENT',
         NULL,
         NULL,
         0,
         0.00,
         'COMPLETED',
         1,
         candidate.created_at,
         candidate.completed_at
    FROM tmp_stock_legacy_underwriting_history candidate
   WHERE NOT EXISTS (
       SELECT 1
         FROM stock_underwriting_contract contract
        WHERE contract.symbol = candidate.symbol
   );

  INSERT INTO stock_security_allocation_ledger(
      idempotency_key, event_type, corporate_action_id,
      underwriting_contract_id, source_account_id,
      destination_account_id, symbol, quantity, unit_price,
      allocation_reason, tradability_status,
      effective_business_date, unlock_business_date, created_at
  )
  SELECT CONCAT(
             'HISTORICAL-INITIAL-ISSUE:',
             candidate.symbol,
             ':TRADABLE'
         ),
         'INITIAL_ISSUE',
         NULL,
         contract.id,
         NULL,
         candidate.legacy_account_id,
         candidate.symbol,
         candidate.tradable_shares,
         candidate.issue_price,
         'INITIAL_FLOAT_UNDERWRITER',
         'TRADABLE',
         candidate.effective_from,
         NULL,
         candidate.created_at
    FROM tmp_stock_legacy_underwriting_history candidate
    JOIN stock_underwriting_contract contract
      ON contract.symbol = candidate.symbol
     AND contract.contract_code = candidate.contract_code
   WHERE NOT EXISTS (
       SELECT 1
         FROM stock_security_allocation_ledger allocation
        WHERE allocation.idempotency_key = CONCAT(
                  'HISTORICAL-INITIAL-ISSUE:',
                  candidate.symbol,
                  ':TRADABLE'
              )
   );

  INSERT INTO stock_market_policy_version(
      policy_scope, scope_key, version_no,
      effective_business_date, status, config_json,
      change_reason, changed_by, created_at, updated_at
  )
  SELECT 'UNDERWRITING_CONTRACT',
         candidate.contract_code,
         1,
         candidate.effective_from,
         'RETIRED',
         JSON_OBJECT(
             'preset', 'HISTORICAL_COMPLETED_UNDERWRITING_V1',
             'symbol', candidate.symbol,
             'historicalBackfill', TRUE,
             'assetMutation', FALSE,
             'hotLedgerMutation', FALSE
         ),
         'Reconstruct completed issue-underwriting history without changing current assets',
         'legacy-underwriting-history-backfill',
         candidate.created_at,
         candidate.completed_at
    FROM tmp_stock_legacy_underwriting_history candidate
   WHERE NOT EXISTS (
       SELECT 1
         FROM stock_market_policy_version policy
        WHERE policy.policy_scope = 'UNDERWRITING_CONTRACT'
          AND policy.scope_key = candidate.contract_code
          AND policy.version_no = 1
   );

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
    JOIN stock_account legacy_account
      ON legacy_account.id = candidate.legacy_account_id
    LEFT JOIN stock_market_participant_account role_mapping
      ON role_mapping.account_id = candidate.legacy_account_id
     AND role_mapping.participant_id = underwriter_participant_id
    LEFT JOIN stock_underwriting_contract contract
      ON contract.symbol = candidate.symbol
     AND contract.contract_code = candidate.contract_code
     AND contract.account_id = candidate.legacy_account_id
    LEFT JOIN stock_security_allocation_ledger history_allocation
      ON history_allocation.idempotency_key = CONCAT(
             'HISTORICAL-INITIAL-ISSUE:',
             candidate.symbol,
             ':TRADABLE'
         )
    LEFT JOIN stock_market_policy_version policy
      ON policy.policy_scope = 'UNDERWRITING_CONTRACT'
     AND policy.scope_key = candidate.contract_code
     AND policy.version_no = 1
    LEFT JOIN (
        SELECT allocation.underwriting_contract_id,
               SUM(allocation.quantity) AS total_quantity,
               SUM(
                   CASE
                     WHEN allocation.tradability_status = 'TRADABLE'
                     THEN allocation.quantity
                     ELSE 0
                   END
               ) AS tradable_quantity,
               SUM(
                   CASE
                     WHEN allocation.tradability_status = 'LOCKED'
                     THEN allocation.quantity
                     ELSE 0
                   END
               ) AS locked_quantity
          FROM stock_security_allocation_ledger allocation
         WHERE allocation.event_type = 'INITIAL_ISSUE'
           AND allocation.source_account_id IS NULL
         GROUP BY allocation.underwriting_contract_id
    ) allocation_sum
      ON allocation_sum.underwriting_contract_id = contract.id
   WHERE legacy_account.status <> 'CLOSED'
      OR legacy_account.participant_category <> 'ISSUE_UNDERWRITER'
      OR legacy_account.self_trade_group_id <> 'ISSUE_UNDERWRITER:DEFAULT'
      OR legacy_account.account_code <> candidate.account_code
      OR role_mapping.id IS NULL
      OR role_mapping.account_role <> 'ISSUE_UNDERWRITER'
      OR role_mapping.desk_code <> candidate.symbol
      OR role_mapping.status <> 'CLOSED'
      OR role_mapping.effective_from <> candidate.effective_from
      OR role_mapping.effective_to <> candidate.effective_to
      OR contract.id IS NULL
      OR contract.status <> 'COMPLETED'
      OR history_allocation.id IS NULL
      OR history_allocation.event_type <> 'INITIAL_ISSUE'
      OR history_allocation.corporate_action_id IS NOT NULL
      OR history_allocation.underwriting_contract_id <> contract.id
      OR history_allocation.source_account_id IS NOT NULL
      OR history_allocation.destination_account_id
           <> candidate.legacy_account_id
      OR history_allocation.symbol <> candidate.symbol
      OR history_allocation.quantity <> candidate.tradable_shares
      OR history_allocation.unit_price <> candidate.issue_price
      OR history_allocation.allocation_reason
           <> 'INITIAL_FLOAT_UNDERWRITER'
      OR history_allocation.tradability_status <> 'TRADABLE'
      OR history_allocation.effective_business_date
           <> candidate.effective_from
      OR history_allocation.unlock_business_date IS NOT NULL
      OR policy.id IS NULL
      OR policy.effective_business_date <> candidate.effective_from
      OR policy.status <> 'RETIRED'
      OR policy.changed_by <> 'legacy-underwriting-history-backfill'
      OR COALESCE(allocation_sum.total_quantity, 0) <> candidate.issued_shares
      OR COALESCE(allocation_sum.tradable_quantity, 0)
           <> candidate.tradable_shares
      OR COALESCE(allocation_sum.locked_quantity, 0) <> 0;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Legacy underwriting history backfill post-condition failed';
  END IF;

  SELECT COUNT(*)
    INTO violation_count
    FROM tmp_stock_legacy_underwriting_history candidate
    LEFT JOIN (
        SELECT holding.symbol, SUM(holding.quantity) AS holding_quantity
          FROM stock_holding holding
         GROUP BY holding.symbol
    ) holding_sum
      ON holding_sum.symbol = candidate.symbol
   WHERE COALESCE(holding_sum.holding_quantity, 0) <> candidate.issued_shares;
  IF violation_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Issued-share reconciliation failed after underwriting history backfill';
  END IF;

  COMMIT;
  DROP TEMPORARY TABLE IF EXISTS tmp_stock_legacy_underwriting_history;
END//
DELIMITER ;

CALL backfill_stock_legacy_underwriting_history();

DROP PROCEDURE IF EXISTS backfill_stock_legacy_underwriting_history;
