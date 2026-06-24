# Auto Market

이 문서는 자동 참여자 기반 자동장 구조를 설명한다.

## 현재 역할

자동장은 수요/공급 주문장이 텅 비어 보이지 않도록 자동 참여자가 실제 `stock_order` 원장에 주문을 넣는 기능이다. 가짜 프론트 상태가 아니라 DB 주문과 같은 체결 엔진을 사용한다.

관련 코드:

- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`
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
- intensity: 참여자별-종목별 설정이 없을 때 쓰는 기본 자동 가격 방향 강도, 1~10
- max_order_quantity
- order_ttl_seconds

`stock_auto_participant_symbol_config`

- user_key, symbol
- enabled
- intensity: 참여자별 종목 가격 방향 강도, 1~10
  - 10에 가까울수록 해당 참여자가 매수 우위와 공격적 매수가로 상승 압력을 만든다.
  - 1에 가까울수록 보유가 있는 참여자가 매도 우위와 공격적 매도가로 하락 압력을 만든다.

## 실행 플로우

1. enabled 자동 참여자 목록을 읽는다.
2. enabled 자동장 config, `OPEN` 주문장 market config, 종목 현재가를 읽는다.
3. 자동 참여자 계좌가 없으면 0원 계좌만 만든다.
4. 종목별 참여자 전략을 읽는다. 명시 설정이 없으면 종목 기본 intensity를 fallback으로 쓴다.
5. 오래된 자동 주문을 취소하고 예약금/예약수량을 되돌린다.
6. 참여자별 intensity에 따라 신규 자동 주문 수를 계산한다.
7. 참여자의 현금/보유수량을 보고 매수 또는 매도 방향을 고른다.
8. 현재가, 최우선 매수/매도 호가, tick size를 참고해 지정가를 만든다.
9. 실제 `stock_order`에 `ORDER_BOOK` LIMIT 주문을 넣는다.
10. `StockBatchJobService.runAutoMarket()`는 자동 주문 생성 후 주문장 체결 엔진을 바로 실행한다.

## 현재 구현상 의미

- 자동장 주문도 일반 주문과 같은 `stock_order` 원장에 들어간다.
- 자동 참여자도 `stock_account`, `stock_holding`을 가진다.
- 자동장으로 생긴 주문도 일반 사용자 주문과 체결될 수 있다.
- 자전거래 방지는 주문장 체결 엔진에서 처리한다.
- 종목별 장 상태가 `CLOSED` 또는 `HALTED`면 자동 주문을 만들지 않는다.

## 현재 한계

- 자동 참여자 수, 운용 현금 입금/회수, 참여자별-종목별 강도 정책은 관리자 화면/API에서 명시적으로 관리한다.
- tick size는 종목별 `stock_order_book_instrument.tick_size`를 따른다.
- 자동장 강도는 가격 방향성 모델로 동작하지만 실제 시장 심리/뉴스/체결강도 기반 모델은 아니다.
- 장 상태는 보지만 장전/장마감 같은 시간표는 아직 보지 않는다.

## 다음에 바꿀 때 순서

1. 참여자별 종목 config에 bid/ask spread 범위, 최대 노출 수량을 추가한다.
2. 장전/장마감 상태에서는 자동장 생성 여부를 별도 정책으로 분리한다.
3. 자동 참여자별 전략 유형을 추가한다.
