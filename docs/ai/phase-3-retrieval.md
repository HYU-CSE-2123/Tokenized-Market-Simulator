# AI Phase 3 — Retrieval 품질 개선과 독립 실험

> 2026-09-28 / 구현·실제 비교·전체 회귀·별도 검토 완료. Phase 2와 Phase 3은 사용자가 완료 승인했다.

## 승인 범위와 불변 조건

Phase 2 golden 12개와 기대 문서는 변경하지 않는다. baseline은 hit@5 10/12다. threshold 0.25, 충분한 후보를 검색한 뒤 문서당 청크 제한, 문장 경계 overlap, 순수 근거 링크 청크 제외를 독립적으로 비교한다. Hybrid/RRF는 단순 개선 후 추가 근거가 있을 때만 비교한다. Tool·Agent·Skill·UI·거래 시스템 변경은 없다.

ADMIN-only API·서비스 인가, active 문서·manifest·내용 해시·문서 버전·현재 색인 검증을 유지한다. domain/type은 저장 metadata이며 이번에 자동 필터나 USER 검색 개방을 추가하지 않는다. AI DB/private JDBC와 거래 JPA는 독립적이다.

## Phase 2 실패 분석

- 견적 30초 질문: signed-quote-policy의 정답 절이 1위지만 similarity 약 0.2968로 threshold 0.3에서 제외됐다. 권한·문서 누락 문제가 아니다.
- 소켓 복구 질문: WebSocket 기대 문서의 청크가 7위/9위(약 0.4078/0.4041)였다. 상위 5개 중 4개가 주문 문서이며, 링크 목록도 상위에 있었다. 7위는 절 뒷부분이고 직접적인 REST 재동기화 문장은 9위에 있다.
- 기본 simple FTS에 한국어 질문 전체를 넣으면 두 질문 모두 0건이었다. 조사·영문/한글 표현 차이와 AND 조건을 한국어 형태소 분석으로 오해하지 않는다.

## 구현과 비교 방식

- MarkdownChunker: LEGACY(원래 byte-bound-v1), BOUNDARIES_ONLY, REFERENCES_ONLY, CLEAN 모드를 비교한다. API 입력으로 모드를 고르지 않는다. 서비스는 CLEAN을 사용한다.
- 문장/행 경계에서 overlap을 시작한다. 완전한 단위가 overlap 예산에 들어오지 않으면 생략한다. 단일 문장/행이 전체 청크 예산보다 긴 경우에만 Unicode-safe 강제 분할이 남는다.
- 마지막 제목이 `근거`이고 본문 전체가 Markdown 링크 목록인 절만 검색 청크에서 제외한다. 설명이 섞인 절과 원본 문서·링크는 보존한다.
- 청크 알고리즘/크기 변경은 새 index fingerprint를 만든다. 원래 9개 문서와 승인 manifest는 변경하지 않았다.
- EvidenceSelector: 후보 순위와 similarity를 보존하며 중복 ID 제거·문서당 최대 2개로 최종 Top-K를 구성한다. 서비스는 max(40, Top-K × 8) 후보를 요청한다.
- 채택한 기본값: 1200바이트 상한, overlap 60, threshold 0.25, Top-K 5. AI_CHUNK_TOKENS라는 기존 이름은 유지하지만 실제 tokenizer가 아닌 UTF-8 byte 상한이다.

실험은 별도 exchange_ai_retrieval_test DB를 사용한다. Phase 2 DB의 기존 embedding cache는 읽기만 하고, 평가 DB에서만 각 corpus를 publish한다. 질문 벡터는 모델/차원/질문 hash로 build 디렉터리에 캐시해 실험 간 동일하게 사용한다. 저장된 보고서에는 API 키가 없다. 기존 LiveRagEvaluationTest는 exchange_ai_test에 현재 서비스 조합을 색인하므로 이 테스트 DB의 active 색인은 바뀔 수 있다. 이전 색인 행과 baseline 보고서는 삭제하지 않는다.

## 지표 정의

- hit@5: 최종 청크 5개 중 기존 expected 문서 하나라도 포함한 질문 수 / 12.
- MRR@5: 첫 expected 문서 청크의 역순위 평균. Top-5에 없으면 0. 문서 중복이 있어도 청크의 실제 위치를 사용한다.
- 기존 성공 회귀: Phase 2에서 성공한 10개가 실패로 바뀐 개수.
- 직접 근거: 별도 supplement의 수작업 문장 anchor가 expected 문서의 검색 본문에 포함되는지 확인한다. 원래 golden을 바꾸지 않으며 의미적 답변 완전성의 자동 증명은 아니다.
- 무관 질문 후보 반환: 범위 밖 질문 8개에서 하나라도 반환되면 1건. 실제 오답 생성과 구분하며 별도 모델 응답 평가도 수행한다.
- 식별자: 별도 8개 질문의 expected 문서 hit/MRR 및 식별자와 설명 문장 포함 여부. 이 8개를 원래 12개 분모에 섞지 않는다.

동일 golden으로 개선을 선택했으므로 개발 세트 결과다. 독립적인 일반화 성능이나 통계적 유의성을 주장하지 않는다.

