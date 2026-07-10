# Corporate Actions

## 현재 구현

기업 이벤트는 주문장 종목에만 적용한다. 현재 초기 필수 범위는 아래 타입이다.

- `INITIAL_ISSUE`
- `PAID_IN_CAPITAL_INCREASE`
- `STOCK_SPLIT`
- `CASH_DIVIDEND`
- `BONUS_ISSUE`
- `STOCK_DIVIDEND`
- `DELISTING`

상세 범위 기준은 `../15-corporate-action-scope.md`를 우선한다.

## 코드 역할

- back registration/query/subscription: `OrderBookInstrumentCommandService`, `CorporateActionCommandService`, `CorporateActionQueryService`, `CorporateActionSubscriptionService`, `MarketCorporateActionController`
- entity/DTO: `StockCorporateAction`, `StockCapitalIncreaseOfferingType`, `StockCorporateActionType`, `StockCorporateActionStatus`, `StockCorporateActionEntitlement`, `CorporateActionRequest`, `CorporateActionResponse`, `CorporateActionEntitlementResponse`, `CorporateActionSubscriptionRequest`
- batch application: `CorporateActionService.applyDueCorporateActions`, `CorporateActionScheduler`, `StockBatchJobLauncher.applyCorporateActions`, `CorporateActionJob`
- front: `stock-front-service/app/corporate-actions/page.tsx`, `stock-front-service/app/supply-demand/admin/AdminCorporateActionFormPanel.tsx`, `stock-front-service/app/types/stockMarket.ts`

## 현재 흐름

1. 주문장 종목 생성 시 `INITIAL_ISSUE` 원장이 `LISTED` 상태와 `listed_at`을 가진 확정 기록으로 생성된다.
2. 관리자가 기업 이벤트를 등록하면 back은 validation 후 `stock_corporate_action`에 `ANNOUNCED` 상태로 저장한다.
3. 열린 주문장 주문이 있으면 이벤트 등록을 막는다.
4. batch 적용 시점에도 가격, 주식수, 보유수량을 바꾸는 단계는 열린 주문장 주문이 있으면 대기한다.
5. batch는 날짜에 따라 권리락, 지급, 상장, 분할을 처리한다.
6. 사용자별 현금배당/무상주/주식배당/유상증자 배정·청약은 `stock_corporate_action_entitlement`로 남긴다.
7. DDL은 `chk_stock_corporate_action_field_scope`로 이벤트 타입별 의미 없는 컬럼 조합을 거부한다.
8. DDL은 `chk_stock_corporate_action_initial_listed`로 `INITIAL_ISSUE`가 대기 이벤트처럼 저장되지 못하게 막는다.

## 이벤트별 현재 처리

- 유상증자: 주주배정과 일반공모만 지원한다. 주주배정은 권리락 직전 완료 snapshot의 권리부종가로 확정가격과 배정한도를 만든다. 권리부종가가 발행가보다 높을 때만 희석 산식을 적용해 1원 미만을 절사하고, 그렇지 않으면 권리부종가를 유지한다. 일반공모는 전체 잔여수량을 action row lock으로 직렬화한다. 납입일 미청약 권리를 만료하고 상장일 실제 청약 합계만 발행한다.
- 액면분할: 상장일에 보유수량과 발행/유통주식수를 비율만큼 증가. 평균단가와 최신가는 비율만큼 낮춘다.
- 현금배당: 배당락일에 사용자별 현금 지급 예정 entitlement 생성. 지급일에 계좌 현금을 증가. 현재가는 강제로 조정하지 않는다.
- 무상증자/주식배당: 권리락 직전 완료 snapshot의 종가와 발행주식수로 가격을 확정하고 사용자별 주식 지급 예정 entitlement 생성. 상장일에 발행/유통주식수 증가와 사용자 보유수량 증가.
- 상장폐지: 상장폐지일에 미체결 주문과 예약을 정리하고 시장/자동장을 중지한 뒤 가격을 0으로 확정한다.

## 앞으로 구현할 후보

- 감자.
- 액면병합.
- 양도 가능한 신주인수권 거래.
- 단주/현금 보상 정책.
- 권리락일과 별도 기준일을 분리하는 정책.

## 변경 순서

1. 새 이벤트가 초기 필수 범위인지 먼저 판단한다.
2. `StockCorporateActionType`과 DDL CHECK constraint를 수정한다.
3. `CorporateActionRequest`에 필요한 필드를 추가한다.
4. `CorporateActionCommandService`와 `CorporateActionFieldScopeValidator` validation, entity factory method를 추가한다.
5. `CorporateActionService.applyDueCorporateActions` 상태 전이를 추가한다.
6. entitlement가 필요하면 생성/지급 로직을 추가한다.
7. back DTO, front type, 관리자 입력 UI를 수정한다.
8. MySQL full DDL과 H2 DDL을 모두 갱신한다.

## 검증

- `CorporateActionServiceTest`
- `StockSchemaConstraintTest`
- `StockMysqlDdlContractTest`
- `StockDdlContractTest`
- `npm run verify:contract`

## 개발 시 주의점

- 기준일, 효력일, 지급일, 상장일을 섞으면 안 된다.
- 유상증자는 `청약 종료 < 납입 < 상장`, 주주배정은 `권리락 < 청약 시작`을 애플리케이션과 DDL에서 함께 강제한다.
- 보유자 snapshot이 필요한 이벤트의 권리락일은 등록 시점 시뮬레이션 날짜보다 미래여야 한다.
- 같은 날 현금배당 지급과 자동 유상증자 청약이 겹치면 배당 지급을 먼저 처리해 PRE_OPEN 실행 여부에 따른 청약 수량 차이를 없앤다.
- 청약 대금은 cash-flow audit 원장에는 남기되 외부 입출금 수익률 산식에서 제외하고, 상장 전 `SUBSCRIBED` entitlement 예약자산으로 계산한다.
- 유상증자/액면분할/무상증자/주식배당/상장폐지는 종목 row 잠금 아래 진행 중 이벤트와 상호 배타로 등록한다. 현금배당은 이 배타 집합에서 제외한다.
- 단주 처리 정책이 없는 이벤트는 구현하지 않는다.
- 열린 주문 처리 정책 없이 주식수/가격을 바꾸면 주문장 불변식이 깨진다. 현재 batch는 열린 `ORDER_BOOK` 주문이 있으면 권리락, 유상증자/무상증자/주식배당 상장, 액면분할을 적용하지 않고 다음 실행으로 넘긴다.
