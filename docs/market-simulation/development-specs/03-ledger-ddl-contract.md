# Ledger And DDL Contract

## 현재 구현

stock 원장은 주문, 체결, 계좌, 보유, 가격, 주문장 종목, 시장 상태, 자동장 설정, 기업 이벤트, 포트폴리오 스냅샷으로 구성된다.

운영 MySQL business DDL은 `stock-back-service`의 `stock_all.sql` 하나를 canonical source로 사용하고, batch test용 H2 DDL은 공유 제약을 재현해야 한다.

## 핵심 테이블

- `stock_account`: 사용자별 현재 현금, 계좌코드, 복구코드 hash, 계좌 연결 상태.
- `stock_account_cash_flow`: 계좌별 입금/회수 원장. 수익률 기준 순입금액 계산에 사용한다.
- `stock_holding`: 사용자별 보유/예약 수량, 평균단가.
- `stock_order`: 주문 원장. `market_type`으로 `VIRTUAL_PRICE`와 `ORDER_BOOK`을 분리한다.
- `stock_execution`: 체결 원장. `source`로 `VIRTUAL_MARKET_PRICE`와 `INTERNAL_ORDER_BOOK`을 분리한다.
- `stock_price`: 종목별 최신 가격.
- `stock_price_tick`: 가격 이력.
- `stock_instrument`: 현재가 시장 종목.
- `stock_order_book_instrument`: 주문장 시장 종목. admin이 만든다.
- `stock_virtual_market_config`: 현재가 시장 종목별 enabled/status.
- `stock_order_book_market_config`: 주문장 시장 종목별 enabled/status.
- `stock_auto_market_config`: 자동장 심볼별 설정.
- `stock_auto_participant`: 자동 참여자.
- `stock_corporate_action`: 기업 이벤트 마스터.
- `stock_corporate_action_entitlement`: 배당/무상주 권리와 유상증자 배정·청약 원장. `SUBSCRIBED` 청약금은 상장 전 예약 자산이다.
- `portfolio_snapshot`: 시뮬레이션일별 자산 스냅샷과 랭킹 기준.

## 관련 코드

- `stock-back-service/src/main/java/stock/back/service/database/entity/*.java`
- `stock-back-service/src/main/java/stock/back/service/database/repository/*.java`
- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_capital_increase_subscription_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_capital_increase_contract_hardening_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_schema_contract_alignment_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_price_tick_latest_lookup_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_activity_latest_lookup_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_auto_market_pressure_distribution_alter.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_market_turnover_normalization_alter.sql`
- `stock-back-service/src/main/resources/db/maintenance/stock_clear_data.sql`
- `stock-back-service/src/main/resources/db/maintenance/stock_clear_runtime_history_keep_participants.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- `stock-batch-service/src/test/java/stock/batch/service/database/StockSchemaConstraintTest.java`
- `stock-back-service/src/test/java/stock/back/service/database/StockMysqlDdlContractTest.java`

## 현재 불변식

