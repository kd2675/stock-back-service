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
- `POST /api/stock/v1/markets/order-book-instruments` (`ADMIN`, 일시정지 장전 전용. 기본 역할 분리형은 유통 50%·잠금 50%로 배정하고 LP LIVE 전까지 시장을 비활성/CLOSED로 유지하며, `LEGACY_FULL_FLOAT`는 명시한 경우에만 100% 유통)
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions` (`ADMIN`)
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/market-report`
- `GET /api/stock/v1/markets/corporate-actions` (`actionType`, `limit` optional)
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports/latest`
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `PATCH /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `DELETE /api/stock/v1/markets/order-book-instruments/{symbol}/reports` (`ADMIN`)
- `GET /api/stock/v1/markets/corporate-action-entitlements/me`
- `POST /api/stock/v1/markets/corporate-actions/{actionId}/subscriptions/me`
- `GET /api/stock/v1/markets/order-books/{symbol}`
- `GET /api/stock/v1/markets/virtual-market`
- `GET /api/stock/v1/markets/order-book-market`
- `GET /api/stock/v1/markets/auto-market`
- `GET /api/stock/v1/markets/institution-portfolios` (`ADMIN`, 기관 계정·목표 비중·최근 shadow 결정 감사)
- `POST /api/stock/v1/markets/institution-portfolios/scaled-defaults` (`ADMIN`, 축소시장용 4개 SHADOW 기관 기준선)
- `POST /api/stock/v1/markets/institution-portfolios/{portfolioId}/pilot` (`ADMIN`, 일시정지 장전에서 20개 완료 SHADOW 거래일과 최근 실패 0건을 검증하고 단일 종목 PILOT으로 전환)
- `POST /api/stock/v1/markets/institution-portfolios/{portfolioId}/suspend` (`ADMIN`, 실행 중 비상 중단. 정책 버전을 즉시 동결하고 대기 주문 의도·전용 계좌 미체결 주문과 예약을 정리)
- `GET /api/stock/v1/markets/liquidity-mandates` (`ADMIN`, 전용 LP 계약·계정/STP·거래일 위험 상태 감사)
- `POST /api/stock/v1/markets/liquidity-mandates/{symbol}/scaled-shadow` (`ADMIN`, 일시정지 장전에서 유통주식 기준 0.5% 시드 재고·3% 기준 거래량을 기본값으로 종목 전용 LP SHADOW 준비)
- `POST /api/stock/v1/markets/liquidity-mandates/{symbol}/activate` (`ADMIN`, 일시정지 장전에서 종목별 레거시 미체결 주문을 취소하고 기존 공급 설정을 끈 뒤 전용 LP만 LIVE로 전환. 역할 분리형 신규 상장은 이때 다음 장 개장 대상으로 활성화)
- `GET /api/stock/v1/markets/underwriting-contracts` (`ADMIN`, 인수재고·최초 배정·누적 제출/체결·최근 일일 공급 게이트 감사)
- `POST /api/stock/v1/markets/underwriting-contracts/{contractId}/scaled-supply/activate` (`ADMIN`, 일시정지 장전에서 주문장 시장·종목 자동시장·기준 거래량 위험 설정과 계약·최초 배정원장·전체 발행주식 보존을 사전 검증하고 현재 가용 인수재고 대비 1~25%·1~60일의 유한 수동 매도 공급 활성화)
- `POST /api/stock/v1/markets/underwriting-contracts/{contractId}/scaled-supply/suspend` (`ADMIN`, 실행 중 즉시 신규 공급을 중단하고 계약 전용 미체결 주문과 주식 예약을 정리하되 사용한 제출예산은 복원하지 않음)
- `GET /api/stock/v1/markets/batch-jobs/eod/overview` (`ADMIN`)
- `POST /api/stock/v1/markets/batch-jobs/eod/cycles/{cycleId}/retry` (`ADMIN`)
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

