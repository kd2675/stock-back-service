# Front Workspaces

## 현재 구현

front는 두 시장을 최상단 탭으로 나눈다.

- 홈 `/`: 특정가격 자동주문체결, `VIRTUAL_PRICE`
- `/supply-demand`: 수요와 공급 주문장, `ORDER_BOOK`
- `/supply-demand/admin`: 주문장 관리자 화면

## 코드 역할

- `app/components/MarketModeTabs.tsx`: 두 시장의 top-level navigation.
- `app/page.tsx`: 가격 목록, SSE stream, 포트폴리오, 주문, 정정, 부분취소, 체결, 기업 이벤트 배정 내역.
- `app/supply-demand/page.tsx`: 주문장 종목 목록, order book depth, ORDER_BOOK 주문, 주문/체결 필터 조회.
- `app/supply-demand/admin/page.tsx`: 주문장 종목 생성, 장 상태 변경, 기업 이벤트 등록/조회.
- `app/lib/stock.ts`: API client.
- `app/types/stock.ts`: back DTO type.
- `app/lib/auth.ts`: token bootstrap, refresh, local-direct header.
- `app/lib/api.ts`: fetch wrapper와 response envelope 처리.

## 현재 흐름

### 홈

1. instrument, price, ranking을 먼저 가져온다.
2. 실제 존재하는 symbol 중 선택값을 resolve한다.
3. 선택 symbol이 있을 때만 price ticks와 order book을 조회한다.
4. 로그인 상태면 portfolio, holdings, snapshots, profit summary, orders, executions, entitlements를 조회한다.
5. SSE 가격 event를 받아 가격 배열을 merge한다.

### 수요/공급

1. 주문장 instrument, auto-market status, order-book market status를 가져온다.
2. 실제 존재하는 주문장 symbol 중 선택값을 resolve한다.
3. 선택 symbol이 있을 때만 order book을 조회한다.
4. 사용자 주문/체결은 `marketType=ORDER_BOOK`, `source=INTERNAL_ORDER_BOOK`로 조회한다.

### 관리자

1. token과 role을 확인한다.
2. 주문장 종목을 생성한다.
3. 생성 직후 해당 symbol을 기업 이벤트 대상 symbol로 선택한다.
4. 기업 이벤트 payload를 타입별로 만든다.
5. 장 상태를 symbol별로 변경한다.

## 앞으로 구현할 후보

- 주문 정정/부분취소를 `/supply-demand` 화면에도 제공.
- 관리자 자동장 설정 변경 UI.
- 기업 이벤트 단주/권리 내역 상세 UI.
- 장 운영 일정 UI.

## 변경 순서

1. back DTO와 API 응답을 먼저 확인한다.
2. `app/types/stock.ts`를 수정한다.
3. `app/lib/stock.ts` API client를 수정한다.
4. 화면 state와 validation을 수정한다.
5. 긴 숫자/총자산/거래대금은 overflow 없이 보이도록 layout을 확인한다.
6. `scripts/verify-stock-front-contract.mjs`에 계약 검사를 추가한다.

## 검증

- `cd stock-front-service && npm run verify:contract`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`

## 개발 시 주의점

- 브라우저에서 체결 결과를 만들거나 저장하지 않는다.
- 기본 종목을 하드코딩하지 않는다.
- 주문장 시장 종목과 현재가 시장 종목을 섞지 않는다.
- 관리자 화면 숨김은 보조일 뿐이고 back 권한 제한이 기준이다.