- `stock_order.market_type`은 `VIRTUAL_PRICE`, `ORDER_BOOK`만 허용한다.
- `stock_order.order_type`은 `LIMIT`, `MARKET`만 허용한다.
- 시장 상태는 `OPEN`, `CLOSED`, `HALTED`만 허용한다.
- 기업 이벤트 타입은 초기 필수 7개만 허용한다.
- `INITIAL_ISSUE`는 주문장 종목 생성 시 자동 기록되며 admin 이벤트 적용 API에서 직접 받을 수 없다.
- 유상증자는 `subscription_start_date <= subscription_end_date < payment_date < listing_date`를 지키고, 주주배정은 `ex_rights_date < subscription_start_date`도 지켜야 한다.
- 유상증자 entitlement의 `subscribed_share_quantity`는 배정 `share_quantity`를 초과할 수 없다.
- 유상증자 청약 현금 흐름은 내부 자산 이동이므로 외부 순입출금에서 제외하고, `SUBSCRIBED` 금액은 상장 전 총자산의 예약 자산에 포함한다.
- 기존 DB 적용 시 안전한 `ANNOUNCED`/`LISTED` legacy 유상증자만 subscription 일정으로 보정한다. 이미 진행된 상태나 일정 간격이 부족한 row, 배정수량보다 청약수량이 큰 row는 의도적인 `stock_migration_required_*` missing-table marker 조회로 alter를 중단해 수동 migration을 요구한다.
- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`만 MySQL business schema를 소유하며, batch에는 중복 MySQL full DDL을 두지 않는다.
- batch H2 test DDL의 공유 원장 컬럼/제약은 canonical MySQL DDL과 맞춘다.
- 기존 DB와 canonical DDL의 기본값·CHECK 표현 차이는 `stock_schema_contract_alignment_alter.sql`로 정렬한다. 이 alter는 스냅샷 음수 값, 잘못된 레짐 값, 발행 필수값 누락이 있으면 `stock_migration_required_schema_contract_alignment` marker로 중단한다.
- `stock_price_tick`의 시점별 최신가 조회는 전체 이력 윈도우 정렬 대신 `(symbol, price_time, id)` 인덱스 역방향 탐색을 사용한다.
- 자동 참여자 계좌별 최신 주문·체결은 전체 계좌 이력을 `GROUP BY MAX`로 훑지 않고 `(account_id, market_type, created_at)`, `(account_id, source, executed_at)` 인덱스의 계좌별 최신 1건 탐색을 사용한다.
- 주문장 캔들은 `ROW_NUMBER` 전체 정렬 대신 `(source, symbol, side, executed_at, id)` 범위 집계와 버킷별 시가·종가 인덱스 탐색을 분리한다.
- `stock_clear_data.sql`은 `STOCK_SERVICE`의 전체 stock business schema 초기화용이다. 자동참여자, 계좌, 종목, 자동장 설정까지 모두 지운다.
- `stock_clear_runtime_history_keep_participants.sql`은 자동참여자와 설정을 보존한 채 주문/체결/차트/원장 히스토리를 새로 시작하는 개발용 초기화 파일이다. 현금 원장을 지우므로 계좌 현금과 일반 보유는 0 상태로 맞추고, 시뮬레이션 clock의 기준일과 1일 길이는 유지한 채 누적 시간을 0으로 되돌린다. enabled 주문장 종목 가격은 초기 상장가와 시뮬레이션 기준일 00:00으로 되돌린다. 자동장 batch가 거래 가능한 `OPEN` 종목의 `SELL_ONLY` 상장주관 자동계정에는 현재 유통주식수만큼 공급 보유분을 재생성해 손익/수익률 기준, 최신가 기준, 주문장 공급 상태가 섞이지 않게 한다.

## 앞으로 구현할 방향

- 새 주문 타입을 넣으려면 enum, DB check constraint, front union type, verifier를 모두 바꾼다.
- 새 기업 이벤트를 넣으려면 `stock_corporate_action` 필드 조합 제약부터 정한다.
- 체결/정산 값을 바꾸면 `stock_execution`의 금액 컬럼과 손익 summary projection을 같이 본다.
- settlement를 T+N 구조로 확장할 때는 기존 즉시 보유/현금 반영 모델과 새 결제 예정 원장을 분리해야 한다.

## 유상증자 DDL 적용 순서

- 신규 DB 또는 유상증자 청약 feature가 아직 적용되지 않은 DB: `stock_capital_increase_subscription_alter.sql` -> `stock_auto_participant_event_profile_config_alter.sql` -> `stock_capital_increase_contract_hardening_alter.sql` 순서로 적용한다.
- 기존 청약 feature 적용 DB: `stock_capital_increase_contract_hardening_alter.sql`만 적용한다.
- alter가 `stock_migration_required_legacy_paid_in_entitlements`, `stock_migration_required_paid_in_schedule`, `stock_migration_required_entitlement_share_limit`, `stock_migration_required_event_profile_type` 같은 descriptive missing-table 오류로 중단되면 legacy 진행 유상증자, 잘못된 profile, 배정한도 초과 entitlement를 수동 정리한 뒤 같은 alter를 다시 실행한다.

## 실제 DB canonical 정렬

- legacy 자동시장 스키마에 `stock_auto_market_config.intensity`와 방향/강도 기반 레짐 컬럼이 남아 있으면 `stock_order_book_daily_snapshot_alter.sql` -> `stock_order_book_daily_regime_alter.sql` -> `stock_schema_contract_alignment_alter.sql` -> `stock_auto_market_pressure_distribution_alter.sql` 순서로 한 번만 적용한다. schema alignment는 legacy 레짐 컬럼을 검사하므로 압력 분포 alter 뒤에 실행하지 않으며, 압력 컬럼이 이미 존재하는 DB에는 압력 분포 alter를 재적용하지 않는다.
- 압력 분포 alter는 세 대상 테이블의 legacy 컬럼과 CHECK가 모두 존재하고 새 압력 컬럼은 아직 없는지 첫 변경 전에 검사한다. 조건이 맞지 않으면 `stock_migration_required_auto_market_pressure_distribution_schema` 오류로 중단하므로, 부분 적용 여부와 현재 스키마를 확인한 뒤 수동 정리한다.
- 기존 `stock_order_book_daily_snapshot_alter.sql`, `stock_order_book_daily_regime_alter.sql`을 적용했고 아직 legacy 레짐 컬럼을 사용하는 DB는 `stock_schema_contract_alignment_alter.sql`을 추가 적용한다.
- 기존 `stock_price_tick`에 `(symbol, price_time)` 인덱스만 있는 DB는 `stock_price_tick_latest_lookup_alter.sql`을 적용한다.
- 기존 주문·체결 원장에 계좌별 최신 활동 및 캔들 전용 인덱스가 없으면 `stock_activity_latest_lookup_alter.sql`을 적용한다.
- 기존 `stock_order_book_daily_snapshot`이 BUY·SELL 양쪽 행을 합산한 체결수·거래량·거래대금을 보유하면 `stock_market_turnover_normalization_alter.sql`을 적용한다. 이미 정규화된 스냅샷에는 재적용되지 않는다.
- `stock_schema_contract_alignment_alter.sql`은 migration용 임시 기본값을 제거하고, 기존 테이블 생성 시 빠질 수 있던 스냅샷·레짐 CHECK와 기업 이벤트 발행 필수 CHECK를 canonical 정의로 재생성한다.

## 바꿀 때 순서

1. DDL 변경안을 먼저 쓴다.
2. JPA entity/enum/repository를 맞춘다.
3. batch JDBC SQL을 맞춘다.
4. front TypeScript type을 맞춘다.
5. MySQL DDL contract test와 H2 schema constraint test를 맞춘다.
6. `scripts/verify-stock-initial-scope.mjs`의 범위 고정 규칙을 갱신한다.

## 검증

- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `node scripts/verify-stock-initial-scope.mjs`
