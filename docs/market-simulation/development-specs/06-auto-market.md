# Auto Market

## 현재 구현

자동장은 `ORDER_BOOK` 시장에 자동 참여자를 넣어 유동성을 만드는 기능이다. 사용자의 실제 주문과 같은 `stock_order` 원장을 사용하며, batch가 자동 참여자의 계좌를 준비하고 자동 주문을 생성한다.

## 관련 코드

- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/AutoMarketScheduler.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/biz/StockBatchJobService.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`

## 현재 플로우

1. `stock_auto_participant.enabled = true`인 참여자를 찾는다.
2. `stock_auto_market_config.enabled = true`이고 주문장 market status가 `OPEN`인 종목을 찾는다.
3. 자동 참여자 계좌가 없으면 `cash_balance = 0`인 계좌만 만든다.
4. 오래된 자동 주문은 TTL 기준으로 취소하고 예약 현금/예약 수량을 해제한다.
5. 참여자별-종목별 intensity와 최신 활성 평가 보고서 점수로 유효 강도를 계산한다.
6. 평가 보고서가 없거나 최신 이벤트가 `DELETE`이면 참여자 intensity만 사용한다.
7. 자동 참여자 `profile_type`에 따라 보고서 민감도, 모멘텀/역추세, 손실회피, 군중추종, 시장조성, 노이즈, 주문 빈도와 호가 공격성을 보정한다.
8. intensity 10은 해당 참여자의 매수 우위/공격적 매수가로 상승 압력을 만들고, intensity 1은 보유가 있는 참여자의 매도 우위/공격적 매도가로 하락 압력을 만든다.
9. 현재가, 전일종가, 최우선 매수/매도, 호가 잔량, 평균단가, 종목별 tick size 기준으로 자동 주문 방향과 가격을 만든다.
10. 자동 주문을 `stock_order`에 넣는다.
11. 같은 job 안에서 `InternalOrderBookExecutionService.executeEligibleOrders()`를 호출해 바로 매칭을 시도한다.

## 현재 설정

- `stock.batch.auto-market.enabled`
- `stock.batch.auto-market.initial-delay-ms`
- `stock.batch.auto-market.fixed-delay-ms`
- 자동 참여자 입금/회수 이력: `stock_account_cash_flow`
- 자동 참여자 심리 프로필: `stock_auto_participant.profile_type`
- 참여자별-종목별 가동/강도: `stock_auto_participant_symbol_config`
- 종목별 최신 평가 보고서 점수: `stock_instrument_report_event`
- 종목별 자동장 가동/기본 강도/최대 수량/TTL: `stock_auto_market_config`
- 종목별 tick size: `stock_order_book_instrument`

## 현재 불변식

- 자동장은 주문장 시장에만 붙는다.
- 자동 참여자도 일반 사용자와 같은 계좌/보유/주문 원장을 쓰며, 운용 현금 입금/회수와 종목별 전략은 관리자 API/UI에서 제어한다.
- 자동 참여자에게 초기 보유 주식은 지급하지 않는다. 보유는 실제 매수 체결로만 생긴다.
- 자동 참여자 성향은 항상 주된 기준이다. 평가 보고서는 종목별 최신 관리 신호로만 섞이며, 보고서가 없어도 자동장은 동작한다.
- 자동 참여자 profile type은 실제 회원 식별 구조를 바꾸지 않고, 같은 `user_key` 기반 자동참여자에 심리/행동 정책만 부여한다.
- 자동 주문은 open order로 남을 수 있고 TTL로 취소된다.
- 자동장은 수요/공급 시장의 보조 기능이지 현재가 시장 기능이 아니다.

## 앞으로 구현할 후보

- 전략 유형 분리: market maker, trend follower, noise trader.
- 발행자/시장공급자 매도 주문 정책 고도화.
- 자동 주문이 사용자 주문에 미치는 영향을 모니터링하는 지표.

## 바꿀 때 순서

1. 설정만 바꾸는지 원장 구조가 필요한지 분리한다.
2. 원장 구조가 필요하면 `stock_auto_market_config`, `stock_auto_participant`, `stock_auto_participant_symbol_config` DDL부터 바꾼다.
3. 보고서 신호를 바꾸면 `stock_instrument_report_event` 최신 이벤트 조회와 `AutoMarketService.effectiveIntensity`를 함께 확인한다.
4. `AutoMarketService`에서 계좌 준비, 가격 생성, TTL 취소 정책을 바꾼다.
5. `InternalOrderBookExecutionService` 체결 영향 테스트를 확인한다.
6. admin 화면에서 설정을 노출할지 결정한다.

## 검증

- `./gradlew :stock-batch-service:test --tests '*AutoMarketServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*InternalOrderBookExecutionServiceTest*'`
- `cd stock-front-service && npm run build`
