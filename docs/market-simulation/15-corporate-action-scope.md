# Corporate Action Scope

이 문서는 기업 이벤트를 얼마나 구현할지 정하는 범위 문서다. 목표는 실제 시장의 모든 corporate action을 복제하는 것이 아니라, 초기 모의투자에서 원장 안정성과 사용자 이해에 필수인 이벤트만 먼저 유지하는 것이다.

## 공식 분류 기준

공식 자료 기준으로 corporate action은 넓게 보면 다음 범주를 포함한다.

- 배당과 분배: 현금배당, 주식배당, 현물배당, 특별배당
- 주식 수 조정: 액면분할, 액면병합, 무상증자, 감자
- 권리/청약: rights offering, subscription offering, 신주인수권
- 자본/지배구조 변경: 합병, 인수, 주식교환, 지배권 변경
- 상장/식별자 변경: 종목명 변경, 심볼/코드 변경
- 종료 이벤트: 해산, 청산, 파산, 상장폐지

참고 기준:

- FINRA Corporate Actions: stock splits, dividends, M&A, rights offerings, liquidations/dissolutions 등을 설명한다.
- FINRA Rule 6490 / UPC FAQ: cash or in-kind distributions, stock splits/reverse splits, rights/subscription offerings, name/symbol changes, mergers, acquisitions, bankruptcy/liquidations 등을 company-related action 범주로 본다.
- Investor.gov Stock Split: 주식분할은 주식 수와 가격을 반대로 조정하지만 기존 주주의 지분율을 희석하지 않는다고 설명한다.

참고 URL:

- https://www.finra.org/investors/insights/corporate-actions-public-companies-what-you-should-know
- https://www.finra.org/rules-guidance/rulebooks/finra-rules/6490
- https://www.finra.org/filing-reporting/market-transparency-reporting/uniform-practice-code-upc/faq
- https://www.investor.gov/introduction-investing/investing-basics/glossary/stock-split

## 초기 필수 범위

현재 프로젝트 초기 단계에서 필수로 유지할 기업 이벤트는 아래다.

1. `INITIAL_ISSUE`
   - 주문장 종목이 만들어질 때 발행주식수의 기준점을 남긴다.
   - 없으면 이후 증자/분할 이벤트의 기준 발행주식수를 설명하기 어렵다.
   - 관리자가 별도 등록하는 이벤트가 아니라 종목 생성 시 `LISTED` 상태로 확정되는 원장이다.

2. `PAID_IN_CAPITAL_INCREASE`
   - 사용자가 질문한 유상증자 흐름의 핵심이다.
   - 권리락 가격, 납입, 신주상장일을 분리해 실제 주식시장 흐름을 학습하기 좋다.

3. `ADDITIONAL_ISSUE`
   - 사용자 청약권이 없는 단순 추가 발행 모델이다.
   - 유상증자와 다르게 가격 조정 없이 발행주식수만 늘어나는 흐름을 분리한다.

4. `STOCK_SPLIT`
   - 가격과 수량이 반비례로 조정되는 대표 필수 이벤트다.
   - 보유수량, 예약수량, 평균단가, 현재가를 동시에 조정해야 하므로 원장 설계 검증에 중요하다.

5. `CASH_DIVIDEND`
   - 투자자가 체감하는 기본 권리 이벤트다.
   - 가격을 강제로 조정하지 않고 지급일 현금 반영을 entitlement 원장으로 검증한다.

6. `BONUS_ISSUE`
   - 주식 수 증가와 권리락 가격 조정을 다루되, 회사가 현금을 받지 않는 이벤트다.
   - 유상증자와 다른 평균단가 조정 흐름을 확인할 수 있다.

7. `STOCK_DIVIDEND`
   - 현금 대신 주식으로 지급되는 배당 모델이다.
   - 현금배당과 무상증자 사이의 차이를 화면과 원장으로 설명할 수 있다.

8. `DELISTING`
   - 현재는 `ZERO_VALUE` 방식만 지원한다.
   - 보유수량은 삭제하지 않고, 주문장/자동장을 닫고, 현재가를 0원으로 만들어 포트폴리오 평가손실을 반영한다.
   - 미체결 주문은 상장폐지일에 취소하고 예약 현금/수량을 해제한다.

이 8개는 이미 구현된 범위이며, 초기 단계에서는 여기서 기업 이벤트 구현을 멈추는 것이 맞다. 다음 작업은 새 이벤트 추가가 아니라 기존 이벤트의 기준일, 단주 정책, 열린 주문 정책, 테스트를 더 견고하게 만드는 쪽이 우선이다.

