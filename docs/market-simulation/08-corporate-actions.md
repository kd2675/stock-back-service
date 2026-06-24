# Corporate Actions

이 문서는 기업 이벤트 원장과 batch 적용 구조를 설명한다.

초기 프로젝트에서 어떤 기업 이벤트까지 구현할지는 `15-corporate-action-scope.md`를 우선 기준으로 본다. 실제 시장 이벤트가 많아도, 단주/권리처럼 원장 정책이 정해지지 않은 이벤트는 초기 구현에 넣지 않는다.

## 현재 구현된 이벤트

- `INITIAL_ISSUE`: 주문장 종목 생성 시 최초 발행 기록
- `PAID_IN_CAPITAL_INCREASE`: 유상증자
- `ADDITIONAL_ISSUE`: 추가발행
- `STOCK_SPLIT`: 액면분할
- `CASH_DIVIDEND`: 현금배당
- `BONUS_ISSUE`: 무상증자
- `STOCK_DIVIDEND`: 주식배당
- `DELISTING`: ZERO_VALUE 상장폐지

관련 코드:

- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateAction.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateActionEntitlement.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateActionType.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/CorporateActionRequest.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/CorporateActionResponse.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/CorporateActionEntitlementResponse.java`
- `stock-batch-service/src/main/java/stock/batch/service/corporateaction/biz/CorporateActionService.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/CorporateActionScheduler.java`
- `stock-front-service/app/supply-demand/admin/page.tsx`

## 공통 원칙

- 기업 이벤트는 주문 체결 로직과 분리된 원장 이벤트다.
- API 호출 시점에 대부분 즉시 반영하지 않고 `stock_corporate_action`에 기록한다.
- 적용은 날짜와 status를 보고 batch가 수행한다.
- 열린 `ORDER_BOOK` 주문이 있으면 가격/예약 기준이 꼬일 수 있으므로 관리자 API에서 우선 거부한다.
- 등록 후 실행일까지 새 주문이 생길 수 있으므로 batch 적용 시점에도 가격, 주식수, 보유수량을 바꾸는 단계는 열린 `ORDER_BOOK` 주문이 있으면 대기한다.
- 단, `DELISTING`은 열린 주문을 대기하지 않고 상장폐지일에 미체결 주문을 취소하고 예약 현금/수량을 해제한다.

## 조회 API

종목별 이벤트 이력:

- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- 공개 조회다.
- 관리자 화면은 선택한 주문장 종목의 이벤트 이력을 이 API로 보여준다.
- `INITIAL_ISSUE`부터 배당/증자/분할까지 `stock_corporate_action` 원장 상태를 그대로 확인한다.

사용자별 배정 내역:

- `GET /api/stock/v1/markets/corporate-action-entitlements/me`
- 사용자 인증이 필요하다.
- `stock_corporate_action_entitlement` 기준 최근 50건을 반환한다.
- 현금배당은 `cashAmount`, 무상증자/주식배당은 `shareQuantity`로 확인한다.
- 홈 화면의 기업 이벤트 패널은 이 API로 배정 예정/지급 완료 상태를 보여준다.

## 유상증자

관리자 입력:

- 발행수
- 발행가
- 권리락일
- 납입일
- 신주상장일

back 처리:

- 현재가를 base price로 저장한다.
- 이론권리락가격을 계산한다.
- `stock_corporate_action`에 `ANNOUNCED`로 저장한다.

batch 처리:

1. 권리락일에 `stock_price`를 이론권리락가격으로 조정한다.
2. `stock_price_tick`을 남긴다.
3. 납입일에 status를 `PAID`로 바꾼다.
4. 신주상장일에 `issued_shares`, `tradable_shares`를 증가시키고 `LISTED`로 바꾼다.

## 추가발행

관리자 입력:

- 발행수
- 발행가
- 신주상장일

batch 처리:

- 신주상장일에 `issued_shares`, `tradable_shares`를 증가시킨다.
- 가격은 직접 조정하지 않는다.

## 액면분할

관리자 입력:

- 분할 전
- 분할 후
- 효력일

현재 제약:

- 정수 배율만 지원한다.
- `split_to > split_from`이어야 한다.
- 열린 주문이 있으면 batch가 대기한다.

batch 처리:

- `issued_shares`, `tradable_shares`를 배율만큼 증가시킨다.
- 보유수량과 예약수량을 배율만큼 증가시킨다.
- 평균단가, 현재가, 전일종가를 배율로 나눈다.
- 가격 tick을 남긴다.

## 현금배당

관리자 입력:

- 1주당 배당금
- 배당락일
- 지급일

back 처리:

- 배당금이 현재가보다 작아야 한다.
- `theoretical_ex_rights_price = current_price - dividendAmount`로 저장한다.

batch 처리:

1. 배당락일에 가격을 배당락 가격으로 조정한다.
2. 배당락일 현재 `stock_holding.quantity > 0`인 보유자 기준으로 `stock_corporate_action_entitlement`를 만든다.
3. 지급일에 entitlement별 `cash_amount`를 `stock_account.cash_balance`에 더한다.
4. entitlement와 corporate action을 `PAID`로 전이한다.

## 무상증자와 주식배당

관리자 입력:

- 배정 주식수
- 권리락일
- 신주상장일

back 처리:

- 현재가를 base price로 저장한다.
- `basePrice * existingShares / (existingShares + newShares)`로 이론권리락가격을 계산한다.
- `BONUS_ISSUE` 또는 `STOCK_DIVIDEND`를 `ANNOUNCED`로 저장한다.

batch 처리:

1. 권리락일에 가격을 이론권리락가격으로 조정한다.
2. 권리락일 현재 `stock_holding.quantity > 0`인 보유자 기준으로 share entitlement를 만든다.
3. 상장일에 `issued_shares`, `tradable_shares`를 증가시킨다.
4. entitlement별 `share_quantity`를 보유수량에 더한다.
5. 무상 배정이므로 총 취득원가는 유지하고 평균단가를 낮춘다.
6. entitlement를 `PAID`, corporate action을 `LISTED`로 전이한다.

현재 단주 정책:

- `floor(보유수량 * 배정주식수 / 발행주식수)`로 정수 주식만 배정한다.
- 단주 현금 보상은 아직 없다.
- 발행주식수는 공시된 전체 배정 주식수만큼 증가하고, 시뮬레이션 사용자에게 배정되지 않은 단주는 외부 주주 몫으로 본다.

## 상장폐지

관리자 입력:

- 상장폐지일
- 메모

현재 정책:

- `delisting_treatment = ZERO_VALUE`만 지원한다.
- 보유수량은 삭제하지 않는다.
- 포트폴리오 평가는 `stock_price.current_price = 0`으로 0원 처리한다.

batch 처리:

1. 상장폐지일에 미체결 `ORDER_BOOK` 주문을 모두 `CANCELLED`로 바꾼다.
2. 매수 주문 예약 현금은 `stock_account.cash_balance`로 반환한다.
3. 매도 주문 예약수량은 `stock_holding.reserved_quantity`에서 해제한다.
4. `stock_order_book_instrument.enabled = false`, `tradable_shares = 0`으로 바꾼다.
5. 주문장 시장은 `HALTED`와 `enabled = false`로 닫는다.
6. 종목 자동장, 상장주관사 자동계정, 참여자별 종목 전략은 정지한다.
7. `stock_price`와 `stock_price_tick`에 0원 가격을 남긴다.
8. 시세 갱신 배치는 상장폐지된 주문장 종목을 다시 갱신 대상으로 잡지 않는다.

## 아직 구현하지 않은 이벤트

- 감자
- 액면병합
- 신주인수권/권리공모
- 특별배당
- 자사주 매입/처분
- 합병/분할/주식교환
- 종목명/코드 변경

이 항목들은 “몰라서 빠진 것”이 아니라 초기 범위에서 의도적으로 제외한 것이다. 구현하려면 `15-corporate-action-scope.md`의 판단 질문을 먼저 통과해야 한다.

## 다음에 바꿀 때 순서

감자/액면병합:

1. 단주 현금 보상 정책을 먼저 정한다.
2. 보유수량 감소와 평균단가 증가를 구현한다.
3. 열린 주문 처리 정책을 정한다.

거래정지:

1. 거래정지는 현재 장 상태로 최소 구현되어 있다.
2. 거래정지 사유, 시작시각, 종료시각 이력을 별도 원장으로 남길지 결정한다.
