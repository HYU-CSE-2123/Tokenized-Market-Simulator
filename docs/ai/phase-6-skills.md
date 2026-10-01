# AI Phase 6 — 제한된 Skill 진단 절차 설계안

> 2026-10-01 / 구현·검증·별도 검토 완료, 사용자 완료 승인 대기. Phase 5는 사용자 완료 승인됨.
> 기준: master guide의 Phase 6, 실제 AgentService/ToolRegistry/ToolReadFacade/ReadOnlyReceiptClient와 현재 지식 metadata.
> 1~10절은 승인받은 설계 기준이다. 실제 구현과 검증 기록은 11절 이후에 누적한다.

## 1. 목적과 Phase 5와의 차이

Phase 5는 질문을 KNOWLEDGE/STATE/MIXED로 나누고 서버 고정 경로로 조회·설명한다. Phase 6은 반복 조사에 **이름·버전·권한·조회 순서·확인 기준·중단 조건·trace**를 부여한다. 모델이 매번 조사 절차를 새로 만들거나 실행 중 도구를 자유롭게 추가하지 않는다.

책임은 다음과 같이 유지한다.

- RAG: 규칙과 운영 지식.
- Tool: 조회 시점의 사실. 진단 결론을 Tool facade에 넣지 않는다.
- Skill: 반복 가능한 조사 절차와 실행 범위.
- Agent: 적합한 절차 선택과 근거를 사용한 해석. 권한·실행·근거 검증은 서버 책임이다.

이번 제안은 세 Skill을 한 Phase로 구현한다. 최소 완료 기준은 master guide의 settlement-debugging이며, 같은 경계에서 signed-quote-diagnosis와 작은 market-availability-diagnosis도 함께 검증한다. 별도 workflow engine/범용 DSL/새 라이브러리·서비스·DB schema를 도입하지 않는다.

## 2. Skill 범위와 역할

| Skill | 권한·대상 | 허용 Tool | 검색 domain |
|---|---|---|---|
| settlement-debugging | ADMIN, orderId 필수 | getOrder, getQuote, getBlockchainTransaction, getReceiptSummary | trading, settlement, operations, support |
| signed-quote-diagnosis | USER/ADMIN, quoteId 필수 | getQuote, getOrder, getBlockchainTransaction, getReceiptSummary | trading, settlement, support |
| market-availability-diagnosis | USER/ADMIN, target 없음 | getCurrentReferencePrice | market, trading |

settlement-debugging은 운영자용 runbook을 포함하는 정식 운영 절차로 ADMIN-only를 제안한다. USER의 기존 본인 주문 MIXED 설명을 막거나 기존 역할을 확대하지 않는다. 사용자 요청에 관리자 Skill을 대신 적용하지 않는다.

signed-quote-diagnosis의 USER는 본인 견적과 서버가 검증한 연결 주문만 조회한다. ADMIN이어도 모델이나 요청 body의 userId로 조회 대상을 바꾸지 않는다. 시장 Skill은 한 번의 가격 Tool 스냅샷에 포함된 시장·가격·관측 시각을 이용해 두 시점의 값을 합치지 않는다.

## 3. 정의 파일과 registry

승인 후 정의 파일은 `backend/src/main/resources/ai/skills/<id>.md`에 두어 배포 artifact에 포함하는 방식을 제안한다. Markdown 절차와 제한된 YAML front matter로 master guide의 필드를 모두 제공한다.

```yaml
id: settlement-debugging
name: 온체인 정산 조사
purpose: 주문·견적·전송·receipt와 정산 규칙의 관계를 확인
version: 1
allowedRoles: [ADMIN]
preconditions: [ORDER_TARGET_REQUIRED, OWNED_OR_ADMIN_AUTHORIZED]
allowedTools: [getOrder, getQuote, getBlockchainTransaction, getReceiptSummary]
retrievalDomains: [trading, settlement, operations, support]
steps: [LOAD_ORDER, CHECK_ORDER, LOAD_LINKED_QUOTE, LOAD_TRANSACTION,
        LOAD_RECEIPT, CHECK_EVIDENCE, RETRIEVE_POLICY, SUMMARIZE]
stopConditions: [AUTHORIZATION_DENIED, INVALID_LINK, DEADLINE_EXCEEDED]
forbiddenActions: [WRITE_DB, SIGN, BROADCAST, FORCE_SETTLEMENT, CHANGE_BALANCE]
outputSchema: diagnostic-v1
```

