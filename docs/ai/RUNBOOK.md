# AI 운영·검증 Runbook

## 기동과 명시 초기화

거래 DB/Anvil/백엔드/웹 실행은 [backend README](../../backend/README.md), [웹 README](../../tools/websocket-test-client/README.md)를 따른다. AI DB는 기존 거래 compose와 별도 `docker-compose.ai.yml`, 별도 volume이다. 비밀은 실제 backend/.env에 로컬로 보관하며 Git/화면/로그에 출력하지 않는다. 예제값은 backend/.env.example을 참고한다.

1. AI DB5433과 설정/지식 승인 hash를 확인한다. `AI_ENABLED=true`, Agent/Tool/Skill flags를 필요한 경우에만 활성화한다. 서버 재시작이 필요하다.
2. ADMIN JWT로 `POST /api/ai/index`를 명시 호출한다. 지식9개/manifest/활성 index가 맞아야 한다. 서버 시작이 자동 schema/ingest를 하지 않는다.
3. 자동 진단은 `AI_AUTO_DIAGNOSIS_ENABLED=false`로 준비한다. 기존 ADMIN actor loginId와 안정된 원장 source namespace를 지정하고 `POST /api/ai/diagnoses/index`로 AI schema만 초기화한다.
4. 준비 후 자동 flag를 켜고 재시작한다. 같은 원장은 같은 namespace, 체인/원장 초기화 후는 새 namespace다. 다중 서버 안전 설정은 동일해야 한다.
5. ADMIN `GET /api/ai/diagnoses`/`/{id}`와 웹 ADMIN 패널을 사용한다. 새 관측은 `GET /api/ai/observability`다. USER나 미인증은 거부한다.

## 안전값·보존

초기5초/50행/queue100/UTC일20/결과7일. Agent40초·worker2·자동실제run1·Tool4/검색1/모델2, Tool worker4·5초. claim lease90초. 실행 전 BUSY0만10초 간격최대3회/quota반환, 불명확 실행과 lease 만료는 재호출하지 않는다.

종료 결과/actor/tx_hash/claim metadata는 TTL로 제거하고 fingerprint/namespace/대상/종료 상태 marker는 보존한다. 과거 TTL 없는 terminal 행은 purge에서 완료/감지 시각으로 보정한다. dedup marker를 삭제하거나 같은 원장의 namespace를 바꾸면 기존 사건 재실행 위험이 있으므로 임의 삭제하지 않는다. 별도 재진단/복구 endpoint는 없다.

## 장애 해석

- PARTIAL: 알려진 사실은 있으나 정책/receipt/시세나 해석이 부족할 수 있다. Tool/정책/시각·stale를 확인한다.
- AGENT_BUSY/TOOL_BUSY: 현재 용량이 찼다. 사용자가 무한 재시도하거나 자동 quota를 우회하지 않는다.
- APPROVAL_UNVERIFIED/AI_APPROVAL_MISMATCH: 문서/manifest/index를 확인한다. 근거를 확인할 수 없으면 해석을 숨기는 동작이 정상이다.
- AI DB/공급자/RPC 장애: 거래의 정산 실패와 AI 진단 실패를 구분한다. AI를 끄고 사람이 원장·receipt를 확인한다. API에서 자동 복구 명령을 실행하지 않는다.
- INTERRUPTED: 종료 여부가 불명확하다. lease 만료는 새 실행 허가가 아니다.

자동 flag를 끄고 재시작하면 polling이 중단된다. 전체 AI 비활성도 거래/독립 read-only Tool 경계와 별개다. 기존 marker·거래/AI volume은 지우지 않는다. 대기 backlog는 재활성화 후 초기 제한 안에서 처리될 수 있다.

## 관측 읽기

scope는 PROCESS_LOCAL_RESET_ON_RESTART. stage/name별 calls/failures·sum/average/max 시간, 검색 문서 수, provider 보고 토큰·unknown usage를 제공한다. 원문/사용자 ID를 label로 저장하지 않는다. LLM/embedding은 HTTP 호출의 관측 시간·실패이며 JSON/사실 검증과 전체 Agent 상태는 별도다. timeout 시간은 물리적 종료 증거가 아니다. queue/running/UTC claims는 AI DB 전역 snapshot이며 DB 장애는 UNAVAILABLE, AI 비활성은 DISABLED다. 토큰 합계는 보고된 호출만의 부분 합일 수 있고 비용 단가/SLA를 추측하지 않는다. p50/p95는 평가 raw 표본에서만 계산한다.

## 검증

backend에서 `AI_PGVECTOR_TESTS`, `AI_TOOL_POSTGRES_TESTS`, `AI_AGENT_ANVIL_TESTS`, `AI_DIAGNOSIS_INTEGRATION_TESTS`를 true로 설정한 뒤 `./gradlew test --no-daemon --rerun-tasks`. Windows는 `./gradlew.bat`. 전용 exchange_ai_test/exchange_tool_test 및 Anvil 읽기 fixture를 사용한다. `forge test -vv`, 웹 `npm test`/`npm run build`를 함께 실행한다.

유료 opt-in은 `AI_PHASE8_LIVE_EVALUATION=true`, `AI_KNOWLEDGE_REFRESH_EVALUATION=true`, `AI_PHASE8_EVALUATION=true` 후 `--tests '*Phase8LiveEvaluationTest' --tests '*KnowledgeRefreshEvaluationTest'`. OPENAI_API_KEY는 환경에만 전달하고 출력하지 않는다. 테스트 DB 색인/claim/quota와 실제 공급자 평가를 병렬 실행하지 않는다. AI_PUBLISH_CURRENT_INDEX는 별도 명시 승인/확인 후에만 켠다. 전체/직접 검토/유료 결과를 구분하고 실패를 재평가할 때 사유·호출 사용량을 기록한다.
