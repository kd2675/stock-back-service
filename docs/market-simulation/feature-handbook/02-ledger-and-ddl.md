# Ledger And DDL

## 현재 구현

stock 원장은 주문, 체결, 계좌, 보유, 가격, 주문장 종목, 기업 이벤트, 정산 snapshot으로 나뉜다. back과 batch는 같은 MySQL schema를 바라보며, MySQL business DDL은 back의 단일 canonical 파일을 사용한다.

## 주요 테이블과 코드

- `stock_account`: `StockAccount`, `AccountService`. 사용자 현재 현금, 계좌코드, 복구코드 hash, 계좌 연결 상태.
- `stock_account_cash_flow`: `StockAccountCashFlow`, `StockAccountCashFlowRepository`. 입금/회수 원장과 수익률 기준 순입금액.
- `stock_holding`: `StockHolding`. 보유수량, 예약수량, 평균단가.
- `stock_order`: `StockOrder`, `StockOrderRepository`. 주문 접수 원장.
- `stock_execution`: `StockExecution`, `StockExecutionRepository`. 체결 원장.
- `stock_price`, `stock_price_tick`: `StockPrice`, `StockPriceTick`. 최신가와 이력.
- `stock_order_book_instrument`: `StockOrderBookInstrument`. 관리자 생성 주문장 종목.
- `stock_corporate_action`, `stock_corporate_action_entitlement`: 기업 이벤트와 사용자별 지급/배정 원장.
- `stock_virtual_market_config`, `stock_order_book_market_config`: 시장별 enabled와 `OPEN/CLOSED/HALTED`.
- `stock_auto_participant`, `stock_auto_market_config`: 자동장 참여자와 symbol별 자동장 설정.
- `portfolio_snapshot`: 일별 랭킹/성과 snapshot.

## DDL 파일

- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2_smoke_data.sql`

## 개발 초기화 SQL

- `stock-back-service/src/main/resources/db/maintenance/stock_clear_data.sql`: `STOCK_SERVICE` stock business schema 전체 초기화. 자동참여자, 계좌, 종목, 자동장 설정까지 모두 지운다.
- `stock-back-service/src/main/resources/db/maintenance/stock_clear_runtime_history_keep_participants.sql`: 자동참여자, 프로필, 참여자별 전략, 종목, 자동장 설정은 보존하고 주문/체결/가격틱/현금원장/스냅샷을 지운다. 현금원장을 지우는 만큼 계좌 현금과 일반 보유는 0 상태로 맞추고, 시뮬레이션 clock은 기준일과 1일 길이를 유지한 채 누적 시간만 0으로 되돌린다. enabled 주문장 종목 가격은 초기 상장가와 시뮬레이션 기준일 00:00으로 되돌린다. 자동장 batch가 거래 가능한 `OPEN` 종목의 `SELL_ONLY` 상장주관 자동계정에는 현재 유통주식수만큼 공급 보유분을 재생성해 새 시뮬레이션 손익 기준, 최신가 기준, 주문장 공급 상태를 깨끗하게 만든다.
- 두 SQL 모두 stock-back과 stock-batch 스케줄러를 멈춘 뒤 적용한다.

## 계좌 복구 정책

- 신규 계좌는 서버가 `account_code`와 1회성 `recovery_code`를 발급한다.
- `recovery_code` 원문은 DB에 저장하지 않고 `recovery_code_hash`만 저장한다.
- 계좌 분리 시 `ACTIVE -> DETACHED`로 전환하고 `user_key`를 비운다.
- 계좌 분리 시 미체결 주문은 `CANCELLED` 처리하고 예약 현금/예약 수량을 해제한다.
- `DETACHED` 계좌는 `account_code + recovery_code`로만 새 로그인 사용자에게 재연결할 수 있다.
- 재연결 성공 시 `DETACHED -> ACTIVE`로 전환하고 새 `user_key`를 붙이며 복구코드는 재발급한다.
- 복구 가능 기간은 분리 시점부터 30일이다. `recovery_expires_at` 이후에는 재연결을 거부한다.
- 보관 만료는 분리 시점부터 90일이다. `purge_after` 이후 복구 시도는 계좌를 `CLOSED`로 전환하고 거부한다.
- 계좌 접근 권한은 항상 로그인된 `user_key`와 `ACTIVE` 상태 기준으로 판단한다. `account_code`만으로 조회/주문 권한을 주지 않는다.

## 앞으로 구현할 때의 방향

- append-only 이력이 필요한 기능은 기존 row update만으로 끝내지 말고 별도 event table을 먼저 설계한다.
- 체결, 기업 이벤트, 정산처럼 돈/수량을 바꾸는 기능은 DB constraint와 service validation을 같이 둔다.
- 기업 이벤트 원장은 타입별 필수 컬럼뿐 아니라 타입별로 의미 없는 컬럼도 DB constraint에서 거부한다.
- 기존 DB를 유지한 채 제약을 추가해야 하는 경우에는 적용 전 과거 row를 먼저 보정해야 한다.
- DDL은 seed 데이터를 기본으로 만들지 않는다. smoke data는 별도 파일에만 둔다.

## 변경 순서

1. 원장 컬럼/테이블이 필요한지 먼저 결정한다.
2. MySQL full DDL을 수정한다.
3. H2 DDL과 smoke data를 수정한다.
4. back entity/repository/DTO를 수정한다.
6. batch SQL row mapper와 service를 수정한다.
7. front type/API/screen을 수정한다.
8. DDL contract test를 추가한다.

## 검증

- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `git diff --check`
- DDL 정합성 테스트: `StockMysqlDdlContractTest`, `StockDdlContractTest`, `StockSchemaConstraintTest`
