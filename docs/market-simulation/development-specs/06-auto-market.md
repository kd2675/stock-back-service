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
5. 시뮬레이션 시각 06시 주 압력은 항상 갱신하고, 일일 1~4회 적용 가중치로 선택된 09·12·15시 슬롯만 추가 갱신한다. 선택되지 않은 슬롯은 직전 주 압력을 이어받고, 보조 압력은 30분 구간별로 생성하거나 읽는다.
6. 각 압력은 가격·자산 선호·변동성·유동성·체결 공격성 -100~100이며, 관리자 편향을 최빈값으로 쓰는 삼각분포에서 추출한다.
7. 주 압력 70%와 보조 압력 30%를 항목별로 합성한다.
8. 참여자별-종목별 주문 활동 강도와 최신 활성 평가 보고서 점수, 프로필 정책을 함께 읽는다. 명시 강도가 없으면 5를 쓰며 보고서 점수는 활동 강도를 바꾸지 않는다.
9. 자동 참여자 `profile_type`에 맞는 `AutoProfileBehavior` 구현체가 주문 수, 매수/매도 방향, 수량 상한, TTL을 결정한다.
10. 현재가, 전일종가, 최우선 매수/매도, 호가 잔량, 평균단가, 시장·가격대별 호가단위 기준으로 자동 주문 방향과 가격을 만든다. 일반 자동 참여자의 방향 가격은 가격 압력을 현재가 비율로 반영하고 기본 0.6%, 변동성 반영 후 최대 0.8%로 제한한다. 중립 압력에서는 매수를 중심가 아래, 매도를 중심가 위에 두고 압력이 강해질수록 우세 방향 호가가 중심가에 접근한다. 별도 교차 추첨은 상승 압력에서 매수를 높이고 매도를 낮추며 하락 압력에서는 반대로 적용한다. 생성 단계에서는 상대 또는 자기 반대 호가를 피해 한 틱 밖으로 재가격하지 않는다. V2 시장조성은 명시적 시장조성 가격 모드와 목표 재고 모드가 모두 설정된 경우에만 동작하며, 종목별 재고 목표를 기준으로 양방향 수동 호가를 만든다. 일일 가격 제한으로 수동 호가를 만들 수 없으면 상대 호가를 교차시키지 않고 해당 주문을 생략한다.
11. 자동 주문을 `stock_order`에 넣고 ready-symbol 큐에 종목을 등록한다.
12. 상시 체결 worker가 큐를 받아 주문장 매칭을 시도한다.

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
- `behavior_model_version`은 `stock_auto_participant_profile_config`에만 저장하며 `V1` 또는 `V2`를 가진다. 같은 프로필의 모든 자동 참여자는 동일 모델을 사용하고 참여자 행에는 개인별 모델 선택 컬럼을 두지 않는다. 현재 프로필 설정은 모두 V2로 전환하며, 운영 지표가 기준을 벗어날 때만 해당 프로필 전체를 V1으로 되돌린다. 이미 생성된 주문은 `stock_order.auto_behavior_model_version` 스냅샷을 유지해 설정 변경 중에도 실행 의미가 바뀌지 않는다. 판단 비교 전용 저장 테이블이나 주문 hot path의 추가 DB 쓰기는 두지 않는다.
- stock-batch job 중복 실행 방지는 JVM 메모리 락이 아니라 `stock_batch_job_lock` DB 테이블 기준으로 처리한다. 배치 서버가 여러 대 떠도 같은 job은 하나만 실행되어야 한다.
- 자동 참여자 심리 프로필: `stock_auto_participant.profile_type`
- 참여자별-종목별 가동/주문 활동 강도: `stock_auto_participant_symbol_config`
- 종목별 최신 평가 보고서 점수: `stock_instrument_report_event`
- 종목별 자동장 가동/주·보조 압력 분포 편향/최대 수량/TTL: `stock_auto_market_config`
- 종목의 저장 기준 호가단위: `stock_order_book_instrument.tick_size`. 실제 주문 검증과 자동 가격은 시장·지정가 가격대별 동적 호가단위를 다시 계산한다.

## 자동 참여자 프로필

