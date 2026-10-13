# Phase A 통합 인수 보고서

> 2026-10-13 · **Phase A 미완료 / BLOCKED: 호스트와 신규 Docker DB의 시각 차이 및 실제 E2E 실패**. 승인 범위는 무과금 회귀와 신규 격리 PostgreSQL/Anvil뿐이다. B 실제 Toss, C 유료 AI, D 전체 최종 PASS는 미승인/NOT_RUN.

## 1. 기준 소스와 신규 run

- 입력 integration HEAD: `efb3a69aa9b01c585109df18fdd4de2675efb6c3`.
- 별도 worktree/브랜치: `feat/phase-a-acceptance`. 입력에서 미추적이던 `final-integration-acceptance-plan.md`는 내용 그대로 이 브랜치에도 보존했다. 사용자 변경을 제품 변경으로 취급하지 않는다.
- 본 실행 session: `phase-a-20261013043415-4a8b2dcc`. 각 reserve/audit/faucet/conditional E2E는 `fixtures/<종류>-<새random>` 하위에 독립 생성한다. DB/chain/state/keys는 이 session 안에만 있고 Git 제외다.
- 준비 실패 `phase-a-20261013040439-0cf8dafc`: 기존 Dockerfile.tools의 forge build가 캐시 compiler를 찾지 못해 network-none 빌드 실패. private build stderr 보존, 네트워크 허용이나 외부 다운로드로 우회하지 않았다. DB/chain 생성 전 실패다.
- 중간 도구 준비 session `phase-a-20261013043053-f45536da`는 source hash guard 보완 전 준비 기록이며 E2E에 사용하지 않았다. 최종 guard를 적용한 위 본 session을 새로 만들었고 이전 기록을 초기화하지 않았다.

## 2. 격리와 최소 harness 변경

제품 `backend/src/main`, 스마트 컨트랙트, 기존 보안·거래·정산 정책은 수정하지 않는다.

- `deployment/phase-a.py`: clean allowlist child 환경, gate 전체 off 기본값, 전용 source-built tool image, session productHash/image digest 재확인, 새 포트30000..49000, loopback publication, 독립 namespace/label·bind volume·키. 소유 label과 실제 port bindings를 테스트 전에 확인한다. 기존 서비스 중지/port 강제 확보 없음.
- `deployment/Dockerfile.phase-a-tools`: public tools image의 **solc executable 하나만** 네트워크 없는 새 build에 복사. 현재 계약 소스/lib/DeployPublic를 다시 `forge build --offline`. 과거 DB/state/runtime/키나 과거 contracts out을 복사하지 않는다. baseline·mKRW/ETH는 해당 새 private Anvil31337에서만 생성.
- `backend/src/test/java/com/pricetrack/exchange/support/PhaseAFixture.java`: 새 session/child 경로·runId·loopback ports·DB 이름·import off·Synthetic/AI-off를 검증. PostgreSQL의 새 `phase_a_fixture_identity`를 읽기 전용으로 대조한 뒤에만 Spring schema 초기화를 허용한다.
- 기존 E2E5개(reserve, audit, faucet, conditional, observation restart)에 Phase A 선택 경로를 추가하고 직접 RPC의 고정 포트를 검증된 fixture로 대체. 기존 legacy opt-in 경로는 유지하지만 이번에는 실행하지 않는다. 조건부 RPC proxy도 검증된 upstream을 사용한다.
- 기존 dotenv 자동 import는 child `SPRING_CONFIG_IMPORT`와 JVM property로 비활성화. 실제 Toss/OpenAI/AWS 자격증명을 child env에 전달하지 않는다. Java 외부 HTTP/HTTPS는 loopback1 proxy로 차단하고 localhost mock server는 예외다. 이 설정은 실행 harness에만 있고 제품 정책이 아니다.
- Python guard: 첫6개 PASS. 이후 clock 오차/round-trip 불확실성/명시적으로 오염된 child env 차단3개 추가해 **9/9 PASS**. 새 clock preflight는 기존 제품의 미래2초 한도를 변경하지 않고, 측정 불확실성을 포함해 그 한도를 넘으면 신규 배포/업무 schema/테스트 이전에 BLOCKED로 중단한다.

### 고정 endpoint 조사와 실행 경계

