# 공개 데모 Synthetic Market 설계안

> 2026-10-06. 아래 최초 설계 후 사용자가 축소 범위 구현을 승인했다. 현재 구현/검증 상태는 마지막 절을 우선한다. 실제 .env와 기존 운영 DB/체인·외부 인프라는 변경하지 않는다.

## 확정 경계

- 공개 환경은 `PRICE_PROVIDER=simulated`. Toss provider와 실제 연동 기능/테스트는 그대로 유지하고 공개 환경에서만 미등록·미접속한다. 공개 배포에 Toss 키나 기존 Toss 가격 데이터를 복사하지 않는다.
- 실제 시장 데이터를 다운로드/복제/보간/replay하거나 삼성전자 분포에 맞춰 학습하지 않는다. 자체 규칙으로 만든 synthetic market이다. 기존 mSEC/mKRW·REST/STOMP·서명 견적 계약은 유지한다.
- 이 결정은 공개 공급자 문의를 선행 조건에서 제외하지만, 실제 배포 인프라 생성이나 개선 구현을 승인한 것은 아니다. 실제 데이터 replay도 이번 범위가 아니다.

## 현재 코드 분석

| 항목 | 실제 구현 | 공개 데모에서의 한계 |
| --- | --- | --- |
| 가격 | SimulatedPriceProvider가 기본 1초마다 이전 가격에 균등 난수 ±0.3%를 곱함 | 시간 단위에 맞춘 변동성 설정 없음. 짧은 시간에 누적 변동이 큼. 독립 난수라 조용한/활발한 구간 구분 없음 |
| tick/거래량 | 매 tick 가격 이벤트 발행, volume 항상1 | 거래량은 수량이 아닌 tick 수. 활동량 변화 없음 |
| 전일 대비 | previousClose에 직전 tick 가격 저장 | UI의 전일 대비 설명과 불일치. 기존 테스트도 이 동작을 기대함 |
| 장/상태 | UNKNOWN / SIMULATED, 휴장 없이 거래 가능 | 공개 데모의 합성 24시간 시장이라는 설명이 필요 |
| 차트 | SimulatedCandleProvider가 매 tick 1m/1d OHLCV를 DB에 저장, 상위 분봉은 1m 집계 | 6주기는 이미 지원. 기동 이전 합성 이력이 없고 중단 중 봉도 없음 |
| 재시작 | 가격/난수 상태는 메모리, 75,000으로 초기화 | DB 캔들은 남으므로 과거 종가와 새 현재가가 불연속 |
| 확정 순서 | current.set 후 캔들 DB 저장, 그 다음 WS | DB 실패 시 현재 가격과 차트가 어긋날 수 있음 |
| REST+WS | CandleLoadBuffer가 로딩 중 tick을 전부 합산 | REST에 이미 포함된 tick 거래량을 중복 합산할 여지가 있음. 현재 응답에는 snapshot watermark가 없음 |
| 견적 | PriceReportIssuer가 5초 신선도·미래2초·관측시각+30초 만료 검사 | simulated에도 같은 서명 흐름 사용 가능. 과거 초기화 가격을 현재 관측으로 서명하면 안 됨 |
| UI | 공급자 문구는 구분하지만 상품·header·설명은 삼성전자 가격 추종, 거래량은 tick 수 표기 | 공개 합성 가격을 실제 삼성전자 시세로 오해할 수 있음 |

현재는 거래 파이프라인을 검증하는 최소 시뮬레이터이며, 실제 수요·공급/호가창을 재현하는 거래소 엔진은 아니다. `/ticks`는 별도 PriceTickRepository 조회이고 현재 simulator는 raw tick을 그 테이블에 저장하지 않는다. 기존 가격 이력과 합성 tick을 같은 것으로 주장하지 않는다.

근거: `backend/src/main/java/com/pricetrack/exchange/market/provider/simulated/`, `market/MarketCandleAggregator.java`, `MarketCandleEntity.java`, `MarketController.java`, `blockchain/oracle/PriceReportIssuer.java`, `tools/websocket-test-client/src/candles.js`, `chart-controller.js`, `main.js`, `index.html`. 현재 simulated 가격 테스트1개는 난수 범위/직전 tick 대비를, 캔들 테스트3개는 OHLCV/페이지/5m 집계를 검증한다. 이번에는 테스트를 실행하지 않았다.

