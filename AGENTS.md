<!-- Parent: ../AGENTS.md -->
<!-- Updated: 2026-06-17 -->

# stock-back-service

## Purpose

주식 모의투자 서비스의 사용자-facing API 서버입니다. 주문 접수, 가상 계좌, 보유 종목, 체결 내역, 수익률, 랭킹 API를 담당하는 백엔드 진입점입니다.

## Key Paths

- `src/main/java/stock/back/service`
- `src/main/resources/application*.yml`
- `src/test/java/stock/back/service`

## API Surface

- `/api/stock/v1/system/status`
- `/api/stock/v1/markets/**`
- `/api/stock/v1/markets/prices/{symbol}/ticks`
- `/api/stock/v1/markets/order-books/{symbol}`
- `/api/stock/v1/users/me`
- `/api/stock/v1/accounts/me`
- `/api/stock/v1/portfolio/me`
- `/api/stock/v1/portfolio/me/snapshots`
- `/api/stock/v1/orders`
- `/api/stock/v1/executions`

## Run / Check

```bash
./gradlew :stock-back-service:bootRun
./gradlew :stock-back-service:compileJava
./gradlew :stock-back-service:test
```

## Operational Notes

- 포트: `local/dev 20480`, `prod 10480`, `test 30480`
- 공통 응답은 `web-common-core`의 `ResponseDataDTO`, `ResponseErrorDTO`를 사용합니다.
- 인증/사용자 식별은 Gateway가 주입한 헤더와 `auth-common-core`를 기준으로 붙입니다.
- 사용자 프로필은 `auth-common-core`의 `UserServiceClient`를 사용하되, Feign 호출에는 현재 `X-User-*` 헤더를 relay합니다.
- 주문, 체결, 잔고는 DB 원장에 저장하고 최신 시세 조회는 Redis `stock:price:{symbol}` 캐시 후 DB fallback 순서로 처리합니다.
- 주문장 API는 미체결/부분체결 LIMIT 주문만 가격대별로 집계합니다. 시장가 주문은 가격 레벨이 없으므로 호가에 넣지 않습니다.
- `portfolio_snapshot`은 batch 정산 결과의 원장이며 사용자 화면에서는 최근 정산 기록/랭킹 근거로만 읽습니다.
- DDL은 `src/main/resources/db/ddl/stock_all.sql`입니다.

## For AI Agents

- 초기 서버 구성 단계이므로 실제 주문 체결 규칙을 컨트롤러에 직접 넣지 않습니다.
- 주문 접수 API와 체결 엔진 책임을 섞지 말고, 체결 판단은 `stock-batch-service` 또는 이후 분리될 trade engine 쪽으로 둡니다.
- 서비스별 세부 규칙은 이 문서와 README에 두고 루트 문서에는 긴 도메인 설명을 중복하지 않습니다.
- `stock_order`와 `stock_execution`은 나중에 내부 주문장 매칭으로 바뀔 수 있으므로 현재가 기반 체결 가정만으로 컬럼을 좁히지 않습니다.
