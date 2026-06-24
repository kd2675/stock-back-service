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

자전거래 방지:

- `user_key <> buyOrder.userKey()` 조건으로 같은 사용자끼리 체결하지 않는다.

체결가:

- 매도 주문에 지정가가 있으면 매도 지정가
- 아니면 매수 지정가

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
- 가격대별 tick ladder는 아직 없고, 주문장 종목별 단일 tick size를 사용한다.
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
3. 가격대별 tick ladder가 필요하면 주문 검증 정책을 단일 tick에서 ladder로 바꾼다.
4. IOC/FOK 같은 시간 조건을 `stock_order`에 추가한다.
5. order book depth snapshot이 필요하면 별도 materialized table을 둔다.