## 제안 구조

```text
Clock + seed + version + persisted state
                  ↓
        SyntheticMarketEngine (순수 생성 규칙)
                  ↓ tick(price, synthetic quantity, time, sequence)
      상태 checkpoint + 1m/1d OHLCV 원자 저장
                  ↓ commit 이후
     current snapshot / 공개 가격 STOMP
         ├ 기존 MarketPriceService → 서명 견적 → Vault
         └ 기존 CandleProvider → 6주기 REST → 웹 차트
```

### 1. 가격·활동량

- 기본 1초 단위 한 tick/이벤트를 유지한다. 간헐적 동일 가격도 정상 tick이며 수량은 누적한다. 서명 견적의 5초 안전 경계를 맞추기 위해 처음부터 수초 이상 무관측 구간을 만들지 않는다.
- 로그 가격에 시간 간격의 제곱근에 비례하는 noise를 적용하고, 수분 단위로 지속되는 저/중/고 활동 상태를 둔다. 상태 전환은 완만하며 큰 변화가 연속 무작위 점프로 이어지지 않게 한다. 가격은 양수·정밀도·상하 안전 경계를 가진다.
- 초기 튜닝 후보는 24시간 기준 변동성 약1~3%, 드문 작은 shock와 짧은 추세 구간이다. 이는 삼성전자 통계가 아닌 임의의 데모 설정이며 구현 실험으로 조정한다. 장기적인 무조건 상승 drift나 수익 보장 패턴은 두지 않는다. 상한/하한 도달 때 단순 가격 reset 대신 변화량을 제한한다.
- 합성 거래량은 활동 상태와 절대 가격 변화 크기에 연동된 비음수 가상 수량으로 생성한다. 분포가 한 값에 고정되지 않게 하고 드문 거래량 burst도 둔다. 수량 단위는 합성 mSEC 수량이고 실제 삼성전자 거래량이나 사용자 주문 체결량이 아니다.
- 사용자 주문은 가격 경로를 바꾸지 않는다. 기존 Vault 상대 거래를 유지하며 호가창/매칭 엔진·봇 주문·가짜 Trade 행을 만들지 않는다. 공개 체결 목록에는 실제 서비스 사용자 체결만 표시한다.
- seed/version/시간 입력이 같으면 같은 결과. 공개 모델의 예측 가능성은 모의 데모의 한계이며 seed를 금융 보안 장치로 취급하지 않는다. 개발용 Clock/seed 주입으로 테스트는 난수에 흔들리지 않는다.

### 2. 차트·전일 대비

- 공개 데모는 주말 포함 24시간 합성 시장. 일 경계는 기존 KST00:00, 원본 시각은 UTC, UI는 KST 표시. KRX 장시간·공휴일·호가단위 규칙을 실제 시장인 것처럼 적용하지 않는다. 정상 상태는 OPEN/SIMULATED 제안.
- 모든 봉은 동일 생성 tick에서 만든다. 1m→5m/15m/30m/1h, 1d는 동일 tick의 하루 OHLCV. 다른 주기마다 별도 난수를 생성하지 않는다.
- previousClose는 직전 KST일의 합성 종가. change/changeRate도 그 기준으로 고친다. 하루 중에는 고정, 자정에 교체한다. 실제 Toss의 의미는 바꾸지 않는다.
- 최초 공개 DB에 과거30일 합성 이력을 한 번 생성하는 안을 제안한다(설정 상한90일 후보). 기본30일이면 일봉도30개이며 제공하지 않은 기간을 채워 보이지 않는다. tick 전체를 DB에 보관하지 않고 스트리밍 집계 후 1m/1d와 최종 엔진 상태만 batch 저장한다.
- 동일 엔진으로 역사와 현재를 연결하며 생성 이력은 synthetic으로 표기한다. bootstrap은 제한된 별도 작업, chunk별 재개 가능한 초기화로 두고 완료 전 견적을 막는다. 최종 활성화 시각 이후 live tick만 WS에 발행한다. 과거 tick을 live 이벤트/서명 견적으로 발급하지 않는다.
- 시간/CPU/DB 사용량을 측정해 bootstrap 상한과 batch 크기를 확정한다. 초기 과거 이력 생성은 공개 신규 원장에만 명시 활성화하며 기존 개발 DB에 자동 채우기/덮어쓰기를 하지 않는다.

