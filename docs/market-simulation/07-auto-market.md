# Auto Market

이 문서는 자동 참여자 기반 자동장 구조를 설명한다.

## 현재 역할

자동장은 수요/공급 주문장이 텅 비어 보이지 않도록 자동 참여자가 실제 `stock_order` 원장에 주문을 넣는 기능이다. 가짜 프론트 상태가 아니라 DB 주문과 같은 체결 엔진을 사용한다.

관련 코드:

- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/profile/AutoProfileBehavior.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/profile/*Behavior.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/AutoMarketScheduler.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/biz/StockBatchJobService.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-front-service/app/supply-demand/admin/page.tsx`

## 데이터 계약

`stock_auto_participant`

- 자동 참여자 user key와 display name
- enabled 여부
- withdrawn_at: 탈퇴 처리 시각
- 초기 보유 주식은 없다. 보유는 주문장 매수 체결로만 생긴다.
- 운용 현금은 참여자 row에 보관하지 않는다. 실제 현금은 `stock_account.cash_balance`, 입금/회수 이력은 `stock_account_cash_flow`가 책임진다.

`stock_auto_market_config`

- symbol
- enabled
- max_order_quantity
- order_ttl_seconds
- primary_regime_count_1_weight ~ primary_regime_count_4_weight: 하루 주 랜덤 적용 횟수 1~4회의 상대 가중치, 각 0~100이며 합계는 0보다 커야 한다.
- primary_*_pressure_bias: 선택된 주 압력 갱신 슬롯의 항목별 삼각분포 최빈값, -100~100
- secondary_*_pressure_bias: 30분 보조 압력의 항목별 분포 최빈값, -100~100

`stock_auto_participant_symbol_config`

- user_key, symbol
- enabled
- intensity: 기존 API·DB 컬럼명을 유지한 참여자별 주문 활동 강도, 1~10
  - 값이 클수록 주문 생성량과 가격 반응이 적극적이다.
  - 매수·매도 방향은 압력, 보고서, 프로필, 계좌·보유·주문장 상태가 별도로 결정한다.

## 실행 플로우

1. enabled 자동 참여자 목록을 읽는다.
2. enabled 자동장 config, `OPEN` 주문장 market config, 종목 현재가를 읽는다.
3. 자동 참여자 계좌가 없으면 0원 계좌만 만든다.
4. 06시 주 압력은 항상 갱신하고 가중치로 뽑힌 총 횟수에 맞춰 09·12·15시 중 일부만 추가 갱신한다. 현재 슬롯이 갱신 대상이 아니면 직전 주 압력을 이어받고, 30분 보조 압력은 구간별로 편향을 반영해 생성한다.
5. 주 압력 70%와 보조 압력 30%를 항목별로 합성한다.
6. 종목별 참여자 전략을 읽는다. 명시 설정이 없으면 주문 활동 강도 5를 사용한다.
7. 오래된 자동 주문을 취소하고 예약금/예약수량을 되돌린다.
8. 참여자 `profile_type`에 맞는 `AutoProfileBehavior`가 압력·보고서·계좌 상태를 반영해 주문 수, 매수/매도 방향, 수량 상한, TTL을 결정한다.
9. 참여자의 현금/보유수량 제약을 보고 불가능한 주문은 만들지 않는다.
10. 현재가, 최우선 매수/매도 호가, 시장·가격대별 호가단위를 참고해 지정가를 만든다. 일반 자동 참여자는 가격 압력을 현재가 비율로 반영하되 기본 0.6%, 변동성 반영 후 0.8% 이내로 제한한다. 중립 압력은 매수·매도 시작 호가를 중심가 양쪽으로 분리하고, 상승 압력은 매수 교차 확률을 높이고 매도 교차 확률을 낮춘다. 하락 압력은 반대로 작동한다. 생성된 가격이 상대 또는 자기 반대 호가를 넘더라도 한 틱 밖으로 되돌리지 않으며 실제 동일 계좌 또는 동일 `self_trade_group_id` 자기체결은 체결 엔진에서 차단한다.
11. 실제 `stock_order`에 `ORDER_BOOK` LIMIT 주문을 넣는다.
12. 체결 worker가 ready-symbol 큐를 받아 주문장 매칭을 시도한다.

## 현재 구현상 의미

- 자동장 주문도 일반 주문과 같은 `stock_order` 원장에 들어간다.
- 자동 참여자도 `stock_account`, `stock_holding`을 가진다.
- 자동장으로 생긴 주문도 일반 사용자 주문과 체결될 수 있다.
- 자전거래 방지는 주문장 체결 엔진에서 처리한다.
- 종목별 장 상태가 `CLOSED` 또는 `HALTED`면 자동 주문을 만들지 않는다.
- 프로필별 핵심 판단은 `automarket/profile/*Behavior.java`에 둔다. `AutoMarketService`는 계좌 준비, 주문 만료, 가격 생성, 주문 저장 흐름을 조립한다.

## 현재 한계

- 자동 참여자 수, 운용 현금 입금/회수, 참여자별-종목별 활동 강도 정책은 관리자 화면/API에서 명시적으로 관리한다.
- 자동 참여자 탈퇴는 `enabled=false`, `withdrawn_at` 기록에 앞서 자산을 원자적으로 정산한다. 모든 미체결 주문과 예약을 해제하고 활성 예산을 만료하며, 보유주식은 비거래 `SYSTEM_CUSTODY` 계정으로 원가를 보존해 이전하고 잔여 현금은 시스템 회수 현금흐름으로 기록한다. 계좌는 과거 주문·체결·정산 연결을 보존하기 위해 삭제하지 않고 `CLOSED`로 종료한다. 진행 중 기업행사 권리가 있으면 완료 전까지 탈퇴를 거부한다. 탈퇴는 최종 상태이므로 동일 사용자 키를 다시 활성화하지 않으며, 재등록은 새 사용자 키로만 수행한다. 관리자 탈퇴 감사 화면은 `WITHDRAWN` 범위와 `stock_auto_participant_withdrawal`, `stock_auto_participant_share_return`의 `receiver_account_id`·`receiver_role`을 기준으로 읽는다. `underwriter_account_id`는 과거 조회 호환용 필드일 뿐 신규 이전의 경제적 역할을 뜻하지 않는다.
- `stock_order_book_instrument.tick_size`는 저장 기준값이며, 실제 지정가 검증과 자동 가격은 시장·지정가 가격대별 동적 호가단위를 사용한다.
- 자동장 압력은 가격·자산 선호·변동성·유동성·체결 공격성으로 분리되며, 심리/뉴스/체결 잔량 반응은 프로필별 behavior가 추가로 반영한다.
- 자동 주문 생성은 정규장 여부와 종목별 장 상태를 모두 확인한다.

## 다음에 바꿀 때 순서

1. 참여자별 종목 config에 bid/ask spread 범위, 최대 노출 수량을 추가한다.
2. 장전/장마감 상태에서는 자동장 생성 여부를 별도 정책으로 분리한다.
3. 자동 참여자별 전략 유형을 추가할 때는 enum, DDL constraint, back/front 타입, `*Behavior` 클래스, `AutoProfileBehaviorRegistry`, `scripts/verify-stock-auto-profiles.mjs`를 함께 맞춘다.
