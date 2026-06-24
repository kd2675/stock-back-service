# Market Data And Price Stream

이 문서는 시세 갱신, Redis 최신가 캐시, 가격 이벤트 전달 구조를 설명한다.

## 현재 역할

`stock-batch-service`가 가격을 갱신한다. `stock-back-service`는 조회 API와 SSE 연결을 제공한다. Redis는 최신가 캐시와 live event 전달용 보조 채널이다.

## 코드 지도

Batch:

- `stock-batch-service/src/main/java/stock/batch/service/marketdata/biz/MarketDataRefreshService.java`
- `stock-batch-service/src/main/java/stock/batch/service/marketdata/provider/MarketPriceProvider.java`
- `stock-batch-service/src/main/java/stock/batch/service/marketdata/provider/MockMarketPriceProvider.java`
- `stock-batch-service/src/main/java/stock/batch/service/marketdata/provider/KisMarketPriceProvider.java`
- `stock-batch-service/src/main/java/stock/batch/service/scheduler/MarketDataRefreshScheduler.java`

Back:

- `stock-back-service/src/main/java/stock/back/service/market/cache/StockPriceCacheService.java`
- `stock-back-service/src/main/java/stock/back/service/market/stream/PriceStreamService.java`
- `stock-back-service/src/main/java/stock/back/service/market/act/MarketController.java`

Front:

- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/page.tsx`
- `stock-front-service/app/supply-demand/page.tsx`

## 현재 플로우

1. `MarketDataRefreshScheduler` 또는 internal job API가 `MarketDataRefreshService.refreshWatchedPrices()`를 호출한다.
2. 대상 종목은 세 가지에서 모은다.
   - enabled `stock_instrument`
   - 미체결/부분체결 주문의 symbol
   - 보유 중인 symbol
3. provider가 가격을 가져온다.
4. batch가 `stock_price`를 upsert한다.
5. batch가 `stock_price_tick`에 이력을 남긴다.
6. batch가 Redis `stock:price:{symbol}`에 최신가를 저장한다.
7. batch가 Redis channel `stock.price.{symbol}`로 가격 이벤트를 발행한다.
8. stock-back 조회는 Redis 캐시를 우선 보고, 없거나 이상하면 DB `stock_price`를 사용한다.
9. SSE endpoint는 프론트에 live update를 전달한다.

## 데이터 계약

`stock_price`

- `symbol`
- `current_price`
- `previous_close`
- `price_time`
- `provider`

`previous_close`는 전일 종가이자 다음 장의 가격제한폭 기준가다. 장중 체결과 시세 갱신은 `current_price`를 바꾸고, 장마감 롤오버 job이 `current_price`를 `previous_close`로 복사해 다음 장 기준가를 확정한다. 기업 이벤트 권리락/분할처럼 기준가 자체가 바뀌는 이벤트는 corporate action job이 이론가를 `current_price`와 `previous_close`에 함께 반영한다.

`stock_price_tick`

- `symbol`
- `price`
- `provider`
- `price_time`
- `created_at`

Redis:

- key: `stock:price:{symbol}`
- value: 가격 문자열
- channel: `stock.price.{symbol}`
- payload: `{ symbol, currentPrice, priceTime, provider }`

## 다음에 바꿀 때 순서

외부 시세 provider를 바꿀 때:

1. `MarketPriceProvider` 구현체를 추가하거나 기존 구현을 수정한다.
2. provider는 symbol mismatch, 0 이하 가격, 빈 provider, 빈 시간 값을 반환하지 않아야 한다.
3. `application.yml`의 provider property를 확인한다.
4. provider 단위 테스트를 추가한다.
5. `MarketDataRefreshService` 테스트에서 실패한 종목만 skip되는지 확인한다.

호가/체결 가격까지 더 실제처럼 바꿀 때:

1. OHLC/candle 테이블을 별도로 둘지 결정한다.
2. 장전/장마감 동시호가가 생기면 단일가 체결 후 `current_price` 확정 시점을 정한다.
3. 주문장 체결 가격도 `stock_price_tick`에 남기는 현재 구조를 유지한다.

## 주의점

- Redis publish 실패는 DB 가격 저장을 막으면 안 된다.
- `stock_price`가 authoritative하다.
- `stock_price_tick`은 이력용이라 조회 성능을 위해 index가 필요하다.
- 현재 구현은 모든 종목을 무조건 갱신하지 않고 watched symbol만 갱신한다.
