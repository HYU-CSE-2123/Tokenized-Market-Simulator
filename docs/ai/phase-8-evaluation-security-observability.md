# AI Phase 8 — 최종 평가·보안·관측성 설계안

> 2026-10-02 설계 / 2026-10-03 승인 범위 구현·검증·별도 검토 완료, 사용자 완료 승인 대기. Phase7 사용자 완료 승인됨.
> 시작 HEAD8547b99, 작업 트리 깨끗함. master guide 8.1~8.7과 기존 Phase 문서/실제 코드를 기준으로 한다.

## 1. 목적과 유지할 경계

새 기능보다 현재 RAG·8개 read-only Tool·제한 Agent·3개 Skill·자동 진단을 어디까지 신뢰할 수 있는지 검증하고 운영/설명 자료를 마감한다. 기존 거래·컨트랙트·권한·metadata filtering·Agent 예산·자동 진단 안전값을 유지한다. 모델/공급자 교체, Hybrid 서비스 도입, mutation Tool, 자동 복구, 새 챗봇/Android UI, 외부 모니터링 서버는 제외한다.

현재 Agent에는 총 지연·호출 수·토큰 사용·안전한 실패 코드 로그가 있고 Skill에는 단계 trace가 있다. 단계별 관측과 종합 평가 자료를 보완하되 원문 질문/답변/문서/Tool payload를 새 로그로 남기지 않는다.

## 2. 최종 평가

- 기존 golden12와 무관 질문8을 변경하지 않는다. Phase2 hit10/12와 채택 K 및 Phase7 hit12/12·MRR0.8819444444·직접근거12/12·무관 오답0/8·critical ANSWERED2/2·식별자6/8·scoped3/3을 비교 기준으로 유지한다.
- 별도 종합 시나리오 manifest를 만든다. knowledge-only, state-only, mixed, permission denial, missing evidence, stale/contradictory knowledge, Tool timeout/failure, prompt/RAG injection의8분류를 각각 정상/거부 또는 실패 경계로 검증한다. 기존 테스트를 재사용하고 누락만 추가한다. 새 세트의 수치를 기존 golden 개선으로 합산하지 않는다.
- 기대 route/Skill, 허용 Tool·문서, 상태/출처/단위, 근거와 불확실성, 호출 상한, 거래 불변성을 deterministic assertion으로 우선 판정한다. 서버의 pointer 검증만으로 자유 문장 전체의 진실성을 증명했다고 쓰지 않는다. 대표 답변은 별도 rubric으로 수동 대조하며 LLM judge만으로 통과시키지 않는다.
- 실제 공급자는 대표 Agent/Skill/자동 진단 최대8개 요청과 기존 golden/무관20개 RAG 요청을 우선 한 회 평가한다. Agent 모델2회 상한을 유지하므로 chat 시도 최대36회인 계획이다. embedding/재색인 호출은 별도 집계하고 실패 호출의 미보고 usage는 UNKNOWN으로 기록한다. 변경 없는 반복 유료 평가를 피하고 추가 호출은 실패 보완 필요성과 사용량을 밝힌다. 실제 실행 수·토큰·지연을 보고하며 요금 단가를 추측하지 않는다.
- 격리 PostgreSQL/pgvector/Anvil 종단간, 전체 backend/forge/웹 테스트·build와 독립 검토를 수행한다. 미실행 조건부 테스트와 외부 운영/브라우저 시나리오는 별도 표로 남긴다. 실제 MATCH는 전용 체인/fixture 확보 시 검증하며 운영 체인을 임의 배포·거래하지 않는다.

## 3. 보안 보강

- 사용자 질문, 검색 문서, Tool 문자열 필드의 주입 시도를 각각 테스트한다. 데이터 안의 지시가 role/target/Tool allowlist/Skill 단계/호출 예산을 변경할 수 없어야 한다.
- USER 타인 order/quote·ADMIN 문서·자동 진단 이력 거부, actor 재인가, 승인 취소/hash 불일치, timeout·포화/AI DB 장애의 fail-closed와 거래 격리를 회귀한다.
- 가짜 비밀 canary로 provider 입력·응답·로그·trace·저장 이력을 검사한다. private key/API secret/JWT/DB password/raw transaction/불필요 사용자 정보 누출0을 통과 조건으로 둔다. 실제 키나 운영 데이터는 공격 fixture에 사용하지 않는다.
- 종료 marker metadata TTL은 최소 보존 원칙상 통일을 권장한다. 승인 시 invalid SKIPPED/최종 BUSY FAILED에도 기존7일 기준을 적용하고, purge 시 tx_hash/claimed_by/claim token/actor/result만 정리한다. source namespace/eventKey/대상 식별자/종료 상태 dedup marker는 보존한다. 기존 TTL 없는 종료 행도 안전하게 정리 가능한지 검토하고, 중복 재예약0·7일 경계·기존 행 보존 테스트를 추가한다. trading schema/queue quota/재시도 의미는 바꾸지 않는다.

## 4. 최소 관측성

