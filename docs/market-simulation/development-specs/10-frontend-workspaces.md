# Frontend Workspaces

## 현재 구현

front는 최상단 탭으로 두 시장을 분리한다.

- `/`: 특정가격 자동주문체결, `VIRTUAL_PRICE`.
- `/supply-demand`: 수요와 공급 주문 체결, `ORDER_BOOK`.
- `/supply-demand/admin`: 관리자용 주문장 종목/기업 이벤트/장 상태 관리.

## 관련 코드

- `stock-front-service/app/components/MarketModeTabs.tsx`
- `stock-front-service/app/page.tsx`
- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`
- `stock-front-service/app/lib/api.ts`
- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/types/stock.ts`
- `stock-front-service/scripts/verify-stock-front-contract.mjs`

## 현재 화면 책임

홈:

- 현재가 종목과 가격 조회.
- 현재가 주문 접수.
- 내 계좌, 보유, 주문, 체결, 포트폴리오, 손익, 랭킹 조회.
- Redis/SSE 가격 event를 화면 가격에 반영.

수요/공급:

- 주문장 종목 조회.
- 주문장/자동장/장 상태 조회.
- `ORDER_BOOK` 주문 접수.
- 내 `ORDER_BOOK` 주문과 `INTERNAL_ORDER_BOOK` 체결 조회.

관리자:

- 주문장 종목 생성.
- 장 상태 변경.
- 기업 이벤트 등록.
- 기업 이벤트 이력 조회.

## 현재 불변식

- front는 `marketType`을 명시해 두 시장 주문을 분리한다.
- front type union은 Java enum/DDL 범위와 같아야 한다.
- browser storage는 auth token과 UI 상태 보조 용도이며 원장을 대신하지 않는다.
- direct 모드 기본값은 `NEXT_PUBLIC_API_MODE=direct`이다.
- direct 모드에서는 stock API와 auth API base URL이 분리된다.

## 앞으로 구현할 후보

- 주문 정정/부분 취소 UI를 `/supply-demand`에도 동일하게 확장.
- admin 자동장 participant/config 관리 UI.
- corporate action entitlement 상세 화면.
- 장 운영 일정 캘린더.
- 모바일 주문 티켓 개선.

## 바꿀 때 순서

1. API type을 `app/types/stock.ts`에 먼저 추가한다.
2. API client 함수를 `app/lib/stock.ts`에 추가한다.
3. 화면 state와 validation을 붙인다.
4. contract verifier에 endpoint/type 범위를 추가한다.
5. lint/build를 돌린다.

## 검증

- `cd stock-front-service && npm run verify:contract`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`
