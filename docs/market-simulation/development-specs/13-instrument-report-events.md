# Instrument Report Event Development Spec

이 문서는 평가 보고서 기능을 실제로 수정할 때 확인할 코드 경계와 순서를 정의한다.

## 기능 경계

평가 보고서는 주문장 종목에 대한 관리자의 해석 이벤트다.

포함:

- 보고서 발행
- 보고서 수정
- 보고서 삭제
- 최신 활성 보고서 조회
- 보고서 이벤트 이력 조회
- 최신 활성 보고서 점수를 자동장 유효 강도에 반영

제외:

- 주식 발행수 변경
- 현재가 직접 조정
- 보유수량 변경
- 주문장 체결 규칙 변경
- 기업 이벤트 상태 전이

## Back 코드

수정 시작점:

- `stock-back-service/src/main/java/stock/back/service/market/act/MarketController.java`
- `stock-back-service/src/main/java/stock/back/service/market/biz/MarketService.java`
- `stock-back-service/src/main/java/stock/back/service/database/entity/StockInstrumentReportEvent.java`
- `stock-back-service/src/main/java/stock/back/service/database/repository/StockInstrumentReportEventRepository.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/InstrumentReportRequest.java`
- `stock-back-service/src/main/java/stock/back/service/market/vo/InstrumentReportResponse.java`

Back 원칙:

- 관리자 쓰기 API는 `@RequirePrincipalRole(UserRole.ADMIN)`을 유지한다.
- 보고서를 물리 삭제하지 않고 `DELETE` 이벤트를 append한다.
- `UPDATE`는 활성 최신 보고서가 있을 때만 허용한다.
- `GET latest`는 최신 이벤트가 `DELETE`이면 `null`을 반환한다.
- 요청 validation은 DTO annotation과 service 검증을 함께 둔다.

## Batch 코드

수정 시작점:

- `stock-batch-service/src/main/java/stock/batch/service/batch/automarket/reader/AutoMarketReader.java`
- `stock-batch-service/src/main/java/stock/batch/service/batch/automarket/model/AutoMarketConfig.java`
- `stock-batch-service/src/main/java/stock/batch/service/automarket/biz/AutoMarketService.java`

Batch 원칙:

- `AutoMarketReader`는 최신 보고서 점수를 읽기만 한다.
- 최신 이벤트가 `DELETE`이면 점수를 읽지 않는다.
- `AutoMarketService.effectiveIntensity`가 참여자 성향과 보고서 점수를 섞는다.
- 보고서가 없으면 기존 참여자 성향만 사용한다.
- 자동장 job은 보고서를 생성하거나 수정하지 않는다.

## Front 코드

수정 시작점:

- `stock-front-service/app/types/stock.ts`
- `stock-front-service/app/lib/stock.ts`
- `stock-front-service/app/supply-demand/admin/page.tsx`

Front 원칙:

- 보고서 발행/수정/삭제 후 이력을 다시 조회한다.
- 삭제는 최신 활성 보고서를 없애는 이벤트로 표시한다.
- 보고서 점수는 1~10 입력으로 제한한다.
- 상승 이유와 하락 이유를 모두 입력하게 한다.
- 화면에서 보고서 이력을 숨기지 않는다.

## DDL

같이 바꿀 파일:

- `stock-back-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-back-service/src/main/resources/db/ddl/stock_market_execution_split_alter.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_all.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_h2.sql`
- `stock-batch-service/src/main/resources/db/ddl/stock_market_execution_split_alter.sql`

테이블 계약:

- `symbol`은 `stock_order_book_instrument.symbol` 기준이다.
- `event_type`은 `PUBLISH`, `UPDATE`, `DELETE`만 허용한다.
- `score`는 null 또는 1~10이다.
- `PUBLISH`, `UPDATE`는 보고서 본문 필드를 가져야 한다.
- `DELETE`는 보고서 본문 필드를 갖지 않아야 한다.

## 테스트

Back:

- 보고서 발행 시 `PUBLISH` 이벤트가 저장된다.
- 활성 최신 보고서가 없으면 수정이 실패한다.
- 삭제 후 최신 보고서 조회는 null이다.

Batch:

- 보고서 점수가 있으면 참여자 성향과 섞인 유효 강도가 나온다.
- 보고서가 없으면 참여자 성향 그대로다.
- 최신 이벤트가 `DELETE`이면 보고서 점수는 사용하지 않는다.

Front:

- admin 화면에서 보고서 API 함수가 연결된다.
- 보고서 type이 API 응답 필드와 맞는다.
- lint/build가 통과한다.

## 변경 체크리스트

1. DDL을 먼저 바꾼다.
2. back entity/repository/API를 바꾼다.
3. back service validation과 최신 보고서 규칙을 테스트한다.
4. batch reader가 최신 non-delete 보고서만 읽는지 테스트한다.
5. 자동장 유효 강도 계산을 테스트한다.
6. front type/API/admin UI를 바꾼다.
7. 계약 스크립트와 README를 갱신한다.
8. 전체 관련 테스트를 실행한다.
