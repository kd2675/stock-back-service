# Auto Market

## 현재 구현

자동장은 `ORDER_BOOK` 시장에 자동 참여자를 넣어 유동성을 만드는 기능이다. 사용자의 실제 주문과 같은 `stock_order` 원장을 사용하며, batch가 자동 참여자의 계좌를 준비하고 자동 주문을 생성한다.

## 관련 코드

- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/profile/AutoProfileBehavior.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/profile/*Behavior.java`
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
7. 자동 참여자 `profile_type`에 맞는 `AutoProfileBehavior` 구현체를 찾고, 그 구현체가 유효 강도, 주문 수, 매수/매도 방향, 수량 상한, TTL을 결정한다.
8. intensity 10은 해당 참여자의 매수 우위/공격적 매수가로 상승 압력을 만들고, intensity 1은 보유가 있는 참여자의 매도 우위/공격적 매도가로 하락 압력을 만든다.
9. 현재가, 전일종가, 최우선 매수/매도, 호가 잔량, 평균단가, 종목별 tick size 기준으로 자동 주문 방향과 가격을 만든다.
10. 자동 주문을 `stock_order`에 넣는다.
11. 같은 job 안에서 `InternalOrderBookExecutionService.executeEligibleOrders()`를 호출해 바로 매칭을 시도한다.

## 현재 설정

- `stock.batch.auto-market.enabled`
- `stock.batch.auto-market.initial-delay-ms`
- `stock.batch.auto-market.fixed-delay-ms`
- `stock.batch.auto-participant-cash-flow.enabled`
- `stock.batch.auto-participant-cash-flow.initial-delay-ms`
- `stock.batch.auto-participant-cash-flow.fixed-delay-ms`
- 자동 참여자 입금/회수 이력: `stock_account_cash_flow`
- 배치 자동 실행 중지/재개 상태는 yml 런타임 토글 설정이 아니라 `stock_batch_job_control.runtime_enabled` DB 행만 기준으로 한다. 행이 없으면 batch 서버가 최초 조회 시 `runtime_enabled=true`로 생성한다.
- 자동 참여자 주기 입금은 주문 생성 job과 분리된 `auto-participant-cash-flow` job에서 처리한다. 어드민은 stock-back 프록시 API를 통해 batch의 `auto-participant-cash-flow/status`를 조회/변경하고, `auto-participant-cash-flow/run`을 수동 실행한다. `runtime_enabled=false`는 스케줄러 자동 실행을 건너뛰게 하는 운영 제어값이며, 수동 run API는 관리자 명시 실행으로 별도 허용한다.
- stock-batch job 중복 실행 방지는 JVM 메모리 락이 아니라 `stock_batch_job_lock` DB 테이블 기준으로 처리한다. 배치 서버가 여러 대 떠도 같은 job은 하나만 실행되어야 한다.
- 자동 참여자 심리 프로필: `stock_auto_participant.profile_type`
- 참여자별-종목별 가동/강도: `stock_auto_participant_symbol_config`
- 종목별 최신 평가 보고서 점수: `stock_instrument_report_event`
- 종목별 자동장 가동/기본 강도/최대 수량/TTL: `stock_auto_market_config`
- 종목별 tick size: `stock_order_book_instrument`

## 자동 참여자 프로필

각 프로필은 `stock-batch-service/src/main/java/stock/batch/service/automarket/profile` 아래의 별도 `*Behavior` 클래스로 구현한다. 공통 서비스가 하나의 가중치 공식으로 모든 프로필을 처리하지 않는다. `AbstractAutoProfileBehavior`는 현금/보유 제약, 강한 관리자 override, 기본 강도 압력 같은 공통 불변식만 제공하고, 프로필별 핵심 판단은 각 behavior 클래스가 override한다.

| 프로필 | 실제 반영 신호 |
| --- | --- |
| `NEWS_REACTIVE` | 최신 종목 평가 보고서 점수를 강하게 섞어 유효 강도를 바꾼다. |
| `MOMENTUM_FOLLOWER` | 현재가가 전일종가보다 오르면 매수 쪽, 내리면 매도 쪽으로 따라간다. |
| `CONTRARIAN` | 상승 후 매수 편향을 줄이고 하락 후 매수 편향을 키운다. |
| `LOSS_AVERSE` | 손실 중인 보유 종목을 잘 팔지 않고, 손실 구간에서 매수/보유 쪽으로 기운다. |
| `OVERCONFIDENT` | 평가손익이 플러스일수록 주문 수가 늘고 공격성이 올라간다. |
| `HERD_FOLLOWER` | 미체결 매수/매도 잔량이 한쪽으로 몰리면 그 방향을 따라간다. |
| `MARKET_MAKER` | 군중 방향과 반대로 기울며, 최우선 매수/매도 주변에 호가를 공급한다. |
| `NOISE_TRADER` | 랜덤 노이즈가 크지만 현금이 없으면 매수하지 않고 보유가 없으면 매도하지 않는다. |
| `VALUE_ANCHOR` | 현재가와 전일종가 괴리를 기준으로 기준가보다 싸면 사고 비싸면 팔아 차익을 줄인다. |
| `SCALPER` | 주문 빈도와 호가 공격성이 높고 짧은 흐름에 자주 반응한다. |
| `DAY_TRADER` | 단타형보다 더 높은 주문 빈도와 공격성으로 하루 안 가격 흐름을 따라간다. |
| `SWING_TRADER` | 추세추종과 역추세를 섞어 며칠 단위 가격 흐름처럼 움직인다. |
| `LONG_TERM_HOLDER` | 중립 신호에서는 주문을 쉬고, 큰 손실과 큰 수익 모두에서 성급한 매도 회피가 강하다. |
| `PAYDAY_ACCUMULATOR` | 설정한 입금 주기마다 자동 현금 유입 후 매수 편향을 가진다. |
| `DIVIDEND_REINVESTOR` | 작은 정기 현금 유입을 다시 매수에 쓰며, 장기 보유와 저가 매수 성향을 함께 가진다. |
| `LIMIT_DOWN_TRAPPED` | 깊은 손실 구간에서 현금이 부족해도 강제 손절 매도를 하지 않는다. |
| `AVERAGE_DOWN_BUYER` | 손실 구간과 급락 구간에서 평균단가를 낮추기 위해 추가 매수한다. |
| `STOP_LOSS_TRADER` | 손실 구간과 하락 모멘텀에서 빠르게 매도해 손절을 우선한다. |
| `FOMO_BUYER` | 급등과 매수 군중 신호를 강하게 따라가며 주문 빈도와 호가 공격성이 높다. |
| `PANIC_SELLER` | 급락과 군중 신호에 민감하게 매도 쪽으로 움직인다. |
| `DIP_BUYER` | 급락 구간에서 저점매수 편향이 강하다. |
| `PROFIT_LOCKER` | 수익 구간에서 빠르게 매도해 이익 확정을 우선한다. |
| `LIQUIDITY_AVOIDANT` | 중립 신호에서는 주문을 쉬고, 강한 신호에서도 낮은 주문 빈도와 작은 수량으로 반응한다. |
| `CASH_DEFENSIVE` | 현금 보유를 선호해 중립 신호에서는 쉬고 강한 신호에서도 작은 주문만 낸다. |
| `WHALE` | 주문 수보다 주문 크기를 크게 가져간다. |
| `SMALL_DIVERSIFIER` | 작은 주문을 여러 번 나눠 분산한다. |
| `OBSERVER` | 중립 신호에서는 주문을 쉬고 강한 신호에서만 작은 수량으로 반응한다. |

## 현재 불변식

- 자동장은 주문장 시장에만 붙는다.
- 자동 참여자도 일반 사용자와 같은 계좌/보유/주문 원장을 쓰며, 운용 현금 입금/회수와 종목별 전략은 관리자 API/UI에서 제어한다.
- 자동 참여자에게 초기 보유 주식은 지급하지 않는다. 보유는 실제 매수 체결로만 생긴다.
- 자동 참여자 성향은 항상 주된 기준이다. 평가 보고서는 종목별 최신 관리 신호로만 섞이며, 보고서가 없어도 자동장은 동작한다.
- 자동 참여자 profile type은 실제 회원 식별 구조를 바꾸지 않고, 같은 `user_key` 기반 자동참여자에 심리/행동 정책만 부여한다.
- `AutoParticipantProfileType` 값 하나에는 반드시 같은 타입을 반환하는 `*Behavior` 클래스 하나가 있어야 하며, `AutoProfileBehaviorRegistry.createDefault()`에 등록해야 한다.
- 프로필 설정의 `order_multiplier`, `quantity_multiplier`, `profit_taking_weight`는 실제 자동 주문 수, 주문 수량 상한, 보유 수익 구간의 매도 전환에 반영된다.
- 프로필 설정의 핵심 행동 가중치가 저장되어 있지 않으면 해당 프로필의 기본 심리 성향을 유지한다. 기존 커스텀 설정 행이 있어도 새 행동 가중치가 비어 있으면 기본 성향을 0으로 덮어쓰지 않는다.
- `PAYDAY_ACCUMULATOR`는 프로필 설정의 `recurring_deposit_amount`, `recurring_deposit_interval_value`, `recurring_deposit_interval_unit` 기준으로 `AUTO_PROFILE_RECURRING_DEPOSIT` 현금 유입을 만들고, 설정 주기 안의 원장 기록으로 중복 입금을 막는다. `recurring_deposit_interval_days`는 기존 일 단위 설정 호환용으로만 유지한다.
- `DIVIDEND_REINVESTOR`는 월급매수형보다 작은 정기 현금 유입을 쓰되, 장기 보유와 저가 매수 성향으로 현금을 다시 주문장에 투입한다.
- `LONG_TERM_HOLDER`는 주문 빈도와 호가 공격성이 낮고 보유 인내도가 높아 매도를 늦춘다.
- `LIMIT_DOWN_TRAPPED`는 큰 손실 구간에서 매도 회피가 강해 하락 중에도 쉽게 손절하지 않는다.
- `AVERAGE_DOWN_BUYER`는 손실 구간에서 보유를 줄이기보다 추가 매수와 수량 확대를 통해 평균단가를 낮추려 한다.
- `STOP_LOSS_TRADER`는 손실 회피보다 손절 실행을 우선해 하락 모멘텀과 손실 구간에서 매도 쪽으로 움직인다.
- `FOMO_BUYER`는 급등 모멘텀과 미체결 매수 잔량 쏠림에 민감해 상승장에서 추격 매수처럼 행동한다.
- `PROFIT_LOCKER`는 수익 구간에서 보유 인내보다 이익 확정을 우선해 매도 쪽으로 움직인다.
- `DAY_TRADER`는 주문 빈도와 호가 공격성이 높아 짧은 가격 흐름에 빠르게 반응한다.
- `SWING_TRADER`는 추세와 반전 신호를 모두 반영하는 중간 속도 프로필이다.
- `CASH_DEFENSIVE`는 관망형처럼 쉬는 구간이 있고, 강한 신호에서도 낮은 주문 빈도와 작은 수량으로 현금 여력을 남긴다.
- 자동 주문 TTL은 종목별 `stock_auto_market_config.order_ttl_seconds`에 프로필 설정의 `order_ttl_multiplier`를 곱해 계산한다. `SCALPER`, `DAY_TRADER`, `PANIC_SELLER`는 짧게, `LONG_TERM_HOLDER`, `LIMIT_DOWN_TRAPPED`, `OBSERVER`는 길게 유지한다.
- 자동 주문은 open order로 남을 수 있고 TTL로 취소된다.
- 자동장은 수요/공급 시장의 보조 기능이지 현재가 시장 기능이 아니다.

## 앞으로 구현할 후보

- 발행자/시장공급자 매도 주문 정책 고도화.
- 자동 주문이 사용자 주문에 미치는 영향을 모니터링하는 지표.
- 프로필별 행동 성과 지표: 주문 수, 체결률, 매수/매도 비율, 평가손익, 평균 보유 시간.
- 시장 심리 확장: 뉴스 이벤트, 급등락, 유동성 부족, 연속 손실/연속 수익에 따른 프로필별 민감도 추가.

## 바꿀 때 순서

1. 설정만 바꾸는지 원장 구조가 필요한지 분리한다.
2. 원장 구조가 필요하면 `stock_auto_market_config`, `stock_auto_participant`, `stock_auto_participant_symbol_config` DDL부터 바꾼다.
3. 보고서 신호를 바꾸면 `stock_instrument_report_event` 최신 이벤트 조회와 `AutoProfileBehavior.effectiveIntensity`를 함께 확인한다.
4. 프로필별 행동을 바꾸면 해당 `*Behavior` 클래스를 먼저 수정하고, 공통 원장/가격/TTL 흐름이 필요할 때만 `AutoMarketService`를 바꾼다.
5. `InternalOrderBookExecutionService` 체결 영향 테스트를 확인한다.
6. admin 화면에서 설정을 노출할지 결정한다.

## 검증

- `node scripts/verify-stock-auto-profiles.mjs`
- `./gradlew :stock-batch-service:test --tests '*AutoMarketServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*InternalOrderBookExecutionServiceTest*'`
- `cd stock-front-service && npm run build`
