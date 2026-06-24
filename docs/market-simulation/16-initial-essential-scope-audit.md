# Initial Essential Scope Audit

이 문서는 현재 stock 프로젝트가 초기 진행에 꼭 필요한 기능만 유지하고 있는지 확인하는 감사 문서다. 기준은 `15-corporate-action-scope.md`와 현재 코드 계약이다.

## 감사 기준

- 초기 프로젝트는 모든 실제 주식시장 기능을 구현하지 않는다.
- 원장 안정성, 주문/체결 흐름, 관리자 종목 생성, 사용자 포트폴리오 조회에 필요한 기능만 유지한다.
- 새 기능은 back API, batch 상태 전이, DDL, front type, 테스트가 함께 맞을 때만 구현한다.
- corporate action은 현재 7개 타입에서 멈춘다.

## 현재 필수로 유지하는 기능

서비스 경계:

- `stock-back-service`: 사용자/관리자 API, 인증 경계, JPA entity, DTO, DDL 계약의 기준점.
- `stock-batch-service`: 현재가 체결, 주문장 매칭, 자동장 주문 생성, 기업 이벤트, 정산.
- `stock-front-service`: 서버 원장 조회와 주문/관리 UI. 체결, 잔고, 배당 결과를 브라우저에서 직접 만들지 않는다.

시장/주문:

- `VIRTUAL_PRICE`: 특정가격 자동주문체결 시장.
- `ORDER_BOOK`: 수요와 공급 주문장 체결 시장.
- `LIMIT`, `MARKET` 주문.
- 매수, 매도, 취소, 부분 취소, LIMIT 주문 정정.
- 호가 단위와 가격제한폭.
- 종목별 `OPEN`, `CLOSED`, `HALTED`.

원장/조회:

- 계좌, 보유수량, 예약금, 예약수량.
- 체결 이력.
- 수수료, 매도 거래세, 실현손익.
- 포트폴리오 스냅샷과 랭킹.
- Redis 최신가 캐시와 SSE 가격 이벤트.

기업 이벤트:

- `INITIAL_ISSUE`
- `PAID_IN_CAPITAL_INCREASE`
- `ADDITIONAL_ISSUE`
- `STOCK_SPLIT`
- `CASH_DIVIDEND`
- `BONUS_ISSUE`
- `STOCK_DIVIDEND`

## 코드상 범위 고정 지점

- back enum: `src/main/java/stock/back/service/database/entity/StockCorporateActionType.java`
- front type: `stock-front-service/app/types/stock.ts`
- 관리자 이벤트 선택지: `stock-front-service/app/supply-demand/admin/page.tsx`
- MySQL full DDL: `src/main/resources/db/ddl/stock_all.sql`
- MySQL alter DDL: `src/main/resources/db/ddl/stock_market_execution_split_alter.sql`
- batch H2 DDL: `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- back DDL 계약 테스트: `StockMysqlDdlContractTest`
- batch DDL 계약 테스트: `StockDdlContractTest`
- back 실제 route table 계약 테스트: `StockBackApiSurfaceContractTest`
- batch 실제 route table 계약 테스트: `StockBatchApiSurfaceContractTest`
- front 계약 검증: `scripts/verify-stock-front-contract.mjs`

## 의도적으로 제외한 것

기업 이벤트:

- 특별배당
- 감자
- 액면병합
- 신주인수권
- 권리공모
- 합병
- 분할
- 스핀오프
- 상장폐지
- 청산, 해산, 파산
- 종목명/코드 변경

시장/주문:

- 장전/장마감 동시호가
- 시초가/종가 단일가 결정
- IOC/FOK
- stop/stop-limit
- 시간외 거래
- 결제 예정/결제 완료 분리
- 시장 전체 서킷브레이커

이 항목들은 구현이 불필요하다는 뜻이 아니다. 초기 프로젝트에서 필요한 원장 정책이 아직 없거나, 현재 기능의 안정화보다 우선순위가 낮다는 뜻이다.

## 현재 감사 결과

기업 이벤트 범위:

- enum은 7개 초기 타입만 갖는다.
- DDL은 7개 초기 타입만 허용한다.
- front type과 관리자 선택지는 7개 초기 타입 기준이며, 관리자가 직접 등록할 수 없는 `INITIAL_ISSUE`는 선택지에서 제외한다.
- deferred 타입은 계약 테스트와 검증 스크립트의 금지 목록, 보류 문서에만 남긴다.

시드 데이터:

- 기본 종목과 기본 가격을 시작 시 자동 seed하지 않는다.
- 주문장 종목은 관리자 생성 흐름으로 만든다.
- H2 smoke처럼 검증에 필요한 경우에만 명시적으로 smoke data를 사용한다.
- `scripts/stock-smoke.sh`와 `.env.example`의 일반 기본값은 seeded market을 기대하지 않는다.
- `005930`/`삼성전자` 같은 샘플 데이터는 `application-smoke.yml`이 로드하는 `stock_h2_smoke_data.sql` 안에서만 smoke 용도로 둔다.

배치 책임:

- back controller/service는 batch job을 직접 돌리지 않는다.
- 체결, 자동장, 기업 이벤트, 정산은 batch service와 scheduler/internal job API가 맡는다.
- 테스트 profile에서는 batch background scheduler를 모두 끈다. 테스트는 service/controller를 직접 호출하거나 internal job API를 명시적으로 호출해야 한다.

local-direct / gateway:

- stock back/batch의 기본 profile은 `local-direct`다.
- `local-direct`는 Eureka discovery와 service auto-registration을 끈다.
- stock back은 local-direct에서 auth-back-server를 `STOCK_AUTH_BASE_URL` 기본값 `http://localhost:9000`으로 직접 호출한다.
- stock front의 기본 API mode는 `direct`다.
- gateway 경유는 `NEXT_PUBLIC_API_MODE=gateway`, `NEXT_PUBLIC_API_URL=http://localhost:8080`을 명시할 때만 사용한다.
- local-direct 보호 API 호출은 access token과 함께 `X-User-Key`, `X-User-Role`을 붙인다.
- `X-Client-Id`는 auth login/refresh/logout/signup 요청에만 붙인다.