- `SkillDefinitionLoader`: 알려진 필드·ID·역할·도구·domain·단계 enum만 허용. 크기 상한, 중복 key, 경로 참조, YAML 실행 tag/alias와 임의 표현식은 거부한다. 파서 선택은 기존 dependency 범위에서 한다.
- `SkillRegistry`: 승인된 세 ID와 고정 handler를 연결한다. manifest의 version/content hash와 정의를 검사한다. 허용 역할·Tool·domain은 코드의 상한과도 대조하며 파일이나 모델만으로 권한을 넓힐 수 없다.
- `SkillRunner`/handler: 서버가 정의 순서의 알려진 단계를 실행한다. 조건은 연결 유무·성공/실패·기존 상태 등 닫힌 판정이며 eval, shell, SQL, URL, 모델이 만든 nextStep을 실행하지 않는다.
- 정의는 정적 배포 자산이다. 사용자 업로드, 외부 Skill 다운로드, 실행 중 hot reload는 제외한다. 버전 변경은 정의·manifest·시나리오 테스트를 함께 검토하는 코드 배포로 한다.
- 정의 오류는 해당 Skill을 안전하게 비활성화하고 요청을 503으로 격리한다. 거래 서버 기동이나 기존 Phase 5 질문 API 전체를 실패시키지 않는다.

이는 개발 도구용 Codex Skill 설치가 아니라 **이 애플리케이션 내부의 진단 절차**다. 절차 문서를 기존 9개 RAG 정책과 무분별하게 섞어 embedding하지 않는다.

## 4. 기존 Agent 연결과 선택

기존 `POST /api/ai/agent/answers`를 유지하고 optional `skillId`만 추가한다. version은 서버 registry가 결정하며 사용자가 정의 내용·step·Tool·domain을 제출할 수 없다.

```json
{
  "question": "이 주문의 정산 대기 상태를 조사해줘",
  "target": {"orderId": 153},
  "skillId": "settlement-debugging"
}
```

실행 흐름:

```text
JWT·입력 검증
→ 명시 Skill이면 역할·대상 종류 검증
→ 대상 Tool 선인가 (조회 예산에 포함하고 재사용)
→ 명시 Skill 또는 기존 분류 1회에서 eligible Skill 선택
→ 서버 registry·역할·target·예산 검증
→ 제한된 조사 단계 + domain/role 제한 RAG 1회
→ 사실·정책·가설·중단/불확실성·trace 반환
```

- 명시 Skill은 분류 모델 호출을 생략한다. 고정 절차와 target이 이미 정해져 있어 LLM이 읽기 순서를 결정할 필요가 없다. 종합은 최대1회다.
- skillId가 없으면 기존 분류 호출 안에서 선택한다. 선택지는 역할·target에 맞는 승인 Skill ID 또는 NONE뿐이다. KNOWLEDGE/STATE는 기존 경로를 유지하며, 적합한 MIXED 진단만 Skill을 선택한다. 한 run에서 최대1개 Skill, 재계획·다른 Skill 재귀 실행·자동 fallback 확장은 금지한다.
- 잘못된 선택은 재계획 없이 안전하게 거부/추가 대상 확인으로 종료한다. USER에게 settlement-debugging을 적용하거나 target 없는 주문을 모델이 생성하지 않는다.
- 신규 `AI_SKILLS_ENABLED=false`를 제안한다. AI_ENABLED/AI_AGENT_ENABLED와 독립 Tool 경계를 그대로 요구한다. Skill 비활성 시 일반 질문은 기존 Phase 5로 처리하고 명시 Skill 요청은 비활성 오류로 종료한다.
- 외부 LLM 장애에서도 명시 Skill에서 확보한 사실·서버 확인 결과를 PARTIAL로 반환할 수 있다. 모델이 선택을 완료하지 못했다면 Skill을 실행했다고 표시하지 않는다.

## 5. 진단 순서와 중단 조건

### 5.1 settlement-debugging

1. getOrder로 인가된 주문·방향·입력·상태·체결 요약 확인.
2. 연결 quoteId가 있으면 getQuote로 조회. 연결·종목·방향·입력·단위 일치 확인.
3. getBlockchainTransaction으로 저장 전송 상태와 연결 유무 확인.
4. 연결이 있을 때 getReceiptSummary로 receipt·execution·확인 수·Bought/Sold 의미 검증 결과 조회.
5. 제한 domain에서 주문·정산 정책과 ADMIN recovery runbook 검색.
6. 확인된 관측, 조건 불일치, 원인 후보, 추가 확인 사항을 분리해 설명.

선인가 getOrder는 중복 호출하지 않는다. 최대4 Tool, 조회 순서는 고정이며 연결 없으면 해당 단계는 SKIPPED다. FAILED/FILLED라고 해도 권한 범위 안의 연결 사실을 확인할 수 있지만 상태를 변경하지 않는다. raw transaction·nonce·서명 원문을 열어 보는 단계는 없다.

