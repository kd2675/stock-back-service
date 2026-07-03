# stock-back-service

주식 모의투자 서비스의 백엔드 API 서버입니다.

## 역할

- 사용자별 가상 계좌와 현금 관리
- 주문 접수와 주문 상태 조회
- 체결 내역, 보유 종목, 실현/평가 손익 조회
- 수익률과 랭킹 API
- Gateway/auth 공통 모듈 기반 사용자 식별

## 현재 API

- `GET /api/stock/v1/system/status`
- `GET /api/stock/v1/markets/instruments`
- `GET /api/stock/v1/markets/prices`
- `GET /api/stock/v1/markets/prices/{symbol}/ticks`
- `GET /api/stock/v1/markets/order-book-instruments`
- `POST /api/stock/v1/markets/order-book-instruments` (`ADMIN`)
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions` (`ADMIN`)
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports/latest`
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `PATCH /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `DELETE /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `GET /api/stock/v1/markets/corporate-action-entitlements/me`
- `GET /api/stock/v1/markets/order-books/{symbol}`
- `GET /api/stock/v1/markets/virtual-market`
- `GET /api/stock/v1/markets/order-book-market`
- `GET /api/stock/v1/markets/auto-market`
- `GET /api/stock/v1/markets/rankings`
- `GET /api/stock/v1/users/me`
- `GET /api/stock/v1/accounts/me`
- `GET /api/stock/v1/accounts/me/status`
- `POST /api/stock/v1/accounts/me`
- `GET /api/stock/v1/portfolio/me`
- `GET /api/stock/v1/portfolio/me/snapshots`
- `GET /api/stock/v1/portfolio/me/profit-summary`
- `GET /api/stock/v1/holdings`
- `GET /api/stock/v1/orders` (`marketType` optional)
- `POST /api/stock/v1/orders`
- `PATCH /api/stock/v1/orders/{orderId}`
- `POST /api/stock/v1/orders/{orderId}/cancel`
- `DELETE /api/stock/v1/orders/{orderId}`
- `GET /api/stock/v1/executions` (`source` optional)

## 실행과 검증

아래 명령은 `zeroq-common` 루트에서 실행합니다.

```bash
./gradlew :stock-back-service:bootRun
./gradlew :stock-back-service:bootRun --args='--spring.profiles.active=local'
./gradlew :stock-back-service:bootRun --args='--spring.profiles.active=local-direct'
./gradlew :stock-back-service:compileJava
./gradlew :stock-back-service:test
scripts/stock-smoke.sh
```

일반 `scripts/stock-smoke.sh`는 기본 종목을 가정하지 않습니다. `STOCK_SMOKE_EXPECT_SEEDED_MARKET=true` 또는 `STOCK_SMOKE_PLACE_ORDER=true`로 종목 기반 검증을 켤 때는 `STOCK_SMOKE_SYMBOL`을 명시해야 합니다. H2 smoke는 별도 smoke data를 넣기 때문에 wrapper에서 symbol을 명시합니다.

## 포트

| Profile | Port |
|---|---:|
| `local` | `20480` |
| `local-direct` | `20480` |
| `dev` | `20480` |
| `prod` | `10480` |
| `test` | `30480` |

## Local Direct / Gateway 전환

- 기본 활성 profile은 `local-direct`입니다.
- `local-direct`는 `local` DB/Redis 설정을 재사용하면서 Eureka 등록/탐색을 끕니다.
- `local-direct`에서 `auth-common-core`의 `UserServiceClient`는 `STOCK_AUTH_BASE_URL`로 직접 auth-back-server를 호출합니다. 기본값은 `http://localhost:9000`입니다.
- Gateway/Eureka 경유로 되돌리려면 `local` profile을 사용합니다.

## 내부 의존성

- `web-common-core`
- `auth-common-core`

## 데이터베이스

