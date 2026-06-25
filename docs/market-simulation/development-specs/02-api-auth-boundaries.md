# API And Auth Boundaries

## 현재 구현

stock 사용자 API는 `auth-common-core`의 `@RequirePrincipalRole`과 `UserContext`를 통해 principal을 받는다. 공개 조회 API는 principal 없이 허용하고, 주문/계좌/내 정보/내 권리 조회 API는 로그인 principal이 필요하다. 관리자 쓰기 API는 `ADMIN`만 허용한다.

batch internal API는 외부 사용자 API가 아니라 운영/테스트용 job trigger다. `stock-batch-service`의 interceptor가 internal token을 검사한다.

## 사용자 API 표면

공개 조회:

- `GET /api/stock/v1/system/status`
- `GET /api/stock/v1/markets/instruments`
- `GET /api/stock/v1/markets/order-book-instruments`
- `GET /api/stock/v1/markets/prices`
- `GET /api/stock/v1/markets/prices/stream`
- `GET /api/stock/v1/markets/prices/{symbol}/ticks`
- `GET /api/stock/v1/markets/order-books/{symbol}`
- `GET /api/stock/v1/markets/rankings`
- `GET /api/stock/v1/markets/virtual-market`
- `GET /api/stock/v1/markets/order-book-market`
- `GET /api/stock/v1/markets/auto-market`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`

로그인 필요:

- `GET /api/stock/v1/accounts/me`
- `GET /api/stock/v1/accounts/me/status`
- `POST /api/stock/v1/accounts/me`
- `GET /api/stock/v1/users/me`
- `GET /api/stock/v1/portfolio/me`
- `GET /api/stock/v1/portfolio/me/snapshots`
- `GET /api/stock/v1/portfolio/me/profit-summary`
- `GET /api/stock/v1/orders`
- `POST /api/stock/v1/orders`
- `DELETE /api/stock/v1/orders/{orderId}`
- `PATCH /api/stock/v1/orders/{orderId}`
- `POST /api/stock/v1/orders/{orderId}/cancel`
- `GET /api/stock/v1/executions`
- `GET /api/stock/v1/holdings`
- `GET /api/stock/v1/markets/corporate-action-entitlements/me`

관리자 필요:

- `POST /api/stock/v1/markets/order-book-instruments`
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- `PATCH /api/stock/v1/markets/{marketType}/symbols/{symbol}/status`
- `PATCH /api/stock/v1/markets/auto-market/configs/{symbol}`
- `PATCH /api/stock/v1/markets/auto-market/participants/{userKey}`
- `PATCH /api/stock/v1/markets/auto-market/participants/{userKey}/symbols/{symbol}`
- `GET /api/stock/v1/markets/batch-jobs/runtime-controls`
- `PATCH /api/stock/v1/markets/batch-jobs/runtime-controls/{jobName}`

## batch internal API 표면

- `GET /internal/stock-batch/v1/system/status`
- `POST /internal/stock-batch/v1/jobs/market-data/refresh`
- `POST /internal/stock-batch/v1/jobs/virtual-price-execution/run`
- `POST /internal/stock-batch/v1/jobs/order-book-execution/run`
- `POST /internal/stock-batch/v1/jobs/auto-participant-cash-flow/run`
- `GET /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `PATCH /internal/stock-batch/v1/jobs/auto-participant-cash-flow/status`
- `GET /internal/stock-batch/v1/jobs/runtime-controls`
- `PATCH /internal/stock-batch/v1/jobs/runtime-controls/{jobName}`
- `POST /internal/stock-batch/v1/jobs/auto-market/run`
- `POST /internal/stock-batch/v1/jobs/portfolio-settlement/run`
- `POST /internal/stock-batch/v1/jobs/market-close/rollover`
- `POST /internal/stock-batch/v1/jobs/corporate-actions/run`

## 관련 코드

- `stock-back-service/src/main/java/stock/back/service/common/config/MvcConfig.java`
- `stock-back-service/src/main/java/stock/back/service/common/config/FeignHeaderRelayConfig.java`
- `stock-back-service/src/main/java/stock/back/service/user/biz/StockUserService.java`
- `stock-back-service/src/test/java/stock/back/service/common/config/StockBackApiSurfaceContractTest.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/config/StockBatchInternalApiInterceptor.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/config/WebMvcConfig.java`
- `stock-batch-service/src/test/java/stock/batch/service/common/config/StockBatchApiSurfaceContractTest.java`
- `stock-front-service/app/lib/api.ts`
- `stock-front-service/app/lib/stock.ts`

## 앞으로 구현할 방향

- 새 사용자 API는 응답 래퍼로 `ResponseDataDTO`를 쓴다.
- 새 관리자 API는 controller method에 `@RequirePrincipalRole(anyOf = {UserRole.ADMIN})`을 붙인다.
- batch internal API를 새로 만들면 token 검사 대상 path에 포함되는지 테스트로 고정한다.
- direct 모드와 gateway 모드가 모두 동작해야 하므로 front의 `X-User-Key`, `X-User-Role`, `X-Client-Id` 전달 경계를 깨지 않는다.

## 바꿀 때 순서

1. controller method와 path를 추가한다.
2. principal이 필요한지 공개인지 먼저 결정한다.
3. DTO를 추가하고 `web-common-core` 응답 래퍼로 반환한다.
4. front `app/lib/stock.ts`와 `app/types/stock.ts`를 동시에 맞춘다.
5. Spring route table 기준 API surface contract test를 갱신한다.
6. front contract verifier와 controller/API boundary test를 갱신한다.

## 검증

- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `cd stock-front-service && npm run verify:contract`