## Hybrid 비교와 선택

청크 개선 후 QuoteAlreadyUsed·MinimumOutputNotMet 정확 식별자 검색이 약해진 근거로 PostgreSQL FTS와 RRF를 비교했다. test 전용 KeywordExperiment는 allowlist 영문 식별자만 추출하고, simple tsvector·제목/절 가중치·정확 토큰 경계로 후보를 검색한다. 동일 active index/role 조건을 적용한다. 점수 단순 합이 아닌 동일 가중치 RRF(k=60)를 쓰며 semantic 후보와 keyword 후보는 각각 최대 40개다. keyword-only 후보에 cosine threshold를 재적용하지 않는다.

이것은 한국어 자연어 FTS 전체 구현이나 BM25가 아니다. 별도 검색 서버·reranker·확장 설치는 없다. SQL은 평가 DB에서 SELECT만 수행한다.

Hybrid는 식별자 hit 6/8→8/8, MRR 0.6667→1.0의 보완 효과가 있었다. 그러나 같은 청크/threshold/다양성의 semantic 조합보다 golden MRR이 0.8819→0.8542로 하락했다. golden hit 12/12와 직접 실패 근거 2/2는 같았다. 이번 서비스에는 단순 조합을 채택하고 Hybrid는 비교 실험으로만 남긴다. 후속 채택에는 식별자 질의 비중과 별도 표현 세트의 이득/회귀를 판단해야 한다.

## 실행·적용

일반 회귀: backend에서 `.\gradlew.bat test --no-daemon`.

실제 비교: AI 전용 컨테이너에 exchange_ai_retrieval_test DB를 준비하고, API 키와 AI DB 비밀번호를 출력 없이 프로세스 환경에 설정한다.

```powershell
$env:AI_RETRIEVAL_EVALUATION='true'
.\gradlew.bat test --no-daemon --rerun-tasks --tests com.pricetrack.exchange.ai.RetrievalExperimentTest
```

그 다음 보고서 기반 답변 평가:

```powershell
$env:AI_RETRIEVAL_ANSWER_EVALUATION='true'
.\gradlew.bat test --no-daemon --rerun-tasks --tests com.pricetrack.exchange.ai.RetrievalAnswerEvaluationTest
```

환경 플래그가 없으면 유료 테스트는 skipped다. 답변 평가는 앞 단계 보고서가 필요하다. 실험 결과는 build/reports/ai/phase3-experiments.json, 답변은 phase3-answers.json이다. 현재 서비스 live 검증은 retrieval-evaluation-current.json에 기록해 Phase 2 baseline 파일을 덮어쓰지 않는다.

실제 backend/.env는 자동 수정하지 않는다. 기존 값이 있으면 기본값을 덮으므로 AI_CHUNK_TOKENS=1200, AI_MINIMUM_SIMILARITY=0.25 적용 여부를 확인한다. 서버 재시작 후 ADMIN POST /api/ai/index로 명시적 재색인을 수행한다. 그 전에는 새 fingerprint와 일치하지 않아 AI_INDEX_NOT_READY이며 기존 거래에는 영향이 없다. 운영 지식 DB의 재색인은 이번 테스트에서 자동 실행하지 않는다.

## 실험별 결과

| 실험 | 청크 | hit@5 / 12 | MRR@5 | 직접 근거 / 12 | 식별자 hit / 8 |
|---|---:|---:|---:|---:|---:|
| A Phase 2 baseline | 77 | 10 | 0.7778 | 9 | 6 |
| B threshold만 0.25 | 77 | 11 | 0.8611 | 10 | 8 |
| C 문서당 2개만 적용 | 77 | 11 | 0.7944 | 8 | 6 |
| D 문장 경계만 개선 | 76 | 10 | 0.7708 | 9 | 6 |
| E 순수 링크 절만 제외 | 66 | 10 | 0.7778 | 9 | 6 |
| F D+E | 65 | 11 | 0.7875 | 9 | 6 |
| G B+C | 77 | 12 | 0.8778 | 9 | 8 |
| H F+C | 65 | 11 | 0.7917 | 9 | 6 |
| I F+B+C | 65 | 12 | 0.8750 | 10 | 6 |
| J F + 1200바이트 | 50 | 11 | 0.7986 | 11 | 6 |
| **K J+B+C — 채택** | **50** | **12** | **0.8819** | **12** | **6** |
| L K + Hybrid 비교 | 50 | 12 | 0.8542 | 11 | 8 |

모든 실험의 기존 성공 10개 회귀는 0이다. 무관 질문 후보 반환은 모두 5/8로 동일했다. 문서 hit와 직접 근거 지표가 다른 이유를 G(12/12지만 직접 근거 9/12)가 보여 준다. L은 식별자 품질을 높였지만 직접 근거 한 건도 K 대비 감소했다.

채택 K의 견적 질문 기대 문서는 1위, WebSocket 질문은 4위이며 두 질문 모두 직접 근거를 포함한다. 식별자 8개 중 QuoteAlreadyUsed·MinimumOutputNotMet는 여전히 실패한다. 정확 식별자 질의에 대한 개선은 이번 기본 경로에서 해결됐다고 주장하지 않는다.

