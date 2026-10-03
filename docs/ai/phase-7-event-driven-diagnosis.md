# AI Phase 7 — 이벤트 기반 자동 진단 설계·구현 보고서

> 2026-10-02 / 구현·검증·별도 검토 및 사용자 완료 승인됨. AI Phase 6도 사용자 완료 승인됨.
> master guide Phase 7과 실제 정산·Agent·Skill·Tool·AI DB 경계를 기준으로 한다. 1~8절은 당시 설계 제안이며 실제 구현·검증·검토 결과는 9~11절에 기록한다.

## 1. 목표와 실제 코드 기준

첫 자동 진단은 커밋된 `blockchain_transactions.status=REVIEW_REQUIRED`에 연결된 주문을 `settlement-debugging`으로 조사한다. 자동 정산·복구·서명·재전송·잔고 변경은 하지 않는다.

master guide의 `Order -> REVIEW_REQUIRED`는 개념 예시다. 실제 `OrderStatus`에는 이 값이 없고 주문은 PENDING_ONCHAIN으로 남을 수 있다. `OnchainSettlementService.markReviewRequired`가 블록체인 트랜잭션의 상태와 errorMessage를 변경하고 자산 잠금은 유지한다. 따라서 주문 상태나 사용자 WebSocket을 trigger로 사용하지 않는다.

현재 BlockchainTransaction에는 @Version/updatedAt/검토 전환 시각이 없으며, 반복 markReviewRequired 호출도 가능하다. 없는 state version을 가정하거나 order.updatedAt을 전환 시각으로 쓰지 않는다. Phase 7을 위해 거래 entity·정산 상태 전이·거래 schema를 바꾸지 않는 방향을 제안한다.

범위:

- 첫 운영 이벤트 유형 하나: REVIEW_REQUIRED, BUY/SELL, 유효한 orderId 연결.
- 고정 Skill 하나: settlement-debugging. signed-quote/market Skill은 기존 수동 경로로 유지.
- 비동기 감지·예약·중복 억제·진단·AI DB 저장·ADMIN 조회.
- 기존 Vite 검증 클라이언트에 최소 ADMIN 진단 패널. 수동 주문 진단은 기존 Agent API를 사용하고 자동 이력을 조회한다.
- 장시간 PENDING_ONCHAIN 자동 진단은 운영 기준이 승인된 뒤 추가한다. 기존 Tool의 WAITING_LONG 필터를 자동 trigger 정책으로 전용하지 않는다.
- Kafka/Redis/outbox/workflow engine, 새 Tool, 사용자 알림, 대화 기억, 자동 mutation은 제외.

## 2. 언제 실행하는가: 커밋된 상태 이벤트의 비동기 감지

```text
기존 정산 트랜잭션: REVIEW_REQUIRED 기록 → commit → 기존 흐름 종료
                                                     ↓ 독립적으로 관측
진단 스캐너: 거래 DB 제한된 read → AI DB QUEUED 예약
진단 작업자: claim → 현재 상태/권한 확인 → 기존 명시 Skill run
AI DB: 결과 확정 → ADMIN REST/웹 조회
```

초기 제안은 별도 진단 스캐너의 5초 fixed-delay 감지다. 이는 장시간 대기 판정 기준이 아니라 감지 주기다. commit 이전/rollback 상태는 보지 않으며, 감지 주기 + 예약 대기 + Skill 실행 시간 후 결과가 생긴다. 5초 안에 결과가 완료된다고 보장하지 않는다.

스캐너는 Spring 거래 reconciliation의 scheduler 실행 경로와 분리한 단일 전용 실행기를 사용한다. AI 작업을 기존 scheduler 기본 단일 스레드에 올려 정산을 지연시키지 않는다. 스캐너/작업자는 정산 이벤트 listener 안에서 AI DB·LLM을 동기 호출하지 않는다.

- 거래 DB 읽기: 짧은 read-only 트랜잭션, query timeout 2초, FOR UPDATE 없이 DTO projection. raw transaction·signature·errorMessage 본문은 가져오지 않는다.
- 한 주기 최대50건, id keyset으로 순회하고 마지막 페이지 이후 처음부터 재순회한다. 기존 id를 가진 거래가 나중에 REVIEW_REQUIRED가 되어도 다시 발견한다. 높은 id만 계속 읽는 영구 cursor는 사용하지 않는다.
- 읽기 트랜잭션을 닫은 뒤 AI DB에 예약한다. 두 DB 연결/트랜잭션을 동시에 잡지 않고 분산 트랜잭션을 만들지 않는다.
- AI DB 장애/포화 시 예약을 멈추고 다음 순회에서 재발견한다. 거래 실패로 전파하지 않는다.
- 자동 진단 비활성 동안은 조회도 하지 않는다. 활성화 시 기존 REVIEW_REQUIRED도 backlog로 제한적으로 감지한다.

