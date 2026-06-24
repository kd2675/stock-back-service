# Order Book Execution

## 현재 구현

수요와 공급 주문 체결은 `ORDER_BOOK` 시장 주문을 내부 주문장으로 매칭한다. 이 시장의 종목은 관리자가 `stock_order_book_instrument`로 만든다.

- batch service: `InternalOrderBookExecutionService`
- scheduler: `OrderBookExecutionScheduler`
- 수동 job: `StockBatchJobController.executeOrderBookOrders`
- job orchestration: `StockBatchJobService.executeOrderBookOrders`
- front: `stock-front-service/app/supply-demand/page.tsx`

## 코드 흐름

1. `executeEligibleOrders`가 열린 주문이 있는 symbol을 조회한다.
2. symbol별로 `matchNext`를 반복한다.
3. `findBestBuyCandidates`는 시장가 우선, 높은 매수가 우선, 빠른 생성시간 우선으로 매수 후보를 잡는다.
4. `findBestSell`은 같은 userKey 주문을 제외한다.
5. 매칭 가능한 매도 후보가 있으면 남은 수량의 최소값만큼 체결한다.
6. 체결가는 현재 구현상 매도 지정가가 있으면 매도 지정가, 아니면 매수 지정가다.
7. 매수/매도 각각 `stock_execution`을 만들고 주문의 filled 상태를 갱신한다.
8. 마지막 체결가는 `stock_price`, `stock_price_tick`, Redis latest price, Redis pub/sub에 반영한다.
9. `stock_execution.source`는 `INTERNAL_ORDER_BOOK`로 기록한다.

## 현재 불변식

- 같은 사용자끼리 자전거래하지 않는다.
- 주문장 종목은 현재가 시장 종목과 공유하지 않는다.
- `stock_order_book_market_config.enabled=true`, `market_status='OPEN'`인 symbol만 체결한다.
- 체결가 반영은 Redis 실패와 무관하게 DB 원장을 우선한다.

## 앞으로 구현할 후보

- 단일가 매매.
- IOC/FOK/당일 주문 만료.
- 시장가 잔량 처리.
- 종목별 거래정지와 시장 전체 거래정지 분리.
- 체결 우선순위와 가격 결정 규칙 상세화.

## 변경 순서

1. 가격/시간 우선순위 정책을 문서에 먼저 고정한다.
2. `findBestBuyCandidates`, `findBestSell`, `resolveExecutionPrice`를 수정한다.
3. 주문 상태/잔량 정책이 바뀌면 `updateOrderAfterFill` 계열 로직을 같이 본다.
4. 마지막 체결가와 Redis 발행이 필요한지 확인한다.
5. front `/supply-demand`는 `orders?marketType=ORDER_BOOK`, `executions?source=INTERNAL_ORDER_BOOK` 필터를 유지한다.

## 검증

- `InternalOrderBookExecutionServiceTest`
- `StockOrderRepositoryOrderBookTest`
- `ExecutionCostAccountingTest`
- `./gradlew :stock-batch-service:test`
- `cd stock-front-service && npm run verify:contract && npm run lint && npm run build`