### 3. 영속 상태·안전한 시작/중단

- simulated 전용 상태(엔진 version/seed/parameter hash/마지막 시각·순번·가격·활동/변동성 상태·전일 기준)를 거래 DB에 별도로 저장한다. 캔들·checkpoint는 같은 로컬 DB 트랜잭션에서 저장하고 commit 후 현재가/이벤트를 공개한다. AI DB에 시장 상태를 넣거나 분산 트랜잭션을 만들지 않는다.
- 한 backend/생성 writer만 운영한다. 중복 생성은 checkpoint 잠금/순번과 봉의 unique 경계로 막는다. commit 실패면 메모리 상태·순번도 진전시키지 않는다. commit 직후 프로세스 종료로 WS 유실은 가능하며 REST가 정본이다.
- 재시작은 checkpoint에서 복원하고 75,000으로 reset하지 않는다. 중단 시간은 같은 생성 규칙으로 제한된 catch-up 집계 후 실제 현재 시각에 재개한다. catch-up 중에는 INITIALIZING/STALE와 견적 차단, 과거 이벤트 폭주 없음. 오래 중단한 경우도 사용자 잔고/체인을 초기화하지 않고 제한된 작업으로 재개하거나 운영자 확인을 요구한다.
- 기존 simulated 캔들에는 엔진 상태가 없다. 기존 데이터가 있는 DB에서 silent bootstrap/reset을 금지하고 명시 migration/새 공개 DB 중 선택한다. 모델 version·seed 변경도 기존 이력 덮어쓰기 없이 거부하거나 별도 승인된 namespace 전환이 필요하다.
- DB 실패·생성 정지 시 관측 시각을 갱신하지 않는다. 오래된 값은 STALE로 표시한다. 기존 온체인5초 검증을 유지하고 DB-only mock 경로 역시 초기화/장애 상태에서는 새 견적·주문을 막는다. 필요한 초기화 guard는 simulated 경계에 제한하며 Toss 정책을 완화하지 않는다.

### 4. REST/STOMP 계약의 최소 보완

- endpoint/destination/기존 필드는 유지. 합성 모드에 선택적 `simulationId`/`sequence`, 캔들 응답의 동일 snapshot 기준 `asOfSequence`/`asOf`를 추가하는 안을 제안한다. 캔들 조회의 값과 watermark는 일관된 DB snapshot으로 읽는다.
- 클라이언트는 REST 기준 이하의 buffered tick을 다시 더하지 않고 중복·역순도 제외한다. 순번 gap/시뮬레이션 identity 변경은 REST 재동기화한다. Toss는 이 metadata가 없는 기존 계약 경로를 유지한다.
- metadata로 volume 중복 문제를 숨기지 않는다. 로딩 시 전체 봉과 watermark의 일치, reconnect 동안 유실, 과거 페이지 합치기, 주기 변경을 테스트한다. 공개 `/ticks`를 무제한 raw 합성 이력 API로 확대하지 않는다.

### 5. 서명 거래·공개 설정·UI

- 기존 snapshot이 simulated라도 현재 runtime tick이므로 기존 PriceReport+signature 흐름을 그대로 사용한다. quoteId만 제출, 서버 DB의 보고서/서명, 사용자 소유/방향/입력/만료/상태/일회 소비, 운영자 executor와 서명 키 분리, 원자적 잠금·RPC 복구를 보존한다. updatePrice 경로는 복구하지 않고 컨트랙트 ABI/30초 TTL도 변경하지 않는다.
- public 배포 profile/guard에서 simulated만 허용하고 Toss 키를 주입하지 않는다. Toss Bean 미등록과 네트워크 호출0을 검사한다. 로컬 Toss .env는 수정하지 않는다. 공개 DB는 신규 전용이고 로컬 Toss DB/이력을 재사용하지 않는다.
- UI header·시장·차트·거래 확인에 눈에 띄는 `SIMULATED · 합성 시장` 표시. 시장 실패/로딩 중에도 공개 모드 표시는 없어지지 않게 한다. 시장·캔들 provider가 불일치하면 정상 차트/거래인 것처럼 표시하지 않는다.
- 공개 카피: “자체 생성한 합성 가격·거래량으로 거래하는 mSEC 모의 시장입니다. 실제 삼성전자 시세가 아닙니다.” 전일 대비는 “합성 전일 대비”, 거래량은 “합성 거래량”으로 구분. 포트폴리오 손익도 모의 자산 평가다.
- 별도 기술 설명: “Toss REST 초기 시세 조회·실시간 WebSocket 연동은 별도 로컬 환경에서 검증했습니다. 공개 데모에서는 비활성화되어 있습니다.” 실제 장중 브라우저 매수·매도 전체 수동 인수까지 완료했다고 쓰지 않는다.
- 일반 소개·meta description에서도 현재 공개 가격이 삼성전자를 추종한다고 쓰지 않는다. mSEC와 계약/토큰 이름은 호환성을 위해 유지하되 삼성전자 연동은 프로젝트의 별도 실제 연동 모드로 설명한다.
- 구현 후 영향받는 AI 시장/상품 지식과 manifest/hash/index, career brief/runbook도 실제 상태에 맞춰 갱신·검색 회귀한다. 지금은 active 지식/색인을 바꾸지 않는다.