실제 gpt-5.6-terra 답변 비교(A/K/L)는 각 8개 무관 질문 모두 INSUFFICIENT_EVIDENCE였다. 검색이 비면 모델을 호출하지 않는다. 최종 답변 오탐 0/8과 검색 후보 오탐 5/8은 다른 지표다. K/L의 이전 실패 질문 2개는 모두 ANSWERED 및 검색 결과 내 인용 ID를 반환했다. 소규모 1회 평가이며 안전한 거절을 모든 질문에 보장하지 않는다.

원래 golden의 정규화 SHA-256은 22006b55ed6256672195c873399df99fae5741fd47a9ba4319484fb97dd881ac다. [공유용 수치 JSON](phase-3-results.json)에 색인 버전·질문별 결과를 보존했다. 원문 근거/전체 순위는 로컬 build 보고서에 있다.

## 검증·검토

- 실제 비교 12개 실험, 답변 비교, 현재 서비스 LiveRagEvaluationTest 3개 opt-in 테스트 통과.
- 전체 backend 최종 회귀: 190개 중 184 통과, 6 skipped, 실패 0. 실제 pgvector 테스트 포함. golden 해시·채택 조합의 12/12·MRR 개선·직접 근거·회귀·오탐 assertion을 추가한 유료 평가 3개도 별도 재실행하여 통과했다.
- skipped 6개는 기존 Anvil 2개·거래 PostgreSQL 경합 1개와 유료 평가 3개다. 유료 3개는 위 별도 실행에서 통과했다. Anvil/거래 PostgreSQL 실연동은 이번에 재실행하지 않았다.
- forge 36개, 웹 30개 통과. 웹 빌드는 샌드박스 접근 실패 후 승인된 권한으로 재실행하여 통과했다.
- AI 장애 후 모의 거래 유지·JPA 단일 DataSource·미인증/USER 거부, manifest/hash/버전 승인 경계, 변경 중 검색 차단, 청크 경계·링크 보존·Unicode·다양성 선택을 검증했다.
- 별도 검토자 review_ai_phase3: 발견된 필수 수정 없음. tracked/untracked 코드·문서 전체와 청크 종료 조건·인가·승인·재색인 경계를 확인했다. diff 검사, 공유 JSON/원본 보고서 대조, hit/MRR/근거 지표 재계산 및 답변 30행 내용 확인은 검토자가 직접 수행했다. 테스트와 유료 API 호출은 검토자가 재실행하지 않았으며 구현자 실행 결과와 구분한다.
- 검토자 제안: 답변 평가에서 K/L 핵심 2개 질문의 ANSWERED 상태도 assertion으로 추가하면 좋다. 현재는 실제 기록/수동 내용 확인과 검색 anchor assertion으로 검증하며, 해당 생성 상태 assertion은 아직 없다. 필수 수정 사항은 아니었다.

## 사용자 승인 후 작은 테스트 보완 — 2026-09-28

- Phase 3 완료와 K 기본 경로 유지가 사용자 승인됐다. 위 검토 당시의 제안은 후속 보완으로 반영했다.
- RetrievalAnswerEvaluationTest에 K/L의 견적 만료·WebSocket 복구 질문 각 2개가 ANSWERED인지 assertion을 추가했다. 이미 있던 인용 ID 검증과 무관 질문 오답 0 assertion은 유지했다. A baseline의 의도된 실패를 성공으로 강제하지 않는다.
- AI_RETRIEVAL_ANSWER_EVALUATION=true로 해당 테스트를 실제 gpt-5.6-terra에 재실행해 통과했다. K/L 각각 ANSWERED 2/2, A는 1/2. A/K/L 무관 질문 오답 각각 0/8이다. 기존 검색 보고서/evidence를 사용했으며 embedding/검색을 다시 실행하지 않아 hit@5·MRR 등 Phase 3 수치는 변경하지 않았다.
- 서버 코드·모델·색인·golden·거래 기능은 변경하지 않았다. 전체 기본 backend 회귀는 190개 중 183 통과·7 skipped·실패/오류 0이다. 이번에는 실제 pgvector opt-in도 활성화하지 않아 이전 184 통과·6 skipped와 구분한다. skipped는 pgvector 1개·기존 Anvil 2개·거래 PostgreSQL 1개·유료 AI 3개이며 답변 평가는 앞의 별도 실행에서 통과했다. test-only 보완으로 forge/웹은 재실행하지 않았다. 별도 검토 결과는 후속 기록한다.
- 다음 [Phase 4 설계안](phase-4-read-only-tools.md)은 구현 승인 대기다.
- 별도 review_ai_phase3_followup 결과는 발견된 필수 수정 없음이다. 테스트와 관련 소스·Phase 4 제안 포함 6개 변경 파일을 읽기 전용으로 검토하고, diff 검사·답변 JSON 30행 대조·XML 독립 집계를 직접 수행했다. 검토자는 유료 API/테스트를 재실행하지 않았다. Phase 4의 실제 인가/timeout/실연동 검증은 아직 미래 계획이다.
