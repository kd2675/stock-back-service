# Stock Development Specs

이 디렉터리는 stock 모의투자 기능을 기능/역할 단위로 이어서 개발하기 위한 세부 개발 문서다.

기존 `feature-handbook`은 큰 기능별 핸드오프 문서이고, 이 `development-specs`는 실제 코드를 바꿀 때 어떤 파일을 어떤 순서로 확인해야 하는지 더 구체적으로 적는다.

## 읽는 순서

1. 서비스 책임과 배치 경계가 헷갈리면 `01-service-boundaries.md`.
2. API, 인증, principal, internal job 경계는 `02-api-auth-boundaries.md`.
3. 테이블/엔티티/DDL을 바꾸면 `03-ledger-ddl-contract.md`.
4. 특정가격 자동주문체결은 `04-virtual-price-market.md`.
5. 수요와 공급 주문 체결은 `05-order-book-market.md`.
6. 자동 참여자와 자동장은 `06-auto-market.md`.
7. 기업 이벤트는 `07-corporate-actions.md`.
8. 정산, 손익, 랭킹은 `08-portfolio-profit-ranking.md`.
9. Redis, SSE, 가격 갱신은 `09-market-data-redis-sse.md`.
10. 프론트 화면/타입/API client는 `10-frontend-workspaces.md`.
11. 설정, profile, smoke, 초기 구동은 `11-runtime-config-and-smoke.md`.
12. 작업 순서와 검증은 `12-change-order-and-verification.md`.
13. 종목 평가 보고서 이벤트와 자동장 신호는 `13-instrument-report-events.md`.

## 현재 고정된 큰 판단

- `stock-back-service`는 사용자/관리자 API와 도메인 검증을 담당한다.
- `stock-batch-service`는 시세 갱신, 체결, 자동장, 기업 이벤트, 정산처럼 시간이 지나며 원장을 바꾸는 일을 담당한다.
- `stock-front-service`는 화면 상태와 입력 검증을 담당하지만 잔고, 체결, 보유수량을 브라우저에서 확정하지 않는다.
- `VIRTUAL_PRICE`와 `ORDER_BOOK`은 같은 주문 테이블을 쓰지만 `market_type`으로 분리한다.
- 주문장 종목은 admin이 생성하는 `stock_order_book_instrument` 기준이며, 기존 현재가 시장 종목과 공유하지 않는다.
- 현재 초기 범위의 주문 타입은 `LIMIT`, `MARKET`이다.
- 현재 초기 범위의 기업 이벤트는 `INITIAL_ISSUE`, `PAID_IN_CAPITAL_INCREASE`, `ADDITIONAL_ISSUE`, `STOCK_SPLIT`, `CASH_DIVIDEND`, `BONUS_ISSUE`, `STOCK_DIVIDEND`이다.

## 문서 갱신 규칙

- 새 API를 추가하면 `02-api-auth-boundaries.md`, `10-frontend-workspaces.md`, `12-change-order-and-verification.md`를 같이 본다.
- 새 원장 컬럼을 추가하면 `03-ledger-ddl-contract.md`와 back/batch/H2 DDL을 같이 본다.
- 새 배치 job을 추가하면 `01-service-boundaries.md`, `11-runtime-config-and-smoke.md`, `12-change-order-and-verification.md`를 같이 본다.
- 기업 이벤트를 추가하면 먼저 `../15-corporate-action-scope.md`에서 초기 범위인지 확인한다.
- 종목 평가 보고서를 바꾸면 `13-instrument-report-events.md`, `06-auto-market.md`, `10-frontend-workspaces.md`를 같이 본다.