### 5.2 signed-quote-diagnosis

1. getQuote로 선인가 및 storedStatus, expiredByTime, observedAt, validUntil, consumedAt, 방향·입력·minimumOutput 확인.
2. 서버가 검증한 연결 orderId가 있을 때만 getOrder. 되돌아온 quote 연결도 같은 대상인지 확인.
3. 유효한 연결 주문만 getBlockchainTransaction, 필요 시 getReceiptSummary 조회. 최대4 Tool.
4. signed-quote-policy와 허용된 정산/상태 지식 검색 후 결과를 설명.

만료와 소비는 독립 관측이다. CONSUMED와 expiredByTime=true는 동시에 표시할 수 있으며 하나로 덮어쓰지 않는다. **현재 만료됐다는 사실이 과거 체인 실패의 원인임을 뜻하지 않는다.** DB submittedAt은 채굴 시각이 아니며 현재 Tool에 없는 block timestamp·revert 원인을 가정하지 않는다.

타인 견적과 없는 견적은 동일 RESOURCE_NOT_FOUND/404로 끝낸다. master guide의 ownership mismatch 예시를 별도 상세 진단으로 노출하지 않는다. 선인가 실패에는 소유자·상태·문서·모델 호출이 없다.

### 5.3 market-availability-diagnosis

1. getCurrentReferencePrice 한 번으로 시장 상태·priceStatus·provider·observedAt·reference price 확인.
2. market-data-policy/signed-quote-policy 검색.
3. CLOSED/STALE 등의 관측과 발급 규칙을 결합한다. SIMULATED는 Toss 실시간과 구분한다.

현재 시장 상태로 과거 주문 거부 원인을 단정하지 않는다. priceStatus=LIVE도 새로운 quote 발급 성공을 보장하지 않는다. 기존 발급기의 별도 신선도 검증·RPC·잔고 등은 실제 실행하지 않고 필요한 확인 조건으로 남긴다. provider 원시 로그 조회나 새로운 거래 가능 여부 정책을 추가하지 않는다.

### 공통 stop 조건

- 역할/소유권/대상 종류 오류: 데이터 조회 확대 없이 종료.
- 연결 대상 불일치: 그 분기 추가 조회 중단, 인가된 기존 사실과 불일치만 반환.
- Tool 실패/timeout: 실패를 거래 FAILED로 해석하지 않음. 의존 단계만 SKIPPED, 독립적인 기존 대상 조회는 남은 예산 안에서 계속 가능.
- receipt 미발견/확인 부족: 반복 polling 없이 관측 기록. 길게 대기했다는 임의 시간 기준을 추가하지 않음.
- 시간·호출·context/output 예산 소진: 자동 재시도 없이 PARTIAL 또는 기존 timeout/error 계약.
- 문서 승인 검증 실패: Phase 5처럼 정책 근거·생성 해석 폐기. Skill 정의 검증 실패에는 그 정의에 기반한 진단/trace 해석도 사용하지 않음.

## 6. 결과와 근거의 한계

기존 AgentResponse 필드를 보존하고 Skill 사용 시에만 optional diagnostic metadata를 추가한다.

```text
skill: id, version, definitionHash
diagnosis: classification, observedFindings[], hypotheses[]
skillTrace[]: stepId, status, actionName, evidenceRefs, resultCode, latencyMs
```

trace는 최대12항목, 내부 절차는 최대10단계를 제안한다. 단계는 EXECUTED/SKIPPED/UNAVAILABLE 등 닫힌 결과만 기록한다. 본문·전체 Tool DTO·target ID·txHash·개인 ID·JWT·서명·원시 오류·LLM 사고 과정은 trace/log에 저장하지 않는다. 서버의 runId와 단계·안전한 코드·출처 참조로 조사 순서를 확인한다. trace는 이번 응답과 안전한 audit에만 제공하고 진단 DB 저장은 Phase 7로 남긴다.

진단 classification은 **새 AI 결과 분류**이지 OrderStatus나 실제 오류 코드를 추가하는 것이 아니다. 예: SETTLED_OBSERVED, WAITING_OBSERVED, FAILURE_OBSERVED, REVIEW_REQUIRED_OBSERVED, INCONSISTENCY_OBSERVED, INSUFFICIENT_EVIDENCE. 모델이 제안하더라도 해당 분류에 필요한 서버 관측 조건을 통과해야 한다.

