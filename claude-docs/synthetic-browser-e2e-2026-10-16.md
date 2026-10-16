# Synthetic 실제 브라우저 거래 E2E — 2026-10-16

## 범위와 격리

사용자가 Synthetic 실제 Chrome → Spring Boot → PostgreSQL → Anvil의 Faucet/BUY/SELL/개인 WS/포트폴리오/두 대사를 승인했다. Kiwoom/NXT 실환경 검증, 유료 AI, 기존 데이터 변경은 제외한다.

기존 `playground.py`는 단일 pointer와 고정 포트를 사용하고 기존 playground는 실제 사용 중이다. 기존 `kiwoom-live-browser.mjs`는 KIWOOM 준비 조건이 고정되어 있다. 따라서 원본 도구나 제품을 수정하지 않고 ignored runtime의 일회성 fixture adapter로 기존 이미지·Compose 설정·브라우저 흐름을 재사용한다.

- 기존 playground 이미지의 소스 해시는 현재와 다름. 기존 playground를 업그레이드하거나 재사용하지 않았다.
- KRX 준비 run metadata의 `sourceHash`는 현재 `playground.source_hash()`와 일치. 이미지 ID 6개를 읽기 전용 대조하고 같은 불변 이미지로 새 fixture를 구성한다. 제품/컨트랙트/웹 빌드, 이미지 태그 변경 없음.
- 기존 KRX secret이나 baseline은 가져오지 않는다. 새 DB/chain 디렉터리, 키, 비밀번호, 실행 ID, 포트, baseline을 사용한다.
- backend는 internal Docker network에만 연결하고 SIMULATED 및 AI 비활성으로 실행한다. 웹은 별도 host-access network를 통해 localhost에만 공개하며 Nginx로 내부 backend를 호출한다.
- 기존 브라우저 runner 사본은 `ready()`의 KIWOOM 조건 한 곳만 SIMULATED로 바꾼다. 원본 파일·공개 API 계약·제품 시세 정책 변경 없음.
- 새 project만 stop하고 DB/chain/증거를 보존한다. 기존 컨테이너/네트워크 삭제·정지 없음. commit/merge/push 없음.

## 사전 검증과 최초 실패

- Docker desktop-linux Server 28.4.0 정상 응답.
- 기존 playground 도구 단위 테스트 8/8 PASS; fixture adapter AST와 기존 브라우저 JS 구문 검사 PASS.
- 별도 검토자 `review_acceptance_baseline`: 실행 전 안전성 검토에서 발견된 필수 수정 없음. 실제 실행 결과를 검토한 것은 아니다.
- 첫 run `synthetic-browser-20261016040719-15a571ca`: Docker `all predefined address pools have been fully subnetted`로 네트워크 생성 단계 실패. DB/Anvil/거래 미실행. 기존 환경 변경 없이 실패 로그 보존.
- runtime adapter만 기존 network CIDR 읽기 + 8개 후보 중 비중첩 신규 /24 2개 선택으로 보완. 기존 network 삭제·수정 없음. AST/overlap 경계 검사 PASS. 별도 재검토: 발견된 필수 수정 없음.

## 새 실행

`synthetic-browser-20261016040925-6345eba4`를 새 fixture로 실행했다. 신규 PostgreSQL/Anvil 건강 상태, 새 계약 배포, source/image/mount/internal-only 격리 확인, baseline 생성 및 테스트 사용자 등록과 Synthetic market 조회는 완료했다.

최초 실제 Chrome 실행은 04:11:03Z~04:11:38Z에 `WAIT_TIMEOUT`으로 중단됐다. HTTP 증거는 시장/차트 200 각각 2회이며 로그인 요청, userId, Order는 없다. 최초 runner는 HTML auth-open 요소 존재만 기다리므로 JS handler 초기화 전 click 가능성을 가설로 분리했다. 제품 결함으로 확정하지 않았다. 신규 서비스 4개는 모두 stop됐고 최초 증거는 `evidence/`에 보존했다.

새 runtime resume adapter는 동일 신규 fixture만 source/image/mount, 주문 0, 기존 baseline payload 및 canonical blockHash를 대조한 뒤 재시작한다. browser 사본은 초기 대기를 onclick/onsubmit handler 준비로 보강하고 오류 시 비밀값 없는 UI 상태만 관측한다. 원본 browser/제품은 변경하지 않고 `evidence-retry/`를 따로 사용한다. 마지막 별도 안전성 재검토: 발견된 필수 수정 없음, 직접 AST PASS. 실제 Docker/브라우저 결과는 검토자가 재실행하지 않았다.

증거는 각 run의 ACL 제한 `logs/`, `evidence/`, `baseline.json`에 보존한다. 테스트 계정·Chrome profile 등 private 자료는 Git/공유 보고서에 포함하지 않는다.

## 실제 Chrome 재검증 결과