이는 즉시 전달되는 durable message가 아니라 **유지되는 검토 상태를 이벤트로 정규화하는 polling 설계**다. 현재 reconciliation은 REVIEW_REQUIRED를 자동 재처리하지 않으므로 이 경계가 적합하다. 프로세스 재시작/AI DB 장애 후에도 해당 상태가 유지되면 재발견한다. 감지 전에 상태가 바뀐 일시적 이벤트까지 보존하는 보장은 없다. 향후 모든 상태 전환의 유실 없는 전달이 필요하면 별도 승인으로 거래 DB outbox 등을 검토한다.

## 3. 중복 제어와 작업 상태

첫 Phase의 고유 키:

`sourceNamespace + blockchainTransactionId + txHash + REVIEW_REQUIRED`

sourceNamespace는 로컬/테스트 등 원장 인스턴스를 구분하는 고정 서버 설정이다. 같은 원장에 연결한 여러 서버는 같은 값을 사용한다. Anvil/거래 DB를 초기화해 다른 원장으로 바꾸면 namespace도 바꾼다. txHash는 내부 식별용이며 모델 prompt/공개 로그에 넣지 않는다. 유효한 hash/주문 연결 없는 대상은 Skill을 실행하지 않고 안전한 감지 오류로 기록한다.

- AI DB unique 제약 + INSERT ON CONFLICT로 여러 스캐너/재시작/중복 감지를 한 작업으로 합친다.
- Skill/model/index version은 고유 키에 넣지 않는다. 배포·색인 갱신 때 같은 사건을 자동 재분석하지 않는다.
- 실제 source state version이 없으므로 `REVIEW_REQUIRED:v1`은 진단 trigger 정책 버전이지 거래 entity version이 아니다. 동일 tx의 이유 변경/같은 상태 재진입은 새 사건으로 자동 인식하지 않는다.
- 첫 감지 시각과 진단 전/후 관측 시각은 저장하되 실제 검토 상태 전환 시각이라고 표시하지 않는다.

작업 상태 제안: QUEUED → RUNNING → COMPLETED / FAILED / INTERRUPTED / SKIPPED. 결과 품질은 별도 responseStatus(ANSWERED/PARTIAL/INSUFFICIENT_EVIDENCE)와 classification으로 구분한다. PARTIAL도 정상 완료이며 품질이 낮다고 자동 재분석하지 않는다.

claim은 AI DB 짧은 트랜잭션에서 원자적으로 처리하고 lease/token을 저장한다. 여러 인스턴스라도 같은 작업을 동시에 claim하지 못하게 한다. LLM/RPC/Tool 실행 동안 AI DB lock/connection을 잡지 않는다. 결과 확정은 claim token/상태 조건부 UPDATE로 한 번만 허용한다.

재시도 경계:

- Agent admission이 AGENT_BUSY로 거절되고 model/Tool 실행0임을 확인한 경우만 QUEUED로 반환한다. 초기 최대3회, 10초 간격 제안.
- 구성/ADMIN/정의 오류는 실행하지 않고 안전한 코드로 종료하며 무한 재시도하지 않는다.
- 실제 run 시작 뒤 timeout/프로세스 종료/결과 저장 실패는 결과 또는 INTERRUPTED로 종료한다. lease 만료 작업을 곧바로 다른 worker가 재실행하지 않는다. 실행 여부가 불명확한 요청을 새 LLM 호출로 복제하지 않는다.
- AI DB 복구 후 살아 있는 동일 worker가 보유한 완료 결과를 같은 token으로 저장하는 것은 가능하지만 Skill을 다시 실행하지 않는다. lease 만료 후면 결과 확정을 거부한다.
- exactly-once LLM 호출은 주장하지 않는다. 고유 예약·한 번의 결과 확정과 불명확한 실행의 자동 재호출 금지를 보장 목표로 한다.
- 관리자 재진단/재시도 API는 이번 범위에서 제외한다. 필요하면 기존 수동 질문으로 확인하며 저장 이력을 덮어쓰지 않는다.

완료 결과 본문은 기본7일 보관을 제안한다. 삭제 후에도 사건 키/종료 상태/생성 시각의 작은 dedup marker는 해당 원장 namespace 운영 기간 유지한다. 결과 삭제나 서버 재시작이 같은 사건의 재실행을 만들지 않게 한다. 무한 결과 JSON 누적은 하지 않는다.

## 4. 실행·권한·장애 격리

