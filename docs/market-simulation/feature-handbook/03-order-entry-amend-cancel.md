# Order Entry, Amend, And Cancel

## 현재 구현

주문 접수는 back에서 원장에 저장하고 예약금/예약수량만 반영한다. 실제 체결은 batch가 처리한다.

- API: `POST /api/stock/v1/orders`, `GET /api/stock/v1/orders?marketType=...`, `DELETE /api/stock/v1/orders/{orderId}`, `PATCH /api/stock/v1/orders/{orderId}`, `POST /api/stock/v1/orders/{orderId}/cancel`
- 핵심 코드: `TradingController`, `TradingService`, `AccountService`
- DTO: `OrderRequest`, `OrderAmendRequest`, `OrderCancelRequest`, `OrderResponse`

## 코드 흐름

1. `TradingController.placeOrder`가 `UserContext`와 `OrderRequest`를 받는다.
2. `TradingService.placeOrder`가 symbol, clientOrderId, marketType을 normalize한다.
3. `validateSymbolExists`가 시장별 종목 존재를 확인한다.
4. `validateMarketOpen`이 `enabled=true`, `market_status=OPEN`인지 확인한다.
5. `validateLimitPriceRule`이 tick size와 가격제한폭을 검사한다.
6. 매수는 `StockAccount.reserveCash`, 매도는 `StockHolding.reserveQuantity`를 호출한다.
7. `StockOrder.pending`으로 주문 row를 만든다.

## 현재 불변식

- 같은 `clientOrderId`는 같은 사용자 주문에 대해 idempotent하게 처리한다.
- 매수 주문은 주문 접수 시 현금을 예약한다.
- 매도 주문은 주문 접수 시 보유수량을 예약한다.
- 주문 접수는 체결 row를 만들지 않는다.
- 정정은 `LIMIT` 주문만 허용한다.
- 취소는 `PENDING`, `PARTIALLY_FILLED` 주문만 허용한다.

## 앞으로 구현할 후보

- 주문 정정/취소 이력 table.
- IOC/FOK/day order 같은 시간 조건.
- stop, stop-limit 주문.
- 시장가 주문의 잔량 정책 명시.

## 변경 순서

1. `OrderRequest` 또는 amend/cancel DTO에 필요한 필드를 추가한다.
2. `TradingService` validation을 먼저 수정한다.
3. 예약금/예약수량 계산식을 수정한다.
4. `StockOrder` 상태나 컬럼이 필요하면 DDL을 먼저 바꾼다.
5. batch 체결 대상 SQL이 새 상태/주문유형을 처리하는지 확인한다.
6. `stock-front-service/app/types/stock.ts`와 `app/lib/stock.ts`를 갱신한다.
7. 화면 입력 validation을 back validation과 맞춘다.

## 검증

- `TradingServiceTest`
- `StockOrderRepositoryOrderBookTest`
- `stock-front-service`의 `npm run verify:contract`, `npm run lint`, `npm run build`
