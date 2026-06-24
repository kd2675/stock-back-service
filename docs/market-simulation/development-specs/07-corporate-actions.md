# Corporate Actions

## 현재 구현

현재 구현된 기업 이벤트는 초기 필수 범위만 다룬다.

- `INITIAL_ISSUE`: 주문장 종목 생성 시 자동 기록.
- `PAID_IN_CAPITAL_INCREASE`: 유상증자. 권리락 가격 계산, 납입 상태, 신주 상장.
- `ADDITIONAL_ISSUE`: 추가발행. 신주 상장 시 발행주식수와 유통주식수 증가.
- `STOCK_SPLIT`: 액면분할. 주식수 증가, 보유수량 증가, 평균단가/가격 하향 조정.
- `CASH_DIVIDEND`: 현금배당. 배당락 가격 조정, 보유자별 현금 지급.
- `BONUS_ISSUE`: 무상증자. 권리락 가격 조정, 신주 entitlement 지급.
- `STOCK_DIVIDEND`: 주식배당. 무상증자와 같은 지급 구조.

## 관련 코드

back:

- `MarketController.createOrderBookInstrument`
- `MarketController.applyCorporateAction`
- `MarketController.getCorporateActions`
- `MarketController.getMyCorporateActionEntitlements`
- `MarketService.applyCorporateAction`
- `MarketService.validateCorporateActionFieldScope`
- `StockCorporateAction`
- `StockCorporateActionEntitlement`
- `StockCorporateActionType`

batch:

- `CorporateActionService.applyDueCorporateActions`
- `CorporateActionScheduler`

front:

- `stock-front-service/app/supply-demand/admin/page.tsx`
- `stock-front-service/app/types/stock.ts`

## 현재 플로우

종목 생성:

1. admin이 주문장 종목을 생성한다.
2. back이 `StockCorporateAction.initialIssue`를 저장한다.

유상증자:

1. admin이 발행수, 발행가, 권리락일, 납입일, 신주상장일을 등록한다.
2. back이 현재 발행주식수, 현재가, 발행가로 theoretical ex-rights price를 계산한다.
3. batch가 권리락일에 가격을 조정한다.
4. batch가 납입일에 action status를 `PAID`로 바꾼다.
5. batch가 신주상장일에 발행/유통주식수를 늘린다.

추가발행:

1. admin이 발행수, 발행가, 신주상장일을 등록한다.
2. 권리락 없이 신주상장일에 발행/유통주식수를 늘린다.

액면분할:

1. admin이 splitFrom/splitTo와 효력일을 등록한다.
2. batch가 효력일에 발행/유통주식수와 보유/예약수량을 배율만큼 늘린다.
3. batch가 평균단가, 현재가, 전일종가를 배율만큼 나눈다.

현금배당:

1. admin이 1주당 배당금, 배당락일, 지급일을 등록한다.
2. batch가 배당락일에 가격을 하향 조정하고 entitlement를 만든다.
3. batch가 지급일에 사용자 계좌 현금을 늘리고 entitlement를 `PAID`로 바꾼다.

무상증자/주식배당:

1. admin이 배정 주식수, 권리락일, 신주상장일을 등록한다.
2. batch가 권리락일에 가격을 조정하고 사용자별 share entitlement를 만든다.
3. batch가 신주상장일에 발행/유통주식수를 늘리고 사용자 보유수량을 늘린다.

## 현재 불변식

- open `ORDER_BOOK` 주문이 있으면 기업 이벤트 적용 또는 batch 반영을 막는다.
- 이벤트별 허용 필드 조합은 `validateCorporateActionFieldScope`와 DDL check constraint가 같이 잡는다.
- `INITIAL_ISSUE`는 admin 이벤트 적용 API에서 받지 않는다.
- 미구현 이벤트를 enum이나 front select에 미리 넣지 않는다.

## 앞으로 구현할 후보

- 감자.
- 액면병합.
- 거래정지/상장폐지와 기업 이벤트 연동.
- 신주인수권 청약/권리 만료.
- 합병, 분할, 종목코드 변경.

## 바꿀 때 순서

1. `../15-corporate-action-scope.md`에서 넣을 이벤트가 현재 범위인지 결정한다.
2. 이벤트에 필요한 날짜, 가격, 수량, 권리 필드를 정의한다.
3. `StockCorporateActionType`, DDL check constraint, front union type을 바꾼다.
4. back `MarketService.validateCorporateActionFieldScope`를 바꾼다.
5. batch `CorporateActionService`에 상태 전이와 원장 반영을 넣는다.
6. admin form과 사용자 조회 화면을 맞춘다.
7. DB constraint test와 service test를 추가한다.

## 검증

- `./gradlew :stock-back-service:test --tests '*MarketServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*CorporateActionServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*StockSchemaConstraintTest*'`
- `node scripts/verify-stock-initial-scope.mjs`
