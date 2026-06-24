INSERT INTO stock_instrument(symbol, name, market, enabled, created_at)
VALUES ('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP);

INSERT INTO stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, tick_size, price_limit_rate, enabled, created_at, updated_at)
VALUES ('005930', '삼성전자 주문장', 'ORDERBOOK', 70000.00, 100000, 100000, 1.00, 30.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
VALUES ('005930', 72400.00, 70000.00, CURRENT_TIMESTAMP, 'smoke-seed');

INSERT INTO stock_price_tick(symbol, price, provider, price_time, created_at)
VALUES ('005930', 70000.00, 'smoke-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_virtual_market_config(symbol, enabled, market_status, updated_at)
VALUES ('005930', TRUE, 'OPEN', CURRENT_TIMESTAMP);

INSERT INTO stock_order_book_market_config(symbol, enabled, market_status, updated_at)
VALUES ('005930', TRUE, 'OPEN', CURRENT_TIMESTAMP);

INSERT INTO stock_account(user_key, cash_balance, created_at, updated_at)
VALUES ('h2-smoke-user', 1000000.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
SELECT id, 'DEPOSIT', 1000000.00, 'OPENING_GRANT', 'SYSTEM', CURRENT_TIMESTAMP
FROM stock_account
WHERE user_key = 'h2-smoke-user';
