USE STOCK_SERVICE;

-- V3 확률 행동 canary의 관심 상태·주문량·체결률·목적 자금 대사용 읽기 전용 보고서입니다.
-- stock_order를 기간 범위로 읽으므로 정규장 primary DB에서 실행하지 않습니다.
-- 백엔드/배치가 종료된 유지보수 창 또는 읽기 복제본에서만 실행합니다.

SET @profile_v3_report_end_date := (
    SELECT MAX(business_date)
      FROM stock_post_close_cycle
     WHERE scope_type = 'FULL_MARKET'
       AND scope_key = 'ALL'
       AND cycle_kind = 'TRADING'
       AND phase = 'COMPLETED'
       AND status = 'COMPLETED'
);

SET @profile_v3_report_start_date := (
    SELECT MIN(recent.business_date)
      FROM (
          SELECT business_date
            FROM stock_post_close_cycle
           WHERE scope_type = 'FULL_MARKET'
             AND scope_key = 'ALL'
             AND cycle_kind = 'TRADING'
             AND phase = 'COMPLETED'
             AND status = 'COMPLETED'
             AND business_date <= @profile_v3_report_end_date
           ORDER BY business_date DESC
           LIMIT 20
      ) recent
);

SELECT 'REPORT_RANGE' AS section,
       @profile_v3_report_start_date AS start_business_date,
       @profile_v3_report_end_date AS end_business_date;

WITH expected_profile(profile_type) AS (
    SELECT 'NEWS_REACTIVE' UNION ALL SELECT 'MOMENTUM_FOLLOWER' UNION ALL
    SELECT 'CONTRARIAN' UNION ALL SELECT 'LOSS_AVERSE' UNION ALL
    SELECT 'OVERCONFIDENT' UNION ALL SELECT 'HERD_FOLLOWER' UNION ALL
    SELECT 'PASSIVE_LIMIT_TRADER' UNION ALL SELECT 'NOISE_TRADER' UNION ALL
    SELECT 'VALUE_ANCHOR' UNION ALL SELECT 'SCALPER' UNION ALL
    SELECT 'DAY_TRADER' UNION ALL SELECT 'SWING_TRADER' UNION ALL
    SELECT 'LONG_TERM_HOLDER' UNION ALL SELECT 'PAYDAY_ACCUMULATOR' UNION ALL
    SELECT 'DIVIDEND_REINVESTOR' UNION ALL SELECT 'LIMIT_DOWN_TRAPPED' UNION ALL
    SELECT 'AVERAGE_DOWN_BUYER' UNION ALL SELECT 'STOP_LOSS_TRADER' UNION ALL
    SELECT 'FOMO_BUYER' UNION ALL SELECT 'PANIC_SELLER' UNION ALL
    SELECT 'DIP_BUYER' UNION ALL SELECT 'PROFIT_LOCKER' UNION ALL
    SELECT 'LIQUIDITY_AVOIDANT' UNION ALL SELECT 'CASH_DEFENSIVE' UNION ALL
    SELECT 'WHALE' UNION ALL SELECT 'SMALL_DIVERSIFIER' UNION ALL
    SELECT 'OBSERVER'
), participant AS (
    SELECT participant.profile_type,
           COUNT(*) AS total_count,
           SUM(enabled = 1 AND withdrawn_at IS NULL) AS active_count,
           SUM(COALESCE(profile_config.behavior_model_version, 'V3') = 'V3'
               AND enabled = 1 AND withdrawn_at IS NULL) AS v3_active_count,
           SUM(COALESCE(profile_config.behavior_model_version, 'V3') <> 'V3'
               AND enabled = 1 AND withdrawn_at IS NULL) AS invalid_model_count
      FROM stock_auto_participant participant
      LEFT JOIN stock_auto_participant_profile_config profile_config
        ON profile_config.profile_type = participant.profile_type
     GROUP BY participant.profile_type
)
SELECT 'PROFILE_CONTRACT' AS section,
       expected.profile_type,
       COALESCE(participant.total_count, 0) AS participant_count,
       COALESCE(participant.active_count, 0) AS active_count,
       COALESCE(participant.v3_active_count, 0) AS v3_active_count,
       COALESCE(participant.invalid_model_count, 0) AS invalid_model_count,
       profile_config.profile_type IS NOT NULL AS profile_config_present,
       COALESCE(profile_config.behavior_model_version, 'V3') AS behavior_model_version,
       profile_config.decision_frequency_multiplier,
       profile_config.orders_per_decision_multiplier,
       profile_config.pricing_mode,
       profile_config.exit_mode,
       profile_config.inventory_mode,
       profile_config.updated_at AS policy_updated_at
  FROM expected_profile expected
  LEFT JOIN participant ON participant.profile_type = expected.profile_type
  LEFT JOIN stock_auto_participant_profile_config profile_config
    ON profile_config.profile_type = expected.profile_type
 ORDER BY expected.profile_type;

