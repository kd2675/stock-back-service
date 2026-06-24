# Virtual Price Market

## 현재 구현

`VIRTUAL_PRICE`는 사용자가 지정가/시장가 주문을 넣고, batch가 최신 현재가와 주문 조건을 비교해 체결하는 시장이다. 다른 사용자의 반대 주문이 없어도 현재가 조건이 맞으면 체결된다.

이 기능은 사용자가 처음 이해하기 쉬운 특정가격 자동주문체결 영역이다.

## 관련 코드

back:

- `TradingController.placeOrder`
- `TradingService.placeOrder`
- `TradingService.validateSymbolExists`
- `TradingService.validateMarketOpen`
- `TradingService.validateLimitPriceRule`
- `MarketController.getInstruments`
- `MarketController.getPrices`
- `MarketController.streamPrices`

batch:

- `OrderExecutionService.executeEligibleOrders`
- `MarketDataRefreshService.refreshWatchedPrices`
- `VirtualPriceExecutionScheduler`
- `MarketDataRefreshScheduler`

front:

- `stock-front-service/app/page.tsx`
- `stock-front-service/app/lib/stock.ts`

## 현재 플로우

1. front가 `GET /markets/instruments`, `GET /markets/prices`로 종목과 가격을 조회한다.
2. 사용자가 주문을 넣으면 front가 `POST /orders`에 `marketType: "VIRTUAL_PRICE"` 또는 생략값을 보낸다.
3. back은 종목 존재, 장 상태, 호가 단위, 가격제한폭, 현금/보유 예약을 검증한다.
4. batch `OrderExecutionService`가 `stock_virtual_market_config`가 `OPEN`인 종목의 미체결 주문을 조회한다.
5. 시장가는 즉시 체결 대상이다.
6. 매수 지정가는 현재가가 지정가 이하일 때 체결된다.
7. 매도 지정가는 현재가가 지정가 이상일 때 체결된다.
8. 체결 후 `stock_execution`, `stock_order`, `stock_account`, `stock_holding`을 갱신한다.

## 현재 불변식

- 현재가 시장 종목은 `stock_instrument` 기준이다.
- 주문장 종목과 공유하지 않는다.
- batch만 체결 원장을 갱신한다.
- Redis 가격 event는 보조 채널이며 DB 가격이 authoritative source다.

## 앞으로 구현할 후보

- 장 운영 일정과 연동한 자동 `OPEN/CLOSED` 전환.
- 시장가 주문의 slippage 또는 기준가 산정 정책.
- stop/stop-limit 같은 조건부 주문.
- 주문 유효기간, IOC/FOK.

## 바꿀 때 순서

1. `OrderType` 확장이 필요한지 먼저 판단한다.
2. 필요하면 DDL check constraint와 enum부터 바꾼다.
3. `TradingService`에서 주문 접수 검증과 예약 정책을 바꾼다.
4. `OrderExecutionService`에서 체결 가능 조건과 체결 가격을 바꾼다.
5. front 주문 폼과 타입을 맞춘다.
6. 현재가 체결 테스트를 추가한다.

## 검증

- `./gradlew :stock-back-service:test --tests '*TradingServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*OrderExecutionServiceTest*'`
- `cd stock-front-service && npm run verify:contract`
