# Stock Market Simulation Development Guide

이 디렉터리는 stock 모의투자 기능을 이어서 개발하기 위한 기능별 개발 문서다. 각 문서는 한 항목만 읽어도 현재 구현, 관련 코드, 데이터 계약, 다음 변경 순서를 파악할 수 있도록 작성한다.

## 현재 서비스 역할

`stock-back-service`는 사용자 API와 도메인 계약의 기준점이다.

- 주문 접수, 정정, 취소, 조회
- 계좌, 보유, 포트폴리오 조회
- 시장/가격/호가/랭킹 조회
- 주문장 종목 생성과 기업 이벤트 등록
- 인증 principal 확인과 사용자 프로필 조립

`stock-batch-service`는 시간이 지나며 발생하는 상태 변화를 처리한다.

- 외부 또는 mock 시세 갱신
- 현재가 기준 주문 체결
- 수요/공급 주문장 체결
- 자동장 주문 생성
- 기업 이벤트 적용
- 장 마감 포트폴리오 정산

`stock-front-service`는 실제 원장을 조회하고 주문/관리 UI를 제공한다.

- 홈: 현재가 기반 `VIRTUAL_PRICE` 시장
- `/supply-demand`: 수요/공급 `ORDER_BOOK` 시장
- `/supply-demand/admin`: 관리자 종목/기업 이벤트/평가 보고서 화면

## 핵심 원칙

- 프론트는 체결 상태를 localStorage로 만들지 않는다.
- 주문 접수와 체결 판단을 섞지 않는다.
- `stock_order.market_type`으로 현재가 시장과 주문장 시장을 분리한다.
- 유실되면 안 되는 상태는 DB 원장에 쓴다.
- Redis는 최신가 캐시와 live event 전달용 보조 채널이다.
- 관리자 쓰기 API는 `ADMIN` principal만 허용한다.
- batch가 처리하는 이벤트는 수동 internal job API와 scheduler가 같은 service를 호출한다.

## 문서 목록

- `01-domain-data-model.md`: DB 원장과 엔티티 역할
- `02-auth-and-api-boundaries.md`: 인증, role, 내부 API 경계
- `03-market-data-and-price-stream.md`: 시세 갱신, Redis 캐시, SSE
- `04-order-entry-and-reservation.md`: 주문 접수와 예약금/예약수량
- `05-virtual-price-execution.md`: 현재가 기준 체결
- `06-order-book-execution.md`: 수요/공급 주문장 체결
- `07-auto-market.md`: 자동 참여자와 자동장
- `08-corporate-actions.md`: 기업 이벤트
- `18-instrument-report-events.md`: 종목 평가 보고서 이벤트
- `09-portfolio-settlement-ranking.md`: 정산과 랭킹
- `10-frontend-workspace.md`: 프론트 화면/API 구조
- `11-market-session-status.md`: 장 상태와 거래정지
- `12-next-implementation-plan.md`: 앞으로 바꿀 순서
- `13-code-ownership-map.md`: 코드 파일별 책임과 변경 지점
- `14-feature-change-playbooks.md`: 기능별 변경 순서와 검증 체크리스트
- `15-corporate-action-scope.md`: 초기 필수 기업 이벤트와 보류 이벤트 범위
- `16-initial-essential-scope-audit.md`: 초기 프로젝트 필수 기능만 유지되는지 보는 감사 기준
- `17-essential-completion-evidence.md`: 초기 필수 범위가 현재 코드에서 충족되는지 보는 증거 매트릭스
- `feature-handbook/`: 한 파일이 한 개발 항목을 담당하는 실무 핸드오프 문서
- `development-specs/`: 실제 변경 작업을 기능/역할/코드 경계별로 이어가기 위한 세부 개발 사양

## 기능별 핸드오프 문서

아래 문서는 각 항목을 바로 이어서 개발할 수 있도록 현재 구현, 코드 역할, 앞으로 구현할 내용, 변경 순서, 검증 기준을 같은 구조로 정리한다.

