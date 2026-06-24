# Frontend Workspace

이 문서는 `stock-front-service` 화면과 API 클라이언트 구조를 설명한다.

## 현재 라우트

- `/`: 현재가 기반 모의투자 홈
- `/login`: stock OAuth 로그인
- `/supply-demand`: 수요/공급 주문장
- `/supply-demand/admin`: 관리자 종목/기업 이벤트 화면

## 코드 지도

- `stock-front-service/app/page.tsx`
- `stock-front-service/app/supply-demand/page.tsx`
- `stock-front-service/app/supply-demand/admin/page.tsx`
- `stock-front-service/app/login/page.tsx`
- `stock-front-service/app/components/MarketModeTabs.tsx`
- `stock-front-service/app/lib/api.ts`
- `stock-front-service/app/lib/auth.ts`
- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/types/stock.ts`

## API 클라이언트

`app/lib/api.ts`

- 공통 fetch wrapper다.
- `ResponseEnvelope<T>`를 해석한다.
- HTTP status와 business response를 함께 반환한다.

`app/lib/stock.ts`

- stock-back API 호출을 모은다.
- 보호 API 호출 시 access token을 붙인다.
- local-direct 모드에서 `X-User-Key`, `X-User-Role`을 함께 보낸다.
- 401이면 refresh token 흐름을 한 번 시도한다.

## 화면 역할

홈 화면:

- `VIRTUAL_PRICE` market type으로 주문한다.
- 가격, 포트폴리오, 주문, 체결, 보유를 조회한다.
- 미체결/부분체결 주문의 전체 취소, 부분 취소, LIMIT 주문 정정을 호출한다.
- LIMIT 주문 입력 시 기본 1원 tick과 전일종가 기준 ±30% 가격제한폭을 먼저 검증한다.

수요/공급 화면:

- `ORDER_BOOK` market type으로 주문한다.
- 주문장 depth, 자동장 상태, 주문장 종목을 조회한다.
- 사용자의 `ORDER_BOOK` 주문 상태와 최근 체결을 조회한다.
  - 주문 조회는 `/orders?marketType=ORDER_BOOK`를 사용한다.
  - 체결 조회는 `/executions?source=INTERNAL_ORDER_BOOK`를 사용한다.
- 미체결/부분체결 주문은 전체 취소할 수 있다.
- 프론트 자체 체결 상태를 만들지 않는다.
- 주문장 종목의 `tickSize`, `priceLimitRate`, `priceLimitBase`로 LIMIT 주문 입력을 먼저 검증한다.
- 선택 종목의 `marketStatus`가 `OPEN`이 아니면 신규 주문 버튼을 막는다.

관리자 화면:

- ADMIN role만 접근 가능하다.
- 주문장 종목 생성
- 주문장 종목별 호가 단위와 가격제한폭 설정
- 주문장 종목별 장 상태 변경
  - 정규장
  - 마감
  - 거래정지
- 기업 이벤트 등록
  - 유상증자
  - 추가발행
  - 액면분할
  - 현금배당
  - 무상증자
  - 주식배당

## UI/UX 원칙

- 실제 투자 워크스페이스가 첫 화면이어야 한다.
- 카드 중첩을 피하고 정보 밀도를 유지한다.
- 긴 숫자는 영역을 넘치지 않게 `tabular-nums`, responsive grid, overflow 처리를 사용한다.
- 기능 설명 문구보다 실제 조작 가능한 컨트롤을 우선한다.
- 관리자 기능은 사용자 화면과 분리한다.

## 다음에 바꿀 때 순서

새 API 필드가 생길 때:

1. `app/types/stock.ts` 타입을 먼저 수정한다.
2. `app/lib/stock.ts` payload/response wrapper를 수정한다.
3. 화면 state와 validation을 수정한다.
4. 사용자 화면과 관리자 화면 중 어느 쪽에 노출할지 결정한다.
5. 시장별 화면이면 서버 query filter를 먼저 확인한다. 최근 N건 응답을 받은 뒤 프론트에서 시장을 걸러내면 누락될 수 있다.
6. `npm run lint`, `npm run build`를 실행한다.

새 주문 기능을 넣을 때:

1. 백엔드 DTO와 API가 먼저 있어야 한다.
2. 프론트는 원장 API를 호출한다.
3. optimistic 체결 상태를 만들지 않는다.
4. 미체결/부분체결/취소 상태를 화면에 명확히 보여준다.
5. 예약금/예약수량은 프론트에서 임의 계산하지 않고 응답을 다시 조회해 표시한다.
