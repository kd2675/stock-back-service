# Market Session Status

이 문서는 종목별 장 상태와 거래정지 구현을 설명한다.

## 현재 역할

장 상태는 신규 주문 접수, batch 체결, 자동장 주문 생성을 막거나 허용하는 최소 운영 상태다. 현재 단계에서는 시장 전체 시간표가 아니라 종목별 상태만 다룬다.

상태 값:

- `OPEN`: 신규 주문 접수, 체결, 자동장 주문 생성 가능
- `CLOSED`: 신규 주문 접수, 체결, 자동장 주문 생성 불가
- `HALTED`: 신규 주문 접수, 체결, 자동장 주문 생성 불가

현재 정책:

- 조회는 항상 가능하다.
- 주문 취소와 부분 취소는 장 상태와 무관하게 가능하다.
- 신규 주문은 `enabled=true`와 `market_status=OPEN`일 때만 가능하다.
- batch 체결과 자동장 생성도 `market_status=OPEN`만 대상으로 삼는다.

## 관련 코드

백엔드:

- `stock-back-service/src/main/java/stock/back/service/database/entity/MarketSessionStatus.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockVirtualMarketConfig.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockOrderBookMarketConfig.java`
- `stock-back-service/src/main/java/stock/back/service/trading/biz/TradingService.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-back-service/src/main/java/stock/back/service/market/act/MarketController.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/MarketStatusUpdateRequest.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/SymbolMarketConfigResponse.java`

배치:

- `stock-batch-service/src/main/java/stock/batch/service/execution/biz/OrderExecutionService.java`
- `stock-batch-service/src/main/java/stock/batch/service/execution/biz/InternalOrderBookExecutionService.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`

프론트:

- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`
- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/types/stock.ts`

DDL:

- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`

## 데이터 계약

`stock_virtual_market_config`

- `symbol`
- `enabled`
- `market_status`
- `updated_at`

`stock_order_book_market_config`

- `symbol`
- `enabled`
- `market_status`
- `updated_at`

두 config 모두 `market_status` 기본값은 `OPEN`이다. 기존 row가 있는 DB에 alter를 적용하면 기존 종목은 기본적으로 정규장 상태가 된다.

## API 계약

조회:

- `GET /api/stock/v1/markets/virtual-price/status`
- `GET /api/stock/v1/markets/order-book/status`

응답의 `configs[]` 항목은 다음 필드를 가진다.

- `symbol`
- `enabled`
- `marketStatus`

관리자 변경:

- `PATCH /api/stock/v1/markets/{marketType}/symbols/{symbol}/status`
- ADMIN role 필요

요청:

```json
{
  "enabled": true,
  "marketStatus": "HALTED"
}
```

`enabled`, `marketStatus` 중 하나 이상이 있어야 한다.

## 주문 접수 플로우

1. `TradingService.placeOrder()`가 symbol과 market type을 검증한다.
2. `validateMarketOpen()`이 해당 market config를 조회한다.
3. config가 없거나 `enabled=false`면 주문을 거부한다.
4. `market_status`가 `OPEN`이 아니면 주문을 거부한다.
5. 통과하면 기존 예약금/예약수량 검증과 주문 저장을 진행한다.

이 검증은 신규 주문에만 적용된다. 취소와 정정은 별도 플로우다.

## 배치 체결 플로우

현재가 체결:

- `OrderExecutionService`가 `stock_virtual_market_config`와 join한다.
- `enabled=true`와 `market_status='OPEN'`인 주문만 읽는다.

주문장 체결:

- `InternalOrderBookExecutionService`가 `stock_order_book_market_config`와 join한다.
- `enabled=true`와 `market_status='OPEN'`인 주문만 매칭한다.

자동장:

- `AutoMarketService`가 `stock_order_book_market_config`를 함께 확인한다.
- 장이 닫힌 종목에는 자동 주문을 만들지 않는다.

## 프론트 동작

수요/공급 화면:

- 선택 종목의 장 상태를 표시한다.
- `OPEN`이 아니면 주문 버튼을 비활성화하고 신규 주문 요청을 막는다.

관리자 화면:

- 주문장 종목 목록에서 `OPEN`, `CLOSED`, `HALTED`를 변경한다.
- 변경 후 시장 상태와 종목 목록을 다시 조회한다.

## 다음에 바꿀 때 순서

시장 전체 장 운영을 추가할 때:

1. 종목별 config와 별도 시장 전체 schedule을 분리할지 결정한다.
2. 장전/정규장/장마감/휴장 상태 enum을 늘린다.
3. 신규 주문 접수 허용 상태와 체결 허용 상태를 분리한다.
4. batch scheduler가 시간표에 따라 상태를 전환하도록 만든다.
5. 동시호가 체결 엔진을 연속매매 체결 엔진과 분리한다.

거래정지를 고도화할 때:

1. 거래정지 사유, 시작시각, 종료예정시각을 별도 테이블에 남긴다.
2. 열린 주문을 유지할지 자동 취소할지 정책을 정한다.
3. 관리자 화면에 정지 사유와 해제 기록을 보여준다.
4. 주문/체결/가격 tick 조회에서 정지 상태를 같이 보여준다.