- receipt 없음/확인 부족은 대기 관측이지 정상 운영이나 영원한 실패의 증거가 아니다.
- REVIEW_REQUIRED 관측은 저장 트랜잭션 상태가 근거다. event mismatch만으로 이미 REVIEW_REQUIRED가 저장됐다고 말하지 않는다.
- receipt SUCCESS/MATCH와 DB CONFIRMED/FILLED/체결 요약을 분리한다. 성공 정산을 재실행할 이유로 쓰지 않는다.
- quote는 EXPIRED_BY_TIME/CONSUMED_RECORDED 등의 독립 findings로 표시하고 order/chain 완료를 추론하지 않는다.
- 체인 FAILED는 관측할 수 있지만 Oracle 서명·만료·Vault 최소 수령량 중 **어떤 검증이 원인인지는 현재 Tool만으로 확정할 수 없다**. 원인 코드가 없으면 확인 불가 또는 정책에 근거한 가능성만 반환한다.
- 사용자별 주문의 잠금액·서명자 설정·Oracle usedQuoteIds 등을 직접 검증하는 Tool은 현재 없다. 본인 총 잠금 잔고를 특정 주문 잠금으로 대체하지 않는다. 이 Phase에서 새 체인/원장 Tool을 추가하지 않는다.

관측 조건의 일치·불일치 검사는 서버가 하되 긴 원인 설명을 Tool에 하드코딩하지 않는다. 해석·원인 후보는 승인 RAG와 성공 Tool 근거를 인용하고 기존 fact pointer/value 검증을 통과한다. 근거 없는 확정 진단과 자동 수정 안내는 거부하며 자연어의 완전한 진실성을 보장한다고 주장하지 않는다.

## 7. 검색 범위와 실행 예산

현재 SQL은 role 필터만 지원한다. Skill의 retrievalDomains를 실제로 제한하려면 AuthorizedKnowledgeRetrieval/KnowledgeStore/PgKnowledgeStore에 **서버 scope를 전달하는 검색 경계**를 추가해야 한다.

- role 조건과 domain allowlist를 SQL 후보 단계에서 동시에 적용한다. 결과를 Top-K 이후 숨기지 않는다.
- scope는 registry의 승인 정의에서 결정한다. 요청/모델/문서가 임의 domain·SQL 조건을 넘기지 못한다.
- manifest/active/hash/version 검증과 K(.25/candidates40/Top5/per-document2)를 유지한다. Skill 검색 범위에 근거가 없으면 일반 전체 검색으로 자동 확대하지 않는다.
- 일반 Phase 5/Basic RAG 검색은 기존 의미를 보존하고, Skill domain 검색 품질은 별도 시나리오로 평가한다.

예산은 run 전체에 공유한다: Tool≤4(선인가 포함), retrieval≤1, 모델≤2(자동 선택1+종합1, 명시 Skill은 종합1), 전체40초·기존 단계 timeout, worker2/대기열0, context24KiB/output64KiB. Skill 전용 별도 executor나 새 run을 만들어 예산을 우회하지 않는다. 같은 run의 인가된 Tool 결과는 cache 재사용하며 서로 다른 DB/RPC 관측 시각을 보존한다.

## 8. 변경 예정 위치·제외 범위

- 신규 `ai/skill/`: Definition/Loader/Registry/Runner, 세 handler, trace/진단 DTO와 근거 guard.
- 신규 resources/ai/skills 정의3개와 승인 manifest, 테스트 전용 skill scenario fixtures.
- 기존 `ai/agent/`: optional skillId·eligible closed plan·공유 run context·근거/결과 확장. 현재 private Run의 예산/cache/실패 처리를 안전하게 분리해 중복 실행·별도 pool을 만들지 않는다.
- `ai/retrieval/`, `ai/store/`: SQL role+domain scope. `ai/tool/`은 기존 dispatcher/context/DTO 재사용하며 도구8개를 늘리지 않는다.
- 설정/example·README·Phase 보고서와 구현 로그. 구현 완료 시 지식의 Skill 미구현 설명과 manifest/index를 동기화하고 고정 golden 회귀를 검증한다. 이번 설계 단계에서는 active 지식·색인을 변경하지 않는다.

제외: 거래/정산/컨트랙트 변경, 사용자별 지갑, 새로운 mutation·Oracle 검증 Tool, UI, 이벤트 trigger, ai_diagnosis DB, 장기 memory, Kafka/Redis, workflow engine, 임의 Skill 설치. Phase 7의 자동 진단·저장·UI는 별도 설계/승인이 필요하다.

## 9. 테스트 계획과 완료 조건