서버 설정의 기존 ADMIN loginId를 자동 진단 actor로 지정하고 실행 직전 DB에서 실제 계정/현재 ADMIN 역할을 확인한다. 아무 사용자 ID와 ADMIN enum을 임의로 만들어 통과시키지 않으며 ADMIN이 없으면 진단만 중단한다. 자동 작업 때문에 계정을 생성하거나 JWT/비밀번호/키를 저장하지 않는다. 별도 SYSTEM 전체 권한을 추가하지 않는다.

Skill 호출은 서버 고정 question + 감지한 orderId + settlement-debugging만 허용한다. 원래 errorMessage/외부 이벤트 본문/사용자 입력을 명령으로 넘기지 않는다. 서버 코드에서 만든 AUTO_DIAGNOSIS 실행 origin/actor를 감사에 남기며 HTTP body가 origin/role을 지정할 수 없게 한다.

실행 전 non-locking 읽기로 REVIEW_REQUIRED, BUY/SELL, 같은 tx/order/hash 연결을 재확인한다. 대상이 바뀌면 SKIPPED_STALE_TARGET, 연결 이상이면 SKIPPED_INVALID_LINK. 실행 중 거래 상태가 바뀔 수 있으므로 완료 전 다시 조회해 stale 여부를 표시하고 운영자에게 현재 사실로 오인시키지 않는다. 진단을 위해 거래 행 잠금을 유지하지 않는다.

AgentService/SkillRegistry/SkillRunner/ToolDispatcher의 기존 권한·양방향 연결 확인·4 Tool/1 검색/최대2 모델/40초/24KiB context/64KiB output을 유지한다. 명시 Skill은 분류 모델0, 종합 최대1이다. SQL role+domain 검색 제한과 승인 문서 검증을 우회하지 않는다.

자원 정책:

- 글로벌 Agent worker2/대기열0 유지. 자동 실행은 실제 Agent worker에서 최대1개만 허용해 최소1개는 수동 요청에 사용 가능하게 한다. 수동 요청이 이미 두 슬롯을 쓰면 자동은 admission 거절 후 예약 상태로 대기한다.
- 자동 worker 제한은 answer 반환이 아니라 실제 Run 종료 시 해제한다. timeout 후 취소에 응하지 않는 내부 run도 슬롯을 계속 점유하며 추가 자동 요청으로 수동 슬롯을 소진하지 않는다.
- 별도 자동 dispatcher는 한 번에 한 작업만 제출한다. DB에 QUEUED를 저장할 수 있지만 추가 모델 실행 pool/무한 메모리 queue는 만들지 않는다.
- 자동 작업의 전역 대기 상한100, 하루 실행 시작 상한20을 초기 안전값으로 제안한다. 다중 서버에서도 AI DB 원자적 quota/admission으로 제한한다. 상한 초과 대상은 다음 순회/다음 날짜에 재발견하며 누락 완료로 표시하지 않는다.
- AI DB 작업 저장은 지식 검색 private pool과 분리한 작은 private JDBC pool(최대2/접속2초)을 제안한다. 같은 AI PostgreSQL/ai schema를 쓰되 거래 JPA DataSource/TransactionManager에는 등록하지 않는다.
- schema는 명시적인 AI 초기화 명령으로 추가한다. 서버 시작/거래 요청에서 AI schema 생성이나 migration을 기다리지 않는다. AI DB 미초기화/장애 시 진단만 비활성·안전 오류가 된다.

AI 실패가 거래 처리 호출에 연결되지 않는다는 보장과 공유 서버의 CPU/거래 DB read 부하가 물리적으로0이라는 주장은 구분한다. 제한된 추가 읽기는 존재하므로 latency/쿼리 timeout/포화 실험으로 측정한다.

## 5. 어디까지 저장하고 조회하는가

AI DB `ai.diagnoses` 제안: 작업과 결과를 한 행에서 관리하고 source DB FK는 만들지 않는다.

- id, sourceNamespace, triggerType, transactionId, 내부 txHash, targetType/orderId, triggerPolicyVersion, eventKey.
- jobStatus, claimedBy/claimToken/leaseUntil, admissionAttempts, detectedAt/startedAt/completedAt/lastObservedAt.
- actorUserId, executionOrigin, Agent runId, Skill id/version/definitionHash, model, indexVersion.
- responseStatus, server classification, 관측 findings, 검증된 해석, uncertainties, recommendedNextCheck.
- 허용된 Tool evidence snapshot와 retrievedAt, 출처 식별자(path/version/hash/domain/minimumRole/chunkId), citation refs, 안전한 trace, 호출 수/token/latency, safe errorCode.
- targetStale, retentionExpiresAt. 실패에는 검증된 결과가 없으면 answer를 채우지 않는다.