각 프로필은 `stock-batch-service/src/main/java/stock/batch/service/automarket/profile` 아래의 별도 `*Behavior` 클래스로 구현한다. 공통 서비스가 하나의 가중치 공식으로 모든 프로필을 처리하지 않는다. `AbstractAutoProfileBehavior`는 현금/보유 제약, 강한 관리자 override, 기본 활동도 같은 공통 불변식만 제공하고, 프로필별 핵심 판단은 각 behavior 클래스가 override한다.

| 프로필 | 실제 반영 신호 |
| --- | --- |
| `NEWS_REACTIVE` | 최신 종목 평가 보고서의 호재·악재 방향을 첫 주문과 가격 압력에 강하게 반영한다. |
| `MOMENTUM_FOLLOWER` | 1시간 모멘텀과 직전 거래일 수익률의 방향이 일치할 때만 추세를 따라간다. |
| `CONTRARIAN` | 3·5거래일 수익률이 함께 과도하게 움직일 때 반대 방향으로 대응한다. |
| `LOSS_AVERSE` | 손실 구간에서 강제 물타기나 매도 대신 주문을 쉬어 손실 확정을 미룬다. |
| `OVERCONFIDENT` | 미실현 이익 또는 최근 5거래일 실현 성과와 1시간·1거래일 상승이 함께 확인되면 위험을 늘린다. |
| `HERD_FOLLOWER` | 호가 깊이, 5분 모멘텀, 최근 5분 실제 체결량·참여 계좌가 같은 방향을 확인할 때만 따라간다. |
| `MARKET_MAKER` | 전체 주식 목표 50%를 해당 계좌의 활성 자동장 종목 수로 나눈 종목별 목표와 ±20% 밴드 안에서는 수동 양방향 호가를 쌍으로 공급하고, 밴드 밖에서는 재고를 목표로 되돌리는 방향만 유지한다. |
| `NOISE_TRADER` | 랜덤 노이즈가 크지만 현금이 없으면 매수하지 않고 보유가 없으면 매도하지 않는다. |
| `VALUE_ANCHOR` | 펀더멘털 적정가가 아니라 20거래일 수익률 괴리를 천천히 되돌리는 중기 기준가 회귀형이다. |
| `SCALPER` | 5분 신호, 3~5분 최대 보유시간, 작은 익절·손절과 장 마감 청산을 사용한다. |
| `DAY_TRADER` | 마감 90~150분 전 신규 행동을 멈추고 마지막 45~75분에 보유량을 분할 청산한다. |
| `SWING_TRADER` | 3·5·10거래일 신호와 2~10거래일 보유기간을 사용한다. 진입보다 완화된 청산 조건과 최소 보유기간으로 신호 히스테리시스를 둔다. |
| `LONG_TERM_HOLDER` | 15~25거래일 최소 보유기간과 계좌별 5거래일 리밸런싱 창을 사용한다. |
| `PAYDAY_ACCUMULATOR` | 실제 정기 입금에서 아직 예약·체결에 쓰이지 않은 전용 예산 안에서만 매수한다. |
| `DIVIDEND_REINVESTOR` | 배당 원천 종목별 전용 예산의 발생·예약·체결·취소 반환을 대사하며 남은 예산 안에서만 재투자한다. |
| `LIMIT_DOWN_TRAPPED` | 하한가에서는 보유분 매도를 시도하지만 유동성 부족으로 미체결될 수 있고, 하한가가 아닌 깊은 손실에서는 보유한다. |
| `AVERAGE_DOWN_BUYER` | 거래일당 1회·최대 3회·예상 종목 비중 25% 안에서만 평균단가 낮추기를 시도한다. |
| `STOP_LOSS_TRADER` | 수익률 -5% 이하 또는 매우 강한 하락 모멘텀에서 매도하며 작은 손실에서는 주문을 쉰다. |
| `FOMO_BUYER` | 5분·1시간 상승, 매수 호가 깊이, 최근 5분 실제 체결량·참여 계좌가 함께 확인될 때만 추격한다. |
| `PANIC_SELLER` | 5분·1시간 하락, 매도 호가 깊이, 최근 5분 실제 체결량·참여 계좌가 함께 확인될 때 보유분을 판다. |
| `DIP_BUYER` | 1시간 급락 뒤 5분 반전이 확인될 때만 저점매수를 시도한다. |
| `PROFIT_LOCKER` | 계좌별 +4~6% 임계값에서 가용 보유량의 35%를 부분 익절한다. |
| `LIQUIDITY_AVOIDANT` | 실제 스프레드·가시 깊이와 관측 가능한 최근 체결량·참여 계좌가 부족하면 주문하지 않는다. |
| `CASH_DEFENSIVE` | 현금 비중 60~70% 범위를 목표로 매도·제한 매수를 결정한다. |
| `WHALE` | 큰 주문을 내되 평균 거래량·반대 호가 깊이·계좌 위험 상한으로 제한한다. |
| `SMALL_DIVERSIFIER` | 종목 비중 15~25%와 최소 보유 종목 수를 기준으로 작은 주문을 내며, due 계좌의 현재 보유와 미체결 매수 노출이 낮은 활성 종목을 우선한다. |
| `OBSERVER` | 중립 신호에서는 주문을 쉬고 강한 신호에서만 작은 수량으로 반응한다. |