SELECT 'PARTICIPANT_MODEL_EXPORT' AS section,
       participant.user_key,
       participant.profile_type,
       participant.enabled,
       COALESCE(profile_config.behavior_model_version, 'V3') AS behavior_model_version,
       participant.behavior_seed,
       participant.recurring_cash_amount,
       participant.recurring_cash_interval_value,
       participant.recurring_cash_interval_unit,
       participant.withdrawn_at,
       participant.updated_at
  FROM stock_auto_participant participant
  LEFT JOIN stock_auto_participant_profile_config profile_config
    ON profile_config.profile_type = participant.profile_type
 ORDER BY participant.profile_type, participant.user_key;

SELECT 'V3_POLICY_REVISION' AS section,
       policy_version,
       status,
       effective_trade_date,
       runtime_enabled,
       runtime_change_reason,
       runtime_changed_by,
       runtime_changed_at,
       created_by,
       created_at,
       activated_at,
       retired_at
  FROM stock_auto_participant_policy_revision
 ORDER BY policy_version DESC;

SELECT 'V3_DAILY_BEHAVIOR' AS section,
       simulation_trade_date,
       policy_version,
       activity_state,
       activity_session,
       COUNT(*) AS account_count,
       ROUND(AVG(fatigue_score), 6) AS average_fatigue_score,
       SUM(submitted_order_count) AS submitted_order_count,
       SUM(submitted_notional) AS submitted_notional,
       SUM(observed_execution_count) AS observed_execution_count,
       SUM(observed_execution_notional) AS observed_execution_notional,
       SUM(observed_cancel_count) AS observed_cancel_count
  FROM stock_auto_participant_daily_behavior_state
 WHERE simulation_trade_date >= @profile_v3_report_start_date
   AND simulation_trade_date <= @profile_v3_report_end_date
 GROUP BY simulation_trade_date, policy_version, activity_state, activity_session
 ORDER BY simulation_trade_date, policy_version, activity_state, activity_session;

SELECT 'V3_LIQUIDATION_INCOMPLETE' AS section,
       simulation_trade_date,
       urgency,
       status,
       COUNT(*) AS plan_count,
       SUM(target_quantity) AS target_quantity,
       SUM(submitted_quantity) AS submitted_quantity,
       SUM(filled_quantity) AS filled_quantity
  FROM stock_auto_participant_liquidation_plan
 WHERE simulation_trade_date >= @profile_v3_report_start_date
   AND simulation_trade_date <= @profile_v3_report_end_date
   AND status <> 'COMPLETED'
 GROUP BY simulation_trade_date, urgency, status
 ORDER BY simulation_trade_date, urgency, status;

WITH order_base AS (
    SELECT COALESCE(stock_order.auto_profile_type, participant.profile_type, 'UNCLASSIFIED') AS profile_type,
           COALESCE(stock_order.auto_behavior_model_version, 'UNVERSIONED') AS model_version,
           DATE(stock_order.created_at) AS business_date,
           stock_order.account_id,
           stock_order.side,
           stock_order.quantity,
           stock_order.filled_quantity
      FROM stock_order
      LEFT JOIN stock_account account ON account.id = stock_order.account_id
      LEFT JOIN stock_auto_participant participant ON participant.user_key = account.user_key
     WHERE stock_order.market_type = 'ORDER_BOOK'
       AND stock_order.created_at >= TIMESTAMP(@profile_v3_report_start_date, '00:00:00')
       AND stock_order.created_at < TIMESTAMP(DATE_ADD(@profile_v3_report_end_date, INTERVAL 1 DAY), '00:00:00')
       AND (stock_order.auto_profile_type IS NOT NULL OR participant.user_key IS NOT NULL)
)
SELECT 'ORDER_CANARY' AS section,
       profile_type,
       model_version,
       COUNT(DISTINCT account_id) AS account_count,
       COUNT(DISTINCT business_date) AS observed_trading_days,
       COUNT(*) AS order_count,
       ROUND(COUNT(*) / NULLIF(COUNT(DISTINCT account_id) * COUNT(DISTINCT business_date), 0), 4)
           AS orders_per_account_day,
       ROUND(AVG(quantity), 4) AS average_order_quantity,
       ROUND(SUM(filled_quantity) / NULLIF(SUM(quantity), 0) * 100, 4) AS quantity_fill_rate_percent,
       SUM(side = 'BUY') AS buy_order_count,
       SUM(side = 'SELL') AS sell_order_count
  FROM order_base
 GROUP BY profile_type, model_version
 ORDER BY profile_type, model_version;

SELECT 'FUNDING_RECONCILIATION' AS section,
       budget_type,
       status,
       COUNT(*) AS budget_count,
       SUM(granted_amount) AS granted_amount,
       SUM(available_amount) AS available_amount,
       SUM(reserved_amount) AS reserved_amount,
       SUM(spent_amount) AS spent_amount,
       SUM(granted_amount - available_amount - reserved_amount - spent_amount) AS unreconciled_amount
  FROM stock_auto_participant_funding_budget
 GROUP BY budget_type, status
 ORDER BY budget_type, status;
