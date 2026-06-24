# Portfolio Settlement And Ranking

이 문서는 포트폴리오 조회, 일별 정산, 랭킹 구조를 설명한다.

## 현재 역할

사용자 화면의 실시간 포트폴리오는 `stock-back-service`가 계좌/보유/현재가를 조합해 계산한다. 일별 정산과 랭킹 기준 스냅샷은 `stock-batch-service`가 만든다.

관련 코드:

- `stock-back-service/src/main/java/stock/back/service/trading/biz/TradingService.java`
- `stock-back-service/src/main/java/stock/back/service/trading/act/TradingController.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-batch-service/src/main/java/stock/batch/service/marketclose/biz/MarketCloseRolloverService.java`
- `stock-batch-service/src/main/java/stock/batch/service/settlement/biz/PortfolioSettlementService.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/PortfolioSettlementScheduler.java`

## 포트폴리오 조회

`TradingService.getPortfolio()`는 다음을 계산한다.

- 현금: `stock_account.cash_balance`
- 보유 평가금액: `stock_holding.quantity * current_price`
- 예약 매수 현금: 미체결/부분체결 BUY 주문의 `reserved_cash`
- 총자산: 현금 + 예약 매수 현금 + 보유 평가금액
- 수익률: `(총자산 - 순입금액) / 순입금액 * 100`
- 미체결 주문 수
- 보유 종목 목록

순입금액은 `stock_account_cash_flow`의 입금 합계에서 회수 합계를 뺀 값이다.

보유 평가 가격은 Redis 캐시를 먼저 보고, 없으면 DB `stock_price`를 사용한다. 가격이 없으면 평균단가를 fallback으로 사용한다.

## 수수료, 세금, 실현손익

체결 비용과 실현손익은 `stock_execution`에 체결 단위로 기록한다.

컬럼:

- `gross_amount`: 체결가 * 체결수량
- `fee_amount`: 체결 수수료
- `tax_amount`: 매도 거래세
- `net_amount`: 매수는 실제 원가, 매도는 실제 입금액
- `realized_profit`: 매도 체결 시점의 실현손익

batch 설정:

- `stock.batch.execution.fee-rate`: 매수/매도 공통 수수료율. 기본값 `0.0000`
- `stock.batch.execution.sell-tax-rate`: 매도 거래세율. 기본값 `0.0000`

계산 원칙:

- 매수 체결은 `gross_amount + fee_amount`를 현금에서 차감한다.
- 매수 체결의 평균단가는 수수료를 포함한 원가 기준으로 계산한다.
- 매도 체결은 `gross_amount - fee_amount - tax_amount`를 현금에 더한다.
- 매도 체결의 실현손익은 `net_amount - 평균단가 * 수량`이다.
- 현재 단계에서는 체결 즉시 계좌와 보유수량에 반영한다.

## 손익 요약 조회

`GET /api/stock/v1/portfolio/me/profit-summary`는 사용자의 체결 원장과 현재 보유 평가손익을 조합해 반환한다.

응답 항목:

- `realizedProfit`: 매도 체결의 실현손익 합계
- `unrealizedProfit`: 현재 보유수량 기준 평가손익 합계
- `totalProfit`: `realizedProfit + unrealizedProfit`
- `totalFeeAmount`: 체결 수수료 합계
- `totalTaxAmount`: 매도 거래세 합계
- `buyGrossAmount`, `sellGrossAmount`: 매수/매도 총 체결대금
- `buyNetAmount`, `sellNetAmount`: 매수 실제 원가와 매도 실제 입금액
- `netCashFlow`: `sellNetAmount - buyNetAmount`
- `executionCount`: 체결 건수

이 API는 기간 조건 없이 누적 기준만 제공한다. 초기 화면에서 “내가 얼마 벌었는지”를 보여주기 위한 최소 계약이다.

## 일별 정산

장마감 스케줄은 먼저 `MarketCloseRolloverService.rolloverClosingPrices()`를 실행한다. 이 job은 `stock_price.current_price`를 `previous_close`로 복사해 다음 장 가격제한폭 기준가를 확정한다. `current_price`, `provider`, `price_time`은 바꾸지 않으며, 이미 같은 값인 row는 건너뛰므로 같은 날 다시 실행해도 추가 변경이 없다.

`PortfolioSettlementService.settleToday()`는 모든 계좌를 순회한다.

1. 각 계좌의 현금과 순입금액을 읽는다.
2. 보유 평가금액을 계산한다.
3. 예약 매수 현금을 계산한다.
4. 총자산과 수익률을 계산한다.
5. 오늘 날짜의 `portfolio_snapshot`을 upsert한다.

## 랭킹

`MarketService.getRankings()`는 가장 최근 snapshot date를 찾고, 해당 날짜의 상위 20명을 수익률 기준으로 반환한다.

## 현재 한계

- 손익 요약은 누적 기준만 있고 기간별/종목별 집계 API는 아직 없다.
- 수수료/세금 rate는 운영 설정값이며, 실제 증권사/시장별 요율 테이블은 아직 없다.
- 결제 예정/결제 완료가 없다.
- 배당/신주 배정 내역은 최근 50건 조회만 있고, 기간별 조회는 아직 없다.
- 랭킹은 snapshot 기준이라 실시간 포트폴리오와 시점 차이가 있다.

## 다음에 바꿀 때 순서

1. 시장/종목/사용자 등급별 수수료율 테이블을 둘지 결정한다.
2. `stock_execution` 기준 기간별/종목별 손익 집계 API를 추가한다.
3. `stock_settlement`를 둘지 결정한다.
4. 배당/기업 이벤트 entitlement 기간별 조회 API를 추가한다.
5. 랭킹을 실시간으로 할지, 일별 snapshot으로 유지할지 결정한다.