결과 JSON 최대64KiB. 전체 AgentResponse를 그대로 직렬화하지 않고 허용 필드 DTO로 정규화한다. 주문/견적 ID·가격·수량 등 운영에 필요한 안전한 관측은 ADMIN 이력에 저장하되 private key/JWT/secret/signature/raw transaction/password/email/자유 형식 exception/원시 prompt/provider 전체 응답/chain-of-thought는 저장하지 않는다. RAG 청크 본문은 복제하지 않고 승인된 출처 metadata만 보관한다. 진단 이력은 embedding/지식 manifest/RAG corpus에 넣지 않는다.

저장된 Tool 사실은 실행 당시 관측이며 현재 상태가 아니다. 모든 화면에 관측 시각과 stale 여부, 자동 수정 안 함을 표시한다. 지식 승인이 취소되거나 해당 source hash/version을 현재 승인 corpus와 대조할 수 없으면 조회 응답의 정책 해석·인용을 숨기고 APPROVAL_UNVERIFIED를 표시한다. 승인 metadata 확인 장애에서도 전체 저장 해석을 그대로 내보내지 않는다. 원래 보관 기록은 감사용으로 유지하되 외부 조회는 안전하게 축소한다.

조회 API 제안:

- GET /api/ai/diagnoses: ADMIN-only, orderId/jobStatus 제한 필터, keyset cursor, limit 기본20/최대50, 가벼운 요약.
- GET /api/ai/diagnoses/{id}: ADMIN-only, 안전한 상세·trace·출처. USER403, 미존재404.
- 기존 POST /api/ai/agent/answers의 수동 질의는 유지. 자동 진단 저장을 이유로 모든 질문/대화를 저장하지 않는다.

웹은 tools/websocket-test-client의 최소 ADMIN 패널을 확장한다. 주문 선택 후 수동 Skill 실행, 자동 이력 목록/상세, 출처·관측·해석·trace·오류·stale·자동 수정 안 함을 보여준다. 일반 USER에게 자동 운영 진단을 제공하지 않는다. HTML은 textContent 등 안전한 텍스트 렌더링을 사용하며 모델 출력 HTML/링크를 실행하지 않는다. 자동 WebSocket topic/푸시·독립 챗봇/Android 구현은 제외한다.

## 6. 변경 위치와 작업 순서

새 `ai/diagnosis/`: Properties, 제한 스캐너/read projection, 예약/claim 저장소, dispatcher, 허용 결과 DTO, ADMIN controller. AI 전용 초기화 SQL 및 private JDBC 연결 경계. Agent에는 서버 origin과 자동 admission의 최소 내부 확장을 적용한다.

기존 거래 service/entity/schema/정산/컨트랙트는 수정하지 않는 것을 기준으로 한다. 실제 구현 중 이를 바꿔야 한다면 이유를 보고하고 범위 재승인을 받는다.

하나의 승인 범위 안에서 다음 순서로 진행한다:

1. AI schema/중복·claim·보관 계약과 격리 테스트.
2. 커밋 상태 감지/자동 admission/기존 Skill 연결/중단·재시작 검증.
3. ADMIN 조회·최소 웹 패널·권한/출처 유효성 검증.
4. 실제 PostgreSQL/Anvil·공급자·기존 retrieval/거래/웹 회귀, 지식·manifest·색인 갱신, 별도 검토, 완료 보고.

새 AI_AUTO_DIAGNOSIS_ENABLED=false 및 actor/namespace/주기/상한 설정을 example에 기록한다. AI/Agent/Tool/Skill 활성 조건을 모두 요구한다. 실제 .env 활성화는 설정 안내 후 사용자 조치 또는 별도 요청으로 진행한다.

## 7. 테스트 계획과 완료 기준

결정적/동시성 테스트:

- rollback/미커밋은 예약되지 않음, commit 이후 예약, 기존 review backlog 발견, 오래된 id 상태 전환 재발견, keyset 페이지 공정성.
- 같은 사건 반복 감지/다중 scanner/worker에서 작업1개·결과 확정1회, 원장 namespace 분리, Skill/index 변경에도 재분석0.
- QUEUED/RUNNING/완료 저장 직전 crash, lease 만료, 늦은 결과 저장 차단, AI DB 복구 재발견, marker 유지/결과 TTL, BUSY3회/일20회/전역queue100 제한.
- 자동 실제 run1/글로벌worker2/수동 슬롯 보존, 취소 무시 run과 timeout도 추가 자동 실행 차단, 기존 scheduler와 분리.
- actor 비ADMIN/삭제/설정 없음, USER API 거부, 잘못된 대상 연결, 실행 전후 상태 변경, phase6 USER/ADMIN 경계·trace 회귀.
- AI DB/LLM/embedding/RPC/정의/승인 장애에서도 거래 성공/실패/검토 격리·잠금·quote 소비·정산 회귀가 유지됨.
- 진단 전후 거래 DB 전체 관련 행/필드와 Anvil nonce/잔고/블록 불변. AI 결과 기록만 변경됨. 진단 실패가 reconciliation 호출에 전파되지 않음.
- 저장/로그/API/웹 민감 값 canary, source 승인 취소·확인 장애, 결과 크기 상한, 텍스트 렌더링/XSS 차단.

