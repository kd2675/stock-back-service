<!-- Parent: ../AGENTS.md -->
<!-- Updated: 2026-07-10 -->

# stock-back-service

## Purpose

주식 모의투자 서비스의 사용자-facing API 서버입니다. 주문 접수, 가상 계좌, 보유 종목, 체결 내역, 수익률, 랭킹 API를 담당하는 백엔드 진입점입니다.

## Key Paths

- `src/main/java/stock/back/service`
- `src/main/resources/application*.yml`
- `src/test/java/stock/back/service`

## API Surface

- `/api/stock/v1/system/status`
- `/api/stock/v1/markets/**`
- `/api/stock/v1/markets/admin/total-asset-history`
- `GET /api/stock/v1/markets/admin/fund-flow-breakdown` (`ADMIN`, 전체·유저·자동 참여자·상장주관사를 한 번의 역할별 집계로 반환)
- `/api/stock/v1/markets/prices/{symbol}/ticks`
- `/api/stock/v1/markets/order-books/{symbol}`
- `/api/stock/v1/markets/order-book-instruments/{symbol}/market-report`
- `/api/stock/v1/markets/virtual-market`
- `/api/stock/v1/markets/order-book-market`
- `/api/stock/v1/markets/auto-market`
- `GET /api/stock/v1/markets/auto-market/participants?lifecycleScope=CURRENT|WITHDRAWN` (`ADMIN`)
- `GET /api/stock/v1/markets/auto-market/participants/overviews?lifecycleScope=CURRENT|WITHDRAWN` (`ADMIN`, 계좌·보유·주문·체결 요약)
- `GET /api/stock/v1/markets/auto-market/participants/symbol-configs?lifecycleScope=CURRENT|WITHDRAWN` (`ADMIN`, 저장된 종목별 전략)
- `GET /api/stock/v1/markets/admin/investor-flow-summary` (`ADMIN`, 유저·자동 참여자·상장주관사 기준 현재 거래일 비동기 요약)
- `GET /api/stock/v1/markets/admin/investor-flow-history` (`ADMIN`, 오늘은 비동기 요약, 과거 거래일은 권위 있는 full-market cycle의 역할 동결 스냅샷과 집계 상태를 반환)
- `GET /api/stock/v1/markets/institution-portfolios` (`ADMIN`, 축소시장용 기관 계정·목표 비중·최근 LIVE 결정·주문 감사)
- `GET /api/stock/v1/markets/institution-portfolios/recommendations` (`ADMIN`, 활성 종목 수에 따른 권장 기관 개수·기관당 AUM·운용 유형별 수치)
- `POST /api/stock/v1/markets/institution-portfolios` (`ADMIN`, 일시정지 장전에서 선택한 기관 1개를 제한된 다종목 LIVE로 생성)
- `POST /api/stock/v1/markets/institution-portfolios/{portfolioId}/suspend` (`ADMIN`, 실행 중에도 허용되는 비상 중단. 포트폴리오를 먼저 SUSPENDED로 고정한 뒤 대기 의도와 전용 계좌 미체결 주문을 취소하고 예약을 반환)
- `GET /api/stock/v1/markets/liquidity-mandates` (`ADMIN`, 전용 LP 계약·계정 역할·자기체결 그룹·거래일 위험 상태 감사)
- `GET /api/stock/v1/markets/liquidity-mandates/recommendations` (`ADMIN`, 종목별 권장 기준 거래량·시드 수량·초기 현금과 생성 가능 사유)
- `POST /api/stock/v1/markets/liquidity-mandates/{symbol}` (`ADMIN`, 일시정지 장전에서 유통 대기·인수 계정의 시드 자산을 이전하고 종목 전용 LP를 LIVE로 생성하며, 역할 분리형 신규 상장은 다음 장 개장 대상으로 활성화)
- `PATCH /api/stock/v1/markets/liquidity-mandates/{symbol}/policy` (`ADMIN`, 활성·중단 LP의 호가·재고·일일한도 정책을 다음 거래일 적용으로 예약하며 당일 정책과 누적 상태는 변경하지 않음)
- `POST /api/stock/v1/markets/liquidity-mandates/{symbol}/suspend` (`ADMIN`, 즉시 LP 중단과 해당 LP 미체결 주문·예약 취소)
- `POST /api/stock/v1/markets/liquidity-mandates/{symbol}/resume` (`ADMIN`, 일시정지 장전·당일 미사용 상태에서 LP LIVE 재개)
- `GET /api/stock/v1/markets/underwriting-contracts` (`ADMIN`, 인수계정·최초 유통/잠금 배정원장·발행량 수량 대사)
- `GET /api/stock/v1/markets/underwriting-contracts/recommendations` (`ADMIN`, 권장 인수기관·종목별 계정 수와 발행 대기 수량)
- `POST /api/stock/v1/markets/underwriting-contracts/{symbol}` (`ADMIN`, 발행 대기 종목의 인수계정과 계약 1건만 생성)
- `POST /api/stock/v1/markets/underwriting-contracts/{contractId}/supply/activate` (`ADMIN`, 일시정지 장전의 유한·수동 매도 전용 인수재고 공급 활성화)
- `POST /api/stock/v1/markets/underwriting-contracts/{contractId}/supply/suspend` (`ADMIN`, 즉시 공급 중단·계약 주문 취소, 제출예산 비환급)
- `GET /api/stock/v1/markets/system-custody` (`ADMIN`, 탈퇴·발행 대기·잠금 시스템 보관계정의 권장 개수와 실제 잔고)
- 현재 계좌 역할의 권위 소스는 `stock_account.participant_category`이며, 과거 자산·체결 역할은 장마감 스냅샷의 `participant_category`를 사용해 현재 역할 변경으로 재분류하지 않습니다.
- 공개 사용자 랭킹은 `MANUAL_PARTICIPANT`, `AUTO_PARTICIPANT`만 포함합니다. 기관은 성과 정산 대상이지만 별도 기관 감사 화면으로 분리하고, LP·인수·보관 계정은 장마감 수량 대사에는 포함하되 성과 정산과 사용자 랭킹에서는 제외합니다.
- `GET /api/stock/v1/markets/batch-jobs/eod/overview` (`ADMIN`)
- `POST /api/stock/v1/markets/batch-jobs/eod/cycles/{cycleId}/retry` (`ADMIN`, `FAILED` 현재 phase만)
- `/api/stock/v1/users/me`
- `/api/stock/v1/accounts/me`
- `/api/stock/v1/accounts/me/status`
- `POST /api/stock/v1/accounts/me`
- `/api/stock/v1/portfolio/me`
- `/api/stock/v1/portfolio/me/snapshots`
- `/api/stock/v1/orders`
- `/api/stock/v1/executions`

