# Feature Change Playbooks

이 문서는 stock 모의투자 기능을 바꿀 때의 작업 순서와 검증 기준을 정리한다. 각 항목은 독립적으로 읽고 개발을 시작할 수 있도록 작성한다.

## 1. 주문 접수 기능 변경

현재 구현:

- API: `POST /api/stock/v1/orders`
- back: `TradingController.placeOrder`, `TradingService.placeOrder`
- 원장: `stock_order`, `stock_account`, `stock_holding`
- 시장 구분: `OrderRequest.marketType`, `StockOrder.marketType`

변경 순서:

1. `OrderRequest`에 필요한 필드를 추가한다.
2. `TradingService.validateOrderRequest`에서 필수값과 값 범위를 검증한다.
3. 예약금/예약수량 계산이 바뀌면 `calculateReservedCash`, holding reservation 경계를 수정한다.
4. `StockOrder`에 새 상태나 컬럼이 필요하면 DDL을 먼저 확정한다.
5. `stock-front-service/app/types/stock.ts`, `app/lib/stock.ts`, 관련 화면 입력 validation을 갱신한다.
6. `TradingServiceTest`에 현금/보유/주문 원장 불변식 테스트를 추가한다.

검증:

- `./gradlew :stock-back-service:test`
- `npm run lint`
- `npm run build`

주의:

- 주문 접수 API에서 체결을 확정하지 않는다.
- 매수는 현금, 매도는 보유수량을 원장 기준으로 잠근다.
- 같은 `clientOrderId` idempotency는 유지한다.

## 2. 주문 정정/취소 기능 변경

현재 구현:

- 전체 취소: `DELETE /api/stock/v1/orders/{orderId}`
- 정정: `PATCH /api/stock/v1/orders/{orderId}`
- 부분 취소: `POST /api/stock/v1/orders/{orderId}/cancel`
- back: `TradingService.cancelOrder`, `amendOrder`, `cancelOrderPartially`

변경 순서:

1. 허용 상태를 `PENDING`, `PARTIALLY_FILLED` 외로 넓힐지 먼저 정한다.
2. 매수 주문은 남은 `reserved_cash`, 매도 주문은 남은 `reserved_quantity` 불변식을 계산한다.
3. 정정 이력이 필요하면 `stock_order_event` 같은 append-only table을 먼저 설계한다.
4. 프론트는 응답을 다시 조회해 화면을 갱신한다.

검증:

- 매수 정정 시 추가 현금 부족이면 기존 예약금이 변하지 않아야 한다.
- 매도 정정 시 보유 가능 수량 초과면 예약수량이 변하지 않아야 한다.
- 부분 취소 후 남은 수량이 0이면 전체 취소와 같은 상태가 되어야 한다.

## 3. 현재가 기준 체결 변경

현재 구현:

- batch: `OrderExecutionService`
- scheduler: `VirtualPriceExecutionScheduler`
- 대상: `market_type='VIRTUAL_PRICE'`
- 가격 기준: `stock_price.current_price`

변경 순서:

1. 체결 대상 조건을 SQL에서 먼저 확인한다.
2. 시장가/지정가 조건을 바꾸면 매수와 매도 모두 테스트한다.
3. 비용 정책이 바뀌면 `ExecutionCostCalculator`를 먼저 수정한다.
4. 체결 원장 필드를 바꾸면 back DTO와 front type을 함께 바꾼다.
5. 수동 job API와 scheduler가 같은 service를 호출하는지 확인한다.

검증:

- `OrderExecutionServiceTest`
- `ExecutionCostAccountingTest`
- `TradingService.getProfitSummary` 회귀 테스트

주의:

- 현재 구현은 남은 수량 전체 체결이다.
- 부분체결을 넣으려면 `filled_quantity`, `reserved_cash`, holding 평균단가 계산을 함께 재검토한다.

## 4. 수요/공급 주문장 체결 변경

현재 구현:

- batch: `InternalOrderBookExecutionService`
- scheduler: `OrderBookExecutionScheduler`
- 대상: `market_type='ORDER_BOOK'`
- 우선순위: 가격 우선, 시간 우선
- 자전거래 방지: 같은 `user_key`끼리는 매칭하지 않는다.

