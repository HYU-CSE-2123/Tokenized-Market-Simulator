# AI Phase 2 — Basic RAG 실행과 검증

> 2026-09-27 / 상태: 구현·로컬 검증·별도 재검토 완료, 실제 공급자 평가 대기

## 승인 범위

기존 Boot 3.3.4 내부 AI 모듈과 얇은 provider adapter를 사용한다. Spring AI·Hybrid Search·Tool·Agent·챗봇 UI는 추가하지 않는다. Embedding은 text-embedding-3-small 1536차원, 답변은 gpt-5.6-terra다. 거래 기능·JPA 설정·거래 schema.sql·컨트랙트는 변경하지 않는다.

AI 전용 Docker Compose 프로젝트는 exchange-ai이며 PostgreSQL 16 + pgvector 0.8.6의 linux/amd64 manifest digest를 고정했다. AI 데이터는 exchange_ai-data 볼륨에 저장한다. 기존 exchange_postgres-data와 공유하지 않는다. AI DB의 ai schema는 별도 SQL이며 두 DB 사이의 트랜잭션·외래키·분산 트랜잭션은 없다.

## 주요 코드

- backend/src/main/java/com/pricetrack/exchange/ai/AiConfiguration.java: 선택적 활성화, private pool 조립. Spring DataSource Bean을 추가하지 않는다.
- ai/knowledge: 승인 파일과 YAML metadata 검증, 제목 기반 chunking, 내용/색인 fingerprint.
- ai/store: AI 전용 JDBC 저장·정확 cosine 검색·원자적 색인 전환. JPA repository는 없다.
- ai/provider: EmbeddingProvider/ChatModelProvider, OpenAI HTTP adapter. 저장 비활성 응답·구조화 답변, timeout·호출 상한.
- ai/RagService.java: ADMIN 검증, 명시적 ingest, 검색·출처 검증. 거래 서비스를 호출하지 않는다.
- ai/AiController.java: ADMIN 전용 최소 API.