실제 통합/평가:

- 전용 테스트 PostgreSQL에서 unique/claim/quota/TTL과 두 DB 경계를 확인. 모델/RPC 중 AI DB transaction이 열려 있지 않음을 검증.
- Anvil에서는 테스트가 명시적으로 생성한 전용 fixture로 REVIEW_REQUIRED 감지→Skill→저장→ADMIN 조회를 검증한다. 테스트 setup의 거래와 진단의 read-only 실행을 구분하고 운영 데이터/기존 체인을 임의 변경하지 않는다.
- 실제 공급자로 REVIEW_REQUIRED/event mismatch/receipt 부재 등 제한된 진단 시나리오를 평가한다. fixture 기반임을 밝히고 실제 운영 장애 평가라고 주장하지 않는다. model 실패/불확실성은 PASS로 숨기지 않고 결과별로 보고한다.
- Phase 6 baseline: golden hit12/12, MRR0.8819, 직접 근거12/12, 무관 오답0/8, critical ANSWERED2/2 및 scoped3/3 유지. 기존 Agent6route·Skill6선택도 회귀.
- 전체 backend/forge/웹 테스트와 build, 독립 검토. AI 끈 상태/장애/포화 시 거래 latency도 통제된 조건에서 전후 비교하고 수치·한계를 기록한다.

완료는 한 운영 이벤트가 자동으로 안전한 진단 이력에 연결되고 ADMIN이 수동 질의와 함께 확인할 수 있으며 중복·장애·권한·불변성을 검증한 상태다. 자동 복구, 무손실 이벤트 버스, 모든 장애 원인의 정확한 진단을 완료했다고 쓰지 않는다.

## 8. 이번 제안의 승인 포인트

커밋 상태 polling(5초/50건), REVIEW_REQUIRED 한 유형, source namespace+tx 단위 일회성 사건, AI DB 독립 저장과 lease/자동 재호출 금지, 기존 ADMIN actor, 자동 run1/대기100/일20 안전 상한, 결과7일+dedup marker 보존, 최소 ADMIN 웹 패널을 함께 제안한다. 이들은 아직 승인되지 않은 Phase 7 설계값이다. 승인 후 같은 범위로 구현하며 상세 요구사항을 다시 길게 확인하지 않는다.

이번 단계에서는 제품·테스트·설정·거래/AI DB·active 지식/manifest/색인·실제 .env를 변경하지 않았고 새 테스트/공급자 호출도 실행하지 않았다. 읽기 전용 조사와 설계 문서 작성만 수행했다. 동작 변경 없는 설계 제안이므로 별도 검토는 구현 후 수행한다.

## 9. 승인 후 실제 구현과 위치

1~8절은 승인받은 설계의 당시 기록이며 이후 완료 사항은 이 절부터 누적한다. 시작 HEAD는5f114db다. 당시 미커밋 README/implementation-log/Phase6 승인 기록/본 설계 문서는 이전 설계 작업이며 제품 변경과 구분했다.

- `backend/src/main/java/com/pricetrack/exchange/ai/diagnosis/`: DiagnosisProperties 초기 안전값과 범위 검사, DiagnosisSourceReader 짧은 read-only projection/기존 ADMIN 확인, PgDiagnosisStore private pool/예약/claim/quota/결과/TTL, DiagnosisCoordinator 별도 scanner·dispatcher, DiagnosisPayload 저장·조회 allowlist/출처 승인 guard, DiagnosisController ADMIN API.
- `backend/src/main/resources/ai/diagnosis-schema.sql`: ai.diagnoses/ai.diagnosis_daily_usage, eventKey unique와 상태/크기 제약. 기존 거래 schema와 AI 지식 schema는 변경하지 않았다. ADMIN `POST /api/ai/diagnoses/index`만 명시적으로 실행한다.
- `AgentService.answerAutomatic`: 서버 고정 질문/orderId/settlement-debugging. 실제 worker 종료까지 유지되는 자동 gate와 기존 worker2/대기열0 예산 공유. 출처 검색·Tool·응답 검증을 그대로 사용한다. HTTP가 origin을 지정할 수 없다.
- `OpenAiAgentProvider`: 기존 실제 공급자 회귀가 `/data/status`를 잘못 생성하는 문제를 실제 평가에서 발견했다. `/status`와 null 문자열 예시를 출력 지침에 추가했다. schema/사실 값 검증/assertion을 완화하지 않았다.
- `application.yml`/`.env.example`: default false, actor/namespace와 조정 가능한5초/50/100/20/7일 설정. 실제 `.env`는 변경하거나 활성화하지 않았다.
- `tools/websocket-test-client/src/diagnosis.js`, api.js/main.js/index.html: 기존 인증을 사용하는 최소 ADMIN 패널. 수동 Skill·자동 이력/이전 페이지/상세, 안전한 텍스트 출력, 계정 변경 시 늦은 응답 폐기. 독립 챗봇·푸시·Android는 없다.
- active 지식9개 구조/역할/domain 유지. system-overview/authorization-policy만v6와 승인 hash를 갱신했다. 진단 이력/개인 데이터/Skill 정의를 embedding하지 않았다.

