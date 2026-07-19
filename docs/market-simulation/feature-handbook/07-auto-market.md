# Auto Market

## 현재 구현

자동장은 수요/공급 주문장 시장의 유동성을 만들기 위한 batch 기능이다. 별도 fake 체결을 만들지 않고 자동 참여자도 실제 `stock_order`에 주문을 남긴다.

- batch service: `AutoMarketService`
- profile behavior: `automarket/profile/AutoProfileBehavior`, `automarket/profile/*Behavior`
- scheduler: `AutoMarketScheduler`
- 수동 job: `StockBatchJobController.runAutoMarket`
- job orchestration: `StockBatchJobService.runAutoMarket`
- config table: `stock_auto_market_config`
- participant table: `stock_auto_participant`
- participant-symbol strategy table: `stock_auto_participant_symbol_config`

## 코드 흐름

1. `runAutoMarketStep`이 enabled participant와 enabled config를 조회한다.
2. 06시 주 압력은 항상 갱신하고, 일일 적용 횟수 가중치로 선택된 09·12·15시 슬롯만 추가 갱신한다. 선택되지 않은 슬롯은 직전 값을 이어받으며, 30분 보조 압력과 70:30으로 합성한다.
3. 자동 참여자 계좌가 없으면 생성한다.
4. 오래된 자동 주문은 TTL 기준으로 취소하고 예약금/예약수량을 반환한다.
5. 참여자별-종목별 주문 활동 강도와 최신 보고서 점수를 읽는다. 명시 설정이 없으면 활동 강도 5를 쓴다.
6. 참여자 `profile_type`에 맞는 `*Behavior` 클래스가 압력·주문장·계좌 상태를 반영해 주문 수, 매수/매도 방향, 수량 상한, TTL을 정한다.
7. `AutoMarketService`가 가격을 만들고 현금/보유 제약을 확인한다.
8. 실제 `stock_order`에 `ORDER_BOOK`, `LIMIT` 주문을 만들고 ready-symbol 큐에 종목을 등록한다.
9. 상시 체결 worker가 ready-symbol을 받아 독립적으로 체결을 시도한다.

## 현재 불변식

- 자동 주문도 실제 사용자 주문과 같은 체결 엔진을 탄다.
- 자동장은 주문장 종목만 대상으로 한다.
- market status가 `OPEN`인 종목만 자동 주문을 만든다.
- 모든 주·보조 압력과 관리자 분포 편향은 -100~100이다.
- 주 압력은 하루 1~4회만 새로 생성된다. 06시는 필수이고 나머지 갱신 슬롯은 09·12·15시 중 무작위로 선택되며, 보조 압력은 30분 구간마다 한 번 생성된다. 같은 구간에서는 seed와 값을 재사용한다.
- 일반 자동 참여자의 가격 압력은 현재가 대비 비율로 호가 중심을 옮기며 기본 최대 이동률은 0.6%, 변동성 반영 후 절대 상한은 0.8%다. 중립 압력에서는 매수·매도를 중심가 양쪽에 두고, 상승 압력은 매수 교차 확률을 높이고 매도 교차 확률을 낮추며 하락 압력은 반대로 작동한다. 생성 단계는 시장성 지정가를 한 틱 밖으로 되돌리지 않고 실제 동일 계좌 자기체결만 체결 엔진에서 막는다.
- 자동 참여자는 초기 보유 주식을 받지 않는다. 보유는 주문장 매수 체결로만 생긴다.
- `AutoParticipantProfileType` 하나에는 하나의 독립 `*Behavior` 클래스가 있어야 한다.

## 앞으로 구현할 후보

- 참여자별 behavior 성과 지표.
- 자동 주문의 가격 분포를 더 시장스럽게 조정.
- 자동 주문과 일반 사용자 주문의 표시 구분.

## 변경 순서

1. 자동장 설정 컬럼이 종목별 압력 분포 편향인지, 참여자별-종목별 주문 활동 정책인지 먼저 구분한다.
2. 참여자별 전략이면 `stock_auto_participant_symbol_config`와 `AutoMarketService.findEnabledParticipantStrategies` SQL을 수정한다.
3. 주문 생성 정책은 해당 `*Behavior`, `placeAutoOrders`, `createAutoPrice` 순서로 본다.
4. 예약금/예약수량 불변식이 바뀌면 주문 취소 TTL 로직도 같이 수정한다.
5. 관리자 화면에 설정을 노출할 경우 back API와 front type을 추가한다.

## 검증

- `AutoMarketServiceTest`
- `StockBatchJobServiceTest`
- `InternalOrderBookExecutionServiceTest`
- `node scripts/verify-stock-auto-profiles.mjs`
- `./gradlew :stock-batch-service:test`
