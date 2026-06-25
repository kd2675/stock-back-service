# Auth And API Boundaries

이 문서는 stock 서비스의 인증, 권한, public/protected/internal API 경계를 설명한다.

## 현재 인증 구조

`stock-back-service`는 사용자-facing API 서버다. 보호 API는 `auth-common-core`의 principal context를 사용한다.

- 사용자 보호 API: `@RequirePrincipalRole`
- 관리자 API: `@RequirePrincipalRole(anyOf = {UserRole.ADMIN})`
- 사용자 식별: `UserContext.getUserKey()`
- 공통 응답: `web-common-core`의 `ResponseDataDTO`

관련 코드:

- `stock-back-service/src/main/java/stock/back/service/trading/act/TradingController.java`
- `stock-back-service/src/main/java/stock/back/service/trading/act/AccountController.java`
- `stock-back-service/src/main/java/stock/back/service/market/act/MarketController.java`
- `stock-back-service/src/main/java/stock/back/service/common/config/MvcConfig.java`
- `stock-back-service/src/main/java/stock/back/service/common/config/FeignHeaderRelayConfig.java`

## API 경계

Public read API:

- `GET /api/stock/v1/system/status`
- `GET /api/stock/v1/markets/instruments`
- `GET /api/stock/v1/markets/prices`
- `GET /api/stock/v1/markets/prices/stream`
- `GET /api/stock/v1/markets/prices/{symbol}/ticks`
- `GET /api/stock/v1/markets/order-books/{symbol}`
- `GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- `GET /api/stock/v1/markets/rankings`
- `GET /api/stock/v1/markets/virtual-market`
- `GET /api/stock/v1/markets/order-book-market`
- `GET /api/stock/v1/markets/auto-market`

User protected API:

- `GET /api/stock/v1/accounts/me`
- `GET /api/stock/v1/accounts/me/status`
- `POST /api/stock/v1/accounts/me`
- `GET /api/stock/v1/portfolio/me`
- `GET /api/stock/v1/portfolio/me/snapshots`
- `GET /api/stock/v1/portfolio/me/profit-summary`
- `GET /api/stock/v1/markets/corporate-action-entitlements/me`
- `GET /api/stock/v1/orders`
  - optional query: `marketType=VIRTUAL_PRICE|ORDER_BOOK`
- `POST /api/stock/v1/orders`
- `DELETE /api/stock/v1/orders/{orderId}`
- `GET /api/stock/v1/executions`
  - optional query: `source=VIRTUAL_MARKET_PRICE|INTERNAL_ORDER_BOOK`
- `GET /api/stock/v1/holdings`
- `GET /api/stock/v1/users/me`

Admin protected API:

- `POST /api/stock/v1/markets/order-book-instruments`
- `POST /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions`
- `PATCH /api/stock/v1/markets/auto-market/configs/{symbol}`
- `PATCH /api/stock/v1/markets/auto-market/participants/{userKey}`
- `PATCH /api/stock/v1/markets/auto-market/participants/{userKey}/symbols/{symbol}`
- `GET /api/stock/v1/markets/batch-jobs/runtime-controls`
- `PATCH /api/stock/v1/markets/batch-jobs/runtime-controls/{jobName}`

Batch internal API:

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

관련 코드:

- `stock-batch-service/src/main/java/stock/batch/service/common/config/StockBatchInternalApiInterceptor.java`
- `stock-batch-service/src/main/java/stock/batch/service/common/act/StockBatchJobController.java`

## local-direct 모드

Gateway 없이 직접 붙는 로컬 모드에서는 프론트가 access token payload에서 `X-User-Key`, `X-User-Role`을 함께 붙인다.

관련 코드:

- `stock-front-service/app/lib/auth.ts`
- `stock-front-service/app/lib/stock.ts`

Gateway 모드에서는 gateway가 같은 역할의 헤더를 주입해야 한다. 둘 중 어느 모드든 stock-back 입장에서는 principal context가 동일해야 한다.

## 바꿀 때 순서

1. API가 public인지 protected인지 먼저 결정한다.
2. protected면 USER/ADMIN 중 필요한 role을 정한다.
3. controller에 `@RequirePrincipalRole`을 붙인다.
4. local-direct에서 프론트가 필요한 `X-User-*` 헤더를 보내는지 확인한다.
5. gateway 모드에서 같은 헤더가 주입되는지 확인한다.
6. 조회 API에 optional query를 추가하면 기존 무필터 호출과 필터 호출을 모두 테스트한다.
7. 권한 경계 테스트를 추가한다.

## 주의점

- 관리자 화면을 숨기는 것만으로는 보안이 아니다. back API에 ADMIN 제한이 있어야 한다.
- batch internal API는 사용자 토큰으로 열면 안 된다.
- role 문자열 비교는 각 서비스에서 직접 하지 말고 공통 role 판단을 사용해야 한다.
- `/orders`, `/executions`는 최근 50건 제한이 있으므로 화면별 시장 구분이 필요하면 서버 query filter를 사용한다. 프론트에서 받은 뒤 필터링하면 다른 시장의 최근 데이터가 앞쪽을 채워 필요한 데이터가 누락될 수 있다.