AI DB 접속/DDL/embedding은 서버 시작 시 수행하지 않는다. 키 누락·DB/API 장애는 AI 호출에서만 실패한다. 공유 JVM이므로 완전한 자원 격리를 보장하지는 않으며 요청 동시성·pool·timeout을 제한한다. 기존 SecurityConfig 변경은 /api/ai/** ADMIN matcher 추가뿐이다.

## 로컬 설정

backend/.env에 아래 항목을 추가한다. 실제 키는 채팅·Git·로그에 넣지 않는다.

```properties
AI_ENABLED=true
AI_DB_URL=jdbc:postgresql://127.0.0.1:5433/exchange_ai
AI_DB_USERNAME=exchange_ai
AI_DB_PASSWORD=<AI DB에 설정한 비밀번호>
OPENAI_API_KEY=<사용자 API 키>
AI_KNOWLEDGE_ROOT=../docs/ai-knowledge
AI_MANIFEST=../docs/ai/ingest-manifest.json
```

위 경로는 backend 디렉터리에서 서버를 시작하는 기준이다. 저장소 루트에서 IntelliJ로 실행하면 각각 docs/ai-knowledge와 docs/ai/ingest-manifest.json으로 지정한다. AI_ENABLED=false가 기본이다. 외부 API 장애 시 모의 답변으로 몰래 대체하지 않는다.

저장소 루트에서 AI 서비스만 실행한다:

```powershell
docker compose --env-file backend/.env -p exchange-ai -f docker-compose.ai.yml up -d ai-postgres
```

이번 로컬 통합 검증에서는 개발 전용 비밀번호 ai-local-test-only로 컨테이너를 생성했다. 이미 만들어진 DB의 비밀번호는 env 값 변경만으로 바뀌지 않는다. 이 로컬 DB를 이어 쓰면 같은 값을 설정하고, 변경하려면 별도 비밀번호 변경 절차를 사용한다. 외부에 노출하는 비밀번호로 사용하지 않는다. API 키와 실제 backend/.env는 이번 작업에서 수정하지 않았다.

기존 거래 Docker 프로젝트를 down하거나 Anvil을 재시작할 필요가 없다. AI DB도 볼륨을 삭제하지 않고 재시작할 수 있다. AI 볼륨은 Git 문서에서 재색인할 수 있지만 삭제를 자동화하지 않는다.

## API

모든 endpoint는 기존 Bearer JWT로 인증된 ADMIN만 접근한다. USER는 403, 미인증은 401이다. 모델이나 body의 역할을 신뢰하지 않는다.

| 요청 | 입력 | 출력 |
|---|---|---|
| POST /api/ai/index | body 없음 | indexVersion, documents, chunks, unchanged |
| POST /api/ai/search | question 문자열(최대 1000자) | 검색 근거·문서·절·버전·유사도 |
| POST /api/ai/answers | 같은 question | status, answer, indexVersion, sources |

검색/답변은 먼저 명시적 index 요청이 성공해야 한다. 파일 경로·역할·모델·SQL·RPC URL을 API body로 지정할 수 없다. 동적 상태 질문은 LIVE_DATA_REQUIRED, 근거 부족은 INSUFFICIENT_EVIDENCE로 구분한다. 일반 답변은 실제 검색 결과 ID만 인용할 수 있다.

AI 오류는 정제된 코드와 일반 메시지만 반환한다. 원시 공급자 응답·키·DB 비밀번호는 노출하지 않는다. AI_BUSY는 429, 설정/DB/API/색인 오류는 503이다. 관련성 판단과 문장 충실도를 완전히 보장하는 장치는 아니며 실제 평가가 필요하다.

## 승인·색인·삭제 정책

9개 문서 구조와 USER/ADMIN 분류를 유지한다. active는 문서 상태이고, manifest가 승인 경로·문서 버전·SHA-256을 고정한다. 해시는 UTF-8 텍스트의 CRLF/CR을 LF로 정규화한 뒤 계산한다. YAML metadata도 해시에 포함한다. 링크를 따라 다른 파일을 ingest하지 않는다.

문서 수정 시 버전·내용 검토 후 manifest의 documentVersion/sha256을 갱신한다. 자동 승인·자동 해시 갱신 endpoint는 없다. index fingerprint는 승인 문서 목록/해시, embedding 모델·차원, chunking 버전·설정을 포함한다. 같은 fingerprint는 API 호출 없이 재사용하고, 같은 청크 내용의 embedding은 동일 모델의 저장값을 재사용한다.

변경분 embedding을 DB 트랜잭션 밖에서 준비한 뒤 문서·청크 삽입과 active index 전환을 AI DB의 단일 트랜잭션으로 묶는다. 중간 실패 시 새 index를 공개하지 않는다. 이전 index는 보존하지만, 검색은 현재 승인 corpus fingerprint와 일치하는 active index만 사용한다.

삭제/비활성화는 manifest에서 해당 문서를 제거하고 재색인한다. 삭제/변조/비활성 문서가 manifest에 남으면 검증 오류로 검색을 차단한다. 파일 또는 승인 목록이 바뀌어 현재 index와 불일치하면 재색인 전까지 AI_INDEX_NOT_READY/승인 오류다. 오래된 문서를 계속 답변하는 방식으로 fallback하지 않는다. 빈 manifest는 빈 index로 전환할 수 있다.

## Chunking·검색 시작 설정

Markdown 제목 계층을 보존하고 절마다 분할한다. 짧은 절은 유지하며 긴 절은 줄 경계를 우선한다. 각 조각에 문서 제목과 절 경로를 붙이고 표를 나누면 머리글을 반복한다. 서로 다른 문서나 권한을 합치지 않는다.

AI_CHUNK_TOKENS=800, AI_OVERLAP_TOKENS=60을 사용한다. 현재 splitter는 특정 tokenizer 라이브러리 대신 **UTF-8 byte 수를 토큰 수의 보수적 상한**으로 사용한다. 따라서 실제 800토큰보다 작은 한국어 청크가 나올 수 있다. overlap은 긴 일반 절에서만 적용하며 표는 머리글을 반복한다. 이는 품질 최적값이 아니며 실제 golden 평가 후 조정할 초기 구현이다.

topK=5, minimumSimilarity=0.3, cosine 정확 검색이다. threshold는 확률이 아니고 아직 실제 한국어 데이터로 보정하지 않았다. HNSW/IVFFlat·키워드 검색·reranker는 없다. 문서 승인 목록 또는 설정이 바뀌면 새 index가 필요하다.

## 자원과 비용 경계

AI JDBC pool 2개, 검색/답변 동시 요청 2개, ingest 동시 실행 1개. 공급자 요청 timeout 기본 20초, embedding batch 최대 16개, 답변 출력 최대 1000토큰. ingest는 최대 500청크와 batch 사이 3분 deadline 검사로 제한한다. 마지막 진행 중 요청까지 포함하면 deadline보다 timeout만큼 더 걸릴 수 있다.

외부 응답은 성공/오류/chunked 여부와 무관하게 수신 중 누적 2MB를 초과하면 구독을 취소한다. 헤더 이후 느린 본문도 전체 요청 deadline으로 취소한다. 현재/내 주문이라는 단어만으로 정책 질문을 거절하지 않으며, 명확한 현재 값 질문만 선차단하고 나머지는 근거와 구조화 모델 판정으로 구분한다.

개발 프로세스당 공급자 호출 500회 상한을 둔다. 재시작하면 초기화되므로 영속 월간 과금 제한이 아니다. 공급자 오류는 자동 재시도하지 않는다. 문서·질문·검색 근거가 외부 API로 전송되므로 비밀정보를 입력하지 않는다.

공식 형식 확인: [Embeddings](https://developers.openai.com/api/docs/guides/embeddings), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [GPT-5.6 Terra](https://developers.openai.com/api/docs/models/gpt-5.6-terra).

## 검증과 미완료

- 최종 전체 backend 회귀: 179개 중 175개 통과, 4개 skipped, 실패 0.
- 실제 AI pgvector 저장·검색·중복 방지·색인 실패 rollback·빈 색인 전환 통과.
- AI DB 불가 상태에서도 JPA DataSource 1개 유지, 모의 매수 FILLED·health·시장 조회 통과.
- 가짜 공급자 장애 후 기존 모의 거래 통과. HTTP adapter는 로컬 stub으로 형식·벡터 검증·오류 정제를 확인했다.
- 컨트랙트 36개, 웹 30개 통과. 웹 production build는 샌드박스 접근 제한 후 사용자 권한 재실행 성공.
- 기존 Anvil 통합 2개·거래 PostgreSQL 경합 1개는 이번에 재실행하지 않았다.
- 실제 OpenAI embedding/답변과 golden retrieval 평가는 키 미설정으로 미실행이다. 가짜 벡터 통합 성공을 검색 품질 통과로 간주하지 않는다.
- 최초 별도 검토의 응답 본문 크기 제한/정적 질문 오분류 2건을 수정하고 회귀 테스트를 추가했다. 재검토 1회 결과는 발견된 필수 수정 없음이다. 검토자는 diff 검사를 직접 실행했고, 테스트 수치는 구현자 실행 결과와 구분했다.

backend에서 일반 검증은 gradlew test, AI DB 통합은 AI_PGVECTOR_TESTS=true를 추가한다. 통합 테스트는 전용 exchange_ai_test DB를 사용하며 기존 거래 DB에 연결하지 않는다.

실제 유료 평가를 실행하려면 먼저 AI 컨테이너에 exchange_ai_test DB를 만들고, 터미널 환경에 OPENAI_API_KEY·AI_DB_PASSWORD를 비밀 출력 없이 설정한 뒤:

```powershell
$env:AI_LIVE_EVALUATION='true'
.\gradlew.bat test --no-daemon --rerun-tasks --tests com.pricetrack.exchange.ai.LiveRagEvaluationTest
```

이 opt-in 테스트는 .env를 자동 읽지 않는다. 대표 질문 12개의 기대 문서는 backend/src/test/resources/ai/retrieval-golden.json이다. 초기 기준은 hit@5 최소 10/12와 출처 있는 답변·무관 질문 거절·동적 질문 구분이다. 결과는 backend/build/reports/ai/retrieval-evaluation.json에 저장한다. 실제 평가와 사용자 확인 전에는 Phase 2 전체 완료로 표시하지 않는다.
