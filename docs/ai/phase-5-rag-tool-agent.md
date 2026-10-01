# AI Phase 5 — RAG + Read-only Tool Agent 설계·구현 보고서

> 설계 승인: 2026-09-30 / 구현·검증 및 별도 재검토 완료·사용자 완료 승인: 2026-10-01. 1~8절은 승인 설계이며 실제 결과는 9절에 누적한다.
> 기준: master guide Phase 5, Phase 4 실제 Tool 계약, Phase 4 후속 지식 갱신·검색 회귀 보고서.

## 1. 목적과 범위

정적 규칙(RAG)과 현재 사실(Tool)을 구분해서 질문에 답한다. 기존 Boot 내부 모듈·얇은 provider adapter·별도 AI DB, K semantic 검색, 현재 모델/embedding 설정을 유지한다. 새 dependency·DB schema·거래 코드 변경은 제안하지 않는다.

세 가지 질문 유형을 지원한다.

| 유형 | 예 | 실행 |
|---|---|---|
| KNOWLEDGE | quoteId는 왜 일회용인가? | 역할 필터가 적용된 RAG |
| STATE | 현재 시장 상태 / 내 주문 상태 | 인가된 Tool, 정책 검색은 불필요 |
| MIXED | 이 주문이 왜 대기 중인가? | Tool 사실 + RAG 규칙 + 불확실성 |

Skill registry/절차 문서 실행, 이벤트 자동 분석, 진단 DB, 장기 memory, 챗봇 UI, 자동 복구·거래 실행은 제외한다. 별도 검색 서버·Hybrid 기본 채택·reranker도 포함하지 않는다.

## 2. 실행 방식: 한 번 계획하고 제한된 경로 실행

```text
JWT 인증·입력 검증 → 대상이 있으면 소유권 선확인
  → 유형/대상 분류(구조화된 계획 1회)
  → 서버가 계획·역할·대상·예산 검증
  → 필요 Tool/RAG를 제한된 경로로 실행
  → 근거가 있는 사실과 해석을 분리해 응답
```

완전 자유로운 반복형 Agent 대신 LLM이 의도를 분류하고 서버가 실행 경로를 결정하는 제한된 orchestration을 권장한다. 초기에는 공급자의 native function-calling loop를 새로 도입하지 않는다. 계획 adapter와 종합 adapter는 기존 공급자 경계를 확장하되 Basic RAG 답변 계약은 유지한다.

계획의 필드는 route(KNOWLEDGE/STATE/MIXED/CLARIFY/UNSUPPORTED), subject(NONE/ORDER/QUOTE/MARKET/PRICE/PORTFOLIO/ABNORMAL) 등 닫힌 enum이다. LLM이 SQL, URL, 역할, 임의 Tool 이름/인자나 임의 userId를 제안하게 하지 않는다. 잘못된 계획은 재계획 반복 없이 안전하게 중단한다. 분류도 신뢰할 권한 근거가 아니며 모든 후속 조회가 서버 인가를 거친다.

대상별 서버 경로:

- ORDER: getOrder 선확인. MIXED면 getBlockchainTransaction, 연결이 있고 필요한 경우 getReceiptSummary, 유효한 연결 quoteId가 있으면 getQuote. 최대 4회, 중복 조회 결과는 같은 run에서만 재사용한다.
- QUOTE: getQuote. 만료·소비·관측 시각과 signed-quote-policy를 필요 시 결합한다. quote에서 임의 다른 주문으로 탐색하지 않는다.
- MARKET/PRICE: getMarketStatus 또는 getCurrentReferencePrice 중 하나. 조회 시각·공급자·신선도와 현재값/체결가 구분을 보존한다.
- PORTFOLIO: getPortfolio. ADMIN이어도 본인 기준이며 타인 포트폴리오 조회를 추가하지 않는다.
- ABNORMAL: ADMIN만 listAbnormalOrders, 최대 10개 한 페이지. 자동 pagination·각 주문 재귀 진단은 하지 않는다. WAITING_LONG의 시간 기준은 명시적 검증 입력/기존 기본값을 표시하며 새로운 상태로 해석하지 않는다.

각 경로는 범용 Tool 실행 allowlist일 뿐 Phase 6 Skill 엔진이나 진단 결론 하드코딩이 아니다. DB 정산·서명·broadcast·reconciliation을 호출하지 않는다.

