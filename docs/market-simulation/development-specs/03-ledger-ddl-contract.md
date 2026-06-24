# Ledger And DDL Contract

## 현재 구현

stock 원장은 주문, 체결, 계좌, 보유, 가격, 주문장 종목, 시장 상태, 자동장 설정, 기업 이벤트, 포트폴리오 스냅샷으로 구성된다.

운영 MySQL DDL은 back/batch에 같은 계약으로 있어야 하고, 테스트 H2 DDL은 batch test에서 동일 제약을 재현해야 한다.

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
- `stock_corporate_action_entitlement`: 배당/무상주 사용자별 권리.
- `portfolio_snapshot`: 일별 자산 스냅샷과 랭킹 기준.

## 관련 코드

- `stock-back-service/src/main/java/stock/back/service/database/entity/*.java`
- `stock-back-service/src/main/java/stock/back/service/database/repository/*.java`
- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- `stock-batch-service/src/test/java/stock/batch/service/database/StockSchemaConstraintTest.java`
- `stock-back-service/src/test/java/stock/back/service/database/StockMysqlDdlContractTest.java`

## 현재 불변식

- `stock_order.market_type`은 `VIRTUAL_PRICE`, `ORDER_BOOK`만 허용한다.
- `stock_order.order_type`은 `LIMIT`, `MARKET`만 허용한다.
- 시장 상태는 `OPEN`, `CLOSED`, `HALTED`만 허용한다.
- 기업 이벤트 타입은 초기 필수 7개만 허용한다.
- `INITIAL_ISSUE`는 주문장 종목 생성 시 자동 기록되며 admin 이벤트 적용 API에서 직접 받을 수 없다.
- `stock-back-service`와 `stock-batch-service`의 MySQL DDL은 같은 컬럼/제약을 유지해야 한다.

## 앞으로 구현할 방향

- 새 주문 타입을 넣으려면 enum, DB check constraint, front union type, verifier를 모두 바꾼다.
- 새 기업 이벤트를 넣으려면 `stock_corporate_action` 필드 조합 제약부터 정한다.
- 체결/정산 값을 바꾸면 `stock_execution`의 금액 컬럼과 손익 summary projection을 같이 본다.
- settlement를 T+N 구조로 확장할 때는 기존 즉시 보유/현금 반영 모델과 새 결제 예정 원장을 분리해야 한다.

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
