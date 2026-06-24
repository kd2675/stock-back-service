# Stock Feature Handbook

이 디렉터리는 stock 모의투자 기능을 항목별로 이어서 개발하기 위한 핸드오프 문서다. 각 파일은 한 기능 또는 한 역할만 다루며, 문서 하나만 읽어도 현재 구현, 관련 코드, 앞으로 바꿀 방향, 변경 순서, 검증 명령을 파악할 수 있게 작성한다.

더 구체적인 코드 변경 시작점이 필요하면 `../development-specs/00-index.md`를 본다. `feature-handbook`은 큰 기능 이해용이고, `development-specs`는 실제 수정 순서와 파일 경계를 더 자세히 적는다.

## 읽는 순서

1. 서비스 책임이 헷갈리면 `01-service-boundaries.md`를 먼저 본다.
2. 원장/DDL 변경이 있으면 `02-ledger-and-ddl.md`를 먼저 본다.
3. 주문 접수, 정정, 취소는 `03-order-entry-amend-cancel.md`를 본다.
4. 특정가격 자동주문체결은 `04-virtual-price-execution.md`를 본다.
5. 수요와 공급 주문장 체결은 `05-order-book-execution.md`를 본다.
6. 시세, Redis, SSE는 `06-market-data-redis-sse.md`를 본다.
7. 자동장 주문 생성은 `07-auto-market.md`를 본다.
8. 기업 이벤트는 `08-corporate-actions.md`를 본다.
9. 장 상태, 거래정지, 관리자 제어는 `09-market-session-and-admin.md`를 본다.
10. 포트폴리오, 손익, 랭킹은 `10-portfolio-profit-ranking.md`를 본다.
11. 프론트 화면과 API client는 `11-front-workspaces.md`를 본다.
12. 실제 변경 작업 순서는 `12-change-sequence-and-verification.md`를 마지막 체크리스트로 쓴다.
13. 종목 평가 보고서는 `../18-instrument-report-events.md`와 `../development-specs/13-instrument-report-events.md`를 본다.

## 현재 큰 구조

- `stock-back-service`: 사용자/관리자 API, 인증 경계, 도메인 DTO, JPA entity 기준.
- `stock-batch-service`: 시세 갱신, 주문 체결, 자동장, 기업 이벤트, 정산 같은 시간 기반 상태 전이.
- `stock-front-service`: 현재 원장을 조회하고 주문/관리 UI를 제공. 체결 결과나 잔고를 브라우저에서 직접 만들지 않는다.

## 반드시 지키는 원칙

- back에서 batch job을 돌리지 않는다.
- batch가 체결/이벤트/정산 원장을 갱신한다.
- `VIRTUAL_PRICE`와 `ORDER_BOOK`은 `stock_order.market_type`으로 분리한다.
- 주문장 종목은 `stock_order_book_instrument` 기준이며, 현재가 시장 종목과 공유하지 않는다.
- 기업 이벤트는 초기 필수 범위만 구현한다. 범위는 `../15-corporate-action-scope.md`를 우선한다.
- 종목 평가 보고서는 최신 활성 이벤트만 자동장 신호로 쓰고, 보고서가 없거나 삭제되면 자동 참여자 성향만 사용한다.
- 원장 컬럼을 바꾸면 back DDL, batch DDL, H2 DDL, Java/TS DTO, 테스트를 같이 본다.
