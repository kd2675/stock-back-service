# Change Order And Verification

## 공통 변경 순서

1. 요구사항이 어느 시장에 속하는지 정한다: `VIRTUAL_PRICE`, `ORDER_BOOK`, 또는 공통.
2. 사용자 요청인지, batch 시간 기반 원장 변경인지 정한다.
3. DDL이 필요한지 먼저 판단한다.
4. back API/DTO/entity/repository를 바꾼다.
5. batch job/JDBC SQL/scheduler를 바꾼다.
6. front type/API client/screen을 바꾼다.
7. verifier와 테스트를 바꾼다.
8. 문서를 갱신한다.

## 기능별 시작점

- 주문 접수/정정/취소: `TradingService`, `TradingController`, `OrderRequest`, `OrderResponse`.
- 현재가 체결: `OrderExecutionService`.
- 주문장 체결: `InternalOrderBookExecutionService`.
- 자동장: `AutoMarketService`, `AutoMarketScheduler`.
- 기업 이벤트: `MarketService.applyCorporateAction`, `CorporateActionService`.
- 가격 갱신: `MarketDataRefreshService`, `PriceStreamService`.
- 정산/랭킹: `PortfolioSettlementService`, `MarketService.getRankings`.
- 프론트: `app/types/stock.ts`, `app/lib/stock.ts`, 해당 page component.
- 설정: `application*.yml`, `.env.example`, smoke script.

## 검증 매트릭스

back만 변경:

- `./gradlew :stock-back-service:compileJava`
- `./gradlew :stock-back-service:test`

batch만 변경:

- `./gradlew :stock-batch-service:compileJava`
- `./gradlew :stock-batch-service:test`

front만 변경:

- `cd stock-front-service && npm run verify:contract`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`

공통 계약 변경:

- `node scripts/verify-stock-initial-scope.mjs`
- `./gradlew :stock-back-service:test --tests '*StockBackApiSurfaceContractTest*'`
- `./gradlew :stock-batch-service:test --tests '*StockBatchApiSurfaceContractTest*'`
- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `cd stock-front-service && npm run verify:contract`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`

## 변경 전 질문

- 이 기능이 DB 원장을 바꾸는가?
- 이 기능이 batch에서 돌아야 하는가?
- 이 기능이 두 시장을 모두 건드리는가?
- 새 enum 값이 DB, Java, TypeScript에 모두 필요한가?
- 기존 smoke/test profile에서 scheduler가 의도치 않게 돌 가능성이 있는가?
- 기업 이벤트라면 open order 처리 정책이 정해졌는가?

## 구현하지 말아야 하는 패턴

- front에서 체결/잔고/보유수량을 확정하는 코드.
- back 서버에서 scheduler로 체결을 돌리는 코드.
- enum 값만 추가하고 DDL check constraint를 빼는 변경.
- DB에는 없는 값을 TypeScript union에 먼저 넣는 변경.
- `INITIAL_ISSUE`를 일반 admin 이벤트 적용 API로 받는 변경.
- smoke/test seed를 일반 운영 resource에 넣는 변경.
