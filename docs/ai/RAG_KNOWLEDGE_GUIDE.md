# RAG 지식 관리

지식은 규칙·설계·상태 해석이다. 개인 잔고/주문·가격·진단 결과·대화·비밀키는 corpus에 넣지 않는다. 현재 승인 지식9개 구조와 역할을 유지한다.

## 승인·색인 경계

`docs/ai-knowledge/`의 Markdown metadata(`title/domain/type/version/status/minimum_role/updated_at`)와 `docs/ai/ingest-manifest.json`의 승인 목록·version·정규화 content hash가 일치해야 한다. `active` 문서만 로드한다. CRLF는 LF로 정규화한 뒤 SHA-256을 계산한다. 단순 파일 추가나 Agent 이력 저장으로 자동 ingest하지 않는다.

문서 변경 절차: 사실/코드 확인 → 문서 version/date 수정 → manifest hash 갱신 → loader/권한 테스트 → 고정 검색/답변 평가 → 명시 `POST /api/ai/index` 또는 승인된 local publish. indexVersion은 corpus fingerprint로 추적한다. 문서나 승인이 실행 중 바뀌면 정책 응답/인용을 폐기한다. 과거 진단 조회도 출처 hash/version/domain/role을 다시 확인한다.

## 현재 검색 경로

2026-10-07 배포 준비에서 시장정책v2·시스템개요v7을 합성 공개 환경에 맞게 갱신하고 manifest/loader 및9개 artifact를 검증했다. 실제 AI 재색인·새 indexVersion·고정 golden 공급자 평가는 아직 실행하지 않았다. 아래 기존 검색 설정과 과거 평가를 새 corpus의 실행 결과로 오인하지 않는다. 운영자는 새 artifact에 대한 명시 ingest/회귀 후 AI를 공개한다.

- Markdown 제목/문장 경계 기반 청크, 현재1200바이트 기준과 overlap 품질 보정. 설정 이름 `chunkTokens`가 실제 토큰 계량이라는 뜻은 아니다.
- text-embedding-3-small1536차원, PostgreSQL pgvector semantic search.
- similarity0.25, 충분한 후보40 이상, 최종Top5 구성에서 문서당2개 제한.
- USER/ADMIN 후보 권한 및 Skill domain은 SQL 검색 단계에 적용한다. Basic RAG HTTP API는 ADMIN-only다.
- Hybrid keyword/RRF는 비교 실험으로 남겼으며 기본 서비스 경로가 아니다.

## 평가와 한계

golden12·무관8·식별자8은 과거 비교 기준을 유지한다. 검색 hit/MRR와 답변 근거/오답은 별도다. 무관 질문에 검색 후보가 나와도 근거가 부족하면 답변을 유보해야 한다. 모델의 문장 전체를 citation 존재만으로 신뢰하지 않는다.

[평가 보고서](EVALUATION_REPORT.md), [Phase3 실험](phase-3-retrieval.md), [Phase8 설계/실행](phase-8-evaluation-security-observability.md).