## 승인 후 작업 단위와 검증

1. 순수 엔진·튜닝: seed/Clock 결정성, 시간 간격 변동성·활동 지속·거래량 변화·반복 가격·양수/유한 값/범위. 긴 기간 여러 seed 실험 결과와 CPU 시간을 기록한다. 실제 시장 재현/정규성/수익성을 보장하는 시험이 아니다.
2. provider·DB·차트·견적: 6주기 OHLCV 동일성/경계·전일 대비·bootstrap/중단 catch-up·멱등/재시작/rollback·version 불일치, PostgreSQL snapshot/watermark와 fresh/stale/만료·Anvil 매수/매도/RPC 실패 회귀.
3. UI·공개 격리·지식: 항상 보이는 합성 표기, Toss 비활성/호출0, REST+WS volume 중복0/순번 gap/주기 변경/재접속, 모바일/권한/AI 장애 경계와 기존47웹 회귀. 영향받는 지식/승인 index 검색 회귀.

각 구현 작업 단위는 승인 범위 내 검증·독립 검토·문서 갱신과 커밋 메시지를 제공한다. 최종 backend전체/forge/웹 build·browser 및 PostgreSQL+Anvil 합성 종단간 검증. 실제 외부 배포/유료 자원 생성은 여전히 별도 승인이다.

새 검색 서버/Redis/Kafka, 실제 공급자 추가, 예약 주문·수요 공급 매칭, 실데이터 replay, AI 거래 실행, 공개 체인 전환은 제외한다. 승인 요청은 위 합성 엔진+영속 상태/초기 합성 이력+최소 chart metadata+UI/공개 guard 범위이며 인프라 배포를 포함하지 않는다.

## 축소 승인과 실제 구현 (2026-10-06)

사용자는 가격/수량·결정적 seed/Clock·previousClose·checkpoint/재시작·commit 이후 공개·public Toss 호출0·UI·기존 EIP-712 보존을 승인했다. 30일/90일 bootstrap과 전체 downtime catch-up을 제외했다. simulationId/sequence/watermark는 기존 식별자로 해결 불가능한 부분만 최소 보완하도록 승인했다.

### 코드와 운영 설정