2026-10-16 04:14:57.561Z~04:15:28.074Z(한국 13:14:57~13:15:28), 실제 설치된 Chrome headless의 CDP를 이용했다. fixture HTTP mock이나 API-only 거래가 아니다. 로그인 입력, Faucet 클릭, 견적 요청 및 확인창 제출은 브라우저 DOM을 통해 실행했다. 주문 완료 polling과 ADMIN 대사는 기존 runner의 실제 REST 호출을 재사용했다. ADMIN 화면 클릭 인수는 아니다.

| 단계 | 결과 | 근거 |
|---|---|---|
| Synthetic 시세/1분봉 | PASS | provider SIMULATED / priceStatus SIMULATED, 차트 HTTP200 |
| USER 로그인 | PASS | 실제 브라우저 로그인, me USER/userId2, 개인 STOMP 구독 |
| Faucet | PASS | 브라우저 요청 1회, 신규 지급 1,000,000 mKRW |
| 신규 서명 견적/BUY | PASS | 100,000 mKRW 입력, Order1 ONCHAIN/FILLED |
| 신규 서명 견적/SELL | PASS | 보유 1.328883425982269010 mSEC 전량, Order2 ONCHAIN/FILLED |
| 개인 WS | PASS | 주문2건 각각 FILLED 및 최종 portfolio 이벤트와 portfolio REST 일치 |
| 포트폴리오 | PASS(API/개인 WS) | 최종 mSEC0, 평균 매수가0, 총 평가액=최종 mKRW |
| 장부-체인 대사 | PASS | MATCH / EXACT_QUANTITY_MATCH |
| 거래 전수 감사 | PASS | COMPLETED/MATCH; 세부 coverage 및 Receipt는 아래 추가 대조 기록 |

BUY txHash: `0x10fc0ece0f160083783fdbc1763b6bb73d22bf596bffe3751c7ec26f413f253f`.

SELL txHash: `0xe52f3e4348550c830358e3fccd6e7f2dc48acff76b47eb5d03aed00251a9e317`.

BUY 후 포트폴리오 mKRW900,000/mSEC 약1.328883425982269. SELL 후 API mKRW 약999,792.284050297/mSEC0. 원장 정확한 소수는 DB 추가 대조를 따른다. 손실액을 오류로 판정하지 않는다. 매수·매도 수수료 및 두 시점의 합성 가격 변동이 반영된다.

첫 실패의 JS 초기화 race는 handler 대기 보완 후 같은 fixture/동일 제품 이미지에서 성공한 결과가 뒷받침하지만 타이밍을 강제 재현한 단독 실험은 하지 않았다. 따라서 확정 제품 장애가 아닌 테스트 준비 순서 가설로 남긴다. 제품 수정이 필요한 결함은 이번 실행에서 확인되지 않았다.

## 검증 한계

### 실행 후 PostgreSQL/Anvil 읽기 전용 추가 대조

해당 신규 fixture의 PostgreSQL/Anvil만 잠시 기동해 원장과 실제 Receipt를 읽고 다시 정지했다. backend/web은 추가 기동하지 않았고 추가 주문·대사·baseline 생성도 하지 않았다.

- DB: Order1/2 모두 ONCHAIN/FILLED, Trade2건, FaucetGrant1건, Quote2건 모두 CONSUMED.
- 최종 mKRW `999792.284050296936483318`, mSEC `0`. 두 자산 lockedAmount 모두 `0`.
- BUY Receipt `status=0x1`, block4, blockHash `0xaa46feee590ed015cadab79ace52b0a21c3b6cb91712c48626000a10b7b1996b`, logs5.
- SELL Receipt `status=0x1`, block5, blockHash `0x9e62c6fb9fdd24cee19a80b6afc80fd8f7aa5b0cc4f3bbd1c646c56b2324fed7`, logs5.
- 감사 결과: sourceOrders2/scannedEvents2/inspected2, dbComplete/chainComplete/cutStable/environmentVerified 모두true, rpcCalls26. 두 item 모두 MATCH, 모든 개별 check 일치, limitations 빈 배열. Vault 및 Oracle 이벤트/서명/실행 입력/출력 검증 근거를 포함한다.
- 개인 이벤트7건: Faucet portfolio1, BUY pending/filled/portfolio3, SELL pending/filled/portfolio3. 최초 포트폴리오와 최종 포트폴리오 모두 실제 개인 WS로 수신했다.
- `sourcePreserved=true`, 외부 provider 호출0. 정제 요약은 `evidence-retry/final-verification.json`에 보존한다.

### 미실행 범위

- 실제 Kiwoom/NXT/Toss 및 AI 호출은 0회. Synthetic PASS를 실환경 PASS로 확대하지 않는다.
- 개인 WS는 실제 브라우저 수신 frame과 API 결과를 비교했다. 최종 포트폴리오 DOM 숫자 및 모바일/시각 디자인의 별도 assertion은 없다.
- 즉시 BUY/SELL 정상 경로의 1사용자 E2E다. 다중 사용자 격리/장애 주입/조건부 주문/재시작 복구/전체 backend·Foundry 회귀를 새로 실행한 결과는 아니다.
- 독립 검토는 runtime adapter 안전성의 최초1회·재검토2회이며 실제 Docker/Chrome 결과 재실행은 구현자가 수행했다.
