# Phase A 마무리·시장 품질 보완·키움 전환 최종 기록

> 2026-10-13. 구현·기능 검증·통합 완료. 최종 통합 전체 기본 회귀 PASS, 실제 PG/Anvil 추가5종 재실행은 신규 subnet 충돌로 부분 BLOCKED. 전체 최종 인수 PASS가 아니다.

## 커밋과 보존

- Phase A harness/문서 병합 `cfbd625`, 새 회귀 보고서 `0e57d50`.
- 신규 실제 서명 OPEN+LIVE 정책 기능 `966fa76` → 통합 `a626d7a`, 통합 회귀 기록 `5c768f4`.
- 키움 기능/승인 지식9문서 `5e39dbf` → 통합 `cffe471`.
- 최종 회귀 입력 HEAD `cffe471cd57ef06dc49ff8f47ce5efe7841e39b3`, session `phase-a-20261013144612-1cf75377`.
- 사용자 경험 문서는 기존8개 worktree에 문서-only 커밋으로 보존했다. 원본 미추적 인수 계획도 `8b91f73`로 원문 보존 후 병합했으며 제품 충돌 없이 통합했다.
- `melon-init` HEAD `f069a1085fd7cfe1d8d75f7bb454fc20feb50ede`: 이번 제품 병합 없음. 해당 HEAD의 부모 대비 차이는 `claude-docs/ai-development-experience.md` 하나뿐이고 작업 트리는 clean이다. Phase A feature HEAD `9baa927`/작업 트리도 유지했다. 기존 acceptance·개발DB/Anvil/runtime·실제.env와 실패 증거를 접근/변경하지 않는다.

## 실제 실행별 결과

| 실행 | 백엔드 PASS / SKIP / FAIL | 실제 신규 PG/Anvil |
|---|---|---|
| Phase A 병합 cfbd625, session123833-ea09572a | 427 /35 /0 | Faucet·reserve·audit·조건부 각1, 별도JVM1: 5/5 PASS |
| 시장 보완 병합 a626d7a, session140013-a1c80720 | 431 /35 /0 | 조건부1·별도JVM1 PASS |
| 키움 최초 source, session141811-33461756 | 450 /35 /0 | 조건부1·별도JVM1 PASS; 독립 검토 결함 발견 후 수정 |
| 키움 재연결 수정, session142757-26bb2975 | 451 /35 /0 | 조건부1·별도JVM1 PASS |
| 지식9문서 갱신 후 별도 backend-knowledge-refresh | 451 /35 /0 | 기존 새 fixture 결과와 별개; 추가 체인 실행 없음 |
| 최종 통합 cffe471, session144612-1cf75377 | 451 /35 /0 | Faucet1 PASS; reserve BLOCKED; audit·조건부·별도JVM NOT_RUN |

모든 backend 수치는 전체 total에서 SKIP을 뺀 실제 통과다. 반복 실행의 통과 수를 합쳐 고유 테스트 수로 표현하지 않는다. 앞선 각 regression의 웹84/Foundry36·Chrome desktop/mobile fixture도 PASS이며 최종 통합 source에서도 regression 명령이 성공했다. 실제 키움 API는 한 번도 호출하지 않았다. PostgreSQL/Anvil 호환 E2E의 시세는 Synthetic이며 키움 경로 검증은 Mock19개와 조건부 H2/Mock1개다.

## 변경과 독립 검토

신규 서명 발급에만 실제 공급자 OPEN+LIVE+fresh를 추가하며5초/미래2초/TTL30초를 유지했다. 저장 견적의 소비/원문·nonce·raw 복구/Receipt 정산, Synthetic/DB-only 계약은 그대로다. 미래 실제 공급자도 서버 스냅샷 공통 검증을 따른다.