- `feature-handbook/00-index.md`
- `feature-handbook/01-service-boundaries.md`
- `feature-handbook/02-ledger-and-ddl.md`
- `feature-handbook/03-order-entry-amend-cancel.md`
- `feature-handbook/04-virtual-price-execution.md`
- `feature-handbook/05-order-book-execution.md`
- `feature-handbook/06-market-data-redis-sse.md`
- `feature-handbook/07-auto-market.md`
- `feature-handbook/08-corporate-actions.md`
- `feature-handbook/09-market-session-and-admin.md`
- `feature-handbook/10-portfolio-profit-ranking.md`
- `feature-handbook/11-front-workspaces.md`
- `feature-handbook/12-change-sequence-and-verification.md`

## 세부 개발 사양 문서

아래 문서는 더 구체적인 코드 변경 시작점이다. 기능을 실제로 수정할 때는 해당 파일 하나를 먼저 읽고, 필요한 경우 위의 큰 기능 문서로 올라간다.

- `development-specs/00-index.md`
- `development-specs/01-service-boundaries.md`
- `development-specs/02-api-auth-boundaries.md`
- `development-specs/03-ledger-ddl-contract.md`
- `development-specs/04-virtual-price-market.md`
- `development-specs/05-order-book-market.md`
- `development-specs/06-auto-market.md`
- `development-specs/07-corporate-actions.md`
- `development-specs/08-portfolio-profit-ranking.md`
- `development-specs/09-market-data-redis-sse.md`
- `development-specs/10-frontend-workspaces.md`
- `development-specs/11-runtime-config-and-smoke.md`
- `development-specs/12-change-order-and-verification.md`

## 현재 구현된 큰 기능

- `VIRTUAL_PRICE` 주문 접수와 현재가 기준 batch 체결
- `ORDER_BOOK` 주문 접수와 내부 주문장 batch 매칭
- 미체결/부분체결 주문 전체 취소, 부분 취소, LIMIT 주문 정정
- LIMIT 주문 호가 단위와 일일 가격제한폭 검증
- 종목별 장 상태: 정규장, 마감, 거래정지
- 주문장 종목 생성
- 주문장 종목 평가 보고서 이벤트와 최신 보고서 조회
- 자동 참여자 주문 생성
- Redis 최신가 캐시와 가격 event 발행
- 기업 이벤트 일부: 초기 발행, 유상증자, 추가발행, 액면분할, 현금배당, 무상증자, 주식배당
- 종목별 기업 이벤트 이력과 사용자별 배당/신주 배정 내역 조회
- 체결 단위 수수료, 매도 거래세, 실현손익 기록과 누적 손익 요약 조회
- 주문/체결 조회의 시장별 필터: `/orders?marketType=...`, `/executions?source=...`
- 포트폴리오 스냅샷과 랭킹 조회

## 문서 사용법

기능을 바꿀 때는 아래 순서로 본다.

1. 해당 기능 문서에서 현재 구현과 데이터 계약을 확인한다.
2. `13-code-ownership-map.md`에서 수정해야 할 파일 범위를 좁힌다.
3. `14-feature-change-playbooks.md`에서 DDL, back, batch, front, test 순서를 확인한다.
4. 기업 이벤트를 추가하려면 `15-corporate-action-scope.md`에서 초기 필수 범위인지 먼저 확인한다.
5. README와 `AGENTS.md`는 포트, 실행 방법, 운영 경계가 바뀔 때만 갱신한다.

한 기능을 구현할 때 back, batch, front 중 하나만 바꿔도 되는지 먼저 판단한다. 주문 체결, 기업 이벤트, 정산처럼 원장을 바꾸는 기능은 대부분 세 프로젝트 문서와 DDL을 함께 확인해야 한다.

## 현재 의도적으로 아직 안 넣은 것

- 동시호가
- 일정 기반 장전/정규장/장마감 전환
- 상장폐지
- 감자/액면병합
- 결제 예정/결제 완료 분리

이 항목들은 구현이 불가능해서가 아니라, 현재 원장과 정책이 먼저 정해져야 안전하게 넣을 수 있기 때문에 후속 순서로 둔다.
