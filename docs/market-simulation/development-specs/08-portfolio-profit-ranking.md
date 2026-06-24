# Portfolio, Profit, And Ranking

## 현재 구현

포트폴리오는 현재 계좌 현금, 예약 매수 현금, 보유 평가액을 합산한다. 손익은 체결 원장의 수수료, 세금, 실현손익과 현재 보유 평가손익을 합산한다. 랭킹은 일별 `portfolio_snapshot` 기준으로 조회한다.

## 관련 코드

back:

- `TradingService.getPortfolio`
- `TradingService.getHoldings`
- `TradingService.getPortfolioSnapshots`
- `TradingService.getProfitSummary`
- `MarketService.getRankings`
- `StockExecutionRepository.summarizeProfitByUserKey`

batch:

- `PortfolioSettlementService.settleToday`
- `PortfolioSettlementScheduler`

front:

- `stock-front-service/app/page.tsx`
- `stock-front-service/app/types/stock.ts`

## 현재 계산 기준

포트폴리오:

- `marketValue = sum(holding.quantity * currentPrice)`
- `reservedBuyCash = sum(open buy order reserved_cash)`
- `totalAsset = cashBalance + reservedBuyCash + marketValue`
- `returnRate = (totalAsset - netCashFlow) / netCashFlow * 100`

`netCashFlow`는 `stock_account_cash_flow`의 입금 합계에서 회수 합계를 뺀 값이다.

손익:

- 매수 체결은 수수료 포함 순지출을 보유 평균단가에 반영한다.
- 매도 체결은 수수료와 세금을 차감한 순입금을 계좌 현금에 반영한다.
- 실현손익은 매도 시 평균단가 대비로 `stock_execution.realized_profit`에 남긴다.
- 미실현손익은 현재가 기준 보유 평가액과 평균단가 원가 차이다.

랭킹:

- batch 정산이 `portfolio_snapshot`을 생성/갱신한다.
- back은 가장 최근 snapshot date의 상위 20명을 return rate 순으로 반환한다.

## 현재 불변식

- settlement는 batch 책임이다.
- 프로필/계좌 상태 조회 API는 계좌를 자동 개설하지 않는다. 첫 진입 온보딩에서 사용자가 명시적으로 계좌 만들기를 선택하면 계좌 개설 API가 계좌를 만들거나 기존 계좌를 반환한다.
- ranking은 실시간 포트폴리오가 아니라 snapshot 기준이다.

## 앞으로 구현할 후보

- 결제 예정/결제 완료 분리.
- T+N 정산.
- 수수료/세금 정책을 종목/시장/사용자 등급별로 분리.
- 기간별 손익 그래프.
- 랭킹 집계 시 개인 정보 노출 정책.

## 바꿀 때 순서

1. 금액 정의를 먼저 문서화한다.
2. `stock_execution` 컬럼이나 summary projection이 필요한지 확인한다.
3. batch 체결 서비스의 금액 반영을 바꾼다.
4. `PortfolioSettlementService`와 back 조회 DTO를 맞춘다.
5. front 표시 필드와 format을 맞춘다.

## 검증

- `./gradlew :stock-batch-service:test --tests '*ExecutionCostAccountingTest*'`
- `./gradlew :stock-batch-service:test --tests '*PortfolioSettlement*'`
- `./gradlew :stock-back-service:test --tests '*TradingServiceTest*'`
