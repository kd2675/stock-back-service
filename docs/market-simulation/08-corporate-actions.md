# Corporate Actions

이 문서는 기업 이벤트 원장과 batch 적용 구조를 설명한다.

초기 프로젝트에서 어떤 기업 이벤트까지 구현할지는 `15-corporate-action-scope.md`를 우선 기준으로 본다. 실제 시장 이벤트가 많아도, 단주/권리처럼 원장 정책이 정해지지 않은 이벤트는 초기 구현에 넣지 않는다.

## 현재 구현된 이벤트

- `INITIAL_ISSUE`: 주문장 종목 생성 시 최초 발행 기록
- `PAID_IN_CAPITAL_INCREASE`: 유상증자
- `STOCK_SPLIT`: 액면분할
- `CASH_DIVIDEND`: 현금배당
- `BONUS_ISSUE`: 무상증자
- `STOCK_DIVIDEND`: 주식배당
- `DELISTING`: ZERO_VALUE 상장폐지

관련 코드:

- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateAction.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateActionEntitlement.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockCorporateActionType.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/CorporateActionCommandService.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/CorporateActionQueryService.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/CorporateActionSubscriptionService.java`
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
- 종목 row를 잠근 뒤 `PAID_IN_CAPITAL_INCREASE`, `STOCK_SPLIT`, `BONUS_ISSUE`, `STOCK_DIVIDEND`, `DELISTING` 중 진행 중(`ANNOUNCED`, `EX_RIGHTS_APPLIED`, `PAID`) 이벤트가 있으면 같은 집합의 새 이벤트 등록을 막는다. 발행주식수 기준 변경과 상장폐지가 서로 교차하지 않게 하기 위한 규칙이며 `CASH_DIVIDEND`는 제외한다.
- 상장폐지로 `stock_order_book_instrument.enabled = false`가 된 종목은 terminal 상태이며 새 기업 이벤트를 등록할 수 없다.

## 조회 API

종목별 이벤트 이력:

- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- 공개 조회다.
- 관리자 화면은 선택한 주문장 종목의 이벤트 이력을 이 API로 보여준다.
- `INITIAL_ISSUE`부터 배당/증자/분할까지 `stock_corporate_action` 원장 상태를 그대로 확인한다.

전체 이벤트 feed:

- `GET /api/stock/v1/markets/corporate-actions?actionType={type}&limit={1..200}`
- 공개 조회이며 `actionType`은 선택, `limit`은 기본 100/최대 200이다.
- `createdAt desc, id desc` 순서로 반환하고 유상증자는 전체 청약수량과 잔여수량도 함께 계산한다.
- 무필터 또는 유상증자 feed는 최근 `limit`건 밖에 있더라도 `ANNOUNCED`/`EX_RIGHTS_APPLIED`/`PAID` 유상증자를 모두 합친다. 상장 전 청약·예약자산 상태가 feed 제한 때문에 사라지지 않게 하기 위한 규칙이며, 이 경우 응답 건수는 `limit`을 넘을 수 있다.
- 다른 `actionType`의 오래된 진행 이벤트는 강제 병합하지 않고 요청한 최근 `limit`건만 반환한다. 완료된 `LISTED`/`DELISTED` 유상증자도 최근 범위 밖이면 강제 포함하지 않는다.
- 무필터 feed는 `(created_at, id)`, 타입 필터 feed는 `(action_type, created_at, id)` 인덱스를 사용하도록 full DDL과 hardening alter를 맞춘다.

사용자별 배정 내역:

- `GET /api/stock/v1/markets/corporate-action-entitlements/me`
- 사용자 인증이 필요하다.
- `ANNOUNCED`/`PARTIALLY_SUBSCRIBED`/`SUBSCRIBED`인 진행 중 권리는 생성 시점과 무관하게 전부 포함한다.
- 완료/만료 이력은 최근 50건을 더한 뒤 id 기준으로 중복 제거한다.
- 현금배당은 `cashAmount`, 무상증자/주식배당은 `shareQuantity`로 확인한다.
- 홈 화면의 기업 이벤트 패널은 이 API로 배정 예정/지급 완료 상태를 보여준다.

## 유상증자

관리자 입력:

- 공모 방식: `SHAREHOLDER_ALLOCATION` 또는 `PUBLIC_OFFERING`
- 발행수
- 발행가
- 청약 시작일/종료일
- 권리락일(주주배정만)
- 주주확정 기준일(주주배정만)
- 납입일
- 신주상장일

back 처리:

- 주주배정은 등록 시점 현재가로 예상 base price와 예상 이론권리락가격을 저장한다. 예상가격도 권리부종가가 발행가보다 높을 때만 희석 산식을 적용하고 1원 미만을 절사한다.
- 일반공모는 권리락일과 이론권리락가격을 사용하지 않는다.
- 주주배정 권리락일은 이전 장마감 snapshot을 확보할 수 있도록 현재 시뮬레이션 날짜보다 미래여야 한다.
- `subscriptionStartDate <= subscriptionEndDate < paymentDate < listingDate`를 검증한다.
- 주주배정은 `exRightsDate < recordDate <= subscriptionStartDate`도 검증한다.
- 같은 종목의 진행 중 주식구조 변경/상장폐지 이벤트와 상호 배타로 등록한다.
- `stock_corporate_action`에 `ANNOUNCED`로 저장한다.

사용자 청약:

- `POST /api/stock/v1/markets/corporate-actions/{actionId}/subscriptions/me`
- 인증된 본인 활성 계좌만 사용하며 요청 body는 양수 `shareQuantity` 하나다.
- 장 마감 후(`AFTER_CLOSE`)이면서 청약 시작일과 종료일 사이일 때만 허용한다.
- 주주배정은 `EX_RIGHTS_APPLIED` 상태와 본인 `ANNOUNCED`/`PARTIALLY_SUBSCRIBED` entitlement의 남은 배정수량을 확인한다. 부분 청약은 청약 종료일까지 누적할 수 있다.
- 일반공모는 `ANNOUNCED` 상태, 계좌별 1회, 전체 잔여 발행수량을 확인한다.
- lock 순서는 corporate action -> account -> shareholder entitlement다. 일반공모 잔여수량 계산도 action lock 안에서 수행한다.
- 계좌 현금을 차감하고 action/entitlement/효력 거래일을 포함한 `CAPITAL_INCREASE_SUBSCRIPTION` cash-flow와 청약 상태를 같은 트랜잭션에 기록한다.
- 청약 대금은 외부 인출이 아니라 상장 대기 예약자산이다. `PARTIALLY_SUBSCRIBED`/`SUBSCRIBED` 동안 포트폴리오/관리자/자동참여자 총자산에 포함한다.

batch 처리:

1. 주주배정은 권리락일 전 최신 완료 전체시장 close cycle/run을 action에 고정한다. 그 snapshot의 권리부종가와 증자 전 발행주식수로 가격을 확정하고 한국시장 호가단위로 정규화해 action 가격과 가격/tick을 함께 갱신한다.
2. 같은 snapshot의 계좌별 보유수량으로 배정수량을 계산한다.
3. 일반공모는 권리락 처리 없이 `ANNOUNCED` 상태에서 청약한다.
4. 같은 날 현금배당 지급과 자동청약이 모두 예정돼 있으면 배당을 먼저 지급한 뒤 자동청약이 가용 현금을 계산한다.
5. 장 마감 후 자동참여자는 이벤트 전용 프로필 정책 범위에서 같은 청약 원장에 참여한다.
6. 납입일에 완전 미청약 주주배정 entitlement는 `EXPIRED`, 부분 청약 entitlement는 실제 청약수량과 `forfeitedShareQuantity`를 합쳐 배정수량과 일치하도록 확정하고 action을 `PAID`로 전이한다.
7. 신주상장일에는 `SUBSCRIBED` 합계만큼만 발행/유통주식수를 늘리고 계좌 보유수량/평균단가를 반영한 뒤 entitlement와 action을 `PAID`/`LISTED`로 전이한다.

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

- 현재가를 `base_price`로 기록한다.
- 현금배당은 현재가를 강제로 낮추지 않는다.
- 배당락일은 현재 시뮬레이션 날짜보다 미래여야 한다.

batch 처리:

1. 배당락일 전 마지막 완료 장마감 snapshot의 보유수량으로 `stock_corporate_action_entitlement`를 만든다.
2. 지급일에 entitlement별 `cash_amount`를 `stock_account.cash_balance`에 더한다.
3. entitlement와 corporate action을 `PAID`로 전이한다.

## 무상증자와 주식배당

관리자 입력:

- 배정 주식수
- 권리락일
- 신주상장일

back 처리:

- 등록 시점 현재가로 예상 base price와 예상 이론권리락가격을 저장하며 예상가격의 1원 미만을 절사한다.
- 권리락일은 현재 시뮬레이션 날짜보다 미래여야 한다.
- `BONUS_ISSUE` 또는 `STOCK_DIVIDEND`를 `ANNOUNCED`로 저장한다.

batch 처리:

1. 권리락일 전 최신 완료 전체시장 snapshot의 권리부종가와 당시 발행주식수로 `closePrice * existingShares / (existingShares + newShares)`를 계산하고 한국시장 호가단위로 정규화해 가격을 확정한다.
2. 같은 장마감 snapshot의 보유수량으로 share entitlement를 만든다.
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
- 양도 가능한 신주인수권 거래/별도 권리공모 이벤트
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