- 기존 실행 경계에 retrieval/embedding/LLM/Tool/Agent 시간, 검색 문서 수, 호출/실패 수, model·embedding model 설정, 보고된 토큰 사용을 기록한다. runId로 연계하되 민감 원문은 기록하지 않는다. timeout 관측 시간은 실제 외부 호출 종료와 구분한다.
- 추가 라이브러리/Prometheus/Grafana 없이 프로세스 내 고정 label의 count/sum/max 집계를 우선한다. route/Skill/Tool/실패 코드만 허용하고 user/order/quote/runId를 집계 label로 쓰지 않는다. p50/p95는 평가 원시 표본에서 계산하며 운영 집계가 지원하지 않는 백분위를 만들어내지 않는다.
- ADMIN 전용 `GET /api/ai/observability`에서 최소 집계를 조회하도록 제안한다. AI DB와 무관하게 프로세스 집계를 제공하고, queue/UTC quota는 별도 짧은 AI DB 조회가 성공한 경우에만 포함한다. 장애/미집계는 unavailable/unknown이지0이나 정상으로 표시하지 않는다. 재시작 시 초기화되는 비영속 통계이며 공개 Actuator endpoint나 별도 대시보드를 추가하지 않는다.
- 예외 경로·거부·취소도 누락/이중 집계 없이 반영하고, 관측 실패가 Agent/Tool/거래를 실패시키지 않는지 테스트한다. 메트릭 저장을 위한 분산 트랜잭션이나 trading DB 쓰기는 없다.

## 5. 문서와 완료 기준

master guide의6개 산출물을 작성한다: `AI_ARCHITECTURE.md`, `RAG_KNOWLEDGE_GUIDE.md`, `TOOL_SECURITY_MODEL.md`, `SKILL_GUIDE.md`, `EVALUATION_REPORT.md`, `RUNBOOK.md`. 기존 Phase 기록은 상세 근거로 링크하고 지식9개 구조/역할과 과거 수치를 덮어쓰지 않는다. career-project-brief는 실제 완료 내용과 계획/한계를 구분해 갱신한다. RUNBOOK에는 활성화/비활성화, 명시 schema·index, namespace·quota·TTL, 장애 해석과 사람이 수행할 후속 확인을 포함한다.

완료 판단: 기존 검색/권한/거래 회귀 유지, 공격의 권한 우회·민감정보 누출·mutation0, 대표 end-to-end 및 실제 공급자 결과 기록, 관측성/TTL 보완의 경계 테스트, 전체 회귀와 독립 검토. 자연어 완전 진실성·운영 SLA·무손실 polling·모든 장애 원인 진단을 보증하지 않는다.

이번 턴에는 읽기 전용 조사와 설계/Phase7 승인 문서 기록만 한다. 제품·테스트·DB·환경·색인 변경이나 실제 공급자 호출은 하지 않으며 구현 승인을 기다린다.

## 6. 승인 후 구현과 저장 형식 확인

1~5절은 당시 제안이며 실제 완료 기록은 이 절부터 누적한다. 시작 HEAD8547b99, 기존 설계 README/log/Phase7 승인/본 문서를 보존했다.

- 사용자 추가 조건을 먼저 확인했다. PgDiagnosisStore.enqueue는 namespace:transactionId:normalizedTxHash:REVIEW_REQUIRED의 **SHA-25664자리 hex**만 event_key에 저장한다. KnowledgeLoader.hash의 SHA-256/UTF-8 구현과 varchar64 schema를 대조했다. raw composite/txHash는 아니며 identity를 변경하거나 dedup migration을 만들지 않았다.
- PgDiagnosisStore: invalid SKIPPED/최종 BUSY FAILED도 설정 retentionDays를 적용한다. purge는 과거 TTL 없는 terminal만 completedAt/detectedAt으로 보정하며 메타가 claim만 남아 있어도 제거한다. fingerprint/namespace/대상/상태 marker와 QUEUED/RUNNING은 보존한다. 실제 PostgreSQL 테스트에서 purge 후 재예약0/legacy·비만료 경계를 검증했다.
- 신규 ai/observability/AiObservability와 Controller: process-local fixed stage/name/code counters, 호출·sum/average/max 시간·검색 문서 수·provider reported/unknown tokens. ADMIN GET /api/ai/observability와 별도 AI-only queue/UTC snapshot. 모델은 설정값이며 비표준 값은 CUSTOM_UNREPORTED다. runId는 안전 UUID 로그 연결용이고 집계 label이 아니다.
- provider HTTP(LLM/embedding), role-aware retrieval, RAG, Tool, Agent 경계에 optional observer를 연결했다. 기존 constructor/test contract를 유지한다. timeout/거부/예외와 PARTIAL의 dependency failure를 구분하고 관측은 best-effort다. raw payload/질문/문서는 추가 로그에 넣지 않았다. 호출2배 집계를 비용으로 합산하지 않도록 provider 토큰만 보고한다.
- AgentEvidence의 recursive private field에 privateKey/API/JWT/DB secret field를 보강했다. 질문/문서/Tool 주입과 configured-secret/error-body canary를 추가하고 기존 allowlist/role/domain/예산/승인 검증을 그대로 유지했다.
- final-evaluation-manifest8분류와 참조 테스트 존재/frozen golden assertion, paid Phase8LiveEvaluationTest를 추가했다. 기존 KnowledgeRefresh는 역사적 보고서를 덮지 않고 phase8 raw 파일과 관측 snapshot을 기록한다.
- 최종6문서와 career-project-brief에 완료 기능/실제 근거/계획/미검증을 구분해 기록했다. 제품의 거래·컨트랙트·Tool 목록·지식9개/manifest·서비스 색인·실제 .env·운영 schema는 변경하지 않았다. Spring AI/Hybrid 서비스/새 UI/모니터링 서버를 추가하지 않았다.