- schema: `STOCK_SERVICE`
- DDL: `src/main/resources/db/ddl/stock_all.sql`
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- `local`/`dev` 접속값은 기존 백엔드 프로젝트처럼 `application-local.yml`, `application-dev.yml`에 직접 둡니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- `prod`의 `STOCK_DB_URL`, `STOCK_DB_SLAVE_URL`은 query string 없는 기본 JDBC URL로 넣습니다. 공통 JDBC 옵션은 설정 파일에서 `connectTimeout=5000`, `socketTimeout=30000`, `tcpKeepAlive=true`를 기본으로 붙입니다.
- JPA datasource 구조는 다른 백엔드 JPA 서비스와 맞춰 `database.datasource.pub.master/slave1`과 `PubDataConfig`를 사용합니다.
- `@Transactional(readOnly = true)` 트랜잭션은 `RoutingDataSource`에서 slave로 라우팅됩니다. 현재 local/dev는 master와 slave가 같은 `STOCK_SERVICE` 접속값을 봅니다.
- Hikari 풀은 local/dev 기본 8개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- DDL은 schema와 제약만 생성합니다. 기본 종목, 최초 가격, 자동 참여자는 seed하지 않으며 관리자 API 또는 smoke/test 데이터에서 명시적으로 등록합니다.
- 전체 stock 시뮬레이션 데이터를 지울 때는 `src/main/resources/db/maintenance/stock_clear_data.sql`을 사용합니다. 이 파일은 자동참여자, 계좌, 종목, 자동장 설정까지 모두 지우는 전체 초기화용입니다.
- 자동참여자 등록, 프로필, 참여자별 전략, 종목, 자동장 설정은 남기고 실제시간으로 쌓인 주문/체결/차트/원장 히스토리만 새로 시작하려면 `src/main/resources/db/maintenance/stock_clear_runtime_history_keep_participants.sql`을 사용합니다. 이 파일은 계좌 식별 row는 보존하되 현금과 일반 보유를 0으로 리셋하고, 시뮬레이션 clock의 기준일과 1일 길이는 유지한 채 누적 시간을 0으로 되돌립니다. enabled 주문장 종목 가격은 초기 상장가와 시뮬레이션 기준일 00:00으로 되돌린 뒤, 자동장 batch가 거래 가능한 `OPEN` 종목의 `SELL_ONLY` 상장주관 자동계정에는 현재 유통주식수만큼 공급 보유분을 다시 만들어 삭제된 현금 원장과 손익/수익률 기준이 어긋나지 않게 합니다.
- maintenance SQL은 실행 전 stock-back과 stock-batch 스케줄러를 멈춘 뒤 적용합니다.
- stock-back과 stock-batch는 물리적으로 분리된 서버로 본다. stock-back은 stock-batch 내부 HTTP API를 호출하지 않고 `STOCK_SERVICE.stock_batch_job_signal`에 실행 신호를 적재한다.
- batch 스케줄러 runtime 제어는 stock-back이 `stock_batch_job_control.runtime_enabled`를 직접 읽고 쓰며, stock-batch는 자신의 실제 `enabled` 설정을 `scheduler_configured`에 동기화한 뒤 두 값을 함께 읽어 자동 실행 여부를 판단한다.
- batch 수동 실행, 종목 장마감 롤오버, 거래정지/서킷브레이크 미체결 정리는 stock-back이 `PENDING` signal row를 만들고 stock-batch가 폴링해 기존 batch job launcher로 실행한다.
- batch 스케줄러 runtime 제어, 중복 실행 잠금, 비동기 실행 신호는 `STOCK_SERVICE`의 `stock_batch_job_control`, `stock_batch_job_lock`, `stock_batch_job_signal` 테이블을 기준으로 공유한다.

주요 테이블:

- `stock_account`
- `stock_instrument`
- `stock_price`
- `stock_price_tick`
- `stock_order`
- `stock_execution`
- `stock_holding`
- `stock_order_book_instrument`
- `stock_corporate_action`
- `stock_auto_participant`
- `stock_virtual_market_config`
- `stock_order_book_market_config`
- `stock_auto_market_config`
- `portfolio_snapshot`

## 설계 기준

- DB는 주문, 체결, 잔고, 거래 이력의 원장입니다.
- 시장 가격 조회는 Redis `stock:price:{symbol}` 캐시를 우선 사용하고, Redis 장애나 값 오류가 있으면 DB `stock_price`로 fallback합니다.
- Redis에는 최신가 문자열을 저장하므로 `StringRedisTemplate` 기반 설정을 사용합니다. JSON Redis serializer는 현재 Spring Data Redis 4.x에서 removal deprecated 경고가 있어 사용하지 않습니다.
- Redis 가격 pub/sub을 SSE로 전달하는 작업은 `stockBackPriceStreamTaskExecutor` 전용 executor에서 처리합니다. 기본값은 core 1, max 2, queue 1000이며 `STOCK_PRICE_STREAM_EXECUTOR_CORE_SIZE`, `STOCK_PRICE_STREAM_EXECUTOR_MAX_SIZE`, `STOCK_PRICE_STREAM_EXECUTOR_QUEUE_CAPACITY`로 조정합니다. 이 풀은 체결 엔진이 아니라 화면 가격 이벤트 전송 지연을 격리하기 위한 풀입니다.
- 보유 종목 평가는 DB 현재가를 우선 사용하되, 내부 주문장 체결처럼 아직 `stock_price`가 없는 종목은 보유 평단가로 fallback합니다.
- 가격 이력 조회는 `stock_price_tick`에서 종목별 최근 100건을 `price_time desc` 기준으로 반환합니다.
- 주문장 조회는 미체결/부분체결 LIMIT 주문을 가격대별로 집계하며, 매수는 높은 가격 우선, 매도는 낮은 가격 우선으로 반환합니다.
- 주문장 종목 생성과 기업 이벤트 적용은 관리자 전용 쓰기 API입니다. 읽기 API는 사용자 화면 조회를 위해 공개로 둡니다.
- 주문장 종목 평가 보고서는 `PUBLISH`, `UPDATE`, `DELETE` 이벤트로 기록합니다. 최신 이벤트가 삭제가 아니면 그 보고서가 현재 기준이며, 보고서가 없거나 최신 이벤트가 삭제이면 자동장은 참여자 성향만 사용합니다.
- 주문장 종목은 `tick_size`, `price_limit_rate`를 가지며, LIMIT 주문 접수/정정 시 호가 단위와 일일 가격제한폭을 검증합니다.
- 현재가 시장은 초기 기본값으로 1원 tick과 `stock_price.previous_close` 기준 ±30% 가격제한폭을 사용합니다.
- 주문 원장은 공유하되 `stock_order.market_type`으로 `VIRTUAL_PRICE`와 `ORDER_BOOK` 주문을 분리합니다.
- 자동장 상태 조회는 `stock_auto_participant`, `stock_auto_market_config`, 자동 참여자 주문/체결 원장을 읽어 현재 활성 상태와 종목별 설정을 반환합니다.
- 유상증자는 API 호출 시점에 주식 수를 즉시 늘리지 않고, `stock_corporate_action`에 권리락일/납입일/신주상장일을 기록합니다. 권리락 가격 조정과 신주상장일 주식 수 반영은 `stock-batch-service`가 처리합니다.
- 추가발행은 API 호출 시점에 주식 수를 즉시 늘리지 않고, 신주상장일에 `stock-batch-service`가 주식 수를 반영합니다. 액면분할도 효력일에 batch가 주식 수, 보유수량, 가격을 비례 조정합니다.
- 현금배당은 배당금, 배당락일, 지급일을 `stock_corporate_action`에 기록합니다. 배당락일 보유자별 지급 원장 생성과 지급일 현금 반영은 `stock-batch-service`가 처리하며, 현금배당 자체는 현재가를 강제로 조정하지 않습니다.
- 무상증자와 주식배당은 권리락일, 신주상장일, 배정 주식수를 `stock_corporate_action`에 기록합니다. 권리락 가격 조정, 보유자별 신주 entitlement 생성, 상장일 보유수량/평균단가 반영은 `stock-batch-service`가 처리합니다.
- 기업 이벤트 이력은 종목별로 조회할 수 있고, 사용자별 배당/신주 배정 내역은 최근 50건을 조회합니다.
- 체결 이력 응답은 체결가/수량뿐 아니라 `grossAmount`, `feeAmount`, `taxAmount`, `netAmount`, `realizedProfit`을 포함합니다.
- 손익 요약 응답은 `stock_execution`의 누적 실현손익/비용/세금과 현재 보유 평가손익을 조합해 반환합니다.
- 공개 랭킹 응답은 내부 식별자인 `userKey`와 함께 화면 노출용 `displayName`을 제공합니다. 화면에서는 `displayName`을 우선 사용합니다.
- 사용자 읽기 API는 계좌를 자동 개설하지 않습니다. `GET /api/stock/v1/accounts/me`는 기존 계좌가 없으면 404를 반환하고, 주문/체결/보유/스냅샷/손익 요약 조회는 계좌 없이도 빈 목록 또는 0 요약을 반환합니다. 사용자가 첫 진입 온보딩에서 명시적으로 계좌 만들기를 선택할 때만 `POST /api/stock/v1/accounts/me`가 계좌를 만들거나 기존 계좌를 반환합니다.
- 외부 시세 수집과 미체결 주문 체결 판단은 `stock-batch-service`가 담당합니다.
- 주문 API는 체결을 직접 확정하지 않고 주문 원장을 만든 뒤 체결 프로세스가 처리하도록 둡니다.
- 매도 주문은 보유 수량 중 `reserved_quantity`를 제외한 주문 가능 수량만 접수하며, 취소 시 예약 수량을 되돌립니다.
- 미체결/부분체결 LIMIT 주문은 수량과 지정가를 정정할 수 있으며, 정정 시 매수 예약금과 매도 예약수량 차액을 즉시 반영합니다.
- 미체결/부분체결 주문은 부분 취소할 수 있으며, 남은 미체결 수량 전체를 취소하면 기존 전체 취소와 같은 `CANCELLED` 상태가 됩니다.
- 시장가 매수는 접수 시점의 현재가를 기준으로 현금을 예약해야 하므로 현재가가 없으면 접수하지 않습니다.
- 시장가 매도는 현금 예약이 필요 없고 내부 주문장 모드에서 반대편 지정가가 가격 기준이 될 수 있으므로, 현재가가 없어도 보유 수량이 있으면 접수합니다.
- 장 마감 정산 결과는 `portfolio_snapshot`에 저장하고, 사용자별 최근 30개 정산 기록을 조회합니다.
- 현재 체결 source는 외부 현재가 기준 단순 체결의 `VIRTUAL_MARKET_PRICE`와 내부 호가 매칭의 `INTERNAL_ORDER_BOOK`를 함께 지원합니다.
- 실제 주식시장 기능 확장 범위와 우선순위는 `STOCK_MARKET_FEATURE_ROADMAP.md`를 기준으로 봅니다.
- 기능별 현재 구현, 코드 위치, 다음 개발 순서는 `docs/market-simulation/00-overview.md`부터 확인합니다.
- 코드 파일별 책임은 `docs/market-simulation/13-code-ownership-map.md`, 기능별 변경 순서는 `docs/market-simulation/14-feature-change-playbooks.md`를 기준으로 봅니다.
- 기업 이벤트를 추가할 때는 `docs/market-simulation/15-corporate-action-scope.md`에서 초기 필수 범위인지 먼저 확인합니다.