## 3. Entry point와 대상 식별

제안 endpoint: `POST /api/ai/agent/answers` (USER/ADMIN), 독립 `AI_AGENT_ENABLED=false`. 기존 `/api/ai/index`, `/search`, `/answers`는 ADMIN-only를 유지한다. Tool endpoint와 기존 응답 형식도 보존한다.

활성화 조합: Agent는 AI_AGENT_ENABLED=true와 AI_ENABLED=true를 함께 요구한다. 현재 provider/store Bean은 AI_ENABLED 조건부이므로 Agent flag만 켠 잘못된 조합은 Agent 요청 503으로 격리하고 거래 서버 기동을 실패시키지 않는다. AI_TOOLS_ENABLED=false이면 Tool 경로는 안전하게 실패하고 target 없는 knowledge-only는 허용한다. target이 있으면 소유권 선확인을 생략해 우회하지 않는다. Agent가 꺼져도 기존 RAG/독립 Tool의 활성화 설정과 동작은 유지한다.

```json
{"question":"이 주문이 왜 아직 대기 중인가요?","target":{"orderId":153}}
```

question은 1~1000자, 전체 입력 4KiB 이하. target은 생략하거나 orderId/quoteId 중 하나만 허용하고 unknown field·중복 key·후행 JSON을 거부한다. 본문에서 userId/role/JWT/기존 evidence를 받지 않는다. 특정 주문/견적 질문에 명시적 target이 없거나 질문과 target이 충돌하면 NEEDS_CLARIFICATION을 반환한다. 모델이 임의 ID를 생성하거나 문서 속 ID를 조회 대상으로 사용하지 않는다.

target이 제공되면 계획 모델 호출 전 기존 getOrder/getQuote 경계로 소유권을 확인한다(이 호출도 Tool 예산에 포함). 실패 시 타인/미존재 구분 없는 404이며 LLM에 대상 데이터·존재 여부를 보내지 않는다. 관리자 전용 요청은 USER에게 역할 확대 없이 거부한다. mutation 요청은 실행 경로가 없고 거부/미지원으로 응답한다.

초기 API는 단일 turn, stateless로 제안한다. 클라이언트가 이전 메시지를 근거로 주입하는 history와 서버 영속 memory를 받지 않는다. 후속 질문에는 대상과 질문을 다시 명시한다. master guide의 최소 session context는 이 서버 검증 target으로 한정하며 다중 turn 기억 기능을 구현했다고 표시하지 않는다.

## 4. 필요한 권한 확장 — 구현 시 반드시 변경할 경계

현재 RagService는 ADMIN-only이고 PgKnowledgeStore SQL은 USER/ADMIN 문서를 함께 검색한다. 이를 그대로 USER Agent에 호출하거나 ADMIN principal로 치환하면 안 된다.

제안: 내부 AuthorizedKnowledgeRetrieval 경계를 분리하고 서버 인증 주체로 최소 역할을 결정한다. USER는 minimum_role=USER, ADMIN은 USER/ADMIN을 SQL 후보 검색 시점에 적용한다. Top-K 이후 필터링하거나 생성 후 숨기는 방식은 금지한다. active index·manifest·hash·version 검증, K 후보 다양성·threshold·청크 설정을 그대로 유지한다.

기존 ADMIN-only RagService/API는 이 경계에 위임하되 기존 정책/오류/LIVE_DATA_REQUIRED 계약을 유지한다. 색인은 ADMIN만 발행하고 USER Agent에는 색인 기능을 노출하지 않는다. USER에게만 근거가 부족한 경우 관리자 runbook을 대신 주지 않고 INSUFFICIENT_EVIDENCE로 응답한다.

ToolDispatcher에는 서버가 생성한 agentRunId/purpose와 인증 컨텍스트를 전달하는 내부 호출 경계를 추가한다. 현재 독립 Tool HTTP 경로의 READ_ONLY_TOOL_TEST 계약은 보존한다. Agent는 ToolReadFacade/JPA를 직접 호출하지 않고 기존 인자 검증·실행 상한·audit를 통과한다.

## 5. 근거·응답·오류 계약

제안 결과:

```text
runId, status, route, answer
knowledgeSources[]: evidenceId, path, heading, version, indexVersion
toolEvidence[]: evidenceId, tool, version, source, retrievedAt, safe facts/error
uncertainties[]
recommendedNextCheck[]
```

