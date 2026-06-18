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
- `GET /api/stock/v1/markets/order-books/{symbol}`
- `GET /api/stock/v1/markets/rankings`
- `GET /api/stock/v1/users/me`
- `GET /api/stock/v1/accounts/me`
- `GET /api/stock/v1/portfolio/me`
- `GET /api/stock/v1/portfolio/me/snapshots`
- `GET /api/stock/v1/holdings`
- `GET /api/stock/v1/orders`
- `POST /api/stock/v1/orders`
- `DELETE /api/stock/v1/orders/{orderId}`
- `GET /api/stock/v1/executions`

## 실행과 검증

아래 명령은 `zeroq-common` 루트에서 실행합니다.

```bash
./gradlew :stock-back-service:bootRun
./gradlew :stock-back-service:bootRun --args='--spring.profiles.active=local'
./gradlew :stock-back-service:compileJava
./gradlew :stock-back-service:test
scripts/stock-smoke.sh
```

## 포트

| Profile | Port |
|---|---:|
| `local` | `20480` |
| `dev` | `20480` |
| `prod` | `10480` |
| `test` | `30480` |

## 내부 의존성

- `web-common-core`
- `auth-common-core`

## 데이터베이스

- schema: `STOCK_SERVICE`
- DDL: `src/main/resources/db/ddl/stock_all.sql`
- local/dev 접속값은 `STOCK_DB_URL`, `STOCK_DB_USERNAME`, `STOCK_DB_PASSWORD`, `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT` 환경 변수로 바꿉니다.
- 루트 `.env` 또는 `stock-back-service/.env`는 optional import로 읽습니다.
- `local`/`dev` 기본값은 다른 백엔드 서비스와 맞춰 원격 개발 MySQL `kimd0.iptime.org:23306`과 Redis `kimd0.iptime.org:26379`입니다.
- 별도 로컬 MySQL/Redis를 쓰려면 `.env`에서 `STOCK_DB_URL`, `STOCK_REDIS_HOST`, `STOCK_REDIS_PORT`를 직접 오버라이드합니다.
- `prod`는 DB와 Redis 값을 환경 변수로 명시 주입합니다.
- JPA datasource 구조는 다른 백엔드 JPA 서비스와 맞춰 `database.datasource.pub.master/slave1`과 `PubDataConfig`를 사용합니다.
- `@Transactional(readOnly = true)` 트랜잭션은 `RoutingDataSource`에서 slave로 라우팅됩니다. 현재 local/dev는 master와 slave가 같은 `STOCK_DB_URL`을 보게 두고, 운영 분리 시 `STOCK_DB_SLAVE_URL` 계열 환경 변수를 씁니다.
- Hikari 풀은 local/dev 기본 8개이며, prod는 `STOCK_DB_MAX_POOL_SIZE`, `STOCK_DB_CONNECTION_TIMEOUT`, `STOCK_DB_MAX_LIFETIME`, `STOCK_DB_KEEPALIVE_TIME`로 조정합니다.
- 독립 저장소로 실행할 때는 `.env.example`을 기준으로 로컬 환경 변수를 맞춥니다.
- DDL은 기본 관심 종목과 최초 가격을 idempotent seed로 넣고, `MarketSeedService`도 stock-back 시작 시 누락된 기본 row만 보강합니다.

주요 테이블:

- `stock_account`
- `stock_instrument`
- `stock_price`
- `stock_price_tick`
- `stock_order`
- `stock_execution`
- `stock_holding`
- `portfolio_snapshot`

## 설계 기준

- DB는 주문, 체결, 잔고, 거래 이력의 원장입니다.
- 시장 가격 조회는 Redis `stock:price:{symbol}` 캐시를 우선 사용하고, Redis 장애나 값 오류가 있으면 DB `stock_price`로 fallback합니다.
- Redis에는 최신가 문자열을 저장하므로 `StringRedisTemplate` 기반 설정을 사용합니다. JSON Redis serializer는 현재 Spring Data Redis 4.x에서 removal deprecated 경고가 있어 사용하지 않습니다.
- 보유 종목 평가는 DB 현재가를 우선 사용하되, 내부 주문장 체결처럼 아직 `stock_price`가 없는 종목은 보유 평단가로 fallback합니다.
- 가격 이력 조회는 `stock_price_tick`에서 종목별 최근 100건을 `price_time desc` 기준으로 반환합니다.
- 주문장 조회는 미체결/부분체결 LIMIT 주문을 가격대별로 집계하며, 매수는 높은 가격 우선, 매도는 낮은 가격 우선으로 반환합니다.
- 공개 랭킹 응답은 내부 식별자인 `userKey`와 함께 화면 노출용 `displayName`을 제공합니다. 화면에서는 `displayName`을 우선 사용합니다.
- 외부 시세 수집과 미체결 주문 체결 판단은 `stock-batch-service`가 담당합니다.
- 주문 API는 체결을 직접 확정하지 않고 주문 원장을 만든 뒤 체결 프로세스가 처리하도록 둡니다.
- 매도 주문은 보유 수량 중 `reserved_quantity`를 제외한 주문 가능 수량만 접수하며, 취소 시 예약 수량을 되돌립니다.
- 시장가 매수는 접수 시점의 현재가를 기준으로 현금을 예약해야 하므로 현재가가 없으면 접수하지 않습니다.
- 시장가 매도는 현금 예약이 필요 없고 내부 주문장 모드에서 반대편 지정가가 가격 기준이 될 수 있으므로, 현재가가 없어도 보유 수량이 있으면 접수합니다.
- 장 마감 정산 결과는 `portfolio_snapshot`에 저장하고, 사용자별 최근 30개 정산 기록을 조회합니다.
- 현재 체결 source는 `VIRTUAL_MARKET_PRICE`이며, 이후 내부 호가 매칭은 `INTERNAL_ORDER_BOOK` source로 확장합니다.