| 종류 | 기존 고정 경계 | Phase A 처리 |
|---|---|---|
| 선택 E2E | reserve25542/25545, audit/faucet/conditional/restart26542/26545 및 직접 HttpService | 새 session 경로+identity guard에서 가변 포트. 각 E2E 신규 fixture, restart만 conditional의 성공 데이터 읽기 |
| legacy blockchain Anvil | BlockchainAnvilIntegrationTest / BlockchainTransactionAnvilIntegrationTest의 기본8545 | BLOCKCHAIN_INTEGRATION_TESTS off. 실행하지 않고 SKIP 유지 |
| AI 실제 DB/chain/live | exchange_ai_test·exchange_tool_test,5432/5433/8545, 하드코딩 AiProperties와 actual provider URL | 해당 AI gates off. 기존 Mock/loopback fixture만. 유료 또는 기존 DB를 사용하는 suite는 실행하지 않음 |
| 기타 실제 PG | PriceQuotePostgresConcurrencyIntegrationTest의 DB_URL 기본개발DB, SyntheticMarketPostgresAnvilE2ETest의 전용고정fixture | gates off, 이번 필수5개와 구분. 기존 DB로 redirect하지 않음 |
| HTTP/WS mock | 127.0.0.1 또는 localhost의 ephemeral in-process server, mock BlockchainProperties8545 | loopback 테스트 서버/Mockito만 허용. 값이 실제 연결인지 단순 mock인지 코드로 구분 |
| 기존 배포 도구 | acceptance.py Toss 기동, ops.py 외부 secret/실제 환경, 기존 feature fixture scripts의 고정ports/image | 실행하지 않음. 신규 Phase A 도구만 사용 |

격리 evidence는 각 새 child `isolation.json`에 **테스트 시작 전에** 생성됐다. 초기 실행은 stdout/stderr 및 source/image inspect 결과만 private log에 보존했다. 독립 검토의 지적 후 operation/cwd/testSelector/offline/gate booleans/import-off의 **비밀 제외 metadata** 기록을 추가했다. full argv나 전체 env를 기록하지 않으며 초기 로그에 이 metadata가 있었다고 소급 주장하지 않는다. timeout partial stderr와 현재 실행 이후 mtime의 XML만 보존하도록 보완했다. 과거 XML을 이번 결과로 재사용하지 않는다.

## 3. 실행별 결과

기존427/35·웹84·Foundry36 결과를 이번 결과로 재사용하지 않는다. 실행 로그·XML을 `<session>/logs`, `evidence/<step>/xml|summary.json`에 별도 보존한다. 다음 Gradle 실행 전 현재 XML을 복사한다.

| 실행 | 상태 | 실제 수치 | 근거 |
|---|---|---|---|
| backend 전체 기본 | PASS (SKIP 별도) |462 total /427 PASS /35 SKIP /0 FAIL·ERROR | clean env/offline, 새 XML archive |
| 웹 unit | PASS |84 PASS /0 SKIP·FAIL | 이번 session web-unit private log |
| Chrome desktop·mobile fixture | PASS |build 및 smoke PASS | 실제 Toss/유료 AI/서비스네트워크 인수가 아님 |
| Foundry | PASS |36 PASS /0 SKIP·FAIL, fuzz 각256 | 현재 contracts 소스, host offline |
| Faucet PG/Anvil 첫 실행 | FAIL |1 test /0 PASS /1 FAIL /0 SKIP | faucet-fd2248f0 /DB30257/RPC34149. BUY 기대202 실제409 |
| Faucet PG/Anvil 공개code 재현 | FAIL |1 test /0 PASS /1 FAIL /0 SKIP | faucet-91ba6c59 /DB37287/RPC31348. OPERATOR_NOT_READY 재현, 제품 변경 없음 |
| reserve PG/Anvil | PASS |1 test /1 PASS /0 FAIL·SKIP | reserve-0b81cedb /DB36847/RPC32810 |
| audit PG/Anvil | FAIL |1 test /0 PASS /1 FAIL /0 SKIP | audit-1fb32089 /DB48444/RPC35526. SELL 기대202 실제409 |
| conditional/observation PG/Anvil | FAIL |1 test /0 PASS /1 FAIL /0 SKIP | conditional-999016bd /DB31332/RPC44101. WAITING/MARKET_UNAVAILABLE |
| 별도 JVM snapshot restart | NOT_RUN /BLOCKED |시도0 | conditional이 성공 fixture 전제에 도달하지 못함. fabricated snapshot으로 통과 처리하지 않음 |

각 독립 E2E에 새 DB/chain·포트·키·namespace를 사용했고, 두 Faucet 시도도 서로 다른 fixture다. 전체 회귀와 E2E를 합쳐 고유 테스트 수로 계산하지 않는다. 기본 회귀35 SKIP은 AI 관련26 및 비AI9 opt-in이다. 이번에 별도로 실행한 suite는 기본 XML의 SKIP 표기를 그대로 유지한다. H2/Mock에는 실제 PostgreSQL 동시성이나 실제 chain 성공을 주장하지 않는다.