## Run / Check

```bash
./gradlew :stock-back-service:bootRun
./gradlew :stock-back-service:bootRun --args='--spring.profiles.active=local-direct'
./gradlew :stock-back-service:compileJava
./gradlew :stock-back-service:test
```

## Operational Notes

- 포트: `local/dev 20480`, `prod 10480`, `test 30480`
- 기본 로컬 개발은 `local-direct` profile이며 Eureka/Discovery를 끄고 auth-back-server를 `STOCK_AUTH_BASE_URL`로 직접 호출합니다.
- Gateway/Eureka 경유로 되돌릴 때는 기존 `local` profile을 사용합니다.
- 공통 응답은 `web-common-core`의 `ResponseDataDTO`, `ResponseErrorDTO`를 사용합니다.
- 인증/사용자 식별은 Gateway가 주입한 헤더와 `auth-common-core`를 기준으로 붙입니다.
- 주문장 종목 생성과 기업 이벤트 적용 POST API는 `ADMIN` principal만 허용합니다.
- 사용자 프로필은 `auth-common-core`의 `UserServiceClient`를 사용하되, Feign 호출에는 현재 `X-User-*` 헤더를 relay합니다.
- 주문, 체결, 잔고는 DB 원장에 저장하고 최신 시세 조회는 Redis `stock:price:{symbol}` 캐시 후 DB fallback 순서로 처리합니다.
- `stock_order.market_type`은 현재가 체결용 `VIRTUAL_PRICE`와 주문장 체결용 `ORDER_BOOK`을 분리하는 핵심 계약입니다.
- 주문장 API는 미체결/부분체결 LIMIT 주문만 가격대별로 집계합니다. 시장가 주문은 가격 레벨이 없으므로 호가에 넣지 않습니다.
- 자동장 API는 `stock_auto_participant`, `stock_auto_market_config`, 자동 주문/체결 원장을 읽는 조회 API입니다. 주문 생성과 체결은 batch 서버 책임입니다.
- 자동 참여자 탈퇴는 미체결 주문·예약을 해제하고 전용 예산을 만료한 뒤, 보유주식을 비거래 `SYSTEM_CUSTODY` 계정으로 이전하고 잔여 현금을 회수하는 원자적 정산입니다. 계좌 row는 과거 원장 연결을 위해 삭제하지 않고 `CLOSED`로 보존하며, 진행 중 기업행사 권리가 있으면 탈퇴를 거부합니다. 일반 관리 조회는 `CURRENT`, 탈퇴 감사 조회는 `WITHDRAWN` 생명주기 범위를 명시하며 두 범위의 캐시를 섞지 않습니다.
- `portfolio_snapshot`은 batch 정산 결과의 원장이며 사용자 화면에서는 최근 정산 기록/랭킹 근거로만 읽습니다.
- MySQL business DDL의 단일 canonical source는 `src/main/resources/db/ddl/stock_all.sql`입니다.
- 기능별 현재 구현과 다음 개발 순서는 `docs/market-simulation/00-overview.md`, 코드 파일별 책임은 `docs/market-simulation/13-code-ownership-map.md`, 기능별 변경 절차는 `docs/market-simulation/14-feature-change-playbooks.md`를 기준으로 확인합니다.