## 7. 실제 검증

- 표적 테스트 중 usage=null 자동 unboxing이 원래 공급자 오류를 덮는 결함을 발견했다. boxing/null-safe best-effort finally로 수정했고 원래 provider 오류·canary assertion을 유지해 통과했다.
- 최초 전체333/321통과/12skip 후, fixed label·dependency failure 집계·단일 SQL snapshot·manifest assertion까지 포함한 최종 전체334 중322통과/12skip/실패·오류0. 신규10개 결정적 실행, 신규 paid Java1은 skip이다. forge36·웹34/build88 통과.
- 실제 공급자 opt-in Java2개 통과. 대표8개 기대 결과8/8, 정상ANSWERED2/PARTIAL4와 안전 거부2, 의존성 실패0. Agent7표본 median6130ms/nearest-rank p95=7726ms. 고정 golden12/12·MRR0.8819444444·직접근거12/12·무관 오답0/8·critical ANSWERED2/2·식별자6/8·scoped3/3 유지. 실제 model17회/input24920/output3171, embedding8회/input170; 이번 실행만의 usage다.
- 20개 golden/negative 질문 외 기존 identifier8·별도 답변 검증/캐시를 재사용했다. model17회로 계획36회 상한 이하다. HTTP 없는 cache 재사용을 실제 embedding 호출로 세지 않았다. 자세한 수치/응답 rubric/late label 보완과 실행별 한계는 EVALUATION_REPORT.md와 phase-8-results.json을 따른다.
- 전체 skip12는 기존 LiveAgent/LiveDiagnosis/LiveRag/KnowledgeRefresh/RetrievalAnswer/RetrievalExperiment/LiveSkill, 새 Phase8Live, ToolAnvil 성공 event, 기존 Anvil broadcast2, QuotePostgresConcurrency1이다. KnowledgeRefresh/Phase8Live는 별도 opt-in으로 실행했고 다른 기존 유료 tests를 이번 유료 실행으로 가장하지 않는다. 기존 real AI/거래 PostgreSQL·Anvil 조회 통합 및 거래 불변성/장애 latency는 전체 명령에 포함한다.
- 유료 평가 뒤 최종 관측 label/실패 코드/단일 SQL 변경만 전체 회귀로 확인했고 추가 유료 반복은 하지 않았다. 최신 XML은334개 전체이며 검토자 표적 실행으로 대체될 수 있다. 실행별 수치를 구분한다.
- OpenAI Docs의 [주입/Agent 안전](https://developers.openai.com/api/docs/guides/agent-builder-safety)과 [안전 권고](https://developers.openai.com/api/docs/guides/safety-best-practices)를 확인했다. schema/서버 권한·근거 검증과 유한한 공격 테스트를 병행하며 모든 주입/자연어 진실성을 보증하지 않는다.

## 8. 검토와 미검증

독립 review_ai_phase8: 발견된 필수 수정 없음. 제품 diff·신규 파일·테스트·최종6문서/career brief를 직접 확인했고 fingerprint identity, terminal TTL/legacy backfill·dedup, ADMIN 관측 권한과 고정 label·민감정보 배제를 검토했다. 검토자 직접 AiObservability*/AgentServiceTest/OpenAiProviderTest/FinalEvaluationManifestTest 총34개 통과·실패/오류/skip0, diff 검사 통과. raw 평가2개와 공유 수치·대표 답변의 관측/정책/불확실성 구분을 대조했다. 최신 XML은 검토자 표적34개로 대체됐으며 구현자 전체334개와 구분한다. 검토자는 전체/공유 PostgreSQL/Anvil/유료 공급자/forge/웹을 재실행하지 않았다.

최종 검토 후에는 검토 결과와 완료 상태 기록만 추가했고 제품·테스트는 변경하지 않았다. 구현·검증·별도 검토 완료, 사용자 완료 승인 대기다. 운영 Toss/실사용자 장애, 실제 자동 성공 MATCH, kill/다중 서버 장기 chaos, 브라우저 수동 인수·장기 SLA·무한한 주입/자연어 정확성은 미검증이다. 실제 .env/운영 자동 진단·schema를 활성화하지 않았고 커밋은 하지 않았다.

커밋 메시지: `feat(ai): 최종 평가와 보안 검증 및 최소 관측성 보강`
