# Service Boundaries

## 현재 구현

stock 프로젝트는 세 서버가 역할을 나눈다.

- `stock-back-service`: 사용자/관리자 HTTP API, 인증 principal 검증, 주문 접수 검증, 조회 DTO 조립.
- `stock-batch-service`: 시세 갱신, 현재가 체결, 주문장 매칭, 자동장 주문 생성, 기업 이벤트 적용, 포트폴리오 정산.
- `stock-front-service`: 현재가 시장 화면, 수요/공급 주문장 화면, admin 설정 화면, auth token 전달.

## 관련 코드

`stock-back-service`

- `stock/back/service/trading/act/AccountController.java`: 내 계좌 상태 조회, 명시 개설, 기존 계좌 조회.
- `stock/back/service/trading/act/TradingController.java`: 주문, 체결, 보유, 포트폴리오 API.
- `stock/back/service/market/act/MarketController.java`: 시세, 종목, 주문장, 기업 이벤트, market status API.
- `stock/back/service/common/act/StockSystemController.java`: back 서버 상태 조회.

`stock-batch-service`

- `stock/batch/service/common/act/StockBatchJobController.java`: internal job API.
- `stock/batch/service/common/biz/StockBatchJobService.java`: 모든 batch job 진입점.
- `stock/batch/service/scheduler/*.java`: job별 scheduler.

`stock-front-service`

- `app/page.tsx`: `VIRTUAL_PRICE` 현재가 기반 워크스페이스.
- `app/supply-demand/page.tsx`: `ORDER_BOOK` 수요/공급 워크스페이스.
- `app/supply-demand/admin/page.tsx`: admin 종목/기업 이벤트/장 상태 화면.
- `app/components/MarketModeTabs.tsx`: 최상단 시장 모드 분리 탭.

## 현재 불변식

- back 서버에서 체결 scheduler를 돌리지 않는다.
- batch 서버 job은 수동 internal API와 scheduler가 같은 service method를 호출한다.
- front는 `marketType`과 `source` 필터를 써서 두 시장을 섞지 않는다.
- 주문장 종목 생성은 admin API로만 한다.

## 앞으로 구현할 방향

- 새 시간 기반 기능은 먼저 batch service에 둔다.
- 새 사용자 요청 API는 back service에 둔다.
- 새 화면 상태가 필요한 경우 front type과 API client를 먼저 맞춘 뒤 화면을 붙인다.
- 서버 간 호출이 필요해지면 internal API token, gateway/direct profile, 테스트 profile을 같이 정의한다.

## 바꿀 때 순서

1. 기능이 사용자 요청인지, 시간 기반 원장 변경인지 분리한다.
2. 사용자 요청이면 `stock-back-service` controller/service/DTO부터 바꾼다.
3. 시간 기반 원장 변경이면 `stock-batch-service` job service와 scheduler부터 바꾼다.
4. 원장 컬럼이 필요하면 back DDL, batch DDL, H2 DDL을 같이 바꾼다.
5. front API type과 client를 마지막이 아니라 back 계약과 동시에 맞춘다.

## 검증

- `node scripts/verify-stock-initial-scope.mjs`
- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `cd stock-front-service && npm run verify:contract`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`