변경 순서:

1. 매칭 후보 SQL의 정렬과 lock 범위를 먼저 확인한다.
2. 시장가 잔량 정책을 day order, IOC, FOK 중 하나로 명시한다.
3. 체결가 결정 규칙을 문서에 먼저 쓰고 service를 수정한다.
4. 마지막 체결가 반영이 필요하면 `stock_price`, `stock_price_tick`, Redis 발행을 함께 본다.
5. `/supply-demand` 화면은 `orders?marketType=ORDER_BOOK`, `executions?source=INTERNAL_ORDER_BOOK` 조회를 유지한다.

검증:

- `InternalOrderBookExecutionServiceTest`
- 주문장 depth repository test
- 수요/공급 화면 lint/build

주의:

- 주문장 종목은 `stock_order_book_instrument`가 기준이다.
- 현재가 시장 종목과 주문장 종목은 공유하지 않는다.

## 5. 시세 provider 변경

현재 구현:

- interface: `MarketPriceProvider`
- mock: `MockMarketPriceProvider`
- KIS: `KisMarketPriceProvider`
- writer: `MarketDataRefreshService`

변경 순서:

1. provider 응답을 `MarketPriceQuote`로 정규화한다.
2. 0 이하 가격, 빈 symbol, provider 오류는 해당 symbol만 skip한다.
3. `stock_price`, `stock_price_tick`, Redis latest price, Redis pub/sub 갱신을 유지한다.
4. 외부 API credential은 환경 변수로만 받는다.
5. provider 단위 테스트와 refresh service 테스트를 추가한다.

검증:

- `./gradlew :stock-batch-service:test`
- Redis가 없어도 DB 갱신 테스트는 독립적으로 돌아야 한다.

## 6. 장 상태/거래정지 변경

현재 구현:

- enum: `MarketSessionStatus`
- config: `stock_virtual_market_config`, `stock_order_book_market_config`
- 상태: `OPEN`, `CLOSED`, `HALTED`

변경 순서:

1. 시장 전체 상태인지 종목별 상태인지 먼저 결정한다.
2. 신규 주문, 취소, 체결, 자동장 각각의 허용 정책을 표로 정한다.
3. back 주문 접수 guard, batch target SQL, front disabled state를 함께 바꾼다.
4. 관리자 API와 화면을 갱신한다.

검증:

- CLOSED/HALTED 신규 주문 거부
- CLOSED/HALTED 체결 job skip
- CLOSED/HALTED 자동장 주문 생성 skip
- 취소는 계속 허용

## 7. 자동장 변경

현재 구현:

- batch: `AutoMarketService`
- config: `stock_auto_market_config`
- participant: `stock_auto_participant`
- 자동 주문도 실제 `stock_order`에 저장된다.

변경 순서:

1. 자동 참여자 현금/보유 초기화 정책을 정한다.
2. 주문 생성 강도, 주문 수량, TTL 정책을 config에 반영한다.
3. 자동 주문 생성 후 주문장 체결 job을 같이 실행할지 결정한다.
4. 자동 참여자 주문이 사용자 주문과 같은 체결 엔진을 타는지 테스트한다.

검증:

- `AutoMarketServiceTest`
- 자동 주문 TTL 취소 시 예약금/예약수량 반환
- 장 정지 시 자동 주문 생성 없음

## 8. 기업 이벤트 변경

현재 구현:

- API 등록: `MarketService.applyCorporateAction`
- batch 적용: `CorporateActionService`
- 원장: `stock_corporate_action`, `stock_corporate_action_entitlement`
- 화면: `/supply-demand/admin`, 홈 기업 이벤트 패널

변경 순서:

1. 이벤트 타입을 `StockCorporateActionType`에 추가한다.
2. 필요한 날짜와 수치 필드를 `CorporateActionRequest`와 DDL에 추가한다.
3. 관리자 입력 validation을 `MarketService`에 추가한다.
4. 날짜별 상태 전이를 `CorporateActionService`에 추가한다.
5. 사용자별 배정/지급이 있으면 entitlement row를 만든다.
6. full DDL, alter DDL, H2 DDL을 모두 갱신한다.
7. 관리자 화면 입력과 이력 표시를 갱신한다.