- `SyntheticMarketEngine`: seed+step별 독립 난수 스트림, 시간에 맞춘 로그 가격 변화, 완만한 activity/trend와 가상 수량. seed와 Clock 입력의 결정성, version과 상태를 보존. 실제 데이터나 봇 주문·Trade 행을 생성하지 않는다.
- `SyntheticMarketState`/repository/schema: 종목1개 checkpoint와 엔진 version/seed/step/가격/전일 기준/활동/추세·관측 시각. pessimistic row lock과 봉 변경을 동일 REQUIRES_NEW 트랜잭션에서 commit한다. provider는 commit 성공 후 current를 교체하고 WS에 발행한다. 기동 상태는 REST로 노출하고 초기화 이벤트는 발행하지 않는다.
- 공개 profile에서만 최초 빈 캔들 DB에 과거2일(KST 자정 기준, 현재일 구간 포함) 합성 이력을 생성. 최소 과거 일봉2개·1h 탐색·전일 기준을 제공한다. 15초 해상도의 동일 엔진으로 합성 prehistory를 만들고 1m/1d로 저장하며 live는 기본1초. 역사 생성과 현재 상태가 연결된다. 실제 tick history로 주장하지 않는다.
- 로컬 기본 bootstrap0. 캔들만 있는 legacy simulated DB는 최신 종가와 이전 일봉 기준을 채택하고 기존 행을 보존한다. seed/version 불일치이면 생성 실패이며 자동 reset하지 않는다. 공개는 기존 Toss/개발 데이터가 없는 신규 전용 DB를 사용한다.
- 긴 중단 후에는 저장된 상태의 다음 bounded step 하나만 실제 현재 시각에 생성한다. 중단 기간 봉·이벤트는 만들지 않는다. 장시간 catch-up/30일 bootstrap은 미구현·이번 범위 제외. 중단을 가로질러 표시되는 봉은 관측 구간만 포함하며 gap 없는 시장 기록이라고 설명하지 않는다. 전날 무관측이면 마지막 관측일 가격을 기준값으로 carry하며 실제 전일 시세라고 주장하지 않는다.
- 생성 전 INITIALIZING/CLOSED, 5초 이상 갱신이 없으면 STALE. 관측 timestamp는 브라우저와 동일 millisecond precision으로 단조 증가한다. 같은 시각/clock rollback이면 checkpoint/WS mutation 없음.
- `application-public.yml`은 provider를 simulated로 고정한다. public guard는 더 높은 우선순위에서 toss가 강제되면 client 등록 전에 시작을 거부한다. Toss 구현과 로컬 실제 .env는 변경하지 않는다. public credential 주입은 배포에서 금지한다.

### REST/STOMP 최소 보완 판단

eventId는 WS끼리 중복만 식별한다. bucket 시각은 봉 시작 경계이므로 봉 내부 어느 tick까지 REST에 포함됐는지 알 수 없다. occurredAt는 이벤트 생성 시각으로 DB snapshot 관측 시각이 아니다. 따라서 추가 simulationId/sequence는 넣지 않고 **캔들 응답에 optional asOf 하나**를 추가했다. 기존 필드/endpoint/STOMP envelope는 그대로다.

SimulatedCandleProvider의 REPEATABLE_READ 트랜잭션에서 checkpoint 관측 시각과 OHLCV를 같은 DB snapshot으로 조회한다. 웹은 asOf 이하의 buffered tick을 합산하지 않으며 같은 합성 관측 시각/역순은 중복으로 제외한다. 이후 현재 tick의 timestamp를 cutoff로 유지한다. Toss는 asOf=null인 기존 경로로 동일 시각 여러 체결을 보존한다. 단절 후 기존 REST 재동기화가 정본이며 공개 sequence나 영속 이벤트 로그는 없다.

### UI와 거래 경계

header/provider/거래 확인에 SIMULATED·합성 시장, 합성 전일 대비·합성 거래량, 자체 생성 과거 봉/24시간 시장 설명. Toss REST+WS 별도 로컬 검증과 공개 비활성, 장중 전체 수동 인수 한계를 구분한다. meta/공개 소개에서 현재 합성 가격을 삼성전자 실제 시세라고 쓰지 않는다.

컨트랙트·주문·quoteId·PriceReportIssuer·서명자/executor 키 분리·5초/30초·receipt 복구는 변경하지 않았다. 기존 mock 거래는 유지하며 초기화/STALE 상태에서 신규 거래가 차단된다. 기존 TradingIntegrationTest는 원래75000 고정 계산 assertion을 유지하도록 시장 fixture를 명시해 합성 경로 변경과 거래 계산 회귀를 분리했다.

### 검증 상태

