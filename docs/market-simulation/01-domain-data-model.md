# Domain Data Model

이 문서는 stock 모의투자의 DB 원장과 엔티티 역할을 설명한다. 구현 기준은 `stock-back-service/src/main/resources/db/ddl/stock_all.sql`이며, batch H2 테스트 스키마는 `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`이다.

## 핵심 테이블

`stock_account`

- 사용자별 현금 계좌다.
- `cash_balance`는 실제 사용 가능 현금이다.
- 매수 주문 접수 시 예약금만큼 차감되고, 체결/취소/거절 시 남은 금액이 되돌아온다.
- 관련 코드: `AccountService`, `TradingService`, batch execution services.

`stock_holding`

- 사용자별 보유 종목 원장이다.
- `quantity`는 총 보유수량, `reserved_quantity`는 매도 주문으로 잠긴 수량이다.
- `average_price`는 평균단가다.
- 매도 주문 접수 시 `reserved_quantity`가 증가하고, 체결/취소/거절 시 감소한다.

`stock_order`

- 모든 주문의 접수 원장이다.
- `market_type`이 핵심 분리 기준이다.
  - `VIRTUAL_PRICE`: 현재가 기준 batch가 처리
  - `ORDER_BOOK`: 내부 주문장 batch가 처리
- `reserved_cash`는 매수 주문에서 잠긴 금액이다.
- `filled_quantity`, `average_fill_price`, `status`로 체결 상태를 추적한다.
- 정정과 부분 취소는 현재 별도 이벤트 테이블 없이 이 주문 원장의 현재 수량, 지정가, 예약금을 갱신한다.

`stock_execution`

- 체결 원장이다.
- 한 주문은 여러 체결을 가질 수 있다.
- `source`는 `VIRTUAL_MARKET_PRICE` 또는 `INTERNAL_ORDER_BOOK`다.

`stock_price`

- 종목별 최신 조회 가격이다.
- `current_price`, `previous_close`, `price_time`, `provider`를 가진다.
- 최신 표시와 체결 기준이 되므로 DB 값이 authoritative하다.

`stock_price_tick`

- 가격 변경 이력이다.
- 차트와 최근 tick 조회에 사용한다.

`stock_order_book_instrument`

- 수요/공급 주문장 전용 종목이다.
- 관리자가 만든다.
- `issued_shares`, `tradable_shares`는 기업 이벤트와 자동장 공급 제한에 사용한다.
- `tick_size`는 종목 생성 시 현재가 기준으로 저장한 표시·호환용 호가단위다. LIMIT 주문의 실제 유효성은 market과 지정가 가격대에서 동적 호가단위를 다시 계산해 검증한다.
- `price_limit_rate`는 `stock_price.previous_close` 기준 일일 상하한 검증에 사용한다.

`stock_virtual_market_config`

- 현재가 기반 `VIRTUAL_PRICE` 시장의 종목별 운영 config다.
- `enabled`와 `market_status`가 모두 주문 접수와 현재가 체결 batch의 대상 여부를 결정한다.
- `market_status`는 `OPEN`, `CLOSED`, `HALTED`를 가진다.

`stock_order_book_market_config`

- 수요/공급 `ORDER_BOOK` 시장의 종목별 운영 config다.
- `enabled`와 `market_status`가 모두 주문 접수, 주문장 체결 batch, 자동장 주문 생성 대상 여부를 결정한다.
- 주문장 종목 생성 시 기본값은 `enabled=true`, `market_status=OPEN`이다.

`stock_auto_market_config`

- 수요/공급 주문장의 자동장 설정이다.
- 종목별 자동 주문 생성 여부, 최대 주문 수량, 주문 TTL, 주 랜덤 일일 1~4회 적용 가중치와 주·보조 압력별 분포 편향을 가진다.
- batch의 자동장 job은 이 설정이 켜진 종목만 대상으로 자동 주문을 만든다.
- 주 압력은 시뮬레이션 시각 06:00에 항상 새로 생성한다. 설정 가중치로 뽑힌 총 적용 횟수에 맞춰 09:00·12:00·15:00 중 일부 슬롯만 추가 갱신하며, 선택되지 않은 슬롯은 직전 값을 이어받는다. 보조 압력은 30분마다 새로 생성한다.
- 가격·자산 선호·변동성·유동성·체결 공격성의 각 편향은 -100~100이며, 해당 값 부근이 가장 자주 나오도록 삼각분포의 최빈값을 이동한다.

`stock_auto_participant`

- 자동장이 주문을 넣을 때 사용하는 시스템 투자자 풀이다.
- 사용자의 실제 계좌와 분리된 자동장 전용 참가자이며, 자동장 주문 생성과 체결 테스트에 사용한다.
- 표시명, 가동 여부, 탈퇴 시각을 가진다. 운용 현금은 계좌 잔고와 현금 흐름 원장이 가진다.
- 초기 보유 주식은 없다. 자동 참여자의 보유는 주문장 매수 체결 결과로만 생긴다.

`stock_auto_participant_symbol_config`