파일 로그의 공통 루트는 `STOCK_LOG_ROOT`로 지정할 수 있고 기본값은 실행 디렉터리의 `logs`입니다. 서비스별 경로를 직접 지정하려면 `STOCK_BACK_LOG_DIR`을 사용합니다. 여러 프로세스를 같은 날짜에 실행할 때는 `STOCK_INSTANCE_ID`를 지정하면 모든 파일 로그 행의 PID·포트와 함께 인스턴스가 표시됩니다. 테스트 프로필은 파일 로그를 기록하지 않습니다.

## Local Direct / Gateway 전환

- 기본 활성 profile은 `local-direct`입니다.
- `local-direct`는 `local` DB/Redis 설정을 재사용하면서 Eureka 등록/탐색을 끕니다.
- `local-direct`에서 `auth-common-core`의 `UserServiceClient`는 `STOCK_AUTH_BASE_URL`로 직접 auth-back-server를 호출합니다. 기본값은 `http://localhost:9000`입니다.
- Gateway/Eureka 경유로 되돌리려면 `local` profile을 사용합니다.

## 내부 의존성

- `web-common-core`
- `auth-common-core`

## 장중 집계 조회 부하 보호

- `GET /api/stock/v1/markets/order-book-market`의 당일 체결 수는 대형 `stock_execution`을 매번 세지 않고, 배치가 체결 커밋 후 비동기로 적재하는 `stock_execution_account_day_summary`를 읽습니다. BUY·SELL 계좌 delta 두 행이 한 거래이므로 합계를 2로 나누며 정상 flush 상태에서 화면 값은 기본 간격인 약 30초 늦을 수 있습니다. flush 실패·재기동·요약 슬롯 상한 초과 시에는 더 늦을 수 있고 야간 REPORTS 원본 대사로 복구됩니다. 자동 참여자 당일 체결 수도 같은 요약을 활성 자동 참여자 계좌와 조인합니다.
- 사용자 손익 요약과 관리자 전체 자금흐름의 매수/매도 금액·수수료·세금·실현손익도 계좌 전체 `stock_execution`을 반복 합산하지 않고 `(account_id, simulation_trade_date)` 일별 요약을 읽습니다. 장중 값은 정상 flush 상태에서 약 30초 지연될 수 있고, flush 실패·재기동 등으로 더 늦어진 값은 야간 보고서 단계가 해당 거래일의 `[00:00, 다음 날 00:00)` 원본 범위만 대사해 정확 값으로 확정합니다.
- 종목 거래요약과 캔들은 아직 당일 체결 범위를 읽으므로 `OrderBookLiveAggregateCacheService`가 정규화된 종목/구간 키별 single-flight와 기본 10초 TTL을 적용합니다. 캐시는 인스턴스당 최대 1,000키이며 원장 쿼리는 5초 read-transaction timeout을 사용합니다.
- 관리자 자금흐름·종목 흐름 같은 수동 분석 조회는 자동 폴링하지 않고 10초 read-transaction timeout을 적용합니다. 분석 조회가 실패하더라도 주문·체결용 DB 연결을 30초 socket timeout까지 점유하게 두지 않습니다.
- 이 캐시는 읽기 계층에만 있습니다. 주문 접수·정정·자동 주문·체결 트랜잭션에는 캐시 쓰기, 요약 UPSERT, 신규 `stock_order`/`stock_execution` 인덱스나 추가 commit을 넣지 않습니다.
- 설정은 `STOCK_ORDER_BOOK_TRADE_SUMMARY_CACHE_TTL_MS`, `STOCK_ORDER_BOOK_CANDLE_CACHE_TTL_MS`, `STOCK_ORDER_BOOK_LIVE_AGGREGATE_CACHE_MAX_ENTRIES`로 조정합니다. TTL은 1~60초, 키 상한은 10~10,000 범위만 허용합니다. TTL을 줄이기 전에는 현재/확장 거래량 MySQL A/B에서 주문·체결 TPS와 p95/p99를 확인해야 합니다.
- EOD 재시도 API는 시장이 닫힌 상태에서 가장 오래된 전체시장 `FAILED` cycle의 현재 phase backoff만 해제합니다. Job 실행은 batch coordinator가 다시 판정하며, API는 `stock_order`·`stock_execution`·계좌·보유 원장을 읽거나 쓰지 않습니다. 정책상 `DEFERRED` 상태와 강제 마감은 이 API로 우회할 수 없습니다.

