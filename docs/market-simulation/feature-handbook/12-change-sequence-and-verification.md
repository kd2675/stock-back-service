# Change Sequence And Verification

## 기능 변경 공통 순서

1. 기능이 어느 시장에 속하는지 정한다.
   - 현재가 시장: `VIRTUAL_PRICE`
   - 주문장 시장: `ORDER_BOOK`
2. 원장을 바꾸는지 확인한다.
   - 원장을 바꾸면 DDL, entity, batch SQL, DTO, front type을 같이 본다.
3. 권한 경계를 확인한다.
   - 사용자 API: `@RequirePrincipalRole`
   - 관리자 API: `@RequirePrincipalRole(anyOf = {UserRole.ADMIN})`
   - batch internal API: `X-Internal-Token`
4. back API 계약을 먼저 수정한다.
5. batch 상태 전이를 수정한다.
6. front type/API/screen을 수정한다.
7. 문서와 테스트를 갱신한다.

## 기능별 시작 파일

- 주문 접수/정정/취소: `TradingService.java`, `TradingController.java`, `StockOrder.java`, `OrderRequest.java`, `OrderAmendRequest.java`, `OrderCancelRequest.java`
- 현재가 체결: `OrderExecutionService.java`, `VirtualPriceExecutionScheduler.java`, `ExecutionCostCalculator.java`
- 주문장 체결: `InternalOrderBookExecutionService.java`, `OrderBookExecutionScheduler.java`, `StockOrderRepository.java`
- 시세/Redis/SSE: `MarketDataRefreshService.java`, `StockPriceCacheService.java`, `PriceStreamService.java`, `app/page.tsx`
- 자동장: `AutoMarketService.java`, `AutoMarketScheduler.java`, `stock_auto_market_config`
- 기업 이벤트: `MarketService.applyCorporateAction`, `CorporateActionService.applyDueCorporateActions`, `StockCorporateAction.java`, `CorporateActionRequest.java`, `app/supply-demand/admin/page.tsx`
- 장 상태/관리: `MarketService.updateMarketStatus`, `TradingService.validateMarketOpen`, `stock_virtual_market_config`, `stock_order_book_market_config`
- 포트폴리오/손익/랭킹: `TradingService.getPortfolio`, `TradingService.getProfitSummary`, `PortfolioSettlementService`, `MarketService.getRankings`
- front 계약: `app/types/stock.ts`, `app/lib/stock.ts`, `scripts/verify-stock-front-contract.mjs`

## 검증 매트릭스

- back API/서비스 변경: `./gradlew :stock-back-service:test`
- batch job/SQL 변경: `./gradlew :stock-batch-service:test`
- front 변경:
  - `cd stock-front-service && npm run verify:contract`
  - `cd stock-front-service && npm run lint`
  - `cd stock-front-service && npm run build`
- DDL 변경:
  - `StockMysqlDdlContractTest`
  - `StockDdlContractTest`
  - `StockSchemaConstraintTest`
  - `git diff --check`

## 변경 전 질문

- 이 기능은 주문 접수 단계인가, 체결 단계인가?
- 이 기능은 현재가 시장인가, 주문장 시장인가?
- 사용자가 직접 호출하는 API인가, batch가 처리하는 job인가?
- 돈, 수량, 가격, 상태 중 무엇을 바꾸는가?
- 기존 주문/보유/계좌와의 불변식은 무엇인가?
- 실패 시 rollback되어야 하는 단위는 어디까지인가?

## 구현하지 말아야 하는 패턴

- front local state만으로 체결/잔고/배당을 만든다.
- back controller에서 batch 로직을 직접 실행한다.
- DDL 없이 Java entity만 바꾼다.
- batch H2 DDL만 바꾸고 MySQL full DDL을 놓친다.
- 새 기업 이벤트를 enum에만 추가하고 batch 상태 전이를 빼먹는다.
- 현재가 시장과 주문장 시장의 symbol을 자동으로 공유한다고 가정한다.
