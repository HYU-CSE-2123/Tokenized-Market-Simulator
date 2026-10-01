# AI Phase 4 승인 후 — SELL 테스트·지식/색인 갱신

> 2026-09-30. 사용자 승인 범위: 선택 SELL 테스트 보완, Tool 구현 상태를 반영한 기존 9문서/manifest/index 동기화, retrieval 회귀, 이후 Phase 5 설계 제안만 수행.

## 변경 범위

- `backend/src/test/java/com/pricetrack/exchange/ai/tool/ToolIntegrationTest.java`: SELL 주문·견적 입력 mSEC/출력·수수료 mKRW, 최소 수령량/체결량, receiptInput의 SELL/wei 값, 타인 거부·방향 불일치 시 RPC 미호출, 5개 테이블 불변 검증 추가.
- `backend/src/test/java/com/pricetrack/exchange/ai/tool/receipt/ReadOnlyReceiptClientTest.java`: 실제 로컬 HTTP 응답의 Sold 이벤트·input/output/fee wei·priceE8, DB 상태 유지, 잘못된 방향·입력량 불일치 검증 추가.
- `docs/ai-knowledge/authorization-policy.md` v3: 기존 RAG ADMIN-only와 독립 Tool USER/ADMIN 권한 구분. ADMIN 포트폴리오도 본인 기준, 소유권·입력 경계 명시.
- `docs/ai-knowledge/system-overview.md` v3: Phase 2/3 평가 완료, K 기본 검색, 8개 Tool 완료와 Agent/Skill/UI 미구현 구분.
- `docs/ai-knowledge/transaction-recovery-runbook.md` v2: 이미 구현된 요약 DTO와 errorCategory/receipt 상태 해석, 읽기 전용 진단 한계.
- `docs/ai/ingest-manifest.json`: 위 3개 문서 버전·정규화 SHA-256 갱신. manifest의 version=1은 포맷 버전이므로 유지. 나머지 6문서·9개 역할 구조 유지.
- `KnowledgeLoaderTest`: 실제 9개 문서 승인 로딩, ADMIN runbook 경계·버전·낡은 미구현 표현 제거 확인.
- `KnowledgeRefreshEvaluationTest`: 현행 서비스 K 경로의 고정 golden/보조 질문 평가, 역할 차단·멱등 색인 및 별도 opt-in 로컬 색인 발행. 과거 Phase 3 실험/답변 보고서는 덮어쓰지 않는다.
- 이 보고서·[공유 수치](phase-4-knowledge-refresh-results.json)·[Phase 5 설계안](phase-5-rag-tool-agent.md)과 공통 문서 링크/이력.

새 dependency, DB schema, 거래·컨트랙트·웹·Agent 제품 코드 변경은 없다. 작업 시작 시 Phase 4와 이전 assertion/문서 변경이 미커밋 상태였으며 그대로 보존했다.

## 평가와 발견한 문제

Phase 2 baseline 10/12와 기존 golden 12개/기대 문서, supplement, K 설정(CLEAN·1200바이트·overlap 60·threshold .25·후보 최소 40·문서당 2·Top-5)은 변경하지 않았다. 모델은 기존 text-embedding-3-small 1536차원/gpt-5.6-terra다. 기존 Phase 3 query vector cache를 재사용해 질의 벡터 차이와 문서 변경 영향을 분리했다.

| 측정 | Phase 3 K | 최초 문서 갱신 | 최종 절 정리 후 |
|---|---:|---:|---:|
| hit@5 | 12/12 | 12/12 | 12/12 |
| MRR@5 | 0.8819 | 0.8778 | 0.8819 |
| 직접 답변 근거 anchor | 12/12 | 11/12 | 12/12 |
| K 성공 질문 hit 회귀 | — | 0 | 0 |
| 무관 질문 후보 반환 | 5/8 | 5/8 | 5/8 |
| 무관 질문 최종 오답 | 0/8 | 0/8 | 0/8 |
| 핵심 2문항 ANSWERED | 2/2 | 2/2 | 2/2 |
| 식별자 hit / MRR | 6/8 / 0.6667 | 6/8 / — | 6/8 / 0.6667 |

첫 시도는 runbook의 기존 REVIEW_REQUIRED 절에 Tool 계약을 길게 덧붙이면서, 성공 receipt 질문의 직접 정산 근거가 Top-5에서 밀리고 소켓 질문 기대 문서가 4→5위로 내려갔다. 문서 hit만 확인했다면 놓칠 회귀다. 직접 근거 assertion이 실패해 로컬 exchange_ai 발행을 막았다.

해결: 거래 정책은 기존 절에 유지하고 새 Tool 출력 계약은 별도 제목으로 짧게 분리했다. 검색 알고리즘·threshold·golden·anchor는 바꾸지 않았다. 최종 성공 receipt의 직접 근거가 돌아왔고 소켓 기대 문서도 4위로 회복했다. 동일 개발 세트를 확인하며 수정했으므로 독립 일반화 성능이 아니라 corpus 갱신 회귀 검증이다.

QuoteAlreadyUsed/MinimumOutputNotMet의 미검색 2건과 무관 후보 5/8은 여전히 남는다. 최종 답변 거절 0/8은 해당 소규모 1회 평가의 결과이며 모든 질문의 무오답 보장이 아니다. Tool/Agent 실시간 답변 평가는 아직 아니다.

