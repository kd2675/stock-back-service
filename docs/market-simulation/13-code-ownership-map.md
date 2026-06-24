# Code Ownership Map

이 문서는 stock 모의투자 기능을 바꿀 때 어떤 코드를 봐야 하는지 정리한다. 기능별 상세 흐름은 같은 디렉터리의 번호별 문서를 먼저 보고, 이 문서는 수정 파일 범위를 좁히는 용도로 사용한다.

## stock-back-service 역할

`stock-back-service`는 사용자 API와 도메인 계약의 기준이다. 주문을 접수하고, 계좌/보유/체결/시장 상태를 조회하며, 관리자 쓰기 API를 제공한다. 시간이 지나며 상태가 바뀌는 batch 작업은 여기서 돌리지 않는다.

### 공통/인증

- `common/config/MvcConfig.java`
  - `auth-common-core`의 principal argument resolver, role interceptor를 MVC에 연결한다.
  - 보호 API가 `UserContext`를 받을 수 있게 하는 진입점이다.
- `common/config/FeignHeaderRelayConfig.java`
  - stock-back이 auth-back-server를 호출할 때 현재 요청의 `X-User-*` 헤더를 relay한다.
  - local-direct 모드에서 auth 사용자 프로필 조회가 401로 깨지면 먼저 확인한다.
- `common/exception/StockException.java`, `GlobalExceptionHandler.java`
  - stock 도메인 예외를 `web-common-core` 응답 포맷으로 변환한다.
  - 새 에러 바디 포맷을 만들지 않는다.

### 데이터 원장

- `database/entity/StockAccount.java`
  - 사용자 현금, 초기자산 원장이다.
  - 매수 예약, 취소 환불, 체결 입출금은 이 엔티티의 금액 불변식을 깨지 않아야 한다.
- `database/entity/StockHolding.java`
  - 사용자 보유수량, 예약수량, 평균단가 원장이다.
  - 매도 주문은 `reservedQuantity`, 체결은 `quantity`와 `reservedQuantity`를 함께 조정한다.
- `database/entity/StockOrder.java`
  - 주문 접수 원장이다.
  - `marketType`이 `VIRTUAL_PRICE`와 `ORDER_BOOK` 체결 엔진을 나누는 핵심 컬럼이다.
- `database/entity/StockExecution.java`
  - 체결 원장이다.
  - `source`, `grossAmount`, `feeAmount`, `taxAmount`, `netAmount`, `realizedProfit`은 손익 조회의 기준이다.
- `database/entity/StockPrice.java`, `StockPriceTick.java`
  - 최신가와 가격 이력이다.
  - 주문 접수 가격제한폭, 포트폴리오 평가, 주문장 마지막 체결가 표시가 이 값을 사용한다.
- `database/entity/StockOrderBookInstrument.java`
  - 관리자 생성 주문장 종목이다.
  - 발행주식수, 유통주식수, 호가 단위, 가격제한폭을 가진다.
- `database/entity/StockCorporateAction.java`, `StockCorporateActionEntitlement.java`
  - 기업 이벤트와 사용자별 배정/지급 원장이다.
  - 이벤트 등록 API는 원장을 만들고, 실제 반영은 batch가 한다.
- `database/entity/StockInstrumentReportEvent.java`
  - 주문장 종목 평가 보고서 이벤트 원장이다.
  - 최신 이벤트가 `DELETE`가 아니면 자동장 판단에 쓰이는 현재 보고서다.

### 주문/계좌 API

- `trading/act/TradingController.java`
  - `/api/stock/v1/orders`, `/executions`, `/portfolio/me`, `/holdings` 진입점이다.
  - `GET /orders`는 optional `marketType`, `GET /executions`는 optional `source` query를 받는다.
- `trading/biz/TradingService.java`
  - 주문 접수, 취소, 부분취소, 정정, 포트폴리오/손익 조회의 핵심 서비스다.
  - 체결을 확정하지 않는다. 체결은 batch 서비스가 원장에 반영한다.