구현 상세와 조정:

- 실제 거래 상태 전환/version 컬럼을 추가하지 않았다. 검토 전환 시각 대신 detectedAt/Tool retrievedAt와 시작/완료 시각을 저장한다. 이벤트와 대상 종류는 REVIEW_REQUIRED/ORDER로 고정되어 일부 개념 필드는 상수/응답 DTO에 표현된다.
- SQL advisory transaction lock으로 전역 QUEUED100/하루 UTC quota20/동시 RUNNING claim1을 직렬화한다. 여러 namespace에서도 AI DB 전체 안전 상한을 공유한다. Agent 실제 worker2/자동 gate1은 프로세스 단위다. 각 서버는 같은 원장 namespace와 동일 안전 설정을 사용해야 한다.
- claim lease90초, BUSY 실행0만10초 간격 최대3회. BUSY는 예약 당시 UTC 날짜의 quota를 반환한다. preflight/구성 오류도 보수적으로 claim quota에 포함된다. 이는 정확한 유료 model 호출 수가 아니라 실행 시작의 안전 상한이다.
- lease 만료 RUNNING은 INTERRUPTED marker로 전환하고 재실행하지 않는다. 결과 저장은 동일 claim token + RUNNING + 유효 lease 조건으로 한 번만 확정한다. uncertain call을 다시 실행하거나 결과 품질을 이유로 재분석하지 않는다.
- 결과 본문7일 후 제거하되 원장 namespace/eventKey/대상/종료 상태 marker는 유지한다. 조회 시 TTL도 검사하므로 purge 주기 전에도 만료 JSON을 노출하지 않는다. 작은 marker의 원장 수명 관리와 운영 삭제 절차는 향후 정책이다.
- private AI pool2/접속2초/query2초. initialize와 매 DB 작업에서 current_database의 exchange_ai 계열을 확인하여 거래 DB 오지정을 거부한다. LLM/Tool/RPC 중 AI connection/lock을 잡지 않는다.
- 해석·출처 승인 무효화 시 answer/citations/모델 uncertainties/다음 확인 항목과 정책·종합 trace refs를 폐기한다. Tool 관측과 서버 분류는 이력으로 남긴다. source metadata는 기록하지만 RAG 본문/txHash/키/서명/원시 모델 응답은 결과 JSON에 저장하지 않는다.
- 정상 PARTIAL은 COMPLETED다. 작업 실패/불명확 실행과 답변 품질은 서로 다른 상태다. 이력에서 관측 시각·targetStale·자동 수정 안 함을 확인해야 한다.

## 10. 구현·실제 검증 기록

### 결정적/통합 회귀

- 신규 진단24개 실행 통과: coordinator7, 자동 admission2, payload3, ADMIN HTTP3, source commit/rollback2, 실제 PgDiagnosisStore5, PostgreSQL+Anvil 종단간/장애 latency2. 별도 live Java 테스트1개는 일반 suite에서 skip하고 opt-in 평가로 실행한다.
- commit 이전/rollback 비노출, 이전 id 상태 재발견, actor DB 역할, 페이지 wrap/AI 실패 시 cursor 유지, stale/손상 연결, BUSY 실행0 재예약, 실패 전파 차단을 검증했다.
- 실제 PostgreSQL에서 경쟁 예약8회 중 성공1, 경쟁 claim2회 중 성공1, 결과 확정1회, queue/daily quota, BUSY3회 종료, namespace 분리, lease 만료 재호출0, TTL 후 marker 유지·재예약0을 검증했다. quota 테스트는 현재 날짜의 기존 test DB 값을 보존/복원하며 자신이 만든 namespace 행만 제거한다.
- 자동 provider/Tool이 취소에 응하지 않아도 gate를 실제 종료까지 유지하고 추가 자동 실행을 거부하며 수동 run이 남은 슬롯에서 실행되는 테스트가 통과했다.
- 전용 exchange_tool_test+exchange_ai_test+실제 Anvil에서 REVIEW_REQUIRED→예약→Skill→저장→ADMIN 상세를 검증했다. 주문/전송/견적/잠긴 잔고 모든 fixture 행·필드와 trade 생성0, Anvil nonce/잔고/블록 불변을 확인했다. 진단 과정의 배포/서명/broadcast는0이며 RPC NOT_FOUND를 실제 조회했다.
- 전체 backend323 중312통과/11skip/실패·오류0. forge36통과, 웹34통과/build88modules. 최종 전체 실행은 출력 지침 보완·정의/manifest·DB guard와 테스트를 포함한다. 이후 opt-in receipt 부재 fixture의 executionStatus만 UNKNOWN으로 정정하고 같은 live 평가를 다시 실행한다.
- skip11: 기존 LiveAgent/KnowledgeRefresh/LiveRag/RetrievalAnswer/RetrievalExperiment/LiveSkill 각1, 이번 LiveDiagnosis1, 기존 ToolAnvil 성공 event1/거래 broadcast Anvil2/quote PostgreSQL concurrency1. LiveAgent/KnowledgeRefresh/LiveSkill/LiveDiagnosis는 별도 opt-in으로 실행했다. 나머지 조건부 시나리오는 이번 전체 명령에서 실행하지 않았다.
- 첫 unit 실행에서는 새 Mockito 재-stub과 페이지 fixture 기대값2건이 실패했고 fixture를 수정했다. 주장/검증 기준을 줄이지 않았다. 그 뒤 단위·실제 DB·전체 회귀가 통과했다.

