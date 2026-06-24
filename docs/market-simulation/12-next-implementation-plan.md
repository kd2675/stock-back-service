# Next Implementation Plan

이 문서는 현재 코드 기준으로 다음에 바꿀 순서를 정리한다. 초기 진행 단계이므로 “있으면 좋은 기능”보다 원장 안정성과 실제 주식시장에 가까운 필수 기능을 우선한다.

## 구현됨: 주문 정정과 부분 취소

이유:

- 현재 주문 접수/전체 취소/체결 원장이 이미 있다.
- 사용자 입장에서 미체결 주문을 고치는 기능은 기본 기능이다.
- 구현 범위가 장 상태/동시호가보다 작다.

바뀐 코드:

- `TradingController`
- `TradingService`
- `StockOrder`
- `StockOrderRepository`
- `OrderAmendRequest`
- `OrderCancelRequest`
- `stock-front-service/app/lib/stock.ts`

현재 결정:

- 정정/부분취소 이력 테이블은 아직 두지 않고, `stock_order` 현재 상태를 갱신한다.
- 부분 취소 후 남은 수량이 있으면 주문을 유지하고, 남은 수량 전체를 취소하면 `CANCELLED`로 둔다.
- 주문 정정은 `LIMIT` 주문만 허용한다.

남은 보강:

- 수요/공급 전용 화면에 별도 주문 내역 패널이 필요하면 홈 주문 목록과 같은 원장 API를 재사용한다.
- 정정/취소 이벤트 이력 테이블 추가

검증:

- 매수 정정 시 예약금 차액 반영
- 매도 정정 시 예약수량 차액 반영
- 부분체결 주문의 정정 제한
- 타인 주문 정정 불가

## 구현됨: 호가 단위와 가격제한폭

이유:

- 주문 접수 단계에서 비현실적인 가격을 막는다.
- 주문장 매칭 안정성에 직접 연결된다.

바뀐 코드:

- `TradingService`
- `StockOrderBookInstrument`
- `OrderBookInstrumentRequest`
- `OrderBookInstrumentResponse`
- MySQL/H2 DDL
- `stock-front-service/app/page.tsx`
- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`

현재 결정:

- 초기 단계는 가격대별 tick ladder가 아니라 종목별 단일 tick size를 사용한다.
- `VIRTUAL_PRICE`는 기본 1원 tick, `stock_price.previous_close` 기준 ±30%를 사용한다.
- `ORDER_BOOK`은 `stock_order_book_instrument.tick_size`, `price_limit_rate`를 사용한다.
- 가격제한폭 기준가는 `stock_price.previous_close`이며, 주문장 종목에 아직 가격 row가 없으면 `initial_price`로 fallback한다.

검증:

- tick size에 맞지 않는 지정가 거부
- 상한가 초과/하한가 미만 주문 거부
- 시장가 주문은 가격 검증 대상에서 제외

## 구현됨: 장 상태와 거래정지

이유:

- 동시호가, 가격제한폭, 기업 이벤트 적용 대기, 거래정지를 자연스럽게 연결하려면 시장 상태가 필요하다.

바뀐 코드:

- `MarketSessionStatus`
- `StockVirtualMarketConfig`
- `StockOrderBookMarketConfig`
- `TradingService`
- `OrderExecutionService`
- `InternalOrderBookExecutionService`
- `AutoMarketService`
- `MarketController`
- 관리자 화면과 수요/공급 화면

현재 결정:

- 별도 시장 상태 테이블을 새로 만들지 않고, 현재 초기 단계에서는 `stock_virtual_market_config`, `stock_order_book_market_config`에 `market_status`를 둔다.
- 상태는 `OPEN`, `CLOSED`, `HALTED`다.
- 신규 주문 접수와 batch 체결, 자동장 주문 생성은 `enabled=true`와 `market_status='OPEN'`일 때만 허용한다.
- 취소와 조회는 장 상태와 무관하게 허용한다.

검증:

- 장 정지 시 신규 주문 거부
- 거래정지 시 체결 job skip
- 취소 허용 정책 테스트

## 1순위: 동시호가와 장 운영 일정

이유:

- 장 상태가 생겼으므로 다음은 시간표에 따라 장 상태를 전환하고, 장전/장마감 동시호가를 처리할 수 있다.

바꿀 코드:

- 신규 market calendar/session schedule
- `TradingService`
- `InternalOrderBookExecutionService`
- 관리자 장 운영 화면
- batch scheduler

필요한 결정:

- 장전 주문 접수를 허용할지
- 장전/장마감 동시호가 체결가 결정 규칙
- 휴장일/임시공휴일 입력 방식
- 시장 전체 상태와 종목별 상태를 분리할 시점

검증:

- 장전 주문은 접수되지만 연속매매 체결 job에서는 제외
- 장 시작 시 단일가로 체결
- 장마감 동시호가 후 종가 확정

## 구현됨: 배당/기업 이벤트 기본 조회

이미 구현:

- 유상증자
- 추가발행
- 액면분할
- 현금배당
- 무상증자
- 주식배당
- 종목별 기업 이벤트 이력 조회
- 사용자별 배당/신주 배정 내역 조회

초기 필수 기업 이벤트 범위는 `15-corporate-action-scope.md`를 기준으로 본다. 현재 단계에서는 새 이벤트를 더 늘리는 것보다 기준일, 단주, 열린 주문 정책을 더 명확히 하는 것이 우선이다.

바뀐 코드:

- `StockCorporateActionEntitlement`
- `StockCorporateActionEntitlementRepository`
- `CorporateActionResponse`
- `CorporateActionEntitlementResponse`
- `MarketService.getCorporateActions`
- `MarketService.getMyCorporateActionEntitlements`
- `MarketController`
- 홈 기업 이벤트 패널
- 관리자 이벤트 이력 테이블

남은 후보:

- 감자
- 액면병합
- 기간별/종목별 배정 내역 조회

필요한 결정:

- 단주 처리
- 현금 보상
- 배정 기준일
- 효력일
- 열린 주문 처리

바꿀 코드:

- `StockCorporateActionType`
- `StockCorporateAction`
- `CorporateActionRequest`
- `MarketService.applyCorporateAction`
- `CorporateActionService`
- DDL 2종: MySQL full, H2 test
- 관리자 화면

검증:

- 이벤트별 필수값 DB 제약
- batch 날짜별 상태 전이
- 보유수량/평균단가/가격 조정
- 미체결 주문 존재 시 정책 반영

## 구현됨: 수수료, 세금, 실현손익 원장 기록

이유:

- 실제 투자 기능으로 가려면 매도 손익과 비용이 필요하다.
- 초기 화면에서 사용자가 누적 실현손익과 평가손익을 바로 확인해야 한다.

바뀐 코드:

- `StockExecution`
- `StockExecutionRepository.summarizeProfitByUserKey`
- `OrderExecutionService`
- `InternalOrderBookExecutionService`
- `ExecutionCostCalculator`
- `ExecutionResponse`
- `ProfitSummaryResponse`
- `TradingService.getProfitSummary`
- `TradingController.getMyProfitSummary`
- DDL 2종: MySQL full, H2 test
- 프론트 최근 체결 카드와 상단 손익 지표

현재 결정:

- 수수료율과 매도 거래세율은 batch 설정값으로 둔다.
- 매수 평균단가는 수수료 포함 원가 기준이다.
- 매도 실현손익은 `net_amount - 평균단가 * 수량`으로 기록한다.
- 손익 요약 API는 누적 기준으로만 제공한다.
- 현재 단계는 체결 즉시 계좌/보유수량에 반영한다.

남은 보강:

- 실현손익 기간별/종목별 집계 API
- 시장/종목/사용자 등급별 수수료율 테이블
- 결제 예정/결제 완료 분리

## 3순위: 결제 예정/결제 완료 분리

이유:

- 실제 주식은 체결과 결제가 분리된다.
- 초기 모의투자는 즉시 결제로 충분하지만, 정교화 단계에서는 필요하다.

필요한 신규 원장:

- `stock_settlement`
- `stock_cash_transaction`
- `stock_position_transaction`

주의:

- 수수료/세금/실현손익 원장 기록은 들어갔으므로 다음 큰 원장 변경은 결제 분리다.