의존성:

- stock back은 API/JPA/Redis/Auth Feign/Eureka Client까지만 둔다.
- stock batch는 API/JDBC/Redis/Eureka Client까지만 둔다.
- cache starter, Kafka/Rabbit/Stream, GraphQL, WebSocket, batch 전용 프레임워크는 초기 필수 범위에 넣지 않는다.
- batch에는 Feign client가 없으므로 `spring-cloud-starter-openfeign`과 `@EnableFeignClients`를 두지 않는다.

API 표면:

- stock back public/user/admin API는 system, accounts, users, markets, portfolio, orders, executions, holdings 안에서만 유지한다.
- stock batch API는 `/internal/stock-batch/v1/system`과 `/internal/stock-batch/v1/jobs`만 유지한다.
- controller annotation 문자열뿐 아니라 Spring이 실제 등록한 route table도 `StockBackApiSurfaceContractTest`, `StockBatchApiSurfaceContractTest`로 고정한다.
- 관심종목, 알림, 권리행사, 상장폐지, 동시호가, 서킷브레이커 API는 초기 필수 범위에 넣지 않는다.
- stock back system status의 `gatewayRequired`는 `false`다. 기본 구동은 direct/local-direct이고 gateway는 명시 선택 모드다.

## 다음 변경 순서

초기 필수 범위 안에서만 고치려면 아래 순서를 따른다.

1. 변경하려는 기능이 `VIRTUAL_PRICE`인지 `ORDER_BOOK`인지 먼저 정한다.
2. 돈, 주식 수량, 예약금, 예약수량, 평균단가, 현재가 중 무엇이 바뀌는지 적는다.
3. 원장을 바꾸면 DDL 3종, entity, DTO, batch SQL, front type을 같이 본다.
4. corporate action이면 `15-corporate-action-scope.md`에서 초기 7개 타입인지 확인한다.
5. 초기 7개 밖이면 구현하지 말고 보류 문서에 필요한 정책만 적는다.
6. back test, batch test, front contract/lint/build를 실행한다.

## 검증 명령

```bash
node scripts/verify-stock-initial-scope.mjs
./gradlew :stock-back-service:test --tests '*StockBackApiSurfaceContractTest*'
./gradlew :stock-batch-service:test --tests '*StockBatchApiSurfaceContractTest*'
./gradlew :stock-back-service:test
./gradlew :stock-batch-service:test
cd stock-front-service && npm run verify:contract
cd stock-front-service && npm run lint
cd stock-front-service && npm run build
git diff --check
git -C stock-back-service diff --check
git -C stock-batch-service diff --check
git -C stock-front-service diff --check
```
