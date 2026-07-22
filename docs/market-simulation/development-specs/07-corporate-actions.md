# Corporate Actions

## 현재 구현

현재 구현된 기업 이벤트는 초기 필수 범위만 다룬다.

- `INITIAL_ISSUE`: 주문장 종목 생성 시 자동 기록.
- `PAID_IN_CAPITAL_INCREASE`: 유상증자. 권리락 가격 계산, 납입 상태, 신주 상장.
- `STOCK_SPLIT`: 액면분할. 주식수 증가, 보유수량 증가, 평균단가/가격 하향 조정.
- `CASH_DIVIDEND`: 현금배당. 보유자별 현금 지급.
- `BONUS_ISSUE`: 무상증자. 권리락 가격 조정, 신주 entitlement 지급.
- `STOCK_DIVIDEND`: 주식배당. 무상증자와 같은 지급 구조.
- `DELISTING`: 미체결 주문을 취소하고 가격을 0으로 확정하는 상장폐지.

## 관련 코드

back:

- `OrderBookMarketController.createOrderBookInstrument`
- `MarketCorporateActionController.applyCorporateAction`
- `MarketCorporateActionController.getCorporateActions`
- `MarketCorporateActionController.getMyCorporateActionEntitlements`
- `MarketCorporateActionController.subscribeCorporateAction`
- `CorporateActionCommandService`
- `CorporateActionQueryService`
- `CorporateActionSubscriptionService`
- `CorporateActionFieldScopeValidator`
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

1. admin이 주주배정/일반공모 방식, 발행수/발행가, 청약기간, 납입일, 신주상장일을 등록한다. 주주배정은 권리락일과 주주확정 기준일도 별도로 기록한다.
2. 주주배정 권리락일은 현재 시뮬레이션 날짜보다 미래이고 `권리락 < 기준일 <= 청약 시작`이어야 한다. 일반공모는 권리락일과 기준일을 사용하지 않는다.
3. back은 등록 시점 가격으로 예상 theoretical ex-rights price를 제공한다.
4. batch는 권리락일 전 최신 완료 전체시장 close cycle/run을 action에 한 번 고정한다. 그 snapshot의 권리부종가와 발행주식수로 확정가격을 다시 계산해 한국시장 호가단위에 맞추고, 같은 snapshot 보유자별 entitlement를 만든다.
5. 사용자와 자동참여자가 장 마감 후 청약기간에 배정한도 또는 일반공모 잔여수량 안에서 청약한다. 주주배정 부분 청약은 누적하며 남은 권리를 청약 종료일까지 유지한다.
6. back은 청약 현금을 차감하되 외부 인출이 아닌 상장 대기 예약자산으로 계산한다.
7. batch가 납입일에 완전 미청약 권리는 만료하고, 부분 청약 권리는 실제 청약수량과 포기수량을 분리해 확정한 뒤 action을 `PAID`로 바꾼다.
8. batch가 신주상장일에 실제 `SUBSCRIBED` 합계만큼만 발행/유통주식수와 계좌 보유수량을 늘린다.

액면분할:

1. admin이 splitFrom/splitTo와 효력일을 등록한다.
2. batch가 효력일에 발행/유통주식수와 보유/예약수량을 배율만큼 늘린다.
3. batch가 평균단가, 현재가, 전일종가를 배율만큼 나눈다.

현금배당:

1. admin이 1주당 배당금, 배당락일, 지급일을 등록한다.
2. 배당락일은 등록 시점 시뮬레이션 날짜보다 미래여야 한다.
3. batch가 배당락일에 entitlement를 만든다.
4. batch가 지급일에 사용자 계좌 현금을 늘리고 entitlement를 `PAID`로 바꾼다.

무상증자/주식배당:

1. admin이 배정 주식수, 권리락일, 신주상장일을 등록한다.
2. 권리락일은 등록 시점 시뮬레이션 날짜보다 미래여야 한다.
3. batch가 권리락 직전 최신 완료 전체시장 snapshot의 권리부종가와 당시 발행주식수로 계산한 값을 한국시장 호가단위에 맞춰 확정하고 사용자별 share entitlement를 만든다.
4. batch가 신주상장일에 발행/유통주식수를 늘리고 사용자 보유수량을 늘린다.

## 현재 불변식

- 미래 이벤트 공시는 미체결 주문과 함께 등록할 수 있다. 효력일 batch는 open `ORDER_BOOK` 주문이 있으면 가격·주식수 변경을 대기한다.
- 이벤트별 허용 필드 조합은 `CorporateActionFieldScopeValidator.validate`와 DDL check constraint가 같이 잡는다.
- `INITIAL_ISSUE`는 admin 이벤트 적용 API에서 받지 않는다.
- 유상증자 일정은 `subscriptionStart <= subscriptionEnd < payment < listing`이고, 주주배정은 `exRights < recordDate <= subscriptionStart`다.
- 보유자 snapshot이 필요한 주주배정·현금배당·무상증자·주식배당의 권리락일은 등록일보다 미래여야 한다.
- 같은 종목의 `PAID_IN_CAPITAL_INCREASE`, `STOCK_SPLIT`, `BONUS_ISSUE`, `STOCK_DIVIDEND`, `DELISTING`은 진행 중 상태에서 서로 겹칠 수 없다. `CASH_DIVIDEND`는 이 배타 집합에 포함하지 않는다.
- 사용자 청약 lock 순서는 action -> account -> entitlement이며 현금/entitlement/cash-flow는 동일 JPA 트랜잭션이다.
- 장마감 현금 단계는 T일 배당 지급과 유상증자 자동청약·납입을 먼저 끝낸 뒤 T+1 정기입금을 실행한다.
- 기업행사 또는 자동참여자 정기입금이 활성화된 배포에서는 날짜별 phase를 소유하는 post-close coordinator를 반드시 활성화한다. 레거시 독립 스케줄러 조합은 시작 검증에서 차단한다.
- 미구현 이벤트를 enum이나 front select에 미리 넣지 않는다.

## 앞으로 구현할 후보

- 감자.
- 액면병합.
- 거래정지/거래재개 이벤트 정책.
- 양도 가능한 별도 신주인수권 거래.
- 합병, 분할, 종목코드 변경.

## 바꿀 때 순서

1. `../15-corporate-action-scope.md`에서 넣을 이벤트가 현재 범위인지 결정한다.
2. 이벤트에 필요한 날짜, 가격, 수량, 권리 필드를 정의한다.
3. `StockCorporateActionType`, DDL check constraint, front union type을 바꾼다.
4. back `CorporateActionFieldScopeValidator`를 바꾼다.
5. batch `CorporateActionService`에 상태 전이와 원장 반영을 넣는다.
6. admin form과 사용자 조회 화면을 맞춘다.
7. DB constraint test와 service test를 추가한다.

## 검증

- `./gradlew :stock-back-service:test --tests '*CorporateActionCommandServiceTest*' --tests '*CorporateActionQueryServiceTest*' --tests '*CorporateActionSubscriptionServiceIntegrationTest*' --tests '*StockMysqlDdlContractTest*'`
- `./gradlew :stock-batch-service:test --tests '*CorporateActionServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*StockSchemaConstraintTest*'`
- `node scripts/verify-stock-initial-scope.mjs`