- status: ANSWERED/PARTIAL/INSUFFICIENT_EVIDENCE/NEEDS_CLARIFICATION/UNSUPPORTED. 미인증 401, 권한 403, 타인/미존재 404, 형식 400, 전체 의존성 실패/비활성/busy 503, 실행 시간 초과 504는 구조화된 오류로 구분한다.
- STATE는 Tool 사실을 서버가 정형 표시할 수 있으므로 불필요한 최종 생성 호출을 생략한다. 정책 설명이 필요하면 MIXED다.
- 모델은 knowledge/tool evidence ID를 인용해 종합한다. 서버는 존재하지 않는 ID, 실패 Tool을 성공 근거로 인용, 대상/단위 불일치를 거부한다. 사실 필드와 숫자는 서버가 복사한 값으로 제공하고 모델 문장은 해석으로 구분한다. ID 검증만으로 자연어의 완전한 진실성을 보장한다고 주장하지 않는다.
- Tool이 실패하면 해당 live 사실은 비워 둔다. 다른 근거만 확보됐으면 PARTIAL과 조회 실패를 명시하며 정책에서 현재 상태를 추측하지 않는다. 모든 근거가 없으면 모델을 호출하지 않는다.
- AI DB 장애라도 성공한 Tool 사실을 구조화해 반환할 수 있다. LLM 장애라면 이미 확보한 사실과 안전한 오류만 반환하고 생성 설명은 하지 않는다. Agent 자체 실패가 독립 Tool API/거래 처리에 전파되지 않는다.
- 실행 SUCCESS·확인 수·event MATCH와 DB CONFIRMED/FILLED는 구분한다. quote CONSUMED는 체결 완료가 아니며 expiredByTime과 storedStatus를 합치지 않는다. Tool 오류는 거래 실패를 뜻하지 않는다.
- DB/RPC/시장 조회는 서로 다른 시점의 스냅샷이다. 각 시각을 보존하고 충돌·부분 조회·오래된 관측은 uncertainties에 명시한다. 하나의 원자적 최신 상태라고 주장하지 않는다.
- 체인 hash는 사용자에게 필요한 evidence에만 유지하고 일반 모델 prompt에서는 제거한다. 개인 ID·JWT·서명·원시 오류는 모델/로그에 전달하지 않는다. 최종 응답도 기존 DTO보다 권한 범위를 넓히지 않는다.
- retrieved 문서, Tool 문자열, 사용자 질문은 지시가 아닌 데이터다. 출처 안의 명령으로 Tool allowlist·권한·대상을 변경하지 않는다. 근거 없는 원인은 가능성/확인 불가로 구분하며 투자 조언을 생성하는 목적이 아니다.

## 6. 실행 예산과 장애 격리

제안 기본값(구현/테스트에서 검증할 값): 계획 1회 + 종합 최대 1회, Tool 최대 4회(선확인 포함), retrieval 1회, 동일 Tool/args 중복 금지. 재시도·자동 재계획 없음.

전체 40초 deadline, 계획 최대 5초, 종합 최대 10초, Tool은 기존 호출당 5초/DB 2초/RPC 3초 이하이면서 남은 전체 시간으로 더 제한한다. 검색/외부 HTTP에도 남은 예산과 취소를 전파한다. 단계별 상한의 합을 모두 보장하지 않으며 시간이 부족하면 PARTIAL 또는 timeout으로 종료한다.

Agent 동시 worker 2개·대기열 없음, 취소 후 실제 작업이 끝나야 슬롯을 재사용한다. Tool 4-worker 상한은 유지한다. 모델 입력 evidence 합계 24KiB, 최종 응답 64KiB, 생성 상한 계획 400/종합 1600 output tokens를 제안한다. 초과 사실을 조용히 잘라 완전한 결과처럼 보이지 않고 명시적으로 제외/실패 처리한다.

Agent 전체를 거래 DB 트랜잭션으로 감싸지 않는다. AI DB와 거래 DB의 분산 트랜잭션을 만들지 않는다. audit에는 runId·역할·route·Tool명·소요시간·결과 코드·호출 수·제공자가 반환한 사용량만 기록하고 질문/근거 payload를 영속 저장하지 않는다. 가격표 기반 장기 비용 관리나 다중 서버 rate limit 구현으로 과장하지 않는다.

