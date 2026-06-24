# Service Boundaries

## 현재 구현

stock 프로젝트는 세 서비스로 나뉜다.

- `stock-back-service`: HTTP API와 도메인 계약의 기준.
- `stock-batch-service`: scheduler와 internal job API를 통해 시간 기반 처리를 수행.
- `stock-front-service`: 사용자가 보는 현재가 시장, 주문장 시장, 관리자 화면.

## 코드 역할

### stock-back-service

- `common/config/MvcConfig.java`: principal resolver와 role filter를 MVC에 연결한다.
- `common/config/FeignHeaderRelayConfig.java`: auth-back-server 호출 시 사용자 헤더를 relay한다.
- `trading/act/TradingController.java`: 주문, 체결, 보유, 포트폴리오 API 진입점.
- `trading/biz/TradingService.java`: 주문 접수, 정정, 취소, 조회, 포트폴리오 계산. 체결 확정은 하지 않는다.
- `market/act/MarketController.java`: 시장 조회, 가격 stream, 주문장 종목 생성, 기업 이벤트 등록, 장 상태 변경 API 진입점.
- `market/biz/MarketService.java`: 시장 조회 조립, 관리자 변경 요청 validation, 기업 이벤트 원장 등록.

### stock-batch-service

- `common/act/StockBatchJobController.java`: 수동 internal job API.
- `common/biz/StockBatchJobService.java`: job별 중복 실행 방지와 orchestration.
- `scheduler/*.java`: 각 batch job의 주기 실행 트리거.
- `marketdata/biz/MarketDataRefreshService.java`: 관심 symbol 가격 refresh와 Redis publish.
- `execution/biz/OrderExecutionService.java`: `VIRTUAL_PRICE` 주문 체결.
- `execution/biz/InternalOrderBookExecutionService.java`: `ORDER_BOOK` 주문장 매칭.
- `automarket/biz/AutoMarketService.java`: 자동 참여자 계좌/보유 보정과 자동 주문 생성.
- `corporateaction/biz/CorporateActionService.java`: 기업 이벤트 날짜별 상태 전이.
- `settlement/biz/PortfolioSettlementService.java`: 일별 포트폴리오 snapshot 생성.

### stock-front-service

- `app/page.tsx`: `VIRTUAL_PRICE` 시장 화면.
- `app/supply-demand/page.tsx`: `ORDER_BOOK` 시장 화면.
- `app/supply-demand/admin/page.tsx`: 관리자 종목 생성, 장 상태 변경, 기업 이벤트 등록 화면.
- `app/lib/stock.ts`: stock-back API client.
- `app/types/stock.ts`: back DTO와 맞춰야 하는 TypeScript 계약.
- `app/components/MarketModeTabs.tsx`: 두 시장을 최상단에서 분리하는 navigation.

## 앞으로 구현할 때의 방향

- 사용자-facing 조회/명령 API는 back에 둔다.
- 주기 처리와 체결은 batch에 둔다.
- front는 상태를 만들어내지 말고 back 원장을 다시 조회한다.
- 새 도메인 계약은 back DTO와 front type을 동시에 갱신한다.

## 변경 순서

1. 기능이 사용자 API인지, batch 상태 전이인지, 화면 표시인지 먼저 분리한다.
2. 원장을 바꾸는 기능이면 back entity/DDL과 batch SQL을 먼저 같이 확인한다.
3. 사용자/관리자 권한이 있으면 `RequirePrincipalRole` 경계를 먼저 확정한다.
4. front는 API 계약이 확정된 뒤 마지막에 바꾼다.
5. 문서, 테스트, smoke script를 변경 범위에 맞게 갱신한다.

## 검증

- back 변경: `./gradlew :stock-back-service:test`
- batch 변경: `./gradlew :stock-batch-service:test`
- front 변경: `cd stock-front-service && npm run verify:contract && npm run lint && npm run build`
- 포맷/공백: `git diff --check`
