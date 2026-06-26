# Corporate Actions

## 현재 구현

기업 이벤트는 주문장 종목에만 적용한다. 현재 초기 필수 범위는 아래 타입이다.

- `INITIAL_ISSUE`
- `PAID_IN_CAPITAL_INCREASE`
- `ADDITIONAL_ISSUE`
- `STOCK_SPLIT`
- `CASH_DIVIDEND`
- `BONUS_ISSUE`
- `STOCK_DIVIDEND`

상세 범위 기준은 `../15-corporate-action-scope.md`를 우선한다.

## 코드 역할

- back registration: `MarketController.createOrderBookInstrument`, `MarketController.applyCorporateAction`, `MarketService.createOrderBookInstrument`, `MarketService.applyCorporateAction`
- entity/DTO: `StockCorporateAction`, `StockCorporateActionType`, `StockCorporateActionStatus`, `StockCorporateActionEntitlement`, `CorporateActionRequest`, `CorporateActionResponse`, `CorporateActionEntitlementResponse`
- batch application: `CorporateActionService.applyDueCorporateActions`, `CorporateActionScheduler`, `StockBatchJobService.applyCorporateActions`
- front: `stock-front-service/app/supply-demand/admin/page.tsx`, `stock-front-service/app/page.tsx`, `stock-front-service/app/types/stock.ts`

## 현재 흐름

1. 주문장 종목 생성 시 `INITIAL_ISSUE` 원장이 `LISTED` 상태와 `listed_at`을 가진 확정 기록으로 생성된다.
2. 관리자가 기업 이벤트를 등록하면 back은 validation 후 `stock_corporate_action`에 `ANNOUNCED` 상태로 저장한다.
3. 열린 주문장 주문이 있으면 이벤트 등록을 막는다.
4. batch 적용 시점에도 가격, 주식수, 보유수량을 바꾸는 단계는 열린 주문장 주문이 있으면 대기한다.
5. batch는 날짜에 따라 권리락, 지급, 상장, 분할을 처리한다.
6. 사용자별 현금배당/무상주/주식배당은 `stock_corporate_action_entitlement`로 남긴다.
7. DDL은 `chk_stock_corporate_action_field_scope`로 이벤트 타입별 의미 없는 컬럼 조합을 거부한다.
8. DDL은 `chk_stock_corporate_action_initial_listed`로 `INITIAL_ISSUE`가 대기 이벤트처럼 저장되지 못하게 막는다.

## 이벤트별 현재 처리

- 유상증자: 권리락일에 이론 권리락 가격 반영, 납입일에 `PAID`, 상장일에 발행/유통주식수 증가.
- 추가발행: 상장일에 발행/유통주식수 증가. 기존 주주 배정 원장은 만들지 않는다.
- 액면분할: 상장일에 보유수량과 발행/유통주식수를 비율만큼 증가. 평균단가와 최신가는 비율만큼 낮춘다.
- 현금배당: 배당락일에 사용자별 현금 지급 예정 entitlement 생성. 지급일에 계좌 현금을 증가. 현재가는 강제로 조정하지 않는다.
- 무상증자/주식배당: 권리락일에 가격 조정과 사용자별 주식 지급 예정 entitlement 생성. 상장일에 발행/유통주식수 증가와 사용자 보유수량 증가.

## 앞으로 구현할 후보

- 감자.
- 액면병합.
- 신주인수권/권리공모.
- 상장폐지.
- 단주/현금 보상 정책.
- 기준일 보유자 snapshot.

## 변경 순서

1. 새 이벤트가 초기 필수 범위인지 먼저 판단한다.
2. `StockCorporateActionType`과 DDL CHECK constraint를 수정한다.
3. `CorporateActionRequest`에 필요한 필드를 추가한다.
4. `MarketService.applyCorporateAction` validation과 factory method를 추가한다.
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
- 단주 처리 정책이 없는 이벤트는 구현하지 않는다.
- 열린 주문 처리 정책 없이 주식수/가격을 바꾸면 주문장 불변식이 깨진다. 현재 batch는 열린 `ORDER_BOOK` 주문이 있으면 권리락, 추가발행 상장, 무상증자/주식배당 상장, 액면분할을 적용하지 않고 다음 실행으로 넘긴다.