## 7. 구현 위치와 검증 계획

예상 변경 위치:

- `backend/.../ai/agent/`: controller, orchestration, plan validator, evidence assembler, bounded execution.
- `ai/provider/`: 제한된 계획/종합 adapter. 기존 Basic RAG adapter 계약 유지.
- `ai/retrieval/`, `ai/store/`, `RagService`: 내부 역할 필터 경계 및 ADMIN 기존 API 호환.
- `ai/tool/ToolDispatcher`, `ToolContext`, `ToolAudit`: 서버 run context 전달, 기존 인가/조회 계약 유지.
- `common/config/SecurityConfig`, AI 설정/example: 정확한 Agent endpoint만 개방, 기본 비활성.
- `backend/src/test/.../ai/agent`, `src/test/resources/ai`: 정형 시나리오 및 fake provider 우선. 실제 공급자 평가는 명시적 opt-in.

필수 테스트:

1. 3개 route의 정상 결과, target 누락/충돌, invalid plan/Tool/argument, mutation 거부.
2. USER 본인 성공/타인·미존재 동일 거부, ADMIN 운영 조회, ADMIN 본인 포트폴리오 경계. HTTP와 내부 경계 모두 검사.
3. USER query SQL 후보에 ADMIN runbook이 들어오지 않음. LLM 입력·출력·trace에 관리자 문서나 타인 사실이 없는지 canary 검증. 기존 RAG API ADMIN-only 회귀.
4. 문서/질문/Tool 문자열의 prompt injection, 위조 인용·위조 현재값, 실패 Tool 인용, 잘못된 단위, 미래 기능의 완료 주장 거부.
5. quote 만료와 소비, BUY/SELL, receipt 미발견/실패/확인 부족/불일치, DB 상태와 chain 상태 차이, 부분 실패·충돌의 근거 표현.
6. 시간·동시성·메모리·출력·호출 예산, 비협조적 worker 취소/용량 회복, provider/AI DB/RPC 장애 후 기존 모의 거래 유지.
7. 실제 PostgreSQL 읽기 전후 원장 불변, 기존 Anvil receipt 조회 전후 nonce·잔고·블록 불변. 테스트를 위해 거래를 자동 생성하지 않음.
8. 고정 golden 12개·동일 K 검색 회귀(ADMIN 기준 비교), USER 공개 지식 별도 세트. 기존 성공·MRR·직접 근거·무관 질문 오탐을 분리 측정.
9. fake provider의 결정적 assertion을 주 검증으로 삼고 실제 공급자 소규모 knowledge/state/mixed 평가를 별도로 기록한다. LLM-as-judge만으로 통과시키지 않음.
10. 전체 backend 회귀, 변경 영향에 따른 contracts/web 검증, 별도 검토·문서 갱신 후 사용자 완료 승인.

## 8. 승인 요청

이 Phase는 하나의 승인 범위다. 내부 작업 순서는 역할 필터/실행 경계 → 제한된 orchestration/근거 응답 → 장애·권한·실연동 평가이며 불필요한 별도 Phase로 쪼개지 않는다.

승인할 핵심은 **USER Agent를 위한 SQL 역할 필터 확장**, **명시적 target·stateless 입력**, **분류 1회와 서버 제한 실행**, **근거/불확실성 응답 및 예산**이다. 사용자 승인 후 아래 구현을 진행했다.

## 9. 실제 구현·검증 기록

### 코드와 계약