키움은 고정 읽기 TR ka10001/ka10080/ka10081과 실시간0B/0s만 사용한다. 키/토큰은 설정/메모리에서만 관리하며 원문 오류를 공개하지 않는다. 가격·등락·거래량/KST 변환, continuation/WS token/reconnect, 같은 초 체결 중복과 조건부·AI Tool·웹 기존 계약을 연결했다. 최초 independent review에서 재연결 OPEN 재사용 결함을 발견해 세대별 시장 확인과 회귀를 추가했으며 재검토는 필수 수정 없음이다. reviewer는 코드를 읽고9개 manifest 정규화 SHA256/version/role을 직접 확인했지만 테스트를 독립 재실행하거나 private runtime을 확인하지 않았다.

AI9문서는 Faucet/조건부/두대사/정체 관측/운영자 장부 모델/새 시장 정책/키움 범위로 갱신했다. 구조·active·USER8/ADMIN1 유지, manifest/hash/version 변경. 유료 재색인·검색 품질 재평가·실제 AI 인수는 NOT_RUN이며 기존 활성 AI index를 최신 corpus로 자동 바꾸지 않았다.

## 실제 변경 파일

다음은 이번 순차 통합의 입력8b91f73 대비 cffe471에서 Git이 확인한52개 경로다. 경험 문서·인수 계획의 별도 문서 커밋 및 마지막 보고서-only 변경은 별도로 구분한다.

- `backend/.env.example`
- `backend/README.md`
- `backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java`
- `backend/src/main/java/com/pricetrack/exchange/common/exception/GlobalExceptionHandler.java`
- `backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderService.java`
- `backend/src/main/java/com/pricetrack/exchange/market/MarketDataEligibility.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomApiException.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomCandleProvider.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceConfiguration.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceProperties.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceProvider.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomProtocol.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRealtimeClient.java`
- `backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRestClient.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/pricetrack/exchange/ai/KnowledgeLoaderTest.java`
- `backend/src/test/java/com/pricetrack/exchange/blockchain/observation/ObservationRestartPostgresTest.java`
- `backend/src/test/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuerTest.java`
- `backend/src/test/java/com/pricetrack/exchange/conditional/ConditionalOrderIntegrationTest.java`
- `backend/src/test/java/com/pricetrack/exchange/conditional/ConditionalPostgresAnvilE2ETest.java`
- `backend/src/test/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomCandleProviderTest.java`
- `backend/src/test/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceProviderTest.java`
- `backend/src/test/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomProtocolTest.java`
- `backend/src/test/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRealtimeClientTest.java`
- `backend/src/test/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRestClientTest.java`
- `backend/src/test/java/com/pricetrack/exchange/reserve/ReservePostgresAnvilE2ETest.java`
- `backend/src/test/java/com/pricetrack/exchange/support/PhaseAFixture.java`
- `backend/src/test/java/com/pricetrack/exchange/tradeaudit/TradeAuditPostgresAnvilE2ETest.java`
- `backend/src/test/java/com/pricetrack/exchange/wallet/faucet/FaucetPostgresAnvilE2ETest.java`
- `claude-docs/README.md`
- `claude-docs/implementation-log.md`
- `claude-docs/kiwoom-market-provider-implementation.md`
- `claude-docs/phase-a-integration-acceptance-report.md`
- `claude-docs/phase-a-merged-regression.md`
- `claude-docs/signed-market-eligibility-implementation.md`
- `deployment/Dockerfile.phase-a-tools`
- `deployment/PhaseAClockProbe.java`
- `deployment/phase-a.py`
- `deployment/phase_a_clock.py`
- `deployment/test_phase_a.py`
- `deployment/test_phase_a_clock.py`
- `docs/ai-knowledge/authorization-policy.md`
- `docs/ai-knowledge/error-and-status-guide.md`
- `docs/ai-knowledge/market-data-policy.md`
- `docs/ai-knowledge/onchain-settlement-policy.md`
- `docs/ai-knowledge/order-lifecycle.md`
- `docs/ai-knowledge/signed-quote-policy.md`
- `docs/ai-knowledge/system-overview.md`
- `docs/ai-knowledge/transaction-recovery-runbook.md`
- `docs/ai-knowledge/websocket-consistency-policy.md`
- `docs/ai/ingest-manifest.json`
- `tools/websocket-test-client/src/main.js`

