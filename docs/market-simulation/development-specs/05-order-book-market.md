# Order Book Market

## 현재 구현

`ORDER_BOOK`은 사용자의 매수 주문과 매도 주문이 서로 만나야 체결되는 수요/공급 시장이다. 특정가격 자동주문체결과 달리 현재가만으로 체결하지 않는다.

주문장 종목은 admin이 생성한다. 기존 현재가 시장 종목과 공유하지 않는다.

## 관련 코드

back:

- `MarketController.createOrderBookInstrument`
- `MarketController.getOrderBookInstruments`
- `MarketController.getOrderBook`
- `TradingService.placeOrder`
- `TradingService.getOrders`
- `TradingService.getExecutions`
- `StockOrderBookInstrument`
- `StockOrderBookMarketConfig`

batch:

- `InternalOrderBookExecutionService.executeEligibleOrders`
- `OrderBookExecutionScheduler`

front:

- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`
- `stock-front-service/app/components/MarketModeTabs.tsx`

## 현재 플로우

1. admin이 `/supply-demand/admin`에서 종목 코드, 이름, 초기 가격, 발행주식수, 호가 단위, 가격제한폭을 입력한다.
2. back은 `stock_order_book_instrument`, `stock_order_book_market_config`, `stock_auto_market_config`, `stock_price`, `stock_corporate_action(INITIAL_ISSUE)`를 만든다.
3. 사용자는 `/supply-demand`에서 주문장 종목을 선택한다.
4. front는 주문에 `marketType: "ORDER_BOOK"`을 붙여 보낸다.
5. back은 주문 접수 시 장 상태와 주문 가격 규칙, 현금/보유 예약을 검증한다.
6. batch가 심볼별로 최우선 매수 후보와 최우선 매도 후보를 찾는다.
7. 자기 주문끼리는 매칭하지 않는다.
8. 가격/시간 우선으로 체결하고 부분체결을 허용한다.
9. 체결 가격은 현재 구현에서 매도 주문의 limit price가 우선이고, 매도 시장가면 매수 주문 limit price를 쓴다.
10. 체결 후 마지막 체결가를 `stock_price`와 `stock_price_tick`에 반영하고 Redis price event를 발행한다.

## 현재 불변식

- `ORDER_BOOK` 주문은 `stock_order.market_type = 'ORDER_BOOK'`이다.
- 주문장 종목은 `stock_order_book_instrument.enabled = true`여야 주문 가능하다.
- `stock_order_book_market_config.enabled = true`이고 `market_status = 'OPEN'`이어야 주문 접수와 체결이 가능하다.
- open order가 있으면 기업 이벤트 적용을 건너뛰거나 거절한다.

## 앞으로 구현할 후보

- 시장가-시장가 매칭 정책.
- 시초가/종가 동시호가.
- 주문 정정 시 시간 우선순위 재부여 정책.
- 호가 잔량 상세 depth 확장.
- 거래정지/상장폐지와 주문 취소 정책 연동.

## 바꿀 때 순서

1. 주문장 종목 계약을 바꾸면 admin form, DTO, DDL을 먼저 맞춘다.
2. 주문 접수 정책을 바꾸면 `TradingService`와 front validation을 같이 바꾼다.
3. 매칭 정책을 바꾸면 `InternalOrderBookExecutionService`와 order-book execution test를 먼저 고친다.
4. 가격 이벤트 정책을 바꾸면 Redis/SSE 문서와 `MarketDataRefreshService` 영향까지 본다.

## 검증

- `./gradlew :stock-back-service:test --tests '*StockOrderRepositoryOrderBookTest*'`
- `./gradlew :stock-batch-service:test --tests '*InternalOrderBookExecutionServiceTest*'`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`