- `backend/src/main/java/com/pricetrack/exchange/ai/agent/`: controller/service/request/evidence/response, 닫힌 plan과 provider adapter, 설정·오류 경계. Agent에서 거래 서비스나 repository를 직접 호출하지 않는다.
- `ai/retrieval/AuthorizedKnowledgeRetrieval`, `ai/store/PgKnowledgeStore`: 실제 principal의 역할을 SQL 후보에 적용한다. USER=USER 문서, ADMIN=USER/ADMIN, K 설정 유지. 기존 ADMIN Basic RAG는 호환 entry를 사용한다.
- `ai/provider/OpenAiProvider`: strict JSON plan/interpretation, 남은 HTTP deadline, `store=false`. 공급자에 실행 Tool을 등록하지 않는다. [Structured Outputs 공식 계약](https://developers.openai.com/api/docs/guides/structured-outputs)을 따른다.
- `ai/tool/ToolDispatcher`, `ToolContext`: 서버 UUID runId와 AGENT_READ_ONLY 목적을 전달한다. 독립 Tool API의 기존 인가·DTO·audit·worker 상한을 보존한다.
- 정확한 POST `/api/ai/agent/answers`만 USER/ADMIN에 허용한다. `AI_ENABLED=true`, `AI_AGENT_ENABLED=true`, 상태 조회에는 `AI_TOOLS_ENABLED=true`가 필요하다. 실제 `.env`와 실행 서버는 자동 변경하지 않았다.

STATE는 분류 1회+Tool만 실행한다. KNOWLEDGE는 retrieval+종합, MIXED는 선인가 Tool+역할별 RAG+종합이다. 모델에 targetKind만 전달하며 실제 ID는 보내지 않는다. 명백한 실행 요청은 거부하고 임의 Tool/SQL/URL·history·userId·role 입력을 지원하지 않는다.

성공 Tool 사실은 서버 DTO를 복사하고 모델 prompt에서 개인 ID·체인 hash·서명을 제거한다. JSON pointer/value·출처 ID를 검증하지만 모든 자연어 문장의 진실성을 보장하지 않는다. 각 관측 시각을 보존하며 원자적 최신 스냅샷이라고 해석하지 않는다.

주문/견적의 방향·입력량·단위 불일치, receipt 미발견·event mismatch·확인 부족, 체인 성공/DB 미확정은 불확실성으로 명시한다. 정산·재전송·잔고 변경은 하지 않는다. 공급자/AI DB 장애에서는 이미 확보한 사실만 PARTIAL로 제공한다.

### 검증 위치·한계

- 결정적 테스트: AgentServiceTest, AgentIntegrationTest, AgentConfigurationIsolationTest, AuthorizedKnowledgeRetrievalTest, OpenAiAgentProviderTest. route·JWT·USER/ADMIN·선인가·위조 인용/사실·실패 Tool·비밀값·예산·비협조적 worker·장애 격리와 모의 거래를 검사한다.
- 실제 PostgreSQL: PgKnowledgeStoreIntegrationTest의 role-before-TopK canary, ToolPostgresIntegrationTest의 Agent STATE와 원장 불변성. 전용 exchange_ai_test/exchange_tool_test만 사용한다.
- 실제 Anvil: 재시작한 체인은 블록 0. AgentAnvilIntegrationTest는 실제 NOT_FOUND RPC와 nonce/잔고/블록/DB 불변성을 검사한다. 성공 receipt MATCH는 이번 체인에서 재검증하지 못한 항목이며 fake RPC·이전 Phase 4 검증과 구분한다. 이를 위해 거래를 자동 생성하지 않는다.
- 실제 공급자: LiveAgentEvaluationTest는 기존 gpt-5.6-terra·실제 pgvector·격리 H2 Tool fixture·시뮬레이션 시장을 사용한다. USER 정책/시장/주문/혼합 주문·견적, ADMIN 정책 6개다. Toss/운영자 온체인 거래 평가로 표시하지 않는다.
- 첫 공급자 평가에서 USER 정책·시장 STATE는 통과했으나 명시적 주문을 CLARIFY로 분류했다. targetKind가 서버의 대상 확인 결과임을 명확히 전달하도록 prompt를 보완하고 동일 시나리오를 재평가한다. 실제 ID를 모델에 보내는 대안은 채택하지 않았다.
- 지식: 9개 구조·역할 유지, authorization-policy/system-overview v4, recovery runbook v2. manifest 해시 갱신. 새로운 corpus 평가를 Phase 5 보고서에 기록하며 과거 Phase 3/4 결과는 보존한다.
- 전체 회귀·실제 공급자·golden 회귀·별도 검토 결과는 완료 후 기록한다. 진행 중을 완료로 표시하지 않는다.

### 실행 완료 결과 (검토 전)

- 전체 backend: 260개 중 **251 통과 / 9 skipped / 실패·오류 0**. 실제 pgvector 2개, 거래 테스트 PostgreSQL 3개, Agent Anvil 1개 포함. skip은 LiveAgent/KnowledgeRefresh/LiveRag/RetrievalAnswer/RetrievalExperiment 각1, 기존 event 필요 ToolAnvil 1, 거래 전송 Anvil 2, 견적 동시성 PostgreSQL 1이다. 유료 공급자/현행 corpus 회귀는 별도로 실행하며 과거 실험과 거래 전송은 이번에 다시 실행하지 않는다.
- 실제 Agent 공급자 최종 6/6: KNOWLEDGE 2, STATE 2는 ANSWERED; MIXED 2는 성공 Tool+문서 인용·검증된 사실을 제공하며 확인하지 못한 상태는 PARTIAL. PLAN/SYNTHESIS/RAG 실패로 인한 PARTIAL은 최종 결과에 없다. target 선소유권 거부 404·모델0, USER 공개 문서 질문3개와 SQL 권한도 검증했다.
- 성공한 최종 Agent 평가의 제공자 사용량: input 10,174 / output 1,838 tokens (이전 실패 평가·embedding·golden 비용을 포함한 전체 사용량은 아니다). 지연 1.702~10.095초; Tool 0~3, retrieval 0~1, 모델1~2. H2 fixture/시뮬레이션 시장 평가이며 일반화 성능이나 실제 장중 체결 진단으로 과장하지 않는다.
- 실제 공급자 실패 평가의 기록: 첫 target CLARIFY, 종합 문자열 참조 오류를 재현해 안내를 보완, 명시적 null 사실 거부를 수정했다. 최종 테스트는 정상 불확실성 PARTIAL을 허용하지만 SYNTHESIS_UNAVAILABLE/PLAN_UNAVAILABLE/RAG_UNAVAILABLE은 통과시키지 않는다.
- 컨트랙트36/36, 웹30/30·빌드87모듈 통과. 거래·컨트랙트·웹 제품 코드는 변경하지 않았다.
- 현행 corpus(9문서/54청크): golden hit@5 12/12, MRR@5 0.8819444444444443, 직접 근거12/12, K 성공 회귀0. 무관 후보5/8·답변 오탐0/8, 핵심 ANSWERED2/2, 식별자 hit6/8. Phase 2 baseline10/12와 Phase 3 K 결과를 유지한다. 같은 개발 세트의 회귀 검사이며 품질 일반화 증명은 아니다.
- golden 회귀 통과 후 별도 opt-in으로 로컬 exchange_ai 색인을 발행했다. 활성 fingerprint=1c94dd53978602b4c04a132777f65e1b893e8ded67675f4f0c40db316185630a. 기존 거래 DB/AI 볼륨은 삭제하지 않았다.
- [공유 수치](phase-5-results.json)는 개인정보 없는 요약이다. 원시 보고서는 아래 build 경로에 있고 과거 Phase 3/4 보고서는 보존했다. 최종 XML은 마지막 표적 평가가 덮어쓰므로 전체260 결과와 별도 유료 평가1+1을 구분한다.
- 최초 별도 review_ai_phase5: 필수 수정3건(승인 무효화 뒤 문서 반환, STATE 오래된 가격 표시 누락, 실패 audit 수치0). 검토자가 격리 표적23개를 직접 실행해 통과했고 코드로 누락을 확인했다. 실제 공급자/DB/Anvil/전체/forge/웹은 직접 재실행하지 않았다.
- 2026-10-01 수정: 모든 문서 출력 경로에서 승인 검증을 재확인하고 검증 실패 시 knowledge/index/citations/생성 해석을 폐기한다. 성공 Tool만 있으면 사실만 PARTIAL, 없으면503이다. 공급자 실패 fallback도 동일 검증을 통과한다. STALE/DEGRADED/INITIALIZING와 포트폴리오 reference를 불확실성에 표시하며 기존 가격 정책·신선도 기준은 변경하지 않는다. 실패/timeout audit은 관측한 호출 수·받은 사용량만 보존하며 미완료 사용량을 추정하지 않는다.
- 결정적 보완3개와 기존 denied/timeout assertions를 추가했다. 표적 Agent/RAG 실행은 통과했다. 마지막 제품 코드 변경 이후 전체 회귀·공급자와 재검토를 다시 진행하며 최초260개 결과와 구분한다.

### 검토 수정 후 최종 실행 (2026-10-01, 재검토 대기)

- backend **263개 중254 통과/9 skipped/실패·오류0**. skip 항목은 위 최초 전체 실행과 동일하며 실제 pgvector·거래 테스트 PostgreSQL·Agent Anvil NOT_FOUND/불변성을 다시 실행했다. 제품 변경은 Agent에 한정되어 앞서 통과한 forge36·웹30/build87을 추가 재실행하지 않았다.
- 실제 공급자 동일6개 재실행 통과. route6/6, ANSWERED5·정상 PARTIAL1이다. MIXED 주문은 확인 불가 receipt/원인과 조회 시점 차이를 표시하고, MIXED 견적은 조회된 상태·만료 정책을 설명하며 체결 완료를 주장하지 않는다. 정상 PARTIAL의 개수는 LLM이 표시한 불확실성에 따라 달라질 수 있지만 검증되지 않은 해석·의존성 실패를 정상 통과시키지 않는다.
- 최종 성공 실행 input10,153/output1,740 tokens, 지연1.569~10.638초. 이전 최초 성공 실행과 실패 평가·embedding·golden을 포함한 합계는 아니다. raw Agent 보고서는 최종 실행으로 교체됐다. corpus/검색 설정·retrieval 코드는 검토 수정에서 바꾸지 않았으므로 위 golden 수치·발행 fingerprint는 그대로다.
- 현재 별도 재검토 대기. 최초 필수3건의 수정과 새 테스트·전체/공급자 결과를 재검토 범위로 전달한다.

### 최종 별도 검토·완료 상태

- review_ai_phase5 재검토1회 결론: **발견된 필수 수정 없음**. 최초3건 해결과 회귀를 독립 확인하고 AgentService19·AuthorizedKnowledgeRetrieval4·OpenAiAgentProvider3, 총26개를 직접 재실행해 실패/오류/skip0을 확인했다. 최신 Gradle XML은 검토자26개 결과이며 전체263개 결과와 구분한다.
- 원시 실제 공급자 보고서·공유 수치의 route6/6, ANSWERED5/PARTIAL1, 사용량·지연을 대조했다. 전체 backend·실제 공급자·공유DB/Anvil·forge/웹은 검토자가 재실행하지 않고 제공 실행 결과와 코드를 검토했다.
- 승인된 제한 orchestration 구현과 검증을 마쳤으며 사용자 완료 승인 대기다. 실제 체인 성공 receipt MATCH 재검증, 자연어 진실성 한계는 위에 명시한 대로 남는다. Skill·이벤트 자동 진단·AI UI·장기 기억은 구현하지 않았다.
- 실제 `.env`·실행 서버 설정과 기존 미커밋 변경은 보존했다. Agent는 기본 비활성이고 활성화 방법은 backend README를 따른다. 커밋은 실행하지 않았다. 최종 검토 이후 변경은 결과·완료 상태 기록뿐이며 제품 코드나 테스트는 변경하지 않았다.

### 재현·보고서

backend에서 AI_PGVECTOR_TESTS=true, AI_TOOL_POSTGRES_TESTS=true, AI_AGENT_ANVIL_TESTS=true로 `./gradlew test --no-daemon --rerun-tasks`를 실행한다. 기존 AI_TOOL_ANVIL_TESTS는 실제 Bought/Sold event가 있는 체인에서만 활성화한다.

OPENAI_API_KEY를 출력 없이 주입하고 AI_AGENT_LIVE_EVALUATION=true로 LiveAgentEvaluationTest를 별도 실행한다. 고정 golden은 AI_KNOWLEDGE_REFRESH_EVALUATION=true, AI_PHASE5_EVALUATION=true로 KnowledgeRefreshEvaluationTest를 실행한다. AI_PUBLISH_CURRENT_INDEX=true는 회귀 성공 후 로컬 AI 색인만 발행하는 별도 opt-in이다. 실제 공급자 테스트와 다른 색인 교체 테스트를 동시에 실행하지 않는다.

원시 결과: `backend/build/reports/ai/phase5-agent-evaluation.json`, `phase5-knowledge-refresh.json`. 커밋 메시지: `feat(ai): 권한 기반 RAG와 읽기 전용 Tool Agent 구현 및 검증`.

## 10. 사용자 승인

2026-10-01 사용자 Phase 5 완료 승인. 후속 [Phase 6 Skill 설계안](phase-6-skills.md)을 제안하며 승인 전 구현하지 않는다. 기존 수치·미검증 한계는 그대로 유지한다.