1. 정의/registry: 버전·hash·중복/unknown field·미등록 Tool/단계·역할 확대·무효 자산 격리. 문서를 바꾸어 mutation 권한을 만들 수 없음.
2. 권한/선인가: USER 타인·미존재 동일404/모델0, USER 관리자 Skill403, ADMIN 운영 조회, 연결 대상 재인가·오염 링크 차단, target 종류/누락/충돌.
3. 반복성: 동일 fixtures에서 조회 순서·분기·SKIPPED·출처 scope가 일치. BUY/SELL, receipt 없음/실패/MATCH/MISMATCH/확인 부족/DB 미확정, quote 만료·소비 동시 관측, 상태 불일치 포함.
4. 근거: 위조 classification·출처·사실 값·진단 원인, 실패 Tool 인용, expired-now=failed-then 오판, ownership 정보 노출, 문서/질문/Tool/Skill 설명 injection을 거부. 정의/지식 무효화 시 근거 폐기.
5. domain SQL: 역할+domain 후보 필터 전용 pgvector canary. 범위 밖 문서가 더 유사해도 Top-K에 들어오지 않으며 자동 범위 확대 없음.
6. 예산/장애: 전체 worker 공유·취소 후 슬롯 유지, 중복 선인가 호출0, 호출 상한·trace/output 상한·부분 실패·실패 audit. 정의/AI DB/LLM/RPC 장애 후 기존 Tool/Phase 5/모의 거래 유지.
7. 실제 DB/Anvil 읽기: 전용 PostgreSQL rows와 nonce/잔고/블록 불변. 새 배포·거래 생성 없이 기존 receipt를 사용하며 성공 event가 없으면 그 항목을 미검증으로 보고.
8. 검색 회귀: 고정12개를 바꾸지 않고 현재 hit12/12·MRR0.8819444444444443·직접근거12/12·오탐0/8과 비교. Skill scoped retrieval은 별도 기대 출처 세트로 평가하고 수치를 섞지 않음.
9. 공급자: fake model의 결정적 assertions가 주 검증. 실제 공급자 소규모 명시/자동 Skill 선택·권한·근거/분류 평가를 별도 opt-in으로 기록. LLM 해석 문장이 매번 동일하다고 요구하지 않음.
10. 전체 backend 회귀·영향에 따른 contracts/web 검증·별도 검토·문서/지식/색인 갱신 후 사용자 완료 승인.

완료 보고에는 실제 정의3개·단계 trace·역할별 경계·시나리오 통과/실패·실제 공급자 결과·검색/거래 회귀·미검증을 기록한다. 기존 Phase 5 수치는 이번 Phase의 새 실행 결과로 바꿔 주장하지 않는다.

## 10. 승인 요청

제안 결정: **버전 관리된 정의+고정 registry/handler**, **운영 정산 Skill ADMIN-only/견적·시장 Skill USER+ADMIN**, **명시 선택 및 기존 분류 내 선택**, **SQL role+domain 제한**, **현재 run 예산 공유**, **단계 trace와 근거 검증된 진단**.

승인 전 제품 코드·설정·Skill 정의·DB·환경 파일은 구현/변경하지 않는다. 사용자 승인 후 이 설계를 기준으로 한 Phase로 진행한다.

## 11. 사용자 승인 후 실제 구현 (2026-10-01)