- `trading/biz/AccountService.java`
  - 계좌 상태 확인, 명시 개설, 기존 계좌 조회와 계좌 row lock 경계다.
  - 현금 경쟁 조건이 생기면 이 서비스와 repository lock을 먼저 확인한다.
- `trading/vo/*.java`
  - 프론트와 공유되는 API 계약이다.
  - 새 필드는 `stock-front-service/app/types/stock.ts`와 함께 갱신한다.

### 시장/관리 API

- `market/act/MarketController.java`
  - 가격, 주문장, 랭킹, 시장 상태, 주문장 종목, 기업 이벤트 API 진입점이다.
  - 관리자 쓰기 API는 `ADMIN` role 제한이 있어야 한다.
- `market/biz/MarketService.java`
  - 시장 조회 조립, 주문장 종목 생성, 장 상태 변경, 기업 이벤트 등록, 평가 보고서 이벤트 등록을 담당한다.
  - batch가 처리할 원장 이벤트를 여기서 즉시 반영하지 않는다.
- `market/cache/StockPriceCacheService.java`
  - Redis 최신가 캐시 조회와 DB fallback 경계다.
- `market/stream/PriceStreamService.java`
  - Redis pub/sub 가격 이벤트를 SSE client로 전달한다.

## stock-batch-service 역할

`stock-batch-service`는 시간 경과에 따른 상태 변화를 처리한다. 같은 DB/Redis 원장을 보며, 사용자-facing API를 제공하지 않는다.

### Job 진입점

- `common/act/StockBatchJobController.java`
  - 수동 internal job API다.
  - scheduler와 같은 service를 호출해야 한다.
- `common/biz/StockBatchJobService.java`
  - 중복 실행 방지, job status 응답, job orchestration 경계다.
- `common/config/StockBatchInternalApiInterceptor.java`
  - 내부 job API의 `X-Internal-Token` 보호 경계다.

### 시세 갱신

- `marketdata/biz/MarketDataRefreshService.java`
  - 관심/보유/미체결 symbol을 모아 provider로 가격을 가져온다.
  - `stock_price`, `stock_price_tick`, Redis latest price, Redis pub/sub을 갱신한다.
- `marketdata/provider/MarketPriceProvider.java`
  - 시세 provider 교체 interface다.
- `marketdata/provider/MockMarketPriceProvider.java`
  - 로컬/테스트용 mock provider다.
- `marketdata/provider/KisMarketPriceProvider.java`
  - KIS OpenAPI 현재가 provider다.

### 체결 엔진

- `execution/biz/OrderExecutionService.java`
  - `VIRTUAL_PRICE` 주문을 DB 현재가 기준으로 체결한다.
  - 남은 수량 전체를 한 번에 체결하는 단순 모델이다.
- `execution/biz/InternalOrderBookExecutionService.java`
  - `ORDER_BOOK` 주문을 가격 우선, 시간 우선으로 매칭한다.
  - 마지막 체결가를 `stock_price`와 Redis에 반영한다.
- `execution/biz/ExecutionCostCalculator.java`
  - 수수료, 세금, 순금액 계산을 공통화한다.
  - 새 비용 정책은 두 체결 엔진에 중복 구현하지 말고 여기서 시작한다.

### 자동장/기업 이벤트/정산

- `automarket/biz/AutoMarketService.java`
  - 자동 참여자 주문을 실제 `stock_order`에 생성한다.
  - 참여자별-종목별 `stock_auto_participant_symbol_config`가 있으면 해당 강도를 우선 사용한다.
  - 최신 활성 평가 보고서 점수가 있으면 참여자 강도와 섞어 유효 강도를 만든다.
  - 자동 참여자 보유수량은 초기 지급하지 않고 실제 주문장 매수 체결 결과만 사용한다.
  - 프론트 전용 fake 주문 상태를 만들지 않는다.
- `marketclose/biz/MarketCloseRolloverService.java`
  - 장마감 종가를 다음 장 가격제한폭 기준가로 넘긴다.
  - `stock_price.current_price`를 `previous_close`로 복사하되 현재가/제공자/시간은 바꾸지 않는다.
