# Runtime Config And Smoke

## 현재 구현

stock 서버는 기본 profile을 `local-direct`로 둔다. local direct에서는 eureka/gateway 의존 없이 stock-front가 stock-back과 auth-back을 직접 호출한다.

seed는 일반 실행에서 기본 종목을 만들지 않는다. smoke/H2 전용 데이터만 테스트 목적의 최소 데이터를 넣는다.

## 관련 파일

- `.env.example`
- `stock-back-service/.env.example`
- `stock-batch-service/.env.example`
- `stock-front-service/.env.example`
- `stock-back-service/src/main/resources/application.yml`
- `stock-back-service/src/main/resources/application-local-direct.yml`
- `stock-batch-service/src/main/resources/application.yml`
- `stock-batch-service/src/main/resources/application-local-direct.yml`
- `stock-batch-service/src/main/resources/application-test.yml`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2_smoke_data.sql`
- `scripts/stock-smoke.sh`
- `scripts/stock-h2-smoke.sh`
- `scripts/stock-gateway-h2-smoke.sh`

## 현재 profile 기준

back local-direct:

- discovery/eureka 비활성.
- auth-back 직접 URL 사용.
- local front origin 허용.

batch local-direct:

- discovery/eureka 비활성.
- batch job은 scheduler와 internal API 둘 다 service method를 호출.

test:

- batch scheduler는 모두 비활성화한다.
- H2 DDL과 smoke data로 테스트한다.

front:

- 기본 API mode는 direct.
- dev origin 허용은 필요한 host만 제한적으로 둔다.

## 현재 불변식

- 일반 DDL/resource는 시작 시 기본 종목/가격을 1회 seed하지 않는다.
- smoke data는 smoke profile/test용으로만 둔다.
- batch test에서 scheduler가 실제로 돌면 안 된다.
- docker compose 파일은 현재 초기 범위에서 사용하지 않는다.

## 앞으로 구현할 후보

- gateway 모드 smoke 분리.
- 실제 운영 profile 문서화.
- KIS provider 운영 credential 주입 방식 정리.
- 배치 job 수동 실행 runbook.

## 바꿀 때 순서

1. profile 기본값을 바꾸면 `.env.example`과 세 서버 README를 같이 확인한다.
2. scheduler property를 추가하면 `application-test.yml`에 비활성화 값을 반드시 넣는다.
3. seed가 필요하면 smoke/test 전용인지 운영 bootstrap인지 먼저 분리한다.
4. smoke script가 기대하는 API base URL과 auth mode를 같이 바꾼다.

## 검증

- `node scripts/verify-stock-initial-scope.mjs`
- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `scripts/stock-h2-smoke.sh`
