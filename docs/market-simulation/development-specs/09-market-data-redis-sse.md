# Market Data, Redis, And SSE

## 현재 구현

가격은 DB가 기준이고 Redis는 최신가 캐시와 live event 전달 보조 채널이다. batch가 가격을 갱신하면 `stock_price`, `stock_price_tick`, Redis value, Redis pub/sub channel을 갱신한다. back은 SSE 연결에서 Redis price event를 프론트로 전달한다.

## 관련 코드

back:

- `PriceStreamService`
- `PriceStreamEvent`
- `StockPriceCacheService`
- `CachedStockPrice`
- `MarketController.streamPrices`
- `MarketService.getPrices`
- `MarketService.getPriceTicks`

batch:

- `MarketDataRefreshService`
- `KisMarketPriceProvider`
- `MockMarketPriceProvider`
- `InternalOrderBookExecutionService.updateLastTradePrice`

front:

- `stock-front-service/app/page.tsx`
- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/types/stock.ts`

## 현재 플로우

1. batch `MarketDataRefreshService`가 watched symbol을 찾는다.
2. provider가 가격 quote를 반환한다.
3. batch가 `stock_price`를 upsert한다.
4. batch가 `stock_price_tick`을 insert한다.
5. batch가 Redis `stock:price:{symbol}`에 최신가를 TTL과 함께 저장한다.
6. batch가 Redis channel `stock.price.{symbol}`에 JSON event를 발행한다.
7. back `PriceStreamService`가 이를 구독해 SSE client로 전달한다.
8. front는 EventSource로 수신한 가격을 화면 상태에 merge한다.

주문장 체결 가격도 `InternalOrderBookExecutionService`가 같은 방식으로 DB 가격과 Redis event를 갱신한다.

## 현재 불변식

- DB 가격이 authoritative source다.
- Redis 실패는 가격 저장 실패로 취급하지 않는다.
- Redis event JSON은 `ObjectMapper`로 만든다.
- front가 SSE를 받지 못해도 `GET /prices`와 `GET /prices/{symbol}/ticks`로 복구 가능해야 한다.

## 앞으로 구현할 후보

- Redis Stream 또는 Kafka 기반 durable event.
- symbol별 구독 최적화.
- 외부 시세 provider 장애 시 fallback 정책.
- 가격 tick retention 정책.

## 바꿀 때 순서

1. DB 가격 계약을 먼저 확인한다.
2. provider quote 계약을 바꾼다.
3. batch publish payload를 바꾼다.
4. back SSE payload와 front `PriceStreamEvent` 타입을 같이 바꾼다.
5. Redis 장애 시에도 DB 갱신이 유지되는지 테스트한다.

## 검증

- `./gradlew :stock-batch-service:test --tests '*MarketDataRefreshServiceTest*'`
- `./gradlew :stock-back-service:test --tests '*TradingServicePriceCacheTest*'`
- `cd stock-front-service && npm run verify:contract`