- `corporateaction/biz/CorporateActionService.java`
  - 권리락, 지급, 상장, 분할 같은 기업 이벤트 상태 전이를 처리한다.
  - 날짜별 idempotency와 열린 주문 처리 정책이 중요하다.
- `settlement/biz/PortfolioSettlementService.java`
  - 장 마감 자산 snapshot과 랭킹 기준 데이터를 만든다.
- `scheduler/*.java`
  - 각 job의 주기 트리거다.
  - scheduler는 얇게 두고 실제 로직은 service에 둔다.

## stock-front-service 역할

`stock-front-service`는 DB 원장을 화면으로 보여주고 주문/관리 API를 호출한다. 체결, 잔고, 배당 상태를 브라우저에서 직접 만들지 않는다.

- `app/lib/api.ts`
  - 공통 fetch wrapper와 `ResponseEnvelope` 해석을 담당한다.
- `app/lib/auth.ts`
  - access token, refresh token 흐름, local-direct `X-User-*` header source를 담당한다.
- `app/lib/stock.ts`
  - stock-back API client다.
  - 시장별 조회는 query filter를 여기서 명시한다.
- `app/types/stock.ts`
  - back DTO와 맞춰야 하는 TypeScript 계약이다.
- `app/page.tsx`
  - `VIRTUAL_PRICE` 홈 워크스페이스다.
- `app/supply-demand/page.tsx`
  - `ORDER_BOOK` 주문장 워크스페이스다.
  - 주문/체결 조회는 서버 필터를 사용한다.
- `app/supply-demand/admin/page.tsx`
  - 관리자 주문장 종목/장 상태/기업 이벤트/평가 보고서 화면이다.
- `app/components/MarketModeTabs.tsx`
  - 두 시장을 최상단에서 분리하는 navigation이다.

## DDL 소유권

원장 컬럼을 바꾸면 아래 파일을 함께 본다.

- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_market_execution_split_alter.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2_smoke_data.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_market_execution_split_alter.sql`

back과 batch는 같은 MySQL schema를 보므로 full DDL과 alter DDL을 둘 다 맞춘다. batch 테스트는 H2를 쓰므로 H2 DDL도 같이 갱신한다.

## 테스트 소유권

- 주문 접수/계좌/포트폴리오: `stock-back-service/src/test/java/stock/back/service/trading/biz/TradingServiceTest.java`
- 시장/관리 API: `stock-back-service/src/test/java/stock/back/service/market/biz/MarketServiceTest.java`
- 주문장 repository: `stock-back-service/src/test/java/stock/back/service/database/repository/StockOrderRepositoryOrderBookTest.java`
- 현재가 체결: `stock-batch-service/src/test/java/stock/batch/service/execution/biz/OrderExecutionServiceTest.java`
- 주문장 체결: `stock-batch-service/src/test/java/stock/batch/service/execution/biz/InternalOrderBookExecutionServiceTest.java`
- 비용/손익: `stock-batch-service/src/test/java/stock/batch/service/execution/biz/ExecutionCostAccountingTest.java`
- 자동장: `stock-batch-service/src/test/java/stock/batch/service/automarket/`
- 기업 이벤트: `stock-batch-service/src/test/java/stock/batch/service/corporateaction/`
- DDL 정합성: `stock-batch-service/src/test/java/stock/batch/service/database/StockSchemaConstraintTest.java`

## 변경 범위 판단

- API response field만 추가: back DTO, front type/API/screen, controller/service test.
- 원장 컬럼 추가: back entity/repository, batch SQL, MySQL/H2 DDL, front type, tests.
- 평가 보고서 변경: back report entity/repository/API, batch 최신 보고서 SQL, admin report UI, contract tests.
- 체결 규칙 변경: batch execution service, cost calculator, back read model, DDL 여부 확인, front display.
- 관리자 입력 추가: back request validation, DDL/entity, batch 적용 service, admin page, tests.
- 장 상태/권한 변경: controller annotation/interceptor, service guard, boundary tests, front disabled state.