검증:

- 등록 시 필수값 누락 거부
- 권리락/지급/상장일별 상태 전이
- 열린 주문 존재 시 적용 대기 또는 거부 정책
- 보유수량, 평균단가, 현금, 발행주식수 불변식

주의:

- 기업 이벤트는 API 호출 시점에 대부분 즉시 반영하지 않는다.
- 단주, 현금 보상, 권리 기준일을 정하지 않은 이벤트는 구현하지 않는다.

## 9. 수수료/세금/손익 변경

현재 구현:

- calculator: `ExecutionCostCalculator`
- 원장: `stock_execution.fee_amount`, `tax_amount`, `net_amount`, `realized_profit`
- 조회: `TradingService.getProfitSummary`

변경 순서:

1. 수수료율/세율 source를 설정값, DB table, 사용자 등급 중 어디에 둘지 결정한다.
2. 비용 계산은 `ExecutionCostCalculator`에만 둔다.
3. 매수 평균단가, 매도 실현손익 계산식을 문서에 먼저 쓴다.
4. `StockExecutionRepository.summarizeProfitByUserKey` projection을 갱신한다.
5. front 손익 지표와 체결 카드 표시를 갱신한다.

검증:

- 매수 평균단가가 수수료 포함 원가 기준인지
- 매도 실현손익이 `net - averagePrice * quantity`인지
- 누적 손익 API가 비용/세금/현금흐름을 맞게 합산하는지

## 10. 포트폴리오/정산/랭킹 변경

현재 구현:

- 실시간 조회: `TradingService.getPortfolio`
- 일별 정산: `PortfolioSettlementService`
- 랭킹 조회: `MarketService.getRankings`
- 원장: `portfolio_snapshot`

변경 순서:

1. 실시간 총자산 계산과 장마감 snapshot 계산식을 일치시킬지 결정한다.
2. 예약 매수 현금 포함 여부를 바꾸면 두 계산을 함께 바꾼다.
3. 랭킹 기준일/동점 처리 정책을 정한다.
4. front 상단 지표와 차트 표시를 갱신한다.

검증:

- 보유 가격이 없으면 평균단가 fallback
- 예약 매수 현금 포함 총자산
- snapshot 최신순 조회
- 랭킹 displayName fallback

## 11. 프론트 화면 변경

현재 구현:

- 홈: `app/page.tsx`
- 수요/공급: `app/supply-demand/page.tsx`
- 관리자: `app/supply-demand/admin/page.tsx`
- API client: `app/lib/stock.ts`
- type: `app/types/stock.ts`

변경 순서:

1. back DTO/type을 먼저 확인한다.
2. `app/types/stock.ts`를 갱신한다.
3. `app/lib/stock.ts` API function을 갱신한다.
4. 화면 state와 validation을 갱신한다.
5. 응답 누락/401 refresh/local-direct header 동작을 확인한다.
6. `npm run lint`, `npm run build`를 실행한다.

주의:

- 화면에서 체결 결과를 만들어 저장하지 않는다.
- 긴 숫자는 영역을 넘지 않도록 `tabular-nums`, grid, overflow 처리를 유지한다.
- 관리자 쓰기 화면만 숨겨서는 안 되고 back API 권한도 맞아야 한다.

## 12. DDL 변경

현재 구현:

- MySQL full DDL은 back/batch에 각각 있다.
- 운영 반영용 alter DDL도 있다.
- batch test와 smoke는 H2 DDL을 사용한다.

변경 순서:

1. full DDL을 먼저 수정한다.
2. 기존 DB용 alter DDL을 만든다.
3. H2 DDL과 H2 smoke data를 맞춘다.
4. Java entity/SQL row mapper를 수정한다.
5. DDL 정합성 테스트를 추가한다.

검증:

- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `git diff --check`

주의:

- back entity만 바꾸고 batch SQL을 안 바꾸면 runtime에서 깨진다.
- batch H2 test가 통과해도 MySQL alter 누락은 별도 확인해야 한다.