- 자동 참여자별-종목별 전략 설정이다.
- `enabled`, `intensity`를 가진다.
- `intensity`는 하위 호환 컬럼명이지만 의미는 주문 활동 강도 1~10이다. 주문 생성량과 가격 반응 크기를 조절하며 상승·하락 방향 자체를 뜻하지 않는다.
- batch가 이 row를 찾지 못하면 고정 중립값 5를 사용한다.

`stock_corporate_action`

- 기업 이벤트 원장이다.
- 현재 타입:
  - `INITIAL_ISSUE`
  - `PAID_IN_CAPITAL_INCREASE`
  - `STOCK_SPLIT`
  - `CASH_DIVIDEND`
  - `BONUS_ISSUE`
  - `STOCK_DIVIDEND`
  - `DELISTING`
- batch job이 날짜와 상태에 따라 적용한다.

`stock_corporate_action_entitlement`

- 현금배당, 무료 신주 배정, 유상증자 배정/청약 원장이다.
- 권리락일 전 최신 완료 전체시장 close cycle/run을 action에 한 번 고정하고, 그 snapshot으로 보유자별 현금 지급액 또는 신주 배정 수량을 계산한다.
- 주주배정 유상증자는 권리락일과 별도 기준일을 기록하되, 현재 즉시결제 시뮬레이션에서는 권리락 직전 고정 snapshot으로 배정 수량을 만든다. 일반공모는 청약 시 사용자별 배정 row를 만든다.
- 유상증자 청약금은 계좌 사용 가능 현금에서 차감하지만 상장 전까지 `PARTIALLY_SUBSCRIBED`/`SUBSCRIBED` entitlement의 예약 자산으로 합산한다. 이 출금은 외부 순입출금과 수익률 원금에서 제외한다.
- 지급일에는 현금배당 원장을 기준으로 `stock_account.cash_balance`를 증가시킨다.
- 신주상장일에는 무상증자/주식배당 원장을 기준으로 `stock_holding.quantity`와 평균단가를 조정한다.
- 사용자별 최근 배정 내역 조회를 위해 `(account_id, created_at)` index를 둔다.

`stock_instrument_report_event`

- 주문장 종목별 평가 보고서 이벤트 원장이다.
- 이벤트 타입은 `PUBLISH`, `UPDATE`, `DELETE`다.
- `PUBLISH`와 `UPDATE`는 제목, 요약, 1~10 점수, 상승 이유, 하락 이유를 반드시 가진다.
- `DELETE`는 현재 보고서 내용을 지우는 이벤트이며 기존 이력을 물리 삭제하지 않는다.
- 현재 기준 보고서는 종목별 최신 이벤트가 `DELETE`가 아닐 때의 최신 row다.
- 보고서가 없거나 최신 이벤트가 `DELETE`이면 자동장은 보고서 신호 없이 자동 참여자 성향만 사용한다.

`portfolio_snapshot`

- 일별 정산 결과다.
- 랭킹과 과거 자산·보유량 조회의 기준이다.
- `holding_quantity`는 총 보유수량, `reserved_sell_quantity`는 매도 주문으로 잠긴 수량, `holding_position_count`는 계좌별 양수 보유 종목 row 수다.
- 세 보유량 컬럼은 기존 이력 호환을 위해 nullable이며, 한 row에서는 모두 NULL이거나 모두 값이 있어야 한다. 신규 정산은 항상 세 값을 함께 기록한다.

## 엔티티 코드 지도

- `database/entity/StockAccount.java`
- `database/entity/StockHolding.java`
- `database/entity/StockOrder.java`
- `database/entity/StockExecution.java`
- `database/entity/StockPrice.java`
- `database/entity/StockPriceTick.java`
- `database/entity/StockOrderBookInstrument.java`
- `database/entity/StockVirtualMarketConfig.java`
- `database/entity/StockOrderBookMarketConfig.java`
- `database/entity/StockAutoMarketConfig.java`
- `database/entity/StockAutoParticipant.java`
- `database/entity/MarketSessionStatus.java`
- `database/entity/StockCorporateAction.java`
- `database/entity/StockCorporateActionEntitlement.java`
- `database/entity/StockInstrumentReportEvent.java`
- `database/entity/PortfolioSnapshot.java`

## 변경할 때 지켜야 할 순서

1. `stock_all.sql`에 MySQL DDL을 먼저 반영한다.
2. `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`에 테스트 스키마를 맞춘다.
3. JPA 엔티티 또는 batch SQL mapper를 수정한다.
4. back/batch 테스트에서 DDL 제약과 실제 플로우를 검증한다.
5. front 타입과 관리자 입력이 필요한 경우 `stock-front-service/app/types/stock.ts`, `app/lib/stock.ts`, 화면을 수정한다.

## 주의점

- 새 원장 컬럼을 추가하면 MySQL DDL과 H2 DDL이 모두 맞아야 한다.
- batch는 JPA가 아니라 `JdbcTemplate` 중심이므로 SQL 컬럼명을 직접 확인해야 한다.
- status enum을 늘릴 때는 DB CHECK 제약도 같이 바꿔야 한다.
- 기존 DB에 부정합 row가 있으면 새 CHECK 제약을 가진 fresh DDL 재적용 전에 정리 SQL이 필요하다.
