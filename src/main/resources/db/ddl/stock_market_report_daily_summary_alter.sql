USE STOCK_SERVICE;

DROP PROCEDURE IF EXISTS add_stock_market_report_column;

DELIMITER //
CREATE PROCEDURE add_stock_market_report_column(IN column_name_value VARCHAR(64), IN alter_sql_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order_book_daily_snapshot'
       AND column_name = column_name_value
  ) THEN
    SET @add_column_sql = alter_sql_value;
    PREPARE add_column_stmt FROM @add_column_sql;
    EXECUTE add_column_stmt;
    DEALLOCATE PREPARE add_column_stmt;
  END IF;
END//
DELIMITER ;

CALL add_stock_market_report_column('open_price', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN open_price DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER turnover_amount');
CALL add_stock_market_report_column('high_price', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN high_price DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER open_price');
CALL add_stock_market_report_column('low_price', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN low_price DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER high_price');
CALL add_stock_market_report_column('last_execution_price', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN last_execution_price DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER low_price');
CALL add_stock_market_report_column('first_executed_at', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN first_executed_at DATETIME NULL AFTER pending_corporate_action_count');

DROP PROCEDURE IF EXISTS add_stock_market_report_column;

DROP PROCEDURE IF EXISTS align_stock_market_report_price_check;

DELIMITER //
CREATE PROCEDURE align_stock_market_report_price_check()
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_snapshot_price'
  ) THEN
    ALTER TABLE stock_order_book_daily_snapshot
      DROP CHECK chk_stock_order_book_daily_snapshot_price;
  END IF;

  ALTER TABLE stock_order_book_daily_snapshot
    ADD CONSTRAINT chk_stock_order_book_daily_snapshot_price
    CHECK (
      close_price >= 0
      AND previous_close >= 0
      AND initial_price > 0
      AND open_price >= 0
      AND high_price >= 0
      AND low_price >= 0
      AND last_execution_price >= 0
    );
END//
DELIMITER ;

CALL align_stock_market_report_price_check();

DROP PROCEDURE IF EXISTS align_stock_market_report_price_check;

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
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_execution_daily_account_run_symbol_account (close_run_id, symbol, account_id),
  KEY idx_stock_execution_daily_account_symbol_date (symbol, simulation_trade_date, close_run_id, account_id),
  KEY idx_stock_execution_daily_account_account_date (account_id, simulation_trade_date, close_run_id),
  CONSTRAINT chk_stock_execution_daily_account_category CHECK (CASE `participant_category` WHEN 'LISTING_UNDERWRITER' THEN 1 WHEN 'AUTO_PARTICIPANT' THEN 1 WHEN 'MANUAL_PARTICIPANT' THEN 1 ELSE 0 END = 1),
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

WITH ranked_execution AS (
    SELECT e.symbol,
           DATE(e.executed_at) AS trade_date,
           e.price,
           ROW_NUMBER() OVER (
               PARTITION BY e.symbol, DATE(e.executed_at)
               ORDER BY e.executed_at ASC, e.id ASC
           ) AS open_rank,
           ROW_NUMBER() OVER (
               PARTITION BY e.symbol, DATE(e.executed_at)
               ORDER BY e.executed_at DESC, e.id DESC
           ) AS close_rank
      FROM stock_execution e
     WHERE e.source = 'INTERNAL_ORDER_BOOK'
       AND e.side = 'BUY'
), daily_price AS (
    SELECT symbol,
           trade_date,
           MAX(CASE WHEN open_rank = 1 THEN price END) AS open_price,
           MAX(price) AS high_price,
           MIN(price) AS low_price,
           MAX(CASE WHEN close_rank = 1 THEN price END) AS last_execution_price
      FROM ranked_execution
     GROUP BY symbol, trade_date
)
UPDATE stock_order_book_daily_snapshot snapshot
JOIN daily_price price
  ON price.symbol = snapshot.symbol
 AND price.trade_date = snapshot.simulation_trade_date
   SET snapshot.open_price = price.open_price,
       snapshot.high_price = price.high_price,
       snapshot.low_price = price.low_price,
       snapshot.last_execution_price = price.last_execution_price;

UPDATE stock_order_book_daily_snapshot snapshot
JOIN (
    SELECT symbol,
           DATE(executed_at) AS trade_date,
           MIN(executed_at) AS first_executed_at
      FROM stock_execution
     WHERE source = 'INTERNAL_ORDER_BOOK'
     GROUP BY symbol, DATE(executed_at)
) execution_time
  ON execution_time.symbol = snapshot.symbol
 AND execution_time.trade_date = snapshot.simulation_trade_date
   SET snapshot.first_executed_at = execution_time.first_executed_at;

INSERT IGNORE INTO stock_execution_daily_account_snapshot(
    close_run_id, symbol, simulation_trade_date, account_id, participant_category,
    execution_count, buy_quantity, sell_quantity, buy_amount, sell_amount,
    net_cash_flow, execution_amount, created_at
)
SELECT snapshot.close_run_id,
       execution.symbol,
       snapshot.simulation_trade_date,
       execution.account_id,
       CASE
         WHEN listing_config.user_key IS NOT NULL THEN 'LISTING_UNDERWRITER'
         WHEN participant.user_key IS NOT NULL THEN 'AUTO_PARTICIPANT'
         ELSE 'MANUAL_PARTICIPANT'
       END AS participant_category,
       COUNT(*),
       SUM(CASE WHEN execution.side = 'BUY' THEN execution.quantity ELSE 0 END),
       SUM(CASE WHEN execution.side = 'SELL' THEN execution.quantity ELSE 0 END),
       SUM(CASE WHEN execution.side = 'BUY' THEN execution.gross_amount ELSE 0 END),
       SUM(CASE WHEN execution.side = 'SELL' THEN execution.gross_amount ELSE 0 END),
       SUM(CASE WHEN execution.side = 'BUY' THEN -execution.net_amount ELSE execution.net_amount END),
       SUM(execution.gross_amount),
       snapshot.snapshot_at
  FROM stock_order_book_daily_snapshot snapshot
  JOIN stock_market_close_run close_run
    ON close_run.id = snapshot.close_run_id
   AND close_run.symbol IS NULL
   AND close_run.status = 'COMPLETED'
  JOIN stock_execution execution
    ON execution.symbol = snapshot.symbol
   AND execution.source = 'INTERNAL_ORDER_BOOK'
   AND execution.executed_at >= CAST(snapshot.simulation_trade_date AS DATETIME)
   AND execution.executed_at < DATE_ADD(CAST(snapshot.simulation_trade_date AS DATETIME), INTERVAL 1 DAY)
  JOIN stock_account account ON account.id = execution.account_id
  LEFT JOIN stock_listing_auto_account_config listing_config
    ON listing_config.user_key = account.user_key
   AND listing_config.symbol = execution.symbol
  LEFT JOIN stock_auto_participant participant
    ON participant.user_key = account.user_key
 GROUP BY snapshot.close_run_id,
          execution.symbol,
          snapshot.simulation_trade_date,
          execution.account_id,
          participant_category,
          snapshot.snapshot_at;

REPLACE INTO stock_execution_account_day_summary(
    simulation_trade_date, account_id, execution_count, buy_quantity,
    sell_quantity, gross_amount, buy_gross_amount, sell_gross_amount,
    buy_net_amount, sell_net_amount, fee_amount, tax_amount,
    realized_profit, last_executed_at, updated_at
)
SELECT DATE(executed_at),
       account_id,
       COUNT(*),
       SUM(CASE WHEN side = 'BUY' THEN quantity ELSE 0 END),
       SUM(CASE WHEN side = 'SELL' THEN quantity ELSE 0 END),
       SUM(gross_amount),
       SUM(CASE WHEN side = 'BUY' THEN gross_amount ELSE 0 END),
       SUM(CASE WHEN side = 'SELL' THEN gross_amount ELSE 0 END),
       SUM(CASE WHEN side = 'BUY' THEN net_amount ELSE 0 END),
       SUM(CASE WHEN side = 'SELL' THEN net_amount ELSE 0 END),
       SUM(fee_amount),
       SUM(tax_amount),
       SUM(COALESCE(realized_profit, 0)),
       MAX(executed_at),
       NOW()
 FROM stock_execution
 WHERE source = 'INTERNAL_ORDER_BOOK'
 GROUP BY DATE(executed_at), account_id;
