USE STOCK_SERVICE;

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
  underwriter_account_id BIGINT NOT NULL,
  quantity BIGINT NOT NULL,
  source_average_price DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (withdrawal_id, symbol),
  KEY idx_stock_auto_share_return_underwriter (underwriter_account_id, symbol, withdrawal_id),
  CONSTRAINT chk_stock_auto_share_return_quantity CHECK (quantity > 0),
  CONSTRAINT chk_stock_auto_share_return_average_price CHECK (source_average_price >= 0)
);
