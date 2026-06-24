# Order Entry And Reservation

이 문서는 주문 접수와 현금/보유수량 예약 구조를 설명한다.

## 현재 역할

`stock-back-service`의 `TradingService`는 주문을 접수하고 원장에 남긴다. 실제 체결 여부는 batch가 판단한다.

관련 코드:

- `stock-back-service/src/main/java/stock/back/service/trading/act/TradingController.java`
- `stock-back-service/src/main/java/stock/back/service/trading/biz/TradingService.java`
- `stock-back-service/src/main/java/stock/back/service/trading/biz/AccountService.java`
- `stock-back-service/src/main/java/stock/back/service/trading/vo/OrderRequest.java`
- `stock-back-service/src/main/java/stock/back/service/trading/vo/OrderAmendRequest.java`
- `stock-back-service/src/main/java/stock/back/service/trading/vo/OrderCancelRequest.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockOrder.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockAccount.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockHolding.java`

## 주문 타입

`OrderSide`

- `BUY`
- `SELL`

`OrderType`

- `LIMIT`
- `MARKET`

`MarketType`

- `VIRTUAL_PRICE`
- `ORDER_BOOK`

`OrderStatus`

- `PENDING`
- `PARTIALLY_FILLED`
- `FILLED`
- `CANCELLED`
- `REJECTED`

## 주문 접수 플로우

1. controller가 `UserContext`에서 `userKey`를 읽는다.
2. `TradingService.placeOrder()`가 symbol, side, order type, quantity, limit price를 검증한다.
3. `clientOrderId`가 없으면 UUID를 만든다.
4. 같은 `clientOrderId`가 같은 사용자에게 이미 있으면 기존 주문을 반환한다.
5. `marketType`에 따라 종목 존재 여부를 확인한다.
   - `VIRTUAL_PRICE`: `stock_instrument`
   - `ORDER_BOOK`: `stock_order_book_instrument`
6. 종목별 market config가 `enabled=true`, `market_status=OPEN`인지 확인한다.
   - `VIRTUAL_PRICE`: `stock_virtual_market_config`
   - `ORDER_BOOK`: `stock_order_book_market_config`
7. LIMIT 주문은 호가 단위와 가격제한폭을 검증한다.
   - `VIRTUAL_PRICE`: 기본 1원 tick, `stock_price.previous_close` 기준 ±30%
   - `ORDER_BOOK`: `stock_order_book_instrument.tick_size`, `price_limit_rate`, `stock_price.previous_close` 기준
   - 주문장 종목에 아직 `stock_price`가 없으면 `initial_price`를 가격제한 기준가로 사용한다.
   - `stock_price.previous_close`는 장마감 롤오버 job이 직전 장 `current_price`로 갱신한다.
8. 매수 주문은 예약금을 계산한다.
   - LIMIT: `limitPrice * quantity`
   - MARKET: 현재가 * quantity
9. 매수 주문은 `stock_account.cash_balance`에서 예약금을 차감한다.
10. 매도 주문은 `stock_holding.reserved_quantity`를 증가시킨다.
11. `stock_order`에 `PENDING` 주문을 저장한다.

## 취소 플로우

1. 주문을 `for update`로 조회한다.
2. 본인 주문이 아니면 not found로 응답한다.
3. `PENDING`, `PARTIALLY_FILLED`만 취소 가능하다.
4. 매수 주문이면 남은 `reserved_cash`를 계좌로 되돌린다.
5. 매도 주문이면 남은 수량만큼 `reserved_quantity`를 해제한다.
6. 주문 상태를 `CANCELLED`로 바꾼다.

## 부분 취소 플로우

1. 주문을 `for update`로 조회한다.
2. 본인 주문이 아니면 not found로 응답한다.
3. `PENDING`, `PARTIALLY_FILLED`만 부분 취소 가능하다.
4. 취소 수량은 0보다 커야 하고, 남은 미체결 수량보다 클 수 없다.
5. 취소 수량이 남은 미체결 수량과 같으면 전체 취소와 같은 경로로 처리한다.
6. 매수 주문이면 취소 수량에 해당하는 예약금을 계좌로 되돌린다.
   - LIMIT: `limitPrice * cancelQuantity`
   - MARKET: 현재 남은 `reserved_cash`를 남은 수량 비율로 나눠 해제한다.
7. 매도 주문이면 취소 수량만큼 `reserved_quantity`를 해제한다.
8. 주문의 총 수량을 취소 수량만큼 줄이고, 주문 상태는 기존 `PENDING` 또는 `PARTIALLY_FILLED`를 유지한다.

## 주문 정정 플로우

1. 주문을 `for update`로 조회한다.
2. 본인 주문이 아니면 not found로 응답한다.
3. `PENDING`, `PARTIALLY_FILLED` 상태의 `LIMIT` 주문만 정정 가능하다.
4. 정정 요청에는 수량 또는 지정가 중 하나 이상이 있어야 한다.
5. 정정 후 총 수량은 이미 체결된 수량보다 커야 한다.
6. 새 지정가는 주문 접수와 같은 호가 단위/가격제한폭 검증을 통과해야 한다.
7. 매수 주문이면 남은 미체결 수량 기준으로 새 예약금을 계산한다.
   - 새 예약금이 기존보다 크면 계좌 현금을 추가 차감한다.
   - 새 예약금이 기존보다 작으면 차액을 계좌에 되돌린다.
8. 매도 주문이면 남은 미체결 수량 기준으로 새 예약수량을 계산한다.
   - 새 예약수량이 기존보다 크면 보유 가능 수량을 추가 예약한다.
   - 새 예약수량이 기존보다 작으면 차이를 해제한다.
9. 주문의 총 수량, 지정가, 예약금을 갱신한다.

## 현재 한계

- 정정/취소 이력 테이블은 아직 없다. 현재는 `stock_order` 현재 상태만 갱신한다.
- 시장가 주문 정정은 막혀 있다. 시장가 주문의 가격 기준은 체결 시점 반대편 호가 또는 현재가에 의존하기 때문이다.
- 가격대별 tick ladder는 아직 없다. 현재는 주문장 종목별 단일 tick size와 현재가 시장 기본 1원 tick을 사용한다.
- 장전 주문 접수 같은 시간 조건은 아직 없다. 현재는 `OPEN` 상태에서만 신규 주문을 받는다.

## 다음에 바꿀 때 순서

정정/취소 이력을 보강할 때:

1. 신규 `stock_order_amendment` 또는 `stock_order_event` 테이블을 만든다.
2. 정정 전후 수량, 가격, 예약금, 요청자, 요청 시간을 남긴다.
3. 부분 취소도 주문 이벤트로 기록한다.
4. 사용자 화면에서는 주문 현재 상태와 이벤트 이력을 분리해서 보여준다.

가격 정책을 더 정교하게 바꿀 때:

1. 가격대별 tick ladder 테이블을 추가할지 결정한다.
2. 동시호가/휴장/거래정지가 생길 때 장마감 롤오버 실행 시점을 함께 설계한다.
3. 기존 주문장 종목의 `tick_size`, `price_limit_rate` migration 값을 점검한다.

장 운영 시간을 추가할 때:

1. `OPEN`, `CLOSED`, `HALTED` 외 장전/장마감 동시호가 상태가 필요한지 결정한다.
2. 상태별로 신규 주문, 정정, 취소, 체결 허용 여부를 표로 먼저 고정한다.
3. 주문 접수 검증과 batch 체결 대상 SQL을 같은 정책으로 수정한다.
