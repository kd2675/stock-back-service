# Market Data, Redis, And SSE

## 현재 구현

시세 갱신은 batch가 수행하고, stock-back은 최신가 조회와 SSE 전달을 담당한다. Redis는 원장이 아니라 캐시와 이벤트 전달 채널이다.

- batch writer: `MarketDataRefreshService`
- provider interface: `MarketPriceProvider`
- local/mock provider: `MockMarketPriceProvider`
- KIS provider: `KisMarketPriceProvider`
- back cache reader: `StockPriceCacheService`
- back SSE bridge: `PriceStreamService`
- front stream consumer: `stock-front-service/app/page.tsx`

## 코드 흐름

1. `MarketDataRefreshService.refreshWatchedPrices`가 watch 대상 symbol을 모은다.
2. watch 대상은 enabled `stock_instrument`, 미체결 주문, 보유종목이다.
3. provider가 `MarketPriceQuote`를 반환한다.
4. quote는 symbol, positive price, priceTime, provider를 validation한다.
5. `stock_price` upsert와 `stock_price_tick` insert를 수행한다.
6. Redis key `stock:price:{symbol}`에 최신가를 저장한다.
7. Redis channel `stock.price.{symbol}`로 JSON event를 publish한다.
8. `PriceStreamService`가 Redis message를 SSE client로 전달한다.
9. front 홈 화면은 `EventSource(getPriceStreamUrl())`로 가격 event를 받아 가격 배열을 merge한다.

## 현재 불변식

- Redis publish 실패는 DB 갱신 실패로 보지 않는다.
- provider 오류는 해당 symbol만 skip한다.
- JSON은 `ObjectMapper`로 직렬화한다.
- front는 stream event로 가격 표시를 갱신하지만, 원장은 back/batch가 가진다.

## 앞으로 구현할 후보

- 주문장 종목도 refresh 대상에 넣을지 명확화.
- provider별 rate limit과 retry/backoff.
- 장 상태에 따른 시세 refresh 정책.
- 가격 event schema version.

## 변경 순서

1. provider 계약 변경은 `MarketPriceQuote`부터 수정한다.
2. refresh 대상 SQL을 수정한다.
3. `upsertPrice`, `insertPriceTick`, `publishPrice` 중 어느 단계가 바뀌는지 분리한다.
4. back SSE event 파싱/전달이 바뀌면 `PriceStreamEvent`와 front parser를 같이 수정한다.
5. Redis 없이도 DB 테스트가 통과하도록 service test를 구성한다.

## 검증

- `MarketDataRefreshService` 관련 batch test.
- `TradingServicePriceCacheTest`.
- front: `npm run verify:contract`, `npm run lint`, `npm run build`.