## 최종 통합 실행의 중단 근거

최종 source의 백엔드486 total/451 PASS/35 SKIP/0 FAIL·ERROR, 웹84 PASS/0 FAIL·SKIP, Chrome desktop/mobile fixture PASS, Foundry36 PASS/0 FAIL·SKIP를 새로 실행했다. Faucet `faucet-aadb8413`(DB42590/RPC36103) 실제 E2E1 PASS. preflight DB-host offset=-0.249402초, RTT=0.275834초/불확실성 포함 약0.387초로2초 경계를 통과했다.

이후 신규 `reserve-213924ed`의 `docker network create`가 `invalid pool request: Pool overlaps with other one on this address space`로 실패했다. 해당 fixture는 전용 run/secret 디렉터리만 생겼고 DB/Anvil 컨테이너·schema·계약 배포·테스트는 생성/실행되지 않았다. 시각/거래409/제품 로직 실패로 단정하지 않는다. private network stderr/metadata를 보존했다. 기존 네트워크 조회·삭제, 강제 subnet 확보, 실패 fixture 초기화, 무단 재배포/반복 실행 없음.

따라서 이번 최종 통합 실행은 **실제 PG/Anvil 1 PASS /1 BLOCKED /3 NOT_RUN /테스트 FAIL0**이다. 앞선 feature source의 조건부/재시작 PASS나 Phase A 병합 source5/5를 cffe471의 새 PASS로 재사용하지 않는다. integration 병합은 그대로 보존하되 모든 최종 E2E 검증 완료로 선언하지 않는다.

후속 후보는 신규 fixture 네트워크 생성에만 bounded subnet 충돌 처리(동일 오류일 때 새 후보를 제한 횟수 시도하고 실패 시 중단)를 추가하는 것이다. 제품 코드·기존 네트워크·DB/체인은 변경할 필요가 없다. 이번에는 이를 구현하지 않았으며 이 harness 보완과 남은4종의 새 fixture 실행은 후속 확인 후 진행한다.

## App Key 연결과 후속 작업

1. 적절한 키움 REST App Key/Secret과 서비스 계정·접근/IP 조건을 사용자가 준비하고 외부 시세 호출을 별도 승인해야 한다. 실제/모의 endpoint와 키 종류를 혼용하지 않는다. `backend/.env.example`의 이름만 참고하여 전용 runtime에 비밀값을 준비한다. 기존.env를 수정/자동 import하지 않는다.
2. 실제 WS 로그인/구독·token 만료·재연결·시장0s 초기 전달과 rate limit은 NOT_RUN이다. REST 현재가에는 관측시각이 없어 초기 가격을 LIVE로 승격하지 않는다. 각 연결에서 명시적0s OPEN을 확인하지 못하면 UNKNOWN으로 차단하며 현재 구현이 키만 넣으면 곧바로 장중 거래 가능한지 실환경 인수 전 보장하지 않는다.
3. 실제 native 분봉 timestamp의 시작/종료 labeling과 미완성 봉·웹 tick bucket 일치는 미검증이다. 공식 필드를 그대로 사용하고 임의 Toss식 보정을 하지 않았다. 불명확한 데이터는 추정해 실행하지 않는다.
4. 실제 Toss 장중 인수·유료 AI/embedding·AWS/Sepolia·Android 기기 검증은 이번 NOT_RUN. 최종 통합 인수 Phase D 또는 전체 제품 실환경 PASS를 선언하지 않는다.

상세 구현은 [키움 기록](kiwoom-market-provider-implementation.md), [신규 시장 경계](signed-market-eligibility-implementation.md), [Phase A 병합 실행](phase-a-merged-regression.md)을 참고한다.
