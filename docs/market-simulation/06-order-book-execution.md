# Order Book Execution

이 문서는 수요와 공급 주문장, 즉 `ORDER_BOOK` 시장의 체결 구조를 설명한다.

## 현재 역할

`ORDER_BOOK` 주문은 내부 주문장 batch가 매수/매도 주문을 가격 우선, 시간 우선으로 매칭한다. 사용자는 `/supply-demand` 화면에서 이 시장으로 주문하고, 같은 화면에서 주문 상태와 최근 체결을 확인한다.

관련 코드:

- `stock-batch-service/src/main/java/stock/batch/service/execution/biz/InternalOrderBookExecutionService.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/OrderBookExecutionScheduler.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-front-service/app/supply-demand/page.tsx`

## 체결 대상

- `stock_order.market_type = 'ORDER_BOOK'`
- `status in ('PENDING', 'PARTIALLY_FILLED')`
- `order_type in ('LIMIT', 'MARKET')`
- `stock_order_book_market_config.enabled = true`
- `stock_order_book_market_config.market_status = 'OPEN'`
- `stock_order_book_instrument.enabled = true`

## 매칭 규칙

매수 후보:

- 시장가를 우선한다.
- 지정가는 높은 가격 우선이다.
- 같은 조건이면 먼저 만든 주문 우선이다.

매도 후보:

- 매수 주문이 시장가면 매도도 지정가만 허용한다. 양쪽 시장가는 기준 가격이 없어 체결하지 않는다.
- 매수 지정가면 매도 지정가가 매수가 이하이거나 매도 시장가인 경우 후보가 된다.
- 매도는 낮은 가격 우선이다.
- 같은 조건이면 먼저 만든 주문 우선이다.
- 기본 후보는 방향별 가격·시간 우선 상위 N건으로 제한한다. 상위가 동일 계좌 주문으로 채워지면 최우선 다른 계좌 주문 한 건을 추가하고, 시장가가 상위 N건을 채우면 최우선 지정가 한 건을 추가해 bounded 탐색 때문에 가능한 체결을 놓치지 않는다.

자전거래 방지:

- 후보 SQL에서 매수·매도 `account_id`가 같은 조합을 제외한다.
- 정확한 주문 PK를 잠근 뒤에도 계좌가 같은지 다시 검증한다.

체결가:

- 시장가와 지정가 조합은 가격을 가진 지정가를 사용한다.
- 양쪽이 지정가면 먼저 접수된 주문의 가격을 사용한다.
- 접수시각이 같으면 더 작은 주문 ID를 먼저 접수된 순서로 본다.
- 가격 정정 또는 수량 증가는 주문 우선순위 시각을 정정 시각으로 재설정하고, 같은 가격 수량 감소는 기존 순위를 유지한다.

## 체결 후 처리

매도자:

- `stock_holding.quantity` 차감
- `reserved_quantity` 차감
- 매도 대금 현금 증가
- `stock_execution` 기록
- 주문 상태/평단 업데이트

매수자:

- 예약금에서 실제 금액만큼 사용
- 차액 반환
- `stock_holding` 증가 또는 신규 생성
- `stock_execution` 기록
- 주문 상태/평단 업데이트

가격:

- 마지막 체결가를 `stock_price.current_price`에 반영한다.
- `stock_price_tick`에 이력을 남긴다.
- Redis latest price와 `stock.price.{symbol}` event를 best effort로 발행한다.

## 현재 한계

- 주문 접수 단계에서 호가 단위와 가격제한폭은 검증한다.
- 주문 접수와 자동 가격은 market·지정가 가격대별 동적 호가단위를 사용한다. 거래소·시장별 세부 차이는 아직 프로젝트 공통 규칙으로 단순화되어 있다.
- 종목별 `CLOSED`, `HALTED` 상태에서는 batch 매칭 대상에서 제외된다.
- 같은 가격대 잔량 집계는 조회에만 있고, 별도 order book snapshot table은 없다.
- 시장가 주문의 남은 잔량 정책이 실제 시장의 IOC/FOK와 다르다.
- `/supply-demand` 화면은 주문 상태 확인과 전체 취소만 지원하며, 정정/부분취소는 아직 홈 주문 패널 수준으로만 구현되어 있다.

## 화면 조회 계약

`/supply-demand` 화면은 사용자 활동을 조회할 때 서버 필터를 사용한다.

- 주문: `GET /api/stock/v1/orders?marketType=ORDER_BOOK`
- 체결: `GET /api/stock/v1/executions?source=INTERNAL_ORDER_BOOK`

이 필터는 단순 편의가 아니라 데이터 정확성 계약이다. 주문/체결 조회는 최근 50건 제한이 있으므로 전체 주문을 받은 뒤 프론트에서 `ORDER_BOOK`만 필터링하면, `VIRTUAL_PRICE` 주문이 많을 때 주문장 활동이 응답에서 빠질 수 있다.

## 다음에 바꿀 때 순서

1. 장전/장마감 동시호가가 필요하면 연속매매와 별도 체결 service를 만든다.
2. 장 상태별 주문 접수/취소/체결 허용 정책을 더 세분화한다.
3. 거래소·시장별 호가단위를 더 현실화할 때 back·batch·front의 동일 정책과 계약 테스트를 함께 바꾼다.
4. IOC/FOK 같은 시간 조건을 `stock_order`에 추가한다.
5. order book depth snapshot이 필요하면 별도 materialized table을 둔다.