## 4. 업무 호환성과 실패·미검증

이번 실제 reserve1개는 즉시 BUY/SELL, Faucet 이후 MATCH, 불변 baseline의 수정/삭제/TRUNCATE 거부, pending INCONCLUSIVE, 안정된 불일치 판정, 실제 PG read-only repeatable-read cut 및 DB/chain 변화의 CUT_CHANGED를 포함해 통과했다. 자세한 assertion은 ReservePostgresAnvilE2ETest와 해당 XML이 근거다. 이는 사용자별 ERC-20 소유권/미래유동성 보증이 아니다.

Faucet은 지급 정책·동시성 앞부분 실행 후 BUY 경합에서 실패했고 마지막 전역 예산 등 후반 assertion까지 통과했다고 주장하지 않는다. 전수 대사는 초기 권한·baseline·BUY 뒤 SELL에서 실패해 본 감사 coverage 검사에 도달하지 못했다. 조건부는 첫 실행의 시장 전제에서 실패해 동일raw·receipt·관측·snapshot 후반 검증을 완료하지 못했다. 이 항목들은 이번 실환경 검증으로는 미완료다. 기본 H2/Mock의 실패/권한/quote/nonce/review/관측 회귀와 기존 과거 E2E 결과를 대체 증거로 합치지 않는다.

### 4.1 시각 차이와 원인/가설

자체 생성한 실패 fixture만 잠시 기동해 **읽기 전용**으로 측정한 후 다시 정지했다. 기존 서비스·호스트 시각은 변경하지 않았다.

- faucet-91ba6c59: `dbMinusHostSeconds=-3.0497398376464844` (호스트 측 왕복 약0.317초).
- conditional-999016bd: `dbMinusHostSeconds=-3.8739583492279053` (왕복 약0.310초).
- ConditionalOrderService.checkedMarket은 DB now와 snapshot observedAt을 비교해 미래2초 초과면 MARKET_UNAVAILABLE로 거절한다. PriceReportIssuer와 PriceOracle도 기존2초 미래 허용 경계를 가진다. **두 시계가 이 전제 밖이라는 것은 실측 사실**이며 조건부 실패를 설명하는 강한 근거다. 측정은 실패 이후이므로 실패 순간의 offset을 정확히 소급 확정하지 않는다.
- Faucet 재현은 공개code OPERATOR_NOT_READY로, BlockchainTransactionSender.estimateGas의 거절 경계다. 실제 신규 원장에는 quote CONSUMED와 Order FAILED가 보존됐고 raw 저장 전 실패 보상 경로에 해당한다. Vault/Oracle revert 세부 selector를 최초 실패에서 수집하지 못해 **가스 추정의 정확한 원인은 아직 확정하지 않는다**. clock/future-report가 후보이며 ETH/토큰 부족으로 단정하지 않는다. audit SELL의409도 code 미수집으로 확정 원인 미상이다.
- 결과를 통과시키려고 미래 허용을2→5초로 늘리거나, 정상 데이터/quote/잔고를 수정하거나, 기존 Docker/WSL을 재시작하지 않았다. 추가 실제 E2E는 clock 전제를 바로잡기 전에는 재시도하지 않는다.

**필요 조치 후보**: (A) 사용자가 Windows와 Docker Desktop/WSL 시각 동기화를 안전하게 확인·조치한 후 새 session/fixture로 재실행; (B) 테스트 JVM도 새 전용 Linux container에서 PG/Anvil과 같은 시각으로 실행하도록 harness를 확장. A는 기존 서비스에 영향을 줄 수 있어 이 에이전트가 임의 실행하지 않으며 B도 현재 최소 harness보다 범위가 커 별도 승인 필요. 어떤 안에서도 기존 거래 정책은 변경하지 않는다.

진단 도구의 기존 JSON 덮어쓰기 거부(FileExistsError)와 직접 shell Go-template quoting 거부는 업무 테스트와 별도로 기록한다. 전자는 기존 증거를 보존한 채 timestamp별 새 진단 파일을 쓰도록 보완했고, 후자는 소유권 검사 단계에서 중단돼 컨테이너 기동이 없었다. reset/redeploy로 우회하지 않았다.

기술/테스트 실패는 최초 evidence를 보존하고 제품 수정으로 성공 처리하지 않는다. harness 결함은 승인된 최소 범위에서 보완 후 새 fixture로 재검증하고 실패 기록을 유지한다. 성공으로 보존될 데이터 reset, baseline 재생성, 자동 재배포 없음. 종료는 이번 호출이 생성한 정확한 label의 컨테이너만 소유권 재검증 뒤 stop한다. DB/state/컨테이너/network는 보존한다.

