INSERT INTO stock_instrument(symbol, name, market, enabled, created_at)
VALUES ('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP);

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
VALUES ('005930', 72400.00, 70000.00, CURRENT_TIMESTAMP, 'smoke-seed');

INSERT INTO stock_price_tick(symbol, price, provider, price_time, created_at)
VALUES ('005930', 70000.00, 'smoke-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