## 현재 불변식

- 자동장은 주문장 시장에만 붙는다.
- 자동 참여자도 일반 사용자와 같은 계좌/보유/주문 원장을 쓰며, 운용 현금 입금/회수와 종목별 전략은 관리자 API/UI에서 제어한다.
- 자동 참여자 탈퇴는 장부 동결 permit과 계좌 잠금 아래에서 모든 미체결 주문·예약을 해제하고 전용 자금 예산을 만료한 뒤 수행한다. 보유주식은 비거래 `SYSTEM_CUSTODY` 계정으로 원가를 보존해 이전하고, 잔여 현금은 `ADMIN_WITHDRAW` 현금흐름과 탈퇴 감사 원장으로 회수한다. 계좌 row와 저장 전략·과거 주문·체결은 삭제하지 않고 계좌만 `CLOSED`로 종료한다. 지급·청약·상장 전 기업행사 권리가 있으면 미래 자산 유입 누락을 막기 위해 탈퇴를 거부한다. 탈퇴한 사용자 키는 재활성화하지 않아 1회성 정산 감사 계약을 유지한다. `CURRENT` 운영 조회와 `WITHDRAWN` 탈퇴 감사 조회를 분리하며, 탈퇴 후 자산·예약·활성 예산이 남으면 운영 점검 대상으로 표시한다. 감사 조회는 `receiver_account_id`·`receiver_role`을 권위 필드로 사용한다.
- 자동 참여자에게 초기 보유 주식은 지급하지 않는다. 보유는 실제 매수 체결로만 생긴다.
- 자동 참여자 성향은 항상 주된 기준이다. 평가 보고서는 종목별 최신 관리 신호로만 섞이며, 보고서가 없어도 자동장은 동작한다.
- 자동 참여자 profile type은 실제 회원 식별 구조를 바꾸지 않고, 같은 `user_key` 기반 자동참여자에 심리/행동 정책만 부여한다.
- `AutoParticipantProfileType` 값 하나에는 반드시 같은 타입을 반환하는 `*Behavior` 클래스 하나가 있어야 하며, `AutoProfileBehaviorRegistry.createDefault()`에 등록해야 한다.
- `order_multiplier`는 V1 재현과 기존 설정 마이그레이션용 호환 필드다. V2에서는 `decision_frequency_multiplier`, `orders_per_decision_multiplier`, `order_ttl_multiplier`, `quantity_multiplier`가 각각 의사결정 빈도·결정당 주문 수·TTL·수량만 제어한다. 구형 API가 V2 필드를 생략해도 변경된 `order_multiplier`나 행동 가중치에서 새 실행 정책을 다시 추론하지 않고 프로필별 명시 기본값을 사용한다. 빈도 또는 결정당 주문 수가 0이면 주문 결정을 중지한다.
- V2의 `pricing_mode`, `exit_mode`, `inventory_mode` 기본값은 프로필 유형별 명시 정책이다. 연속 행동 가중치의 0.8/0.85/0.9 경계로 모드를 바꾸는 계산은 V1 재현과 1회성 마이그레이션에만 남긴다.
- 프로필 설정의 핵심 행동 가중치가 저장되어 있지 않으면 해당 프로필의 기본 심리 성향을 유지한다. 기존 커스텀 설정 행이 있어도 새 행동 가중치가 비어 있으면 기본 성향을 0으로 덮어쓰지 않는다.
- `PAYDAY_ACCUMULATOR`의 거래 행동과 정기 자금 공급은 별도 계약이다. API는 프로필 행동 가중치와 독립된 `fundingPolicy`의 `recurringDepositAmount`, `recurringDepositIntervalValue`, `recurringDepositIntervalUnit`를 사용한다. 배치는 이 자금 정책으로 `AUTO_PROFILE_RECURRING_DEPOSIT` 현금 유입과 같은 금액의 전용 funding budget을 만들고, 아직 예약·체결되지 않은 가용 예산 안에서만 매수한다. 저장 컬럼 `recurring_deposit_amount`, `recurring_deposit_interval_value`, `recurring_deposit_interval_unit`는 호환을 위해 기존 프로필 설정 테이블에 유지하지만 `ProfilePolicy`의 행동 가중치에는 포함하지 않는다. `recurring_deposit_interval_days`는 기존 일 단위 설정 호환용으로만 유지한다.
- `DIVIDEND_REINVESTOR`는 `AUTO_PROFILE_RECURRING_DEPOSIT` 월급/정기 현금 유입을 쓰지 않는다. `DIVIDEND_PAYMENT`가 만든 배당 원천 종목별 전용 예산을 주문 예약·체결·취소와 대사하며 남은 금액 안에서만 매수한다.
- `LONG_TERM_HOLDER`는 주문 빈도와 호가 공격성이 낮고 보유 인내도가 높아 매도를 늦춘다.
- `LIMIT_DOWN_TRAPPED`는 큰 손실 구간에서 매도 회피가 강해 하락 중에도 쉽게 손절하지 않는다.
- `AVERAGE_DOWN_BUYER`는 손실 구간에서 보유를 줄이기보다 추가 매수와 수량 확대를 통해 평균단가를 낮추려 한다.
- `STOP_LOSS_TRADER`는 수익률 -5% 이하 또는 매우 강한 하락 모멘텀에서 손절하고, 작은 손실은 반복 매도하지 않도록 주문을 쉰다.
- `FOMO_BUYER`는 급등 모멘텀과 미체결 매수 잔량 쏠림에 민감해 상승장에서 추격 매수처럼 행동한다.
- `PROFIT_LOCKER`는 수익 구간에서 보유 인내보다 이익 확정을 우선해 매도 쪽으로 움직인다.
- `DAY_TRADER`는 주문 빈도와 호가 공격성이 높아 짧은 가격 흐름에 빠르게 반응한다.
- `SWING_TRADER`는 추세와 반전 신호를 모두 반영하는 중간 속도 프로필이다.
- `CASH_DEFENSIVE`는 관망형처럼 쉬는 구간이 있고, 강한 신호에서도 낮은 주문 빈도와 작은 수량으로 현금 여력을 남긴다.
- 자동 주문 TTL은 종목별 `stock_auto_market_config.order_ttl_seconds`에 프로필 설정의 `order_ttl_multiplier`를 곱해 계산한다. `SCALPER`, `DAY_TRADER`, `PANIC_SELLER`는 짧게, `LONG_TERM_HOLDER`, `LIMIT_DOWN_TRAPPED`, `OBSERVER`는 길게 유지한다.
- 자동 주문은 open order로 남을 수 있고 TTL로 취소된다.
- V2 시장조성형의 기존 수동 호가는 최소 30시뮬레이션 초를 유지하고 동일 방향 최우선 호가에서 기본 2틱보다 멀어진 경우에만 TTL 전에 교체 대상으로 삼는다. TTL 만료를 포함한 취소·재등록은 방향별 한 실행 10건으로 제한하고 후보도 종목별 bounded 조회한다.
- LP와 기관의 `reference_daily_volume`은 완료된 종목별 최근 20거래일 `buy_quantity` 평균을 사용한다. 이력이 없는 신규 종목만 유통주식 3%를 사용하며, 이상치로 위험 용량이 무한히 커지지 않도록 0.5~200% 유통주식 범위로 제한한다. 이 값은 체결 목표가 아니라 일일·주문별 위험 상한의 분모다.
- LP 기본 균형형은 최근 ADV의 체결 18%, 제출 90%까지 수용할 수 있지만 체결을 강제하지 않는다. 안정형/균형형/적극형 프리셋은 같은 ADV 분모를 유지한 채 체결 참여·호가 크기·TTL·손실 한도만 달리한다.
- 기관 권장 AUM은 균형형 5%, 가치형 3%, 모멘텀형 2%, 단기형 1%로 두고, 각 정책의 일일 AUM 회전율과 종목 ADV 참여율을 독립 상한으로 적용한다. 여러 기관의 합산 용량은 의미 있게 확보하되 비활성일 0주와 가격 불리 시 미체결·이월을 허용한다.
- 소액분산형의 종목 노출 선택은 profile shard마다 due 계좌·활성 후보 종목을 한 번에 조회한다. 보유수량과 열린 매수 잔량을 현재가로 평가해 미보유·저비중 종목을 우선하되 주문 수·주기·수량 상한은 바꾸지 않는다. 계좌별 또는 주문별 추가 조회는 금지하며 실제 MySQL에서 기존 계좌·종목/주문 인덱스 선택과 p95를 별도로 확인한다.
- 한 실행에서 여러 주문을 계획할 때 계좌 가용 현금·보유 수량과 미체결 잔량은 이미 계획한 주문을 메모리에서 반영해 다음 판단에 사용한다. 가격 기준 최우선 호가는 실행 시작 시점의 주문장으로 고정해 아직 저장되지 않은 자기 계획 주문이 다음 가격을 틱 단위로 연쇄 이동시키지 않는다. 이를 위해 주문마다 DB를 다시 조회하지 않는다.
- 자동 주문 생성 단계에는 상대·자기 반대 호가 교차 방지 재가격이 없다. 합법적인 가격대와 호가단위 안의 시장성 지정가는 그대로 주문 원장에 들어간다. 실제 동일 계좌 또는 동일 `self_trade_group_id` 자기체결은 후보 조회와 주문 잠금 후 재검증에서 차단하며, 상위 후보가 같은 계좌·기관 주문으로 가득 차도 최우선 외부 후보를 bounded 보강 조회해 뒤쪽의 정상 체결을 놓치지 않는다.
- 자동장은 수요/공급 시장의 보조 기능이지 현재가 시장 기능이 아니다.

