# Portfolio, Profit, And Ranking

## 현재 구현

포트폴리오는 실시간 조회와 일별 snapshot으로 나뉜다. 실시간 조회는 back이 계산하고, 일별 snapshot은 batch가 만든다.

## 코드 역할

- 실시간 조회: `TradingController.getMyPortfolio`, `TradingController.getMyPortfolioSnapshots`, `TradingController.getMyProfitSummary`, `TradingService.getPortfolio`, `TradingService.getPortfolioSnapshots`, `TradingService.getProfitSummary`
- 체결 비용/손익: `ExecutionCostCalculator`, `OrderExecutionService`, `InternalOrderBookExecutionService`, `StockExecutionRepository.summarizeProfitByUserKey`
- 정산: `PortfolioSettlementService.settleToday`, `PortfolioSettlementScheduler`
- 랭킹: `MarketService.getRankings`, `PortfolioSnapshotRepository`
- front: `stock-front-service/app/page.tsx`

## 현재 계산 기준

- 실시간 총자산은 현금 + 예약 매수 현금 + 보유 평가금액이다.
- 보유 평가금액은 최신가가 있으면 최신가, 없으면 평균단가 fallback이다.
- 수수료는 매수 원가에 포함된다.
- 매도 실현손익은 net amount에서 평균단가 기준 원가를 뺀 값이다.
- 일별 snapshot은 `portfolio_snapshot`에 user/date unique로 upsert한다.
- 랭킹은 최신 snapshot date의 return rate 상위 20명이다.

## 앞으로 구현할 후보

- 기간별 손익.
- 종목별 손익.
- 실현손익/평가손익 분리 화면 강화.
- 수수료/세금 정책 table화.
- 결제 예정/결제 완료 분리.

## 변경 순서

1. 계산 기준을 문서에 먼저 쓴다.
2. 체결 시 기록해야 하는 값이면 `ExecutionCostCalculator`와 batch 체결 service를 수정한다.
3. 조회 집계만 바뀌면 `StockExecutionRepository`와 `TradingService`를 수정한다.
4. snapshot 기준이 바뀌면 `PortfolioSettlementService`를 수정한다.
5. front 지표와 타입을 갱신한다.
6. 기존 snapshot과 신규 계산 기준의 마이그레이션 필요 여부를 판단한다.

## 검증

- `ExecutionCostAccountingTest`
- `TradingServiceTest`
- `PortfolioSettlementService` 관련 test.
- front: `npm run verify:contract`, `npm run lint`, `npm run build`