- 시작 HEAD `33a8dad`(Phase 6 설계), 직전 `9a0df88`(Phase 4·5 통합). 시작 Git 작업 트리는 깨끗했다. 커밋은 자동 실행하지 않는다.
- `ai/skill/SkillRegistry`: 정의 로딩·제한된 front matter 검증·version/hash·코드 ceiling. 기존 Jackson만 사용하고 일반 YAML 실행 기능은 만들지 않았다. resource8KiB/중복/unknown/tag/alias/경로/권한 확대를 거부하고 개별 정의 오류는 격리한다.
- `SkillRunner`: 세 고정 handler와 RunContext. Agent의 기존 private Run이 callback으로 동일 Tool cache/예산/deadline을 제공하며 pool이나 별도 run을 추가하지 않는다. 단계 목록은 승인 정의와 코드에서 일치해야 한다.
- `SkillResult`: optional response.skill에 id/version/hash, diagnosis(classification/observedFindings/hypotheses), trace. 분류와 관측은 서버가 성공 Tool에서 생성하여 모델의 classification을 받지 않는다. hypotheses 배열은 비우고 정책 기반 가능성 설명은 기존 근거 검증된 answer에서만 제공한다.
- definitions3개와 승인 manifest는 `src/main/resources/ai/skills/`. hot reload/외부 다운로드/Skill embedding 없음. 이름·목적·절차 본문은 검토 가능한 배포 정의이고 실제 실행은 고정 handler다.
- AgentRequest의 optional skillId, AgentService의 명시 선택·기존 분류 내 eligible ID 선택·정의 재검증·trace audit. 명시 선택은 선인가 뒤 바로 절차로 들어가며 분류0/종합≤1, 자동은 분류1/종합≤1. 일반 Phase 5 요청은 기본 비활성 Skill 설정에서 기존 계약을 유지한다.
- `AuthorizedKnowledgeRetrieval`/`KnowledgeStore`/`PgKnowledgeStore`: scoped 검색의 SQL role+domain 후보 필터와 corpus metadata 방어 검증. scope 지원 없는 store는 안전하게 실패하고 일반 검색으로 확대하지 않는다. Basic RAG의 ADMIN-only legacy 호출을 유지한다.
- 조회 연결의 양방향 ID·종목·방향·입력(decimal 비교)·단위를 확인하고 불일치에는 후속 tx/receipt 분기를 중단한다. quote 소비/만료 findings는 독립. chain FAILED 원인은 미검증으로 남기고 성공 정산 분류에는 FILLED/체결 요약/receipt SUCCESS/event MATCH/DB CONFIRMED/확인 수를 모두 요구한다.
- trace≤12, 정의 단계≤8, Tool≤4/검색1/모델≤2/40초/worker2/대기열0. trace에는 payload와 resource ID를 넣지 않는다. 인가 실패는 Skill metadata/근거 없이 기존 안전한 오류로 종료한다. 승인 문서 무효화 시 정책·해석·trace 정책 인용을 함께 폐기한다.
- 설정 `AI_SKILLS_ENABLED=false`와 example만 추가. 실제 `.env`, 거래 코드·컨트랙트·schema·JPA 트랜잭션·Tool8개·의존성은 변경하지 않았다.
- active 지식9개 중 system/authorization을 v5로 갱신하고 manifest/hash를 맞췄다. 기존 golden12는 변경하지 않는다. 로컬 AI 색인은 회귀 성공 후에만 발행한다.

## 12. 이번 검증과 검토 기록

구현 도중 표적 Skill/Agent 회귀는 통과했다. 신규 HTTP 통합 테스트의 Order import 충돌은 컴파일 단계에서 발견해 수정했다. 전체 DB/Anvil 회귀·실제 공급자·현행 지식 검색 평가·별도 검토는 실행 후 결과를 기록하며 아직 완료로 표시하지 않는다.

검증 위치: `ai/skill/SkillRegistryTest`, `SkillServiceTest`, `SkillApiIntegrationTest`, `LiveSkillEvaluationTest`; 기존 agent/AuthorizedKnowledgeRetrievalTest/OpenAiAgentProviderTest/AgentAnvilIntegrationTest, PgKnowledgeStoreIntegrationTest, ToolPostgresIntegrationTest, KnowledgeLoaderTest/KnowledgeRefreshEvaluationTest도 보강했다.

### 최초 전체 회귀

- 신규 테스트의 Mockito 재설정 시 기존 Answer가 실행되는 fixture 오류2건과 H2 Anvil fixture의 txHash/nonce 중복1건을 발견해 수정했다. 테스트 주장을 줄이지 않고 doReturn/doAnswer와 독립 fixture 식별자로 바로잡았다.
- 전체 backend295 중285통과/10skip/실패·오류0. Skill 결정적20·registry3·HTTP3, 실제 pgvector3(신규 domain+role Top-K canary 포함)·Tool PostgreSQL4(세 Skill rows 전부 불변 포함)·Agent Anvil2(신규 settlement Skill NOT_FOUND/nonce·잔고·블록 불변 포함)가 실행됐다.
- skip10은 새 LiveSkill1을 포함한 실제 공급자/검색 opt-in과 기존 거래 broadcast·성공 event/견적 PostgreSQL 조건부 테스트다. 공급자/검색 실행을 따로 수행하며 전체 회귀 결과와 혼동하지 않는다.
- forge test -q 통과. 기존 웹 npm test30/30, vite build87modules 통과. 거래·컨트랙트·웹 제품 코드 변경 없음.

### 실제 공급자·scoped 검색·지식 회귀