## 초기 필수에서 제외할 이벤트

아래 이벤트는 실제 시장에는 존재하지만 초기 구현 필수 범위가 아니다.

`감자`

- 보유수량 감소, 평균단가 증가, 단주 현금 보상, 자본잠식/결손 보전 같은 회계적 의미가 얽힌다.
- 현재 단계에서는 단주 보상 정책이 없으므로 구현하지 않는다.

`액면병합`

- 액면분할의 반대지만 단주가 훨씬 쉽게 발생한다.
- 단주 현금 보상 또는 소수점 주식 정책이 정해진 뒤 구현한다.

`신주인수권`, `권리공모`, `청약`

- 권리 배정, 행사, 미행사 만료, 청약 현금 예치가 필요하다.
- 현재 계좌 모델은 체결 즉시 결제 모델이라 권리 행사 원장을 별도로 추가해야 한다.

`특별배당`

- 현금배당과 거의 같은 엔진으로 처리할 수 있다.
- 별도 타입으로 늘리기보다 `CASH_DIVIDEND`에 description 또는 dividend category를 추가할지 먼저 결정한다.

`자사주 매입`, `자사주 처분`

- 시장 전체 수요/공급과 회사 보유 자기주식 원장이 필요하다.
- 사용자 보유/계좌 원장과 직접 연결되는 기본 기능이 아니므로 보류한다.

`합병`, `인수`, `주식교환`, `주식이전`, `분할`, `스핀오프`

- 종목 간 교환비율, 신규 종목 생성, 기존 종목 종료, 보유 전환이 필요하다.
- 현재 프로젝트의 단일 주문장 종목 모델을 넘어가므로 후순위다.

`종목명 변경`, `종목코드 변경`

- 거래 원장에는 영향이 작지만 심볼 rename은 모든 foreign key/reference를 건드린다.
- 내부 immutable symbol 정책을 먼저 정한 뒤 별도 alias table로 처리한다.

`거래정지`, `거래재개`

- corporate action이라기보다 시장 운영 상태다.
- 이미 `MarketSessionStatus`의 `HALTED`로 최소 구현되어 있으므로 기업 이벤트 타입으로 중복 구현하지 않는다.

`해산`, `청산`, `파산`

- 열린 주문 취소, 가격 표시, 보유 가치 처리, 현금 배분 정책이 필요하다.
- 상장폐지는 `ZERO_VALUE` 방식으로만 구현했다. 해산/청산/파산은 현금 배분 정책이 별도로 필요하므로 보류한다.

## 다음에 바꿀 순서

초기 필수 범위 안에서 다음 작업을 한다면 순서는 아래가 맞다.

1. 기업 이벤트 기준일 명확화
   - 배정 기준일, 권리락일, 지급일, 신주상장일의 의미를 화면 label과 API field에 정확히 맞춘다.

2. 열린 주문 정책 강화
   - 가격/수량을 조정하는 이벤트는 열린 `ORDER_BOOK` 주문이 있으면 적용 대기 또는 관리자 거부를 유지한다.
   - 정책을 이벤트별로 문서화하고 테스트한다.

3. 단주 정책 명시
   - 현재는 정수 주식만 배정하고 단주 현금 보상은 없다.
   - 이 정책을 UI에 숨기지 말고 관리자 문서와 테스트에 남긴다.

4. 특별배당 처리 방식 결정
   - 별도 enum을 만들지, `CASH_DIVIDEND`의 category로 볼지 결정한다.
   - 초기에는 새 enum 없이 `CASH_DIVIDEND`로 충분하다.

5. 감자/액면병합 설계만 보류 문서화
   - 구현은 하지 않고, 필요한 원장과 단주 보상 정책만 설계 후보로 남긴다.

## 구현 판단 기준

새 기업 이벤트를 추가하기 전 아래 질문에 모두 답할 수 있어야 한다.

- 어떤 날짜에 어떤 상태로 전이되는가?
- 주식 수, 유통주식수, 보유수량, 예약수량, 평균단가, 현재가 중 무엇이 바뀌는가?
- 열린 주문은 취소, 대기, 가격/수량 조정 중 무엇을 해야 하는가?
- 사용자별 entitlement가 필요한가?
- 단주가 발생하면 버릴지, 현금 보상할지, 소수점 주식을 허용할지 결정했는가?
- MySQL full DDL, H2 DDL, back entity, batch SQL, front type이 모두 바뀌는가?

하나라도 답이 없으면 초기 프로젝트에서는 구현하지 말고 roadmap 후보로만 둔다.
