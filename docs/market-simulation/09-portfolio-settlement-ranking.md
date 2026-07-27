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
- 총 보유량: `sum(stock_holding.quantity)`
- 예약 매도 보유량: `sum(stock_holding.reserved_quantity)`
- 가용 보유량: 총 보유량 - 예약 매도 보유량
- 보유 포지션 수: 수량이 양수인 계좌별 종목 row 수
- 누적 손익: `총자산 - 외부 순입금액`이며 분모와 관계없이 항상 계산한다.
- 계좌 순입금 대비 수익률: `누적 손익 / 외부 순입금액 * 100`
- 외부 순입금액이 0원 이하이면 수익률은 `0%`가 아니라 산출 불가(`NULL`)다.
- 미체결 주문 수
- 보유 종목 목록

순입금액은 `stock_account_cash_flow`의 입금 합계에서 회수 합계를 뺀 값이다.

자동 참여자 그룹의 대표 수익률은 계좌 수익률 단순 평균이 아니다. 그룹의 총자산,
외부 순입금, 손익을 먼저 합산한 뒤 `합산 손익 / 합산 외부 순입금 * 100`으로 계산한다.
일반적인 계좌의 분포는 별도 `계좌 중앙 수익률`과 `수익 계좌 비율`로 제공한다.

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
2. 계좌별 보유 row를 한 번 집계해 평가금액, 총 보유량, 예약 매도 보유량, 보유 포지션 수를 계산한다.
3. 예약 매수·유상증자 청약 현금을 계산한다.
4. 총자산·외부 순입금·누적 손익을 계산하고, 순입금이 양수일 때만 수익률을 계산한다.
5. 오늘 날짜의 `portfolio_snapshot`에 금액·보유량·수익률 상태를 함께 upsert한다.

`portfolio_snapshot.return_rate_status`는 `DEFINED`,
`UNDEFINED_ZERO_CONTRIBUTION`, `UNDEFINED_NEGATIVE_CONTRIBUTION`,
`LEGACY_UNVERIFIED` 중 하나다. 최근 cycle-linked 행은 불변
`stock_close_account_snapshot.external_net_cash_flow`로 정확히 보정한다. 증명 가능한 입력이 없는
구형 행은 반올림된 과거 수익률로 원금을 역산하지 않고 `LEGACY_UNVERIFIED`로 남긴다.

과거 snapshot의 보유량 컬럼은 NULL일 수 있다. 관리자 일별 합계는 같은 날짜의 모든 계좌 row에 보유량 지표가 있을 때만 합산하며, 일부만 존재하는 날짜는 0이나 부분합으로 표시하지 않는다.
정산 reader는 계좌의 역할별 정산 정책을 기준으로 LP·발행 인수·시스템 보관 계좌를 생성 단계에서 제외한다. 반면 참여자 계좌가 나중에 종료됐다는 이유로 과거 snapshot을 소급 제거하지는 않는다.

## 랭킹

`MarketService.getRankings()`는 가장 최근 snapshot date를 찾고, 해당 날짜에서 수익률 상태가
`DEFINED`인 계좌만 상위 20명을 수익률 기준으로 반환한다.

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