- 최초 live 평가에서 정산/견적 명시 Skill2개는 통과했지만 시장 명시 Skill은 INSUFFICIENT_EVIDENCE였다. 현재 관측+정책 설명과 실제 신규 견적/거래 성공 보장을 분리하도록 종합 지시를 보완하고 같은6개 시나리오를 재실행했다. 통과 assertion을 낮추거나 실패를 정상 PARTIAL로 바꾸지 않았다.
- 최종 live Skill 명시3/자동3, 선택6/6·route MIXED6/6 통과. 모두 근거 검증된 정상 PARTIAL6이며 PLAN_UNAVAILABLE/SYNTHESIS_UNAVAILABLE/RAG_UNAVAILABLE은0이다. USER 공개 문서만, ADMIN 정산 권한, domain별 출처, 역할 거부403/Tool0/모델0·타인404/모델0, fixture 주문/견적 불변도 검증했다.
- 실제 model gpt-5.6-terra/embedding1536/pgvector 사용. 상태는 전용 H2 fixture와 모의 시장이며 새로운 온체인 거래가 아니다. 성공 실행의 input16,506/output3,782 tokens, 지연6.456~11.188초는 이전 실패 실행/embedding/golden/Phase5 평가 사용량을 포함한 총계가 아니다.

| Skill | 명시/자동 결과 | Tool 수 | 모델 명시/자동 | trace 단계 | 관측 분류 |
|---|---|---:|---|---:|---|
| settlement-debugging | PARTIAL / PARTIAL | 3 | 1 / 2 | 8 | WAITING_OBSERVED |
| signed-quote-diagnosis | PARTIAL / PARTIAL | 3 | 1 / 2 | 7 | WAITING_OBSERVED + EXPIRED_BY_TIME |
| market-availability-diagnosis | PARTIAL / PARTIAL | 1 | 1 / 2 | 4 | INSUFFICIENT_EVIDENCE + REFERENCE_SNAPSHOT_OBSERVED |

정산 trace: LOAD_ORDER→CHECK_ORDER→LOAD_LINKED_QUOTE→LOAD_TRANSACTION→LOAD_RECEIPT(SKIPPED: 연결 tx 없음)→CHECK_EVIDENCE→RETRIEVE_POLICY→SUMMARIZE. 견적은 LOAD_QUOTE→LOAD_LINKED_ORDER→같은 tx/receipt/근거/정책/종합 순서다. 시장은 LOAD_REFERENCE→CHECK_EVIDENCE→RETRIEVE_POLICY→SUMMARIZE이며 Tool 스냅샷1개만 사용한다. 모든 절차의 검색은1회다. 시장의 분류는 발급/거래 성공을 확인할 근거 부족이지 스냅샷 조회 실패를 뜻하지 않는다.

- 별도 scoped3문항 기대 문서 hit3/3: 정산 onchain-settlement-policy, 견적 signed-quote-policy, 시장 market-data-policy. 고정 golden12에 이 문항을 섞거나 평가 set을 바꾸지 않았다.
- 기존 Phase5 실제 공급자6개도 재실행 route6/6, ANSWERED4/PARTIAL2, 의존성 실패0. 최신 raw phase5 보고서는 이번 실행이며 역사적 Phase5 결과JSON을 덮어쓰지 않았다.
- 현행9문서56청크: 고정 hit@5=12/12, MRR@5=0.8819444444444443, 직접근거12/12, K회귀0, 무관 검색후보5/8·답변오탐0/8, 핵심 ANSWERED2/2, 식별자6/8. Phase2 baseline hit10/12와 기존 K 설정은 유지한다.
- 회귀 성공 후 local exchange_ai만 index `3904f896c019d95243d1c3eb52bcd0e0e6308db78625f118375181b3d0a660f4`로 발행했다. 테스트용 exchange_ai_test는 canary 테스트가 임시 색인을 발행할 수 있고 서비스 local DB와 구분한다. 거래 DB와 분산 트랜잭션을 만들지 않았다.
- raw: backend/build/reports/ai/phase6-skill-evaluation.json, phase6-scoped-retrieval.json, phase6-knowledge-refresh.json. 공유 요약 수치는 docs/ai/phase-6-results.json에 기록한다.
- 후속 자체 점검에서 미확정 생성 답변의 검증되지 않은 인용을 trace에 넣지 않도록 차단했고 manifest version 타입을 정수로 강제했다. 문서 승인 무효화 시 모델 생성 uncertainties도 정책 해석과 함께 폐기하도록 서버 관측 uncertainties와 분리했다. 정상 공급자/색인 경로와 문서·검색 설정은 바꾸지 않았다. 신규 결정적 assertions를 포함해 최종 전체 회귀를 다시 실행한다.

재현: backend에서 AI_PGVECTOR_TESTS=true, AI_TOOL_POSTGRES_TESTS=true, AI_AGENT_ANVIL_TESTS=true로 `./gradlew test --no-daemon --rerun-tasks`. 공급자는 AI_SKILL_LIVE_EVALUATION=true, 검색 회귀는 AI_KNOWLEDGE_REFRESH_EVALUATION=true/AI_PHASE6_EVALUATION=true. AI_PUBLISH_CURRENT_INDEX=true는 회귀 성공 후 exchange_ai에만 발행하는 별도 opt-in. 공급자/검색/색인 교체 테스트는 동시에 실행하지 않는다.

