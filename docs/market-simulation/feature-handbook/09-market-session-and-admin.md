# Market Session And Admin Controls

## 현재 구현

장 상태는 시장별 config table에 있다.

- `stock_virtual_market_config`
- `stock_order_book_market_config`

상태 enum은 `MarketSessionStatus`다.

- `OPEN`
- `CLOSED`
- `HALTED`

## 코드 역할

- back API: `MarketController.updateMarketStatus`, `MarketService.updateMarketStatus`
- 주문 접수 guard: `TradingService.validateMarketOpen`
- batch guard: `OrderExecutionService.executeEligibleOrders`, `InternalOrderBookExecutionService.executeEligibleOrders`, `AutoMarketService.findEnabledConfigs`
- front admin: `stock-front-service/app/supply-demand/admin/page.tsx`
- front user display: `stock-front-service/app/page.tsx`, `stock-front-service/app/supply-demand/page.tsx`

## 현재 흐름

1. 관리자가 symbol별 enabled 또는 marketStatus를 변경한다.
2. back은 market type에 따라 virtual config 또는 order-book config를 갱신한다.
3. 주문 접수는 `OPEN`이 아니면 거부한다.
4. batch 체결 job은 `OPEN`인 symbol만 scan한다.
5. 자동장도 `OPEN`인 주문장 종목만 주문을 만든다.
6. 취소와 조회는 장 상태와 무관하게 허용한다.

## 앞으로 구현할 후보

- 시장 전체 상태와 종목별 상태 분리.
- 장전/정규장/장마감/휴장 schedule.
- 장전/장마감 동시호가.
- 일일 가격제한폭과 변동성 완화장치 연결.
- 관리자 audit log.

## 변경 순서

1. 상태가 시장 전체인지 symbol 단위인지 먼저 결정한다.
2. 상태 enum을 늘릴 경우 back enum, DDL CHECK, front type을 같이 수정한다.
3. 주문 접수 허용표를 만든다.
4. 체결 job 허용표를 만든다.
5. front disabled state와 관리자 표시를 수정한다.
6. 테스트에서 주문 접수, 체결, 자동장, 취소 허용을 각각 검증한다.

## 검증

- `TradingServiceTest`: CLOSED/HALTED 주문 거부와 취소 허용.
- `OrderExecutionServiceTest`: CLOSED/HALTED 체결 skip.
- `InternalOrderBookExecutionServiceTest`: CLOSED/HALTED 체결 skip.
- `AutoMarketServiceTest`: CLOSED/HALTED 자동 주문 skip.
- front build/lint.
