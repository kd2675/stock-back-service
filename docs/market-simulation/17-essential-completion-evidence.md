# Essential Completion Evidence

이 문서는 stock 프로젝트 초기 진행 범위가 현재 코드에서 충족되는지 증거 중심으로 남긴 완료 감사 기록이다. 기준 문서는 `16-initial-essential-scope-audit.md`이며, 이 문서는 그 기준을 어떤 파일과 테스트가 증명하는지 정리한다.

## 감사 대상

- 초기 프로젝트에 필수인 시장/주문/체결/조회/관리 기능만 유지한다.
- 실제 주식시장의 모든 기업 이벤트와 주문 유형을 선반영하지 않는다.
- `stock-back-service`, `stock-batch-service`, `stock-front-service`의 역할을 분리한다.
- local-direct 기본 구동, smoke 전용 seed, batch 책임, API surface를 자동 검증한다.

## 요구사항별 증거

| 요구사항 | 현재 증거 | 판정 |
|---|---|---|
| 기업 이벤트는 초기 필수 범위만 둔다. | `StockCorporateActionType.java`, `stock.ts`, admin select, DDL check constraint, `verify-stock-initial-scope.mjs` | 충족 |
| 보류 이벤트는 코드에 선반영하지 않는다. | verifier의 deferred corporate action 금지 목록과 main source scan | 충족 |
| 주문 타입은 `LIMIT`, `MARKET`만 둔다. | `OrderType.java`, front `OrderType`, DDL check constraint | 충족 |
| 시장 타입은 `VIRTUAL_PRICE`, `ORDER_BOOK`만 둔다. | `MarketType.java`, front `MarketType`, DDL check constraint | 충족 |
| 장 상태는 `OPEN`, `CLOSED`, `HALTED`만 둔다. | `MarketSessionStatus.java`, front `MarketSessionStatus`, DDL check constraint | 충족 |
| 현재가 시장과 주문장 시장을 분리한다. | `stock_order.market_type`, `stock_instrument`, `stock_order_book_instrument`, `MarketModeTabs.tsx` | 충족 |
| 주문장 종목은 admin이 생성한다. | `MarketController.createOrderBookInstrument`, admin page create form | 충족 |
| back은 batch job을 돌리지 않는다. | `stock-back-service/src/main/java`에 `@Scheduled`, `@EnableScheduling` 없음 | 충족 |
| batch가 체결/자동장/기업 이벤트/정산을 맡는다. | `StockBatchJobService`, scheduler classes, execution/corporateaction/automarket/settlement services | 충족 |
| 테스트 profile에서 scheduler를 끈다. | `stock-batch-service/src/main/resources/application-test.yml`의 job별 enabled false | 충족 |
| 일반 실행에서 기본 종목/가격을 seed하지 않는다. | non-smoke DDL seed marker scan, smoke data 분리 | 충족 |
| smoke는 명시적 H2 smoke data만 쓴다. | `application-smoke.yml`, `stock_h2_smoke_data.sql`, smoke scripts | 충족 |
| 기본 구동은 local-direct다. | stock back/batch `application.yml`, front `api.ts`, `.env.example` | 충족 |
| gateway는 명시 선택 모드다. | `NEXT_PUBLIC_API_MODE=gateway` 주석 예시, system status `gatewayRequired=false` | 충족 |
| stock-back API surface는 초기 endpoint만 가진다. | controller annotation scan, `StockBackApiSurfaceContractTest` | 충족 |
| stock-batch internal API surface는 system/jobs만 가진다. | controller annotation scan, `StockBatchApiSurfaceContractTest` | 충족 |
| front 계약은 Java/DDL 범위와 맞는다. | `verify-stock-front-contract.mjs`, `npm run verify:contract` | 충족 |
| 불필요한 docker compose artifact는 없다. | verifier의 stock service/scripts Docker artifact scan | 충족 |
| 초기 의존성은 필요한 stack만 둔다. | stock back/batch `build.gradle`, verifier dependency check | 충족 |

## 필수 검증 명령

현재 완료 판단은 아래 명령이 모두 통과할 때만 유효하다.

```bash
node scripts/verify-stock-initial-scope.mjs
./gradlew :stock-back-service:test --tests '*StockBackApiSurfaceContractTest*'
./gradlew :stock-batch-service:test --tests '*StockBatchApiSurfaceContractTest*'
./gradlew :stock-back-service:test
./gradlew :stock-batch-service:test
cd stock-front-service && npm run verify:contract
cd stock-front-service && npm run lint
cd stock-front-service && npm run build
git diff --check
git -C stock-back-service diff --check
git -C stock-batch-service diff --check
git -C stock-front-service diff --check
```

## 완료로 보지 않는 경우

아래 중 하나라도 발생하면 이 문서의 완료 판정은 깨진다.

- 새 기업 이벤트 enum 값이 들어갔는데 DDL/front/admin/test가 같이 바뀌지 않았다.
- 새 주문 타입이 들어갔는데 체결 엔진과 front 주문 validation이 같이 바뀌지 않았다.
- back service에 scheduler가 들어갔다.
- batch test profile에서 scheduler가 켜졌다.
- 일반 resource나 DDL에 기본 종목 seed가 들어갔다.
- Spring route table contract test 없이 새 stock API가 추가됐다.
- Docker compose나 Dockerfile이 stock 초기 필수 범위에 다시 들어갔다.

## 다음 확장 판단

다음 기능을 넣을 때는 `STOCK_MARKET_FEATURE_ROADMAP.md`를 바로 구현 목록으로 보지 않는다. 먼저 `15-corporate-action-scope.md`, `16-initial-essential-scope-audit.md`, `development-specs/12-change-order-and-verification.md`를 확인하고, 초기 필수 범위를 넘어서는 기능이면 별도 정책 문서와 원장 설계를 먼저 만든다.
