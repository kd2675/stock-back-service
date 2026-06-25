# Instrument Report Events

이 문서는 주문장 종목 평가 보고서의 현재 구현, 역할, 데이터 계약, 변경 순서를 설명한다.

## 목적

평가 보고서는 관리자가 주문장 종목에 대해 시장 해석을 남기는 원장이다. 보고서는 자동 참여자의 주문 방향을 보조하는 신호로도 쓰인다.

보고서가 없어도 종목은 상장될 수 있다. 초기 발행, 상장, 기업 이벤트 직후에는 보고서가 없는 상태가 정상이다.

## 현재 구현

- API:
  - `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
  - `GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports/latest`
  - `POST /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
  - `PATCH /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
  - `DELETE /api/stock/v1/markets/order-book-instruments/{symbol}/reports`
- back:
  - `MarketController`
  - `MarketService`
  - `StockInstrumentReportEvent`
  - `StockInstrumentReportEventRepository`
  - `InstrumentReportRequest`
  - `InstrumentReportResponse`
- batch:
  - `AutoMarketReader`
  - `AutoProfileBehavior.effectiveIntensity`
  - `stock-batch-service/src/main/java/stock/batch/service/automarket/profile/*Behavior.java`
- front:
  - `stock-front-service/app/lib/stock.ts`
  - `stock-front-service/app/types/stock.ts`
  - `stock-front-service/app/supply-demand/admin/page.tsx`

## 데이터 계약

테이블: `stock_instrument_report_event`

이벤트 타입:

- `PUBLISH`: 새 현재 보고서를 발행한다.
- `UPDATE`: 기존 활성 보고서를 새 내용으로 교체한다.
- `DELETE`: 현재 보고서를 삭제 처리한다. 과거 row는 물리 삭제하지 않는다.

`PUBLISH`와 `UPDATE` 필수값:

- `title`
- `summary`
- `score`: 1~10
- `rise_reason`
- `fall_reason`

`DELETE` 규칙:

- 보고서 본문 필드는 비어 있어야 한다.
- `delete_reason`은 삭제 사유를 기록한다.

현재 기준:

- 종목별 최신 이벤트를 `created_at desc, id desc`로 본다.
- 최신 이벤트가 `DELETE`이면 현재 보고서는 없다.
- 최신 이벤트가 `PUBLISH` 또는 `UPDATE`이면 그 row가 현재 보고서다.

## 자동장 반영

자동장은 참여자별 1~10 성향을 기본으로 한다. 최신 활성 평가 보고서 점수가 있으면 자동 참여자 심리 프로필의 뉴스 민감도에 따라 참여자 성향과 보고서 점수를 섞어 유효 강도를 만든다. 유효 강도 계산은 `AutoProfileBehavior.effectiveIntensity`에서 시작하고, 프로필별 주문 방향과 주문 수 판단은 각 `*Behavior` 클래스가 담당한다.

현재 반영 방식:

- `NEWS_REACTIVE`는 보고서 점수를 크게 반영한다.
- `NOISE_TRADER`, `SCALPER`, `HERD_FOLLOWER` 등은 보고서 점수를 일부만 반영한다.
- `OBSERVER`, `MARKET_MAKER`, `VALUE_ANCHOR` 등은 보고서 점수보다 기존 전략과 호가/가격 흐름을 더 크게 본다.

이 방식을 둔 이유:

- 자동 참여자 성향이 개별 행위자의 주된 개성이다.
- 평가 보고서는 종목별 관리 신호이지만, 모든 참여자를 같은 방향으로 완전히 덮으면 주문장이 단조로워진다.
- 보고서가 없거나 삭제되면 기존 자동장 성향만으로 동작해야 한다.

## 관리자 화면

관리자는 `/supply-demand/admin`에서 종목을 선택하고 평가 보고서를 발행, 수정, 삭제한다.

화면은 최근 이벤트 이력을 보여준다. 삭제 이벤트도 이력에 남겨야 한다. 과거 보고서를 선택해 초안을 채울 수 있지만, 수정은 새 `UPDATE` 이벤트로 남는다.

## 변경 순서

1. 보고서 필드나 점수 정책을 먼저 문서에 정의한다.
2. `stock_instrument_report_event` MySQL DDL과 H2 DDL을 함께 바꾼다.
3. `StockInstrumentReportEvent`, request/response DTO를 바꾼다.
4. `MarketService` validation과 최신 보고서 조회 규칙을 바꾼다.
5. 자동장 영향이 있으면 `AutoMarketReader`, `AutoProfileBehavior.effectiveIntensity`, 관련 `*Behavior` 클래스를 함께 바꾼다.
6. `stock-front-service` type, API client, admin 화면을 바꾼다.
7. back, batch, front 계약 검증을 실행한다.

## 검증

- `./gradlew :stock-back-service:test --tests '*MarketServiceTest*'`
- `./gradlew :stock-batch-service:test --tests '*AutoMarketServiceTest*'`
- `./gradlew :stock-back-service:test`
- `./gradlew :stock-batch-service:test`
- `cd stock-front-service && npm run lint`
- `cd stock-front-service && npm run build`
- `node scripts/verify-stock-initial-scope.mjs`
- `cd stock-front-service && npm run verify:contract`

## 주의점

- 보고서는 기업 이벤트가 아니다. 발행주식수, 가격, 보유수량을 직접 바꾸지 않는다.
- 보고서는 자동장 주문 생성의 보조 신호다. 체결 엔진의 가격 우선, 시간 우선 규칙은 바꾸지 않는다.
- 최신 보고서가 삭제 상태이면 자동장은 보고서 점수를 사용하면 안 된다.
- 과거 이벤트 이력은 감사와 원인 분석을 위해 유지한다.