## For AI Agents

- 초기 서버 구성 단계이므로 실제 주문 체결 규칙을 컨트롤러에 직접 넣지 않습니다.
- 주문 접수 API와 체결 엔진 책임을 섞지 말고, 체결 판단은 `stock-batch-service` 또는 이후 분리될 trade engine 쪽으로 둡니다.
- 서비스별 세부 규칙은 이 문서와 README에 두고 루트 문서에는 긴 도메인 설명을 중복하지 않습니다.
- `stock_order`와 `stock_execution`은 나중에 내부 주문장 매칭으로 바뀔 수 있으므로 현재가 기반 체결 가정만으로 컬럼을 좁히지 않습니다.
- 정규장 운영·관리 조회는 `stock_order`·`stock_execution` 전체 집계를 반복하지 않고 일별 요약, close-cycle 스냅샷, bounded cursor를 우선합니다. 불가피한 원장 조회는 거래일/PK 범위와 페이지 상한을 둡니다.
- 주문·체결 hot table의 인덱스·잠금·쿼리를 바꿀 때는 현재 거래량과 확장 거래량에서 동일 데이터·동일 동시성 MySQL A/B를 수행합니다. 주문/체결 TPS가 기준선의 95% 미만이거나 p95가 `max(5ms, 10%)`보다 증가하면 적용하지 않습니다.
- EOD API는 cycle/attempt/요약 테이블만 읽도록 유지하며, 관리자 화면 조회를 이유로 정규장 주문·체결 원장에 새 인덱스를 추가하지 않습니다. 세부 기준은 `../stock-batch-service/docs/stock-eod-refactoring-plan-2026-07-15.md`를 따릅니다.
- EOD phase 재시도는 가장 오래된 전체시장 `FAILED` cycle의 제어행 backoff만 해제하고 Job을 직접 실행하지 않습니다. `DEFERRED`·정규장·실행 owner가 있는 cycle을 우회하거나 주문·체결·계좌·보유 원장을 조회하지 않습니다.
- 장중 전체 체결 건수와 사용자 누적 손익·관리자 체결 자금흐름은 `stock_execution_account_day_summary` 같은 비동기 read model을 사용하고 최대 지연을 응답/화면에 알립니다. 계좌의 전체 `stock_execution`을 주기적으로 다시 합산하지 않습니다. 종목 거래요약·캔들처럼 원본 범위 집계가 남은 API는 bounded single-flight 읽기 캐시와 짧은 query timeout을 거쳐야 하며, 캐시·요약 DB 갱신을 주문·체결 트랜잭션에 넣지 않습니다.
- 자동 참여자 월급·정기 현금의 신규·수정 운영 설정은 `DAY`·`MONTH`·`YEAR`만 허용합니다. 과거 sub-day 값의 판독 enum은 호환을 위해 유지하되 일반 저장 과정에서 자동 변환하지 않고, 정규장 지급·catch-up 지급을 추가하지 않습니다.
