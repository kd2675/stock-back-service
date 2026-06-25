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
2. 자동 참여자 계좌가 없으면 생성한다.
3. 오래된 자동 주문은 TTL 기준으로 취소하고 예약금/예약수량을 반환한다.
4. 참여자별-종목별 intensity와 최신 보고서 점수로 유효 강도를 만든다.
5. 참여자 `profile_type`에 맞는 `*Behavior` 클래스가 주문 수, 매수/매도 방향, 수량 상한, TTL을 정한다.
6. `AutoMarketService`가 가격을 만들고 현금/보유 제약을 확인한다.
7. 실제 `stock_order`에 `ORDER_BOOK`, `LIMIT` 주문을 만든다.
8. `StockBatchJobService.runAutoMarket`은 자동 주문 생성 후 `InternalOrderBookExecutionService.executeEligibleOrders`를 같이 실행한다.

## 현재 불변식

- 자동 주문도 실제 사용자 주문과 같은 체결 엔진을 탄다.
- 자동장은 주문장 종목만 대상으로 한다.
- market status가 `OPEN`인 종목만 자동 주문을 만든다.
- 자동 참여자는 초기 보유 주식을 받지 않는다. 보유는 주문장 매수 체결로만 생긴다.
- `AutoParticipantProfileType` 하나에는 하나의 독립 `*Behavior` 클래스가 있어야 한다.

## 앞으로 구현할 후보

- 참여자별 behavior 성과 지표.
- 자동 주문의 가격 분포를 더 시장스럽게 조정.
- 자동 주문과 일반 사용자 주문의 표시 구분.

## 변경 순서

1. 자동장 설정 컬럼이 종목 기본값인지, 참여자별-종목별 전략인지 먼저 구분한다.
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