- 결정적 엔진4, public guard2, persistence6(롤백/재시작/seed·Clock/6주기/최소 이력·STALE/시각 회귀), 실제 PostgreSQL+임시 Anvil E2E1 통과. H2 고정 시각2일 bootstrap은 한 실행에서778ms였으며 운영 SLA/production startup 성능을 보장하지 않는다.
- 실제 E2E는 `exchange_synthetic_test`와18545의 테스트 Anvil만 사용. Native STOMP 실제 가격, 공개6주기/asOf, JWT 가입/faucet, 실제 서명 BUY/SELL·receipt/DB 체결가, quote 재사용409·잠금 해제를 확인했다. 기존5432/exchange 사용자 데이터·8545 체인은 초기화하지 않았다. 전용 검증 원장은 남겨두며 임시 Anvil은 검증 종료 후 정리한다.
- 웹 기존47+신규1=48통과, build90modules, Chrome desktop/mobile fixture smoke 통과. forge -q 통과. fixture browser는 실제 DB/체인 통합 브라우저 수동 인수를 대체하지 않는다.
- 최초 public guard 테스트의 root-cause assertion, 신규 E2E 테스트의 기존 Trade 조회 메서드 오인·bootstrap 고정시각 기대를 바로잡았다. 전체 회귀의 기동 가격 알림/정산 publisher 무호출 assertion 충돌은 기동 시 WS 미발행으로 보완했다. 테스트 임계값/거래 규칙을 완화하지 않았다.
- 최종 전체 backend346 중333통과/13skip/실패·오류0. SYNTHETIC_E2E_TESTS/AI_PGVECTOR_TESTS/AI_TOOL_POSTGRES_TESTS/AI_TOOL_ANVIL_TESTS/AI_AGENT_ANVIL_TESTS=true, AI_TOOL_TEST_RPC_URL=http://127.0.0.1:18545로 실행했다. 13개는 별도 opt-in/유료·외부 환경 조건 미활성 항목이며 전체346개를 모두 실행했다고 쓰지 않는다. 실제 pgvector/거래 PostgreSQL·Anvil 읽기와 새로운 합성 E2E를 포함한다.
- 기존 ToolAnvilIntegrationTest는8545의최근200블록에 체결 event가 없어서 첫 확장 실행에서 실패했다. 테스트 RPC를 환경 변수로 선택 가능하게 해 준비된18545체인을 읽도록 했고 원래 MATCH/체인·DB불변 assertion은 유지했다. 기존 체인에 검증용 거래를 만들지 않았다.
- Chrome smoke 만료 assertion은 고정1300ms sleep이1초 UI timer phase에 따라 실패할 수 있어 기존 bounded waitText로 실제 만료 문구를 기다린 뒤 버튼 disabled를 검증하도록 보완했다. 합성 표기3개와 기존 모든 fixture 흐름을 최종 재실행 통과했다.
- 제품·테스트·문서 초안을 동결하고 독립 검토를 요청한다. 구현 완료 승인/외부 배포 완료로 아직 표시하지 않는다.

### 남은 범위

실제 외부 배포/secret·HTTPS/영속 운영 backup과 장기/다중 instance 부하는 별도다. 30일 이력/장기간 catch-up/sequence는 도입하지 않았다. 기존 AI active 지식/manifest/index는 축소된 이번 구현에서 변경하지 않았고 실제 공개 운영용 지식 갱신·재색인은 배포 준비 단계에서 확인한다. AI 도구는 runtime provider/상태를 그대로 조회한다.

### 독립 검토와 완료 보고 (2026-10-06)

- 별도 에이전트 `review_synthetic_market`가 코드·테스트·문서를 독립 확인했다. 축소 승인 범위 기준 **발견된 필수 수정 없음**. checkpoint/commit 경계, public Toss 미등록·강제 Toss 거부, asOf snapshot과 기존 서명 거래 보존을 확인했다.
- 검토자가 H2 엔진4/public2/persistence6/candle3 총15개와 웹48개를 직접 실행해 통과했다. 실행 전 전체 XML346/skip13/failure0/error0 및 PostgreSQL+Anvil E2E1/skip0 증거도 확인했다. 선택 실행 후 현재 XML은15개 결과이며 위 전체 수치는 직전 전체 실행 기록이다. 유료 평가·공유 DB/Anvil mutation·브라우저·forge는 검토자가 직접 재실행하지 않았다.
- 지적된 backend README의 옛 tick-count volume 설명을 가상 mSEC 수량 합계로 수정했다.
- 선택 UI 보완은 미구현으로 명시한다: 최초 REST/WS가 모두 실패하면 `SIMULATED` 대신 `시세 출처 확인 중`이며 실제 가격이라고 주장하지 않는다. 정상 가격 수신 후에는 합성 표시를 유지한다. 시장/차트 provider 불일치 차단도 없다. 현재 단일 backend의 같은 provider 설정을 전제로 하며 원안의 두 UI 경계까지 완료했다고 쓰지 않는다. 배포 준비에서 재검토할 항목이다.
- 축소 범위 구현·자체 통합 검증·독립 검토를 마쳤다. 사용자 완료 승인·외부 배포와 구분한다. 실제 `.env` 및 기존 DB/8545 체인은 보존했고 스테이징/커밋은 하지 않았다.
