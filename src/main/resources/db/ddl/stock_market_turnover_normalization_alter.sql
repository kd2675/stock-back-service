USE STOCK_SERVICE;

-- INTERNAL_ORDER_BOOK 체결은 매수자와 매도자 원장 행을 한 쌍으로 저장한다.
-- 이전 장마감 스냅샷은 두 행을 모두 시장 체결수/거래량/거래대금에 합산했으므로
-- BUY 행 한쪽을 시장 체결 기준으로 삼는 새 집계 계약에 맞춰 한 번만 정규화한다.
-- execution_quantity 조건 덕분에 이미 정규화된 행에는 다시 적용되지 않는다.
UPDATE stock_order_book_daily_snapshot
   SET execution_count = execution_count / 2,
       execution_quantity = buy_quantity,
       turnover_amount = ROUND(turnover_amount / 2, 2)
 WHERE execution_count > 0
   AND MOD(execution_count, 2) = 0
   AND buy_quantity = sell_quantity
   AND execution_quantity = buy_quantity + sell_quantity;