## 실제 색인 반영

- 정지 상태였던 기존 `exchange-ai-ai-postgres-1` 컨테이너만 시작했다. 컨테이너 재생성·볼륨 삭제·거래 DB/Anvil 시작·초기화는 하지 않았다.
- 전용 평가 DB `exchange_ai_test`에서 새 corpus 색인·동일 재색인 unchanged, 실제 공급자 검색·답변을 검증했다.
- 통과 후 명시적 `AI_PUBLISH_CURRENT_INDEX=true`로 로컬 AI DB `exchange_ai`에 동일 corpus/vector를 발행했다. 이는 일반 test의 부작용이 아니라 별도 opt-in이며 기존 index 행을 삭제하지 않는다.
- 활성 index: `f13e19198c2bbc6386caafa9f0ec5e7f346acdac885c050c519df950d685211f`, 9문서·53청크. DB SELECT로 활성 ID·3/3/2 문서 버전·USER 8/ADMIN 1 역할을 확인했다. role metadata 유지이지 USER RAG 검색을 개방한 것은 아니다.
- 실제 `.env` 및 서버 실행 상태는 변경하지 않았다. 기본 K 설정·이 로컬 AI DB를 사용하는 프로세스 기준이며 다른 환경/별도 DB까지 배포했다는 뜻은 아니다. AI 비활성 서버를 자동 활성화하지 않는다.

## 실행과 검증 결과

SELL 보완 후 HTTP/H2 16개 + 로컬 receipt HTTP 8개 + KnowledgeLoader 9개 = 33개 통과. 전체 backend 회귀는 `AI_PGVECTOR_TESTS=true`에서 224개 중 214 통과, 10 skipped, 실패/오류 0이다. 실제 pgvector 통합도 포함됐다.

skipped는 기존 거래 Anvil 2개·견적 PostgreSQL 경합 1개·Tool PostgreSQL 2개·Tool Anvil 1개·유료 평가 4개다. 새 지식 갱신 유료 평가 1개는 별도 실행에서 통과했다. 이번 변경은 테스트·지식 문서·설계에 한정돼 실제 거래 PostgreSQL/Anvil, forge/웹은 재실행하지 않았으며 Phase 4의 앞선 결과와 구별한다.

```powershell
# backend/; 키/AI DB 비밀번호는 기존 .env에서 출력 없이 프로세스에 주입
$env:AI_KNOWLEDGE_REFRESH_EVALUATION='true'
# 기본은 평가 DB만. 로컬 사용 AI DB 발행을 명시적으로 원하는 경우만 설정
$env:AI_PUBLISH_CURRENT_INDEX='true'
.\gradlew.bat test --no-daemon --tests com.pricetrack.exchange.ai.KnowledgeRefreshEvaluationTest
```

MRR·식별자 회귀도 발행 전 차단 assertion으로 보강했다. 마지막 유료 평가 1개 + 문서 로더 9개를 재실행해 10개 모두 통과했고 최종 수치는 동일했다. 이는 전체 회귀 이후 test assertion 보완이며 제품 코드는 변경되지 않았다. 전체 raw 근거·답변은 `backend/build/reports/ai/phase4-knowledge-refresh.json`, 첫 시도 요약은 `phase4-knowledge-refresh-first.json`, 공유 수치는 이 폴더의 `phase-4-knowledge-refresh-results.json`이다. 최초 시도는 집계 요약만 보존하며 최종 raw와 혼동하지 않는다.

과거 RetrievalExperimentTest는 원래 Phase 3 corpus fingerprint를 assert한다. 현재 갱신 corpus에 그 A~L 실험을 그대로 재실행하면 baseline guard가 실패하는 것이 정상이다. 역사 재현은 Phase 3 당시 문서/manifest를 갖춘 checkout에서 수행하고, 현재 corpus 회귀에는 새 테스트를 사용한다. 기존 Phase 3 baseline/결과를 새 문서 수치로 덮어쓰지 않는다.

## 검토와 다음 승인

별도 review_ai_phase4_refresh 검토 결과는 발견된 필수 수정 없음이다. SELL/문서 로더/평가 테스트, 지식 3개·manifest·보고서·Phase 5 제안과 관련 기존 코드를 확인했다. 검토자가 9문서 정규화 해시·golden 해시, raw 28행과 공유 수치, 직접 anchor·인용 ID, 핵심 생성 답변 2개와 거절 8개를 독립 대조했고 불일치는 없었다. 최신 XML 10개와 diff 검사도 확인했다. 테스트·유료 API·DB SELECT는 직접 재실행하지 않았으며 전체 224개 회귀와 활성 색인 확인은 구현자 실행 기록으로 검토했다.

설계 명확화 제안을 수용해 Phase 5의 AI_AGENT_ENABLED/AI_ENABLED/AI_TOOLS_ENABLED 조합, Tool 비활성 시 소유권 선확인 우회 금지와 거래 기동 격리를 명시했고 검토자가 최종 문구까지 확인했다. 제품 동작 변경은 아니다.

Phase 5는 설계만 제안했다. 사용자 승인 전 Agent endpoint·role-aware USER retrieval·planner·생성 답변 연결·설정은 구현하지 않는다.

권장 커밋 메시지(이번 후속 보완만 분리할 경우): `test(ai): SELL Tool 검증 및 지식 색인 회귀 보강`