## 데이터베이스

- schema: `STOCK_SERVICE`
- MySQL business DDL (canonical): `src/main/resources/db/ddl/stock_all.sql`
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- `local`/`dev` 접속값은 기존 백엔드 프로젝트처럼 `application-local.yml`, `application-dev.yml`에 직접 둡니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- `prod`의 `STOCK_DB_URL`, `STOCK_DB_SLAVE_URL`은 query string 없는 기본 JDBC URL로 넣습니다. 공통 JDBC 옵션은 설정 파일에서 `connectTimeout=5000`, `socketTimeout=30000`, `tcpKeepAlive=true`를 기본으로 붙입니다.
- JPA datasource 구조는 다른 백엔드 JPA 서비스와 맞춰 `database.datasource.pub.master/slave1`과 `PubDataConfig`를 사용합니다.
- `@Transactional(readOnly = true)` 트랜잭션은 `RoutingDataSource`에서 slave로 라우팅됩니다. 현재 local/dev는 master와 slave가 같은 `STOCK_SERVICE` 접속값을 봅니다.
- 관리자 PILOT 중단은 의도적으로 장전·일시정지 제약을 두지 않습니다. 포트폴리오 `SUSPENDED` 전환, 정책 버전 감사, 대기 intent 거절, 전용 계정 미체결 주문 취소와 예약 반환을 하나의 트랜잭션에 처리합니다. 주문 정리가 실패하면 상태·intent·정책 변경도 함께 롤백되므로 “중단 상태지만 기존 주문은 살아 있는” 부분 완료를 커밋하지 않습니다.
- Hikari 풀은 local/dev 기본 8개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- DDL은 schema와 제약만 생성합니다. 기본 종목, 최초 가격, 자동 참여자는 seed하지 않으며 관리자 API 또는 smoke/test 데이터에서 명시적으로 등록합니다.
- 기존 DB에 축소시장 역할 재구성 스키마를 추가할 때는 서버와 스케줄러를 모두 중지하고 `stock_market_role_foundation_alter.sql` → `stock_system_custody_withdrawal_alter.sql` → `stock_institution_shadow_engine_alter.sql` → `stock_liquidity_provider_engine_alter.sql` → `stock_issuance_underwriting_alter.sql` → `stock_underwriter_scaled_supply_alter.sql` → `stock_liquidity_transition_alter.sql` 순서로 적용합니다. 각 파일은 additive·재실행 가능하게 작성했지만 MySQL DDL 묶음 전체가 하나의 트랜잭션은 아니므로 파일별 적용 결과와 원장 대사를 남긴 뒤 다음 파일로 진행합니다. 코드 배포는 일곱 스키마의 readiness가 모두 통과한 뒤에만 합니다.
- 역할 분리형 신규 상장의 비유통·락업 물량은 종목별 `SYSTEM_CUSTODY` 하위계정(`ISSUANCE_LOCKUP:<symbol>`)에 보관합니다. 탈퇴자산을 받는 기본 `stock-system-custody` 계정과 실물 보유를 섞지 않으며, 두 계정군의 경제적 출처는 `stock_security_allocation_ledger`와 탈퇴 이전 감사 원장으로 각각 추적합니다.
- 현재 역할 재구성 범위는 기반 스키마, 기관 SHADOW와 단일 종목 PILOT, 종목별 LP SHADOW/LIVE, 유한 인수 공급, 탈퇴 custody까지입니다. 기관 다종목 LIVE, 레짐 V2, 전체 종목의 legacy listing-auto 폐기, 역할별 별도 일일 snapshot, 락업 해제 workflow는 구현 완료로 간주하지 않습니다. `unlock_business_date`가 `NULL`인 최초 락업 배정은 현재 영구 보관 상태이며 운영자가 임의로 유통량에 포함하거나 계정 간 이전해서는 안 됩니다.
- 7종목에서 인수계정 7개, LP계정 7개, 종목별 발행 락업 custody 7개, 기관 포트폴리오 4개, 탈퇴 custody 1개를 모두 만들면 총 26계정입니다. 종목별 락업 custody는 탈퇴 custody와 별개이므로 19계정 설명을 운영 기준으로 사용하지 않습니다.
- 전체 stock 시뮬레이션 데이터를 지울 때는 `src/main/resources/db/maintenance/stock_clear_data.sql`을 사용합니다. 이 파일은 자동참여자, 계좌, 종목, 자동장 설정까지 모두 지우는 전체 초기화용입니다.
- 자동참여자 등록, 프로필, 참여자별 전략, 종목, 자동장 설정은 남기고 실제시간으로 쌓인 주문/체결/차트/원장 히스토리만 새로 시작하려면 `src/main/resources/db/maintenance/stock_clear_runtime_history_keep_participants.sql`을 사용합니다. 이 파일은 계좌 식별 row는 보존하되 런타임 현금·보유·주문·체결을 초기화하고, 시뮬레이션 clock의 기준일과 1일 길이는 유지한 채 누적 시간을 0으로 되돌립니다. 거래 가능 여부와 소유권을 분리해 CLOSED·호가중지 상태의 기존 공급계정도 현재 유통주식 수량으로 복원하고, 역할 분리형 종목은 현재 발행·유통 수량을 최초 배정 계정에 재구성한 뒤 기관 초기 현금과 LP 시드 이전을 재생합니다. LP 시드 원장·출발 보유·도착 계정 중 하나라도 맞지 않거나 최종 보유합계가 발행주식과 다르면 CHECK guard에서 즉시 중단합니다.
- maintenance SQL은 실행 전 stock-back과 stock-batch 스케줄러를 멈춘 뒤 적용합니다.
- EOD v1 운영 ALTER는 `../stock-batch-service/docs/stock-eod-refactoring-plan-2026-07-15.md`에 적힌 11개 파일 순서와 서버 종료·백업·전후 대사 절차를 따릅니다. MySQL DDL은 문장 단위로만 원자적이므로 여러 ALTER를 하나의 트랜잭션처럼 간주하지 않습니다.
- 구버전 애플리케이션 호환용 rollback ALTER는 제공하지 않습니다. 적용 오류는 마지막 성공 지점을 확인한 뒤 멱등 정방향 ALTER로 보정하며, 정확한 이전 스키마와 데이터가 필요할 때만 적용 전 schema·영향 테이블 dump를 복원합니다.
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
- `stock_corporate_action_entitlement`
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
- 공개 종목 시장 보고서는 최신 전체 장마감일을 기준으로 종가·거래실적, 5/20/60일 성과, 기간 체결 빈도, 참가자 유형별 수급, 마감 보유 집중도, 기준일까지의 기업 이벤트, 동일 기준일 전 종목 순위와 데이터 품질을 반환합니다. 현재 호가·호가 깊이·슬리피지·현재 미체결 주문·주관사 운영값과 주문 집행 품질은 보고서에 섞지 않습니다. 전체 계약과 산식은 `docs/market-simulation/19-instrument-market-analytics-report.md`를 따르며 원장이 없는 재무·호가 지표는 추정하지 않습니다.
- 주문장 종목은 `tick_size`, `price_limit_rate`를 가지며, LIMIT 주문 접수/정정 시 호가 단위와 일일 가격제한폭을 검증합니다.
- 현재가 시장은 초기 기본값으로 1원 tick과 `stock_price.previous_close` 기준 ±30% 가격제한폭을 사용합니다.
- 주문 원장은 공유하되 `stock_order.market_type`으로 `VIRTUAL_PRICE`와 `ORDER_BOOK` 주문을 분리합니다.
- 자동장 상태 조회는 `stock_auto_participant`, `stock_auto_market_config`, 자동 참여자 주문/체결 원장을 읽어 현재 활성 상태와 종목별 설정을 반환합니다.
- 유상증자는 `SHAREHOLDER_ALLOCATION`과 `PUBLIC_OFFERING`만 지원합니다. 관리자 등록 시 청약 시작일/종료일, 납입일, 신주상장일을 기록하고, 주주배정은 권리락일과 별도 주주확정 기준일을 사용합니다. 권리 배정에는 action에 고정한 권리락 직전 최신 전체시장 close cycle/run만 사용합니다. 발행주식수 기준과 폐지 상태가 교차하지 않도록 동일 종목의 진행 중 유상증자/액면분할/무상증자/주식배당/상장폐지는 서로 겹쳐 등록할 수 없고, 현금배당은 이 제한에서 제외합니다.
- 사용자 청약은 인증된 본인 계좌로 장 마감 후 청약 기간 안에서만 허용합니다. 주주배정은 배정 entitlement 수량, 일반공모는 전체 잔여 발행수량을 서버 트랜잭션과 row lock으로 검증합니다.
- 주주배정 부분 청약은 청약 종료일까지 누적할 수 있고 남은 권리는 납입일에 포기수량으로 확정합니다. 청약 대금은 즉시 현금에서 차감하고 action/entitlement/효력 거래일이 연결된 `CAPITAL_INCREASE_SUBSCRIPTION` 원장을 남기지만 외부 출금으로 계산하지 않습니다. 신주 상장 전에는 `PARTIALLY_SUBSCRIBED`/`SUBSCRIBED` entitlement의 금액을 예약자산으로 총자산에 포함하고, 상장 시 보유주식 가치로 대체합니다.
- 권리락·미청약 권리 만료·납입·실제 청약수량만큼의 신주 상장은 `stock-batch-service`가 처리합니다.
- 액면분할은 API 호출 시점에 주식 수를 즉시 늘리지 않고, 효력일에 `stock-batch-service`가 주식 수, 보유수량, 가격을 비례 조정합니다.
- 현금배당은 배당금, 배당락일, 지급일을 `stock_corporate_action`에 기록합니다. 배당락일 보유자별 지급 원장 생성과 지급일 현금 반영은 `stock-batch-service`가 처리하며, 현금배당 자체는 현재가를 강제로 조정하지 않습니다.
- 무상증자와 주식배당은 권리락일, 신주상장일, 배정 주식수를 `stock_corporate_action`에 기록합니다. 권리락 가격 조정, 보유자별 신주 entitlement 생성, 상장일 보유수량/평균단가 반영은 `stock-batch-service`가 처리합니다.
- 기업 이벤트 이력은 종목별 API와 전체 feed API로 조회합니다. 전체 feed는 `createdAt desc, id desc`, 기본 100건/최대 200건이며 `actionType`으로 필터링할 수 있습니다. 무필터/유상증자 feed에는 최근 제한 밖의 상장 전(`ANNOUNCED`/`EX_RIGHTS_APPLIED`/`PAID`) 유상증자도 합치므로 응답 건수가 `limit`을 넘을 수 있습니다.
- 사용자별 entitlement 조회는 진행 중인 `ANNOUNCED`/`PARTIALLY_SUBSCRIBED`/`SUBSCRIBED` 권리를 모두 포함하고, 완료/만료 이력은 최근 50건을 함께 반환합니다.
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