## 앞으로 구현할 후보

- 발행자/시장공급자 매도 주문 정책 고도화.
- 자동 주문이 사용자 주문에 미치는 영향을 모니터링하는 지표.
- 프로필별 행동 성과 지표 중 결정·HOLD·V1/V2 행동 일치율, 주문 수, 체결률, 매수/매도 비율, 현재 보유일은 검증 보고서에서 확인한다. 평가손익과 완결 포지션 기준 평균 보유 시간은 후속 성과 원장 범위다.
- 시장 심리 확장: 뉴스 이벤트, 급등락, 유동성 부족, 연속 손실/연속 수익에 따른 프로필별 민감도 추가.

## 바꿀 때 순서

1. 설정만 바꾸는지 원장 구조가 필요한지 분리한다.
2. 원장 구조가 필요하면 `stock_auto_market_config`, `stock_auto_participant`, `stock_auto_participant_symbol_config` DDL부터 바꾼다.
3. 보고서 신호를 바꾸면 `stock_instrument_report_event` 최신 이벤트 조회, `AutoMarketConfig.reportPricePressure`, `AutoParticipantOrderPricing`을 함께 확인한다.
4. 프로필별 행동을 바꾸면 해당 `*Behavior` 클래스를 먼저 수정하고, 공통 원장/가격/TTL 흐름이 필요할 때만 `AutoMarketService`를 바꾼다.
5. `InternalOrderBookExecutionService` 체결 영향 테스트를 확인한다.
6. admin 화면에서 설정을 노출할지 결정한다.

## 검증

- `node scripts/verify-stock-auto-profiles.mjs`: 27개 enum/Behavior/DDL/UI 계약뿐 아니라 배치 `ProfileExecutionPolicy.v2Default()`와 백엔드의 프로필별 `pricing/exit/inventory` 명시 기본값을 직접 읽어 비교한다. 과거 가중치 임계값으로 V2 모드를 재추론해 일치로 오판하지 않는다.
- `./gradlew :stock-batch-service:test --tests '*AutoMarketServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*InternalOrderBookExecutionServiceTest*'`
- `cd stock-front-service && npm run build`