OpenAI Docs 지침에 따라 [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)와 [평가 가이드](https://developers.openai.com/api/docs/guides/evaluation-best-practices)를 확인했다. 기존 승인 모델과 adapter를 유지하고 닫힌 schema와 서버 assertions를 주 검증으로 사용한다. schema 준수가 해석의 진실성 보장은 아니다.

## 13. 남은 한계와 제외

### 최종 전체 회귀와 검토 요청

- 최종 guard/문서/manifest 상태로 전체 backend298 중288통과/10skip/실패·오류0 재실행. SkillService22/SkillRegistry4/SkillHTTP3, 실제 pgvector3/PostgreSQL4/Anvil2를 포함한다. 핵심 제품 소스보다 최신 컴파일 산출물과 최종 XML을 확인했다.
- 이번 Phase의 forge36/36·웹30/30와 build87modules 통과. 마지막 수정은 Agent 정책 폐기/trace와 정의 parser 경계뿐이며 컨트랙트·웹·지식 본문/검색 설정은 변하지 않았다.
- skip10: LiveAgent, KnowledgeRefresh, LiveRag, RetrievalAnswer, RetrievalExperiment, LiveSkill 각1; 기존 ToolAnvil의 성공 event1, 거래 broadcast 관련Anvil2, 견적 PostgreSQL concurrency1. LiveAgent/LiveSkill/KnowledgeRefresh는 위 별도 opt-in 실행으로 성공했고 나머지는 이번 전체 명령에서 건너뛰었다.
- 제품·테스트·문서 초안을 멈추고 별도 검토를 요청한다. 아직 검토 완료로 표시하지 않으며 이후 발견 사항과 결과를 기록한다.

- 현재 Tool에 없던 revert 원인/block timestamp/개별 주문 잠금액/Oracle usedQuoteIds·signer 직접 검증을 추가하지 않았다. 실제 quote 발급 성공 여부와 과거 실패 원인을 현재 스냅샷만으로 보장하지 않는다.
- 실제 Anvil 성공 MATCH는 기존 event가 있을 때만 읽기 검증 가능하다. Phase 6 테스트를 위해 배포·거래를 만들지 않는다. 해당 실행 여부는 결과에서 별도로 표시한다.
- live 공급자 평가는 모델/embedding/pgvector를 실제 사용하되 상태는 전용 H2 fixture와 모의 시장이다. 운영 Toss·사용자 거래의 실제 장애 원인 평가가 아니다.
- structured hypotheses는 이번 서버 계약에서 비우고, 정책 근거에 따른 해석은 answer에 남긴다. 자연어의 완전한 진실성 보장을 주장하지 않는다.
- Phase 7 이벤트 자동 진단·저장·UI·장기 기억·재시도·mutation은 미구현이다.

## 14. 최종 별도 검토와 완료 상태

- 독립 검토자 `review_ai_phase6`: 발견된 필수 수정 없음. 전체 dirty diff, 신규 정의·registry·handler·테스트·결과 JSON과 역할/대상 제한, 양방향 연결 재인가, SQL role+domain 후보 제한, 공유 run/cache/예산, 승인 무효화 시 해석·인용·모델 uncertainties·정책 trace 폐기를 직접 확인했다.
- 검토자 직접 실행: SkillService22/Registry4/HTTP3, AgentService19/AuthorizedKnowledgeRetrieval5/OpenAiAgentProvider4, 총57개 통과·실패/오류/skip0. `git diff --check` 통과. 현재 XML은 검토자57개 표적 결과이며 구현자의 최종 전체298개 실행과 구분한다.
- 검토자는 공유 DB/Anvil/외부 공급자·forge·웹을 재실행하지 않았고 `.env`/키를 읽지 않았다. 실제 공급자·scoped·golden 원시 보고서 수치를 공유 결과와 대조했다. 실제 Anvil 성공 MATCH, 운영 Toss 장애·실제 사용자 거래, 자연어 해석의 완전한 진실성은 미검증으로 유지한다.
- 검토 후 제품 코드와 테스트는 변경하지 않았다. 이 완료·검토 기록만 추가했다. 승인 범위의 구현과 검증을 완료했으며 사용자 완료 승인 전 Phase 7은 시작하지 않는다. 실제 `.env`는 변경하지 않았고 Skill 기본 설정은 false다. 커밋은 실행하지 않았다.

커밋 메시지: `feat(ai): 제한된 Skill 진단 절차와 역할·domain 검색 경계 구현`
