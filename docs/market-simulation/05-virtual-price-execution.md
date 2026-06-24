# Virtual Price Execution

이 문서는 `VIRTUAL_PRICE` 시장의 현재가 기준 체결을 설명한다.

## 현재 역할

`VIRTUAL_PRICE` 주문은 외부 또는 mock 시세의 현재가를 기준으로 체결된다. 사용자는 홈 화면에서 이 시장으로 주문한다.

관련 코드:

- `stock-batch-service/src/main/java/stock/batch/service/execution/biz/OrderExecutionService.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/VirtualPriceExecutionScheduler.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/biz/StockBatchJobService.java`
- `stock-back-service/src/main/java/stock/back/service/trading/biz/TradingService.java`
- `stock-front-service/app/page.tsx`

## 체결 대상

batch는 아래 조건의 주문을 찾는다.

- `stock_order.status in ('PENDING', 'PARTIALLY_FILLED')`
- `stock_order.market_type = 'VIRTUAL_PRICE'`
- `stock_virtual_market_config.enabled = true`
- `stock_virtual_market_config.market_status = 'OPEN'`
- `stock_price`가 존재해야 함

## 체결 규칙

시장가:

- 현재가가 있으면 체결 대상이다.

지정가 매수:

- `current_price <= limit_price`면 체결한다.

지정가 매도:

- `current_price >= limit_price`면 체결한다.

현재 구현은 `VIRTUAL_PRICE`에서 남은 수량 전체를 한 번에 체결한다.

## 매수 체결

1. 실제 체결금액을 `current_price * remainingQuantity`로 계산한다.
2. 수수료를 더한 매수 원가를 계산한다.
3. 예약금보다 실제 원가가 적으면 차액을 계좌에 반환한다.
4. 예약금보다 실제 원가가 크면 추가 현금을 차감한다.
5. 추가 현금이 부족하면 주문을 `REJECTED`로 바꾸고 예약금을 돌려준다.
6. `stock_holding`을 insert/update한다. 평균단가는 수수료 포함 원가 기준이다.
7. `stock_execution`을 쓴다.
8. 주문을 `FILLED`로 바꾸고 `reserved_cash`를 0으로 만든다.

## 매도 체결

1. `stock_holding.quantity`와 `reserved_quantity`가 충분한지 확인한다.
2. 보유수량과 예약수량을 차감한다.
3. 매도 수수료와 거래세를 차감한 순입금액을 `stock_account.cash_balance`에 더한다.
4. 수량이 0이면 holding row를 삭제한다.
5. `stock_execution`에 비용과 실현손익을 쓴다.
6. 주문을 `FILLED`로 바꾼다.

## 현재 한계

- 부분체결이 없다.
- 수수료/세금은 설정 rate 기반이며 시장별 요율 테이블은 아직 없다.
- 결제 예정과 결제 완료가 분리되지 않았다.
- 종목별 `CLOSED`, `HALTED` 상태에서는 batch 체결 대상에서 제외된다.
- 가격제한폭은 주문 접수/정정 단계에서 검증하고, 체결 job은 이미 접수된 주문과 현재가만 비교한다.

## 다음에 바꿀 때 순서

1. 장전/장마감 주문 접수 정책이 필요하면 상태 enum과 batch 조건을 함께 확장한다.
2. 체결 즉시 계좌 반영을 유지할지, `stock_settlement`로 분리할지 결정한다.
3. 부분 체결을 허용하려면 체결 가능 수량 계산 규칙을 먼저 정한다.