### 실제 공급자·retrieval

- model gpt-5.6-terra, embedding text-embedding-3-small1536, 실제 pgvector/AI DB를 사용했다. 상태는 격리 H2와 receipt fixture이며 운영 Toss 장애/사용자 거래를 평가한 것이 아니다.
- 자동 REVIEW_REQUIRED3시나리오: RECEIPT_NOT_FOUND, EVENT_MISMATCH, FAILED_RECEIPT. 명시 settlement-debugging/모델1/Tool4/검색1/trace8, 중복 감지2회 후 이력1개와 추가 dispatch 재호출0을 검증한다. 서버 분류는 각각 REVIEW_REQUIRED_OBSERVED/INCONSISTENCY_OBSERVED/REVIEW_REQUIRED_OBSERVED. 정상 PARTIAL3/의존성 실패0을 최초·출력 지침 수정 후 평가에서 확인했다. UNKNOWN receipt 실행 상태 정정 후 최종 수치는 raw 보고서를 따른다.
- 최종 UNKNOWN fixture 평가도 정상 PARTIAL3/실패0. input9,129/output2,185 tokens, 지연7.262~9.631초. 이는 최종 자동3개만의 사용량이며 이전 실패·재평가·embedding·golden·Agent/Skill 비용 총계가 아니다. 전체 suite323개 결과와 이후 opt-in Java1개 실행을 구분한다. 공유 수치는 `phase-7-results.json`에 기록한다.
- 최초 기존 Agent/Skill 공급자 회귀에서 generated facts의 /data 접두사를 서버가 거절했다. 출력 지침 보완 후 동일 Agent6 route/Skill6 선택이 통과했고 SYNTHESIS_UNAVAILABLE 등 의존성 실패0이다. Agent 답변은 ANSWERED3/PARTIAL3(이전 실행4/2)이며 route·사실 검증은 유지하되 모델의 불확실성 표현까지 동일하다는 주장은 하지 않는다. Skill6개는 모두 정상 PARTIAL.
- 지식9개57청크. 고정 golden12는 변경하지 않았다. hit@5=12/12, MRR@5=0.8819444444444443, 직접 근거12/12, K회귀0, 무관 후보5/8·무관 오답0/8, 핵심 ANSWERED2/2, 식별자6/8 유지. scoped3/3도 유지. Phase2 baseline10/12와 K (.25/40/문서당2/Top5) 변경 없음.
- 평가 성공 후 서비스용 local exchange_ai index를 `6aa55b18266a78c1e86430144dadc78578cab775f4277c7bd44ec977446994ba`로 동기화했다. test DB canary는 별도이며 전체 suite가 임시 test index를 바꾸는 점을 서비스 DB와 구분한다.

### 제한된 거래 latency 비교

exchange_tool_test 모의 DB buy, warmup 후 각6회: baseline 중앙값18.1837ms / 독립 자동 스캐너·dispatcher와 AI DB port1 장애 중19.98405ms. 모든 거래 FILLED이며 검토 대상 tx는 그대로였다. 작은 표본/공유 머신이므로 SLA·통계적 무영향/온체인 전송 latency를 보장하지 않는다. 장애 중 거래 호출이 AI의 접속 대기2초를 동기적으로 기다리는 현상은 관측되지 않았다. 포화 상태의 수동 실행 독립성은 별도 admission 테스트로 검증한다.

### 재현·원시 보고서