실제 Toss/유료 AI/Sepolia/AWS·운영 부하·Android·실제 벽시계 KST 자정은 NOT_RUN. Phase A가 통과해도 최종 전체 인수 PASS가 아니다.

## 5. 시장 정책 최소 수정 제안 — 미구현

현재 `MarketPriceService.requireTradableSnapshot`은 CLOSED/STALE만 거절한다. `PriceReportIssuer`는 별도로5초 나이/2초 미래/관측+30초 TTL을 RPC 전후 검사한다. 따라서 연결이 끊겨 DEGRADED지만5초 이내인 즉시 quote는 허용될 수 있다. `ConditionalOrderService.checkedMarket`은 TOSS+OPEN+LIVE+같은 신선도 조건이다. 이 차이는 버그로 확정하지 않고 기존 계약 차이로 기록한다.

**추천 최소안**: 실제 TOSS의 신규 서명 보고서 발급에만 `OPEN && LIVE` guard를 추가하고 기존 fresh5초/미래2초 검사를 유지. `PriceReportIssuer` 또는 별도 시장 검증 helper가 수정 후보이며 일반 MarketPriceService 전체를 일괄 LIVE-only로 바꾸지 않는다. 그렇게 하면 Synthetic SIMULATED 거래와 기존 DB-only quote까지 의도치 않게 막을 수 있다.

회귀 범위: TOSS OPEN/LIVE fresh 성공; UNKNOWN/INITIALIZING/DEGRADED/STale/CLOSED 거절, 연결 단절 직후 fresh5초 경계, 미래2초·TTL·RPC 중 지연, 조건부 동일 정책, Synthetic/public DB-only 기존 허용 유지, 기존 consumed quote와 SIGNED/SUBMITTED 복구·receipt 정산이 나중 시장 상태 때문에 멈추지 않음. 이미 발급된 보고서를 만료 전까지 허용할지 신규 실행 직전에도 LIVE를 재검사할지는 별도 결정이며 후자는 기존 quote/정산 의미에 더 큰 영향을 준다. 이번 Phase A에서는 어느 제품 코드도 변경하지 않는다.

## 6. 9개 승인 지식 문서 최신화 제안 — 미수정/미색인

| 기존 문서 | 조사 결과 / 최소 갱신안 | 코드 근거와 경계 |
|---|---|---|
| system-overview.md | 실제 충돌:38행 자산 대사를 미완료로 표현. A custody 공식 모델, 두 대사·조건부·Faucet/관측 완료 기능과 실제 Toss/AI/운영 인수 미완료 구분 | reserve/tradeaudit/conditional/wallet/faucet/observation. 사용자별 ERC-20·상환권·PoR 주장 금지 |
| authorization-policy.md | 새 ADMIN 읽기 API·대사 수동 실행 역할과 USER Faucet/조건부 소유권 추가. AI에는 쓰기 Tool이 없음을 유지 | SecurityConfig/각 controller/ToolRegistry. 제품 ADMIN 기능과 AI Tool 목록을 혼동하지 않음 |
| order-lifecycle.md | 조건부 의도 상태와 실행 Order 링크/실행 유형 ONCHAIN·DB_ONLY·UNKNOWN, 등록 미잠금·prepare 잠금·cancel/expire/review 설명 추가 | ConditionalOrderService/OnchainOrderPreparationService. 기존 Order.CANCELED 취소 API 없음은 즉시 Order에 대해서는 여전히 사실 |
| signed-quote-policy.md | 대기 조건부에 선서명 없음, 트리거 때 새 quote·실제가격 정수 재확인. 오래 기다린 의도의expiry와30초 report를 구분 | PriceReportIssuer/PriceQuoteService/ConditionalOrderService. 키 분리/quoteId only/DB에서 서명조회 유지 |
| onchain-settlement-policy.md | 정상 정산·잠금과 Faucet journal, 두 대사의 비교 근거/coverage·일관 cut 경계 추가. 대사가 보정하지 않음을 명시 | OnchainSettlementService/ReserveCalculator/AuditValidator. 불확실 receipt→강제지급 금지 |
| transaction-recovery-runbook.md |57행 전체 원장 대사 미제공 표현은 **AI Tool 범위**로 한정. ADMIN 새 대사·정체 관측 LONG_WAIT/UNRECORDED/최근 완료 snapshot·best-effort 한계 추가 | ToolRegistry와별도ADMIN APIs. 기존 AI WAITING_LONG 계약/REVIEW_REQUIRED 기준 유지 |
| market-data-policy.md | 현 DEGRADED 설명은 현재 코드에 맞으므로 지금 허위로 LIVE-only로 바꾸지 않음. 조건부 LIVE-only 차이·Synthetic 제한·실제Toss 미인수 설명 추가 | TossPriceProvider/ConditionalOrderService/Public guard. 정책 수정이 승인·구현되면 그때 현행조건 갱신 |
| error-and-status-guide.md | Faucet400/429/409/503, 조건부 intent 상태·사유, audit coverage와 관측 파생 경고를 scope별로 추가 | 각Failure/controller. LONG_WAIT는 저장tx상태 아님;INCONCLUSIVE와 거래FAILED 구분 |
| websocket-consistency-policy.md | 본인conditional queue, Faucet replay의추가알림0·AFTER_COMMIT,재접속REST동기화;ADMIN관측은DB조회이지새거래WS이벤트아님 | WebSocketDestinations/UserWebSocketPublisher·웹panel. 실제네트워크인수와fixturePASS구분 |

