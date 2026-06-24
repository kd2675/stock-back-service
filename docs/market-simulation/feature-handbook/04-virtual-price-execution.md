# Virtual Price Execution

## 현재 구현

특정가격 자동주문체결은 `VIRTUAL_PRICE` 시장 주문을 DB 최신가와 비교해 batch에서 체결한다. 프론트 홈 화면이 이 시장을 사용한다.

- batch service: `OrderExecutionService`
- scheduler: `VirtualPriceExecutionScheduler`
- 수동 job: `StockBatchJobController.executeVirtualPriceOrders`
- job orchestration: `StockBatchJobService.executeVirtualPriceOrders`
- front: `stock-front-service/app/page.tsx`

## 코드 흐름

1. `OrderExecutionService.executeEligibleOrders`가 `stock_order`, `stock_price`, `stock_virtual_market_config`를 join한다.
2. 조건은 `status in ('PENDING','PARTIALLY_FILLED')`, `market_type='VIRTUAL_PRICE'`, `market_status='OPEN'`.
3. `MARKET` 주문은 즉시 체결 가능으로 본다.
4. `LIMIT BUY`는 현재가가 지정가 이하일 때 체결한다.
5. `LIMIT SELL`은 현재가가 지정가 이상일 때 체결한다.
6. 현재 구현은 남은 수량 전체를 한 번에 체결한다.
7. 매수는 보유수량을 늘리고 예약금 차액을 반환하거나 부족분을 추가 차감한다.
8. 매도는 보유수량과 예약수량을 줄이고 현금을 입금한다.
9. `stock_execution.source`는 `VIRTUAL_MARKET_PRICE`로 기록한다.

## 현재 불변식

- batch만 `stock_execution`을 만든다.
- 체결 후 주문은 `FILLED`, `reserved_cash=0`이 된다.
- 매도 체결 시 보유 부족이면 주문을 `REJECTED`로 돌린다.
- 수수료/세금/실현손익 계산은 `ExecutionCostCalculator`를 사용한다.

## 앞으로 구현할 후보

- 부분체결.
- 가격 refresh와 체결 실행 순서 보장.
- 장전/장마감 시간대별 체결 제한.
- 결제 예정/결제 완료 분리.

## 변경 순서

1. 체결 조건 SQL을 먼저 바꾼다.
2. 매수/매도 각각 원장 갱신식을 수정한다.
3. 비용 정책이 바뀌면 `ExecutionCostCalculator`부터 바꾼다.
4. `stock_execution` 컬럼이 바뀌면 back DTO와 front type을 같이 수정한다.
5. `StockBatchJobService`와 scheduler가 같은 service를 호출하는지 확인한다.

## 검증

- `OrderExecutionServiceTest`
- `ExecutionCostAccountingTest`
- `TradingServicePriceCacheTest`
- `./gradlew :stock-batch-service:test`
- front 홈 화면 변경 시 `npm run verify:contract`, `npm run lint`, `npm run build`