- backend: `AI_PGVECTOR_TESTS=true AI_TOOL_POSTGRES_TESTS=true AI_AGENT_ANVIL_TESTS=true AI_DIAGNOSIS_INTEGRATION_TESTS=true` 후 `./gradlew test --no-daemon --rerun-tasks`.
- 실제 공급자: AI_DIAGNOSIS_LIVE_EVALUATION=true; 기존 Agent/Skill은 AI_AGENT_LIVE_EVALUATION/AI_SKILL_LIVE_EVALUATION=true. 키는 로컬 .env에서 환경에만 전달하며 출력/기록하지 않는다.
- 지식: AI_KNOWLEDGE_REFRESH_EVALUATION=true AI_PHASE7_EVALUATION=true. AI_PUBLISH_CURRENT_INDEX=true는 성공 후 local AI DB만 동기화하는 명시 opt-in이다. 같은 AI test DB의 색인/claim/quota 평가를 동시에 실행하지 않는다.
- `backend/build/reports/ai/phase7-diagnosis-evaluation.json`, phase7-trading-latency.json, phase7-knowledge-refresh.json, phase5-agent-evaluation.json, phase6-skill-evaluation.json, phase6-scoped-retrieval.json은 ignored 원시 결과이며 역사적 공유 JSON을 덮어쓰지 않았다.
- OpenAI Docs 지침으로 [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)와 [평가 가이드](https://developers.openai.com/api/docs/guides/evaluation-best-practices)를 확인했다. 출력 schema/서버 근거 검증과 실제 공급자 평가를 병행하며 schema 준수를 자연어 진실성 보장으로 해석하지 않는다.

## 11. 남은 한계와 검토

- polling 전에 사라진 상태의 무손실 전달, 운영 Toss 장애 원인/실제 사용자 거래, 실제 Anvil 성공 MATCH는 이번 자동 진단 평가에서 확인하지 않았다. 기존 조회 Tool에 없는 revert/Oracle 직접 검증을 추가하지 않았다.
- 실제 프로세스 kill·여러 서버 장기 chaos는 수행하지 않았다. DB 경쟁/lease/늦은 확정과 취소 무시 run으로 재시작·실행 불확실성 경계를 모사했다. lease 이후 다른 서버의 다른 사건 실행과 기존 비협조적 외부 호출의 물리적 중첩까지 exactly-once라고 보장하지 않는다.
- 웹은34개 자동 테스트와 build를 검증했으며 사용자의 실제 브라우저에서 자동 ADMIN 패널을 클릭하는 수동 시연은 별도 확인 항목이다.
- 자연어 해석/불확실성 문장의 완전한 진실성, 모든 장애 원인의 정확한 자동 판단은 보장하지 않는다. 운영자가 근거와 원장을 다시 확인해야 한다.
- `.env` 활성화와 운영용 AI 진단 schema 초기화는 수행하지 않았다. 테스트용 schema와 명시 지식 색인만 갱신했다. 커밋·Phase8 구현은 하지 않는다.
- 독립 review_ai_phase7 결론: **발견된 필수 수정 없음**. 승인 설계/master guide, 신규 제품8파일·SQL·테스트8클래스, Agent/Provider/설정, 웹과 지식/manifest/문서/공유 결과를 직접 확인했다. 거래 원장 변경 없음, ADMIN/출처 경계, 중복/claim/lease/quota, 취소 불응 gate와 재호출 금지를 검토했다.
- 검토자 직접 `npm test`34/34와 `git diff --check` 통과. 격리 Java 재실행은 Gradle cache lock 권한 오류 후 승인 미완료로 실행하지 못했다. 전체323/312통과/11skip는 구현자 실행 결과이며 검토자가 재실행한 결과로 표기하지 않는다. 검토자는 원시 공급자/golden/latency 수치를 대조했으며 공유 DB/Anvil/외부 공급자/실제 .env/키에는 접근하지 않았다.
- 선택 보완 제안: invalid SKIPPED와 최종 BUSY FAILED는 결과/actor 본문이 없고 TTL이 설정되지 않아 내부 tx_hash/claimed_by 메타가 남는다. 승인된7일 계약은 결과 본문 대상이고 dedup marker는 보존하므로 필수 수정으로 판단하지 않았다. 향후 모든 종료 경로의 메타 보존 기간 통일과 purge 테스트를 검토한다. 이번에는 제품 동작을 추가 변경하지 않았다.
- 검토 후 완료/검토 기록과 안내 상태만 갱신했다. Phase7 구현·검증·독립 검토 완료, 사용자 완료 승인 대기. 검토 결과가 미검증 운영 시나리오나 자연어 진실성 보증을 대신하지 않는다.

커밋 메시지: `feat(ai): 검토 필요 거래 자동 진단과 ADMIN 이력 조회 구현`