9문서 구조·domain/minimum_role·기존 근거 링크를 유지한다. 필요 버전·updated_at·frontmatter와manifest hash를 함께 갱신하는 별도 승인 작업으로 제안한다. 민감 운영 세부는 ADMIN domain/role 경계를 유지하며 USER 문서가 ADMIN 자료를 중계하지 않도록 분리한다. 실제 수정/manifest 재승인/색인/유료 호출은 이번에 하지 않는다. index hash 일치와 내용 최신성은 다른 검증이다. 승인 후 golden12 그대로·기존K/negative/identifier/USERscope 회귀와 최신 기능 supplementary 질문을 별도로 평가한다.

## 7. Phase B 전 승인 사항

Phase A 최종 실제 실행 결과·skip/blocked를 검토한 뒤, 신규 Toss runtime 생성과 credential/IP/비공개 이용조건·요금/호출 상한을 승인받는다. 시장 LIVE-only 정책 수정 여부와 위 knowledge 최신화 범위를 별도로 확정한다. 새 reserve baseline은 초기 자금 준비 후 zero-ledger에서만 생성하며 기존 baseline을 재사용하지 않는다. 유료색인/AI 자동 flag/모델호출·Phase C budget은 B 승인의 일부가 아니다.

## 8. 검토·커밋·통합

독립1차 검토는 harness 필수 코드 결함을 발견하지 못했으나 실제 필수 E2E/별도 JVM 결과 미완료로 **검토 불충분**이었다. 직접 Python6 PASS·제품diff0 확인, metadata 보존 문구 불일치 지적은 위와 같이 수용했다.

첫 재검토(review_phase_a_isolation): **발견된 필수 수정 없음 — 최종 harness·보고서 범위에만 한정**. 직접 guard9/9 PASS, 허용된 이번 기본 회귀 summary462/427/35/0 확인, 제품diff0 확인. timeout·현재XML·metadata·clock guard·공개code assertion과실패/미실행표기의정합성을확인했다. reviewer는DB/RPC를호출하지않았으며실제E2E/clock진단은구현자실행결과로구분한다. 새clockpreflight의운영실행은아직NOT_RUN이다. PhaseA완료나미검증해소승인이아니며병합보류가타당하다는결론이다.

최종 보안 점검은 이번 생성 fixture의 실제 키/DB 비밀번호를 Git 후보와 비출력 대조해 노출0, 금지 runtime/log/env/state/build 산출물0을 확인했다. 본session5fixture의postgres/Anvil총10개 모두exited를읽기확인했다. source/knowledge제품diff0. 비밀·실제 `.env`·DB/state/runtime logs/산출물은stage금지.

Phase A 전제와 필수 E2E가 미완료라 **integration 병합·병합 후 인수는 대기**한다. 안전한 harness·문서의 기능 브랜치 기록은 별도로 남기되 이를 Phase A 통과/제품 구현 완료라고 표현하지 않는다. 기존 integration HEAD/미추적 설계 문서는 그대로 보존한다. 전체 제품 source와 승인 knowledge/manifest는 입력HEAD 대비diff0이며 시장 정책·AI 지식 최신화는 제안만 했다.

실행 전제 해결은 사용자 조치 또는 별도 harness 확장 승인 필요사항이다. Windows/Docker 전역 재시작·시간 변경, 과거 환경 초기화, 제품 오차 허용 확대는 이 작업에서 하지 않는다. 전제 해결 후 같은 실패 DB를 wipe/rebaseline하지 않고 새session/fixture로필수E2E4종+restart를재실행한후통합여부를확정한다.
