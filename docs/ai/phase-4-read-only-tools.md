# AI Phase 4 — Read-only Tool Layer 설계·구현·검증

> 설계 2026-09-28 / 사용자 구현 승인 2026-09-29 / 구현·검증 기록 2026-09-30.
> 1~8절은 승인 설계이며, 실제 구현·테스트·제한·검토 결과는 9절 이후를 따른다.

## 1. 목적과 범위

RAG는 승인된 문서에서 규칙을 검색한다. Tool은 인증 사용자에게 허용된 **현재 시스템 사실**을 조회한다. 예를 들어 주문 상태는 Tool, SIGNED 상태의 의미는 RAG가 담당하며 두 근거를 결합한 설명은 Phase 5의 역할이다.

이번 제안은 내부 Boot 모듈의 명시적 Tool registry, 입력/출력 계약, 서버 인가, 읽기 facade, 최소 검증용 HTTP 진입점과 감사 로그다. Tool 호출 자체에는 embedding·LLM·AI DB가 필요하지 않다. Agent loop, provider function calling 연결, Skill, 자동 분석, 챗봇 UI, mutation, 거래 정책 변경은 제외한다.

Phase 3 K(1200바이트·overlap 60·threshold 0.25·후보 최소 40개·문서당 2개·Top-K 5)는 유지한다. Hybrid는 비교 실험으로만 남기며, RAG API의 ADMIN-only·manifest/hash/index 승인 경계도 바꾸지 않는다.

## 2. 현재 코드에서 확인한 재사용·추가 경계

경로는 backend/src/main/java/com/pricetrack/exchange 기준이다.

| 사실 | 기존 코드와 주의점 | 제안 |
|---|---|---|
| 주문 | order/OrderService.findOne은 ID+userId로 본인 조회. 기존 REST는 expectedOutputAmount를 outputAmount로 표시 | USER는 재사용, Tool DTO는 expectedOutputAmount와 실제 체결을 구별. ADMIN 운영 상세는 별도 인가 조회 |
| 견적 | quote/PriceQuoteService.issue는 발급·저장, lockAndValidate는 만료 상태 변경·행 잠금 가능. 조회 전용 서비스 없음 | 새 읽기 facade에서 소유권 조건 조회. issue/consume/lockAndValidate/signedReport 호출 금지 |
| 체인 거래 | blockchain/transaction/BlockchainTransaction에 rawTransaction·errorMessage가 함께 저장됨 | entity 직렬화 금지. 주문 연결·소유권 검증 뒤 안전한 요약만 반환 |
| receipt | reconciliation은 조회 뒤 재전송·정산·격리를 수행하는 mutation 경로 | 독립된 읽기 RPC adapter. reconciliation/settlement/sender를 호출하지 않음 |
| 시장 | market/MarketPriceService.current → 두 공급자의 current는 메모리 snapshot 조회. Toss는 현재 시각 기준 상태를 재평가하며 미초기화 시 예외 | 기존 조회 재사용. 외부 Toss refresh/tick·거래 가능 여부 검사·새 견적 발급을 유발하지 않음 |
| 포트폴리오 | portfolio/PortfolioService는 본인 DB 잔고와 현재 가격으로 평가. wallet/UserBalance는 총량·잠금·가용량을 보유 | 동일 계산 의미 유지. 읽기 projection으로 잠금·가용량과 가격 관측 시각 보강, 기존 REST 계약은 변경하지 않음 |
| 운영 목록 | transaction 상태에 REVIEW_REQUIRED가 존재. 기존 미완료 조회는 무제한 목록 | ADMIN 전용 bounded query 추가. 주문 자체가 REVIEW_REQUIRED라고 표현하지 않음 |

현재 orderId는 양의 Long, quoteId는 0x 접두 bytes32 hex 문자열(66자), userId는 인증 principal의 Long이다. orders에는 quoteId 필드가 없고 price_quotes.orderId로 역조회한다. 모델·예시의 가상 필드를 실제 entity에 존재하는 것으로 가정하지 않는다.

## 3. 호출 구조·배치

```text
JWT 검증 → 서버 AuthenticatedUser → Tool dispatcher
  → allowlist·역할·입력 검사
  → 소유권 검증 읽기 facade → 거래 DB / 기존 시장 snapshot
  → 권한 확인된 연결 tx만 읽기 RPC adapter로 receipt 조회
  → 안전한 DTO + source/time/error → 감사 로그
```

신규 코드는 ai/tool 아래 registry/dispatcher, DTO/schema, read facade, receipt adapter, audit로 제한한다. 필요한 bounded read repository query만 기존 도메인에 추가한다. 기존 주문·견적 발급·sender·정산 구현은 변경하지 않는다. repository 접근은 서버 읽기 facade의 책임이며 미래 LLM/Agent에는 registry만 제공한다. 함수 이름으로 reflection해 임의 Spring bean을 호출하지 않는다.

내부 서비스를 재사용하며 서버가 자신의 REST API를 HTTP로 재호출하지 않는다. JPA 읽기는 거래 DB 트랜잭션(@Transactional(readOnly=true))에서 수행한다. 이 annotation만으로 보안을 보장한다고 주장하지 않고 호출 allowlist·금지 의존성·DB 불변 테스트를 함께 적용한다. AI DB로 운영 원장을 복사하거나 분산 트랜잭션을 만들지 않는다.

receipt는 짧은 DB 읽기/인가 후 트랜잭션을 종료하고 RPC를 수행한다. DB+RPC+시장 가격의 원자적 snapshot을 주장하지 않으며 각각의 조회/관측 시각을 붙인다. RPC timeout으로 DB connection을 장시간 점유하지 않는다.

## 4. 제안하는 Tool allowlist

| Tool | 입력 | 허용 범위 | 반환 사실 |
|---|---|---|---|
| getOrder | orderId | USER 본인 / ADMIN 운영 상세 | 방향·종목·입력/예상 출력·상태·생성/갱신 시각·연결 quoteId, 존재하는 실제 Trade 요약 |
| getQuote | quoteId | USER 본인 / ADMIN 운영 상세 | 저장 상태·방향·입력·서명 가격·minimumOutput·수수료·observedAt/validUntil/consumedAt·orderId·시간상 만료 여부 |
| getBlockchainTransaction | orderId | USER 본인 / ADMIN 운영 상세 | 연결 유무·type/status·txHash·created/submitted/confirmedAt·blockNumber·안전한 오류 표시 |
| getReceiptSummary | orderId | USER 본인 / ADMIN 운영 상세 | 연결 txHash에서 RPC 조회한 receipt 발견 여부·실행 status·block/hash·confirmation·읽기 이벤트 검증 결과 |
| getMarketStatus | 입력 없음 | 인증 USER/ADMIN | marketStatus·priceStatus·provider·observedAt |
| getCurrentReferencePrice | 입력 없음 | 인증 USER/ADMIN | mSEC 기준 가격·provider·observedAt·상태. 주문 체결가로 해석하지 않음 |
| getPortfolio | 입력 없음 | USER/ADMIN 자신의 자산만 | 총량·잠금·가용량·평단·평가액·미실현손익 및 사용 가격/관측 시각 |
| listAbnormalOrders | 제한된 필터·cursor·limit | ADMIN만 | REVIEW_REQUIRED tx와 연결 주문, 선택적으로 오래 대기한 SIGNED/SUBMITTED 요약·후속 cursor |

master guide의 getReceiptSummary(txHash)는 개념 예시다. 이 제안은 orderId로만 요청받아 서버에서 연결 txHash를 결정한다. 임의 txHash/RPC URL 입력을 허용하지 않아 타인·프로젝트 외 거래 조회와 SSRF 경계를 줄인다. USER에게 orderId 없는 UPDATE_PRICE 시스템 거래를 노출하지 않는다. 시스템 거래의 별도 운영 상세 Tool은 이번 범위에서 제외한다.

ADMIN의 타인 상세 접근은 위 운영 상세 Tool에만 허용하고 역할을 facade에서도 검사한다. 기존 본인 조회 REST API의 ADMIN 동작은 바꾸지 않는다. getPortfolio는 임의 userId를 받지 않는다. 요청자가 userId/role을 지정하는 인자는 어느 Tool에도 없다.

운영 목록은 기본 REVIEW_REQUIRED, 페이지 기본 10/최대 20, 정렬 createdAt+id의 제한된 cursor 방식이다. 대기 시간 필터를 사용하는 경우 기본 300초, 허용 60~3600초를 제안한다. 오래 대기했다는 사실만 표시하며 고장/재전송 필요라는 결론을 내려주지 않는다. FAILED와 PENDING_ONCHAIN 모두를 무조건 비정상으로 분류하지 않는다. 필터와 cursor를 실제 query에 적용해 전체 목록을 읽고 잘라내지 않는다.

## 5. 데이터 계약과 오류

입력은 Tool별 JSON Schema와 동일한 서버 검증을 제공한다. additionalProperties=false, 양의 orderId 범위, quoteId hex 패턴, cursor 길이·형식, 페이지·필터 상한을 명시한다. unknown tool/field, role/userId/token/RPC URL 주입은 거부한다. 목록 필터는 enum allowlist이며 SQL 문자열이나 임의 조건식을 받지 않는다.

결과 공통 envelope는 tool/version/status/retrievedAt/source/data/error를 가진다. 금액·가격은 정밀도 보존 decimal 문자열과 단위를 함께 반환하고, 시각은 UTC ISO-8601로 유지한다. 예: inputSymbol=mKRW/outputSymbol=mSEC인 BUY와 반대인 SELL. DB amount는 token 단위, 견적 input/minimumOutput는 wei(1e18), priceE8은 1e8에서 안전하게 변환한다. 새로운 반올림 정책을 만들지 않는다.

견적은 storedStatus와 expiredByTime(조회 시각 > validUntil)를 별도 반환한다. CONSUMED가 시간상 만료돼도 소비 사실을 지우지 않는다. ISSUED라도 시간상 만료된 값일 수 있다. 조회로 EXPIRED를 저장하거나 실제 주문 가능성을 단정하지 않는다.

receipt SUCCESS는 EVM 실행 성공이라는 사실이며 DB FILLED를 뜻하지 않는다. 이벤트 MATCH/MISMATCH/UNKNOWN은 기존 순수 ContractEventParser를 사용해 읽기 검증하고, 예외를 안전한 분류로 변환한다. receipt absence는 NOT_FOUND로 표시하되 주문 실패라고 해석하지 않는다. confirmation 관측값과 설정된 요구값을 구별한다. DB와 체인 값이 다르면 두 원본 사실을 보존하며 정산을 실행하지 않는다.

개인 자원의 타인 접근과 실제 미존재는 동일 RESOURCE_NOT_FOUND로 처리한다. 미인증 AUTHENTICATION_REQUIRED, ADMIN Tool 거부 TOOL_FORBIDDEN, 입력 INVALID_TOOL_ARGUMENTS, 미등록 TOOL_NOT_ALLOWED, busy TOOL_BUSY를 구분한다. RPC/DB timeout·오류는 TOOL_TIMEOUT/TOOL_UNAVAILABLE로 반환하며 가짜 성공 data를 채우지 않는다. 블록체인 비활성 시 receipt RPC Tool만 BLOCKCHAIN_DISABLED이며 DB에 남은 주문·견적·tx 이력 조회는 유지한다. 기존 본인 주문에 연결 tx/quote가 없으면 NOT_LINKED로 반환한다. 모의 주문도 이 경우에 포함되지만 현재 orders에 거래 모드 필드가 없으므로 부재만으로 모의 거래/실패를 단정하지 않는다. 직접 지정한 quoteId의 미존재는 RESOURCE_NOT_FOUND다. 연결이 끊긴 불일치는 UNKNOWN/INCONSISTENT_LINK로 표시한다. 미래 Agent는 미확인 상태를 사실 확인 실패로 취급해야 하며 Phase 5에서 해석 테스트를 추가한다.

금지 출력: entity 전체, price signature, executor/운영자 비밀 정보, rawTransaction, JWT/refresh token, 비밀번호/해시, DB/API credential, 내부 예외 stacktrace·원문. txHash는 해당 권한 범위의 tx/receipt Tool에만 포함한다. errorMessage는 현재 정형 error code 필드가 없으므로 무리한 원인 추론 대신 hasRecordedError와 일반화된 범주를 사용한다. 인식되지 않는 오류는 UNCLASSIFIED로 남긴다.

## 6. HTTP·인증·격리·감사

검증용 최소 진입점으로 POST /api/ai/tools/{toolName}, body={arguments:{...}} 하나를 제안한다. POST는 dispatcher 입력 전달 형식일 뿐 거래 상태 변경을 의미하지 않는다. 기존 거래 REST는 유지한다. schema/allowlist는 코드 계약·테스트에서 제공하고 별도 관리 API나 UI는 만들지 않는다.

현재 /api/ai/**는 ADMIN-only다. **tools의 정확한 경로만 인증 USER/ADMIN에 허용하는 matcher를 앞에 추가**하고 기존 /index,/search,/answers와 나머지 AI 경로의 ADMIN-only는 유지한다. 내부 dispatcher/facade도 독립적으로 principal/role/소유권을 검사한다. 일반 USER에게 ADMIN 지식 검색을 개방하지 않는다.

독립 AI_TOOLS_ENABLED=false 기본 설정을 제안한다. Tool 활성화는 AI_ENABLED·AI DB/API 키 유무와 독립적이며 초기화 시 AI 색인·외부 API 호출이 없다. runtime toggle·새 DB·새 테이블은 만들지 않는다. 기존 거래 기능이 AI/Tool 실패를 호출하거나 대기하게 하지 않는다.

서버가 AuthenticatedUser에서 불변 실행 context(userId/role/requestId/purpose)를 만든다. loginId/JWT는 Tool data에 넣지 않는다. Phase 4 purpose는 READ_ONLY_TOOL_TEST, 향후 서버 발급 agentRunId는 Phase 5에서 연결한다. 비동기 실행은 이 context를 명시적으로 넘기고 ThreadLocal 자동 전파나 클라이언트의 role/runId를 믿지 않는다.

기존 공유 web3j의 timeout을 변경하지 않고 Tool receipt용 별도 bounded 읽기 client를 제안한다. permit된 eth_getTransactionReceipt/eth_blockNumber만 실행하며 트랜잭션 전송/estimate/sign은 제공하지 않는다. DB query timeout 2초, RPC 전체 deadline 3초, 호출 전체 5초, 동시 호출 최대 4·대기열 없음, 입력 최대 2KiB·출력 최대 32KiB를 초기 상한으로 제안한다. timeout 시 실제 I/O 취소/연결 해제와 permit 반환을 검증한다. 단순 Future timeout으로 백그라운드 호출이 계속 쌓이는 방식은 사용하지 않는다. JDBC timeout/rollback을 실제 PG 테스트로 확인하며 이 상한을 프로세스 전체 장애 격리 보장이라고 주장하지 않는다.

감사 로그는 requestId, 서버의 userId/role, tool, purpose/서버 runId(없으면 null), 성공/실패/안전한 errorCode, latency만 기록한다. 사용자 질문·arguments·data·토큰·서명·원문 오류는 로그에 담지 않는다. 별도 audit DB나 AI DB 의존성을 추가하지 않는다. 내부 registry 허용/거부 모두 기록하고 HTTP 인증 단계의 거부는 기존 보안 경계 결과로 구분한다.

## 7. 작업 순서와 테스트 계획

승인 후 하나의 Phase 4 범위 안에서 다음 순서로 작업·검증한다. 소단계를 별도 기능 승인처럼 반복하지 않고 설계 변경/범위 확대가 있을 때만 다시 요청한다.

1. DTO/schema·allowlist·context·인가·감사·기본 dispatcher를 만든다.
2. 기존 본인 주문/시장 조회와 부족한 견적·tx·portfolio·운영 목록 읽기 facade를 연결한다.
3. 소유권 확인 뒤 receipt 전용 RPC adapter와 안전한 오류/timeout 처리를 연결한다.
4. 최소 HTTP 검증 경계, 전체 회귀·별도 검토·문서 결과를 기록한다.

| 검증 층 | 필수 시나리오 |
|---|---|
| 단위/계약 | 8개 Tool 정상 DTO, enum/시간/정밀도, signature/raw/secret 비노출, schema와 서버 검증 일치, unknown 필드·ID·cursor·상한 |
| 권한 서비스 | USER 본인 성공, 다른 USER/미존재 동일 실패, 다른 사용자 quote/tx/receipt 우회 실패, USER 운영 목록 거부, ADMIN 운영 상세/목록 성공, 본인 portfolio 고정 |
| HTTP/JWT | 미인증 401, USER Tool 허용과 ADMIN-only Tool 거부, USER /index/search/answers 계속 403, 위조/만료 JWT 차단. 인증 context body 주입 차단 |
| DB 읽기/실제 PG | fixture 생성 후 호출 전후 order/quote/transaction/trade/balance 비교. 저장 상태·잠금·소비·체결·행 개수 불변, 조회 시 만료 상태 불변, paging/timeout 및 별도 AI DB 없이 실행 |
| RPC | 로컬 fake RPC의 receipt 없음/성공/실패/이벤트 불일치/confirmation 부족/오류/느림/큰 응답, 허용 method 외 호출 없음, unauthorized면 RPC 호출 0, 취소·permit 회수 |
| Anvil 선택 실연동 | 기존 전용 fixture 거래 receipt와 DB 요약 대조. Tool 호출 자체가 nonce·잔고·상태·체인을 바꾸지 않음. 준비 거래는 기존 opt-in fixture로 분리하고 운영자 현재 nonce 원장을 오염시키지 않음 |
| 장애·회귀 | AI DB/API 불가에서도 Tool 정상, Tool/RPC 장애 뒤 기존 모의 주문 정상, 기본 K 검색·승인·권한 테스트 유지. 전체 backend·forge·웹 테스트 및 웹 build |
| 별도 검토 | tracked/untracked 코드와 phase 기록을 독립 검토. 민감 DTO·우회 인가·mutation 의존성·timeout·테스트/실행 결과 확인 |

실제 DB 검증은 전용 거래 테스트 DB/fixture를 사용한다. 기존 사용자 데이터·Docker 볼륨을 삭제하지 않는다. 외부 paid AI 호출은 Phase 4 Tool 완료 조건이 아니며 기존 opt-in 평가 재실행이 필요한 경우만 따로 기록한다. live DB/RPC 오류를 LLM이 변형하지 않는 **실제 생성 평가**는 LLM을 연결하는 Phase 5에서 수행한다. 이번에는 구조화된 실패·빈 data 계약을 먼저 검증하며 아직 없는 Agent의 통과 결과를 만들어 적지 않는다.

## 8. 승인 요청·완료 기준

승인 대상은 위 8개 Tool, USER/ADMIN 조회 범위, orderId 기반 receipt, 최소 dispatcher HTTP 진입점, 독립 feature flag, bounded 조회·audit 정책과 테스트 계획이다.

완료 조건은 RAG·외부 LLM·AI DB 없이 단독 호출 가능, 서버 소유권/역할 강제, 금지 정보 비노출, 조회 전후 거래 상태 불변, timeout/에러 안전한 반환, 기존 거래·RAG 회귀 없음이다. 이 조건을 구현·검증·독립 검토한 뒤 Phase 5의 RAG+Tool Agent 설계를 따로 승인받는다.

설계 제안 당시에는 제품 코드·endpoint·환경 설정을 변경하지 않았고, 이후 사용자 승인에 따라 아래 구현을 진행했다.

Phase 3 테스트 보완의 별도 검토(review_ai_phase3_followup)에 이 설계 초안도 포함해 실제 코드·master guide/Phase 0과 대조했고 발견된 필수 수정 없음 결과를 받았다. 이는 설계의 구현 승인이나 향후 실행/권한/timeout 테스트 통과를 대신하지 않는다.

## 9. 실제 구현과 코드 위치

모든 신규 제품 코드는 backend/src/main/java/com/pricetrack/exchange/ai/tool 아래에 있다.

| 파일 | 구현 책임 |
|---|---|
| ToolRegistry | 8개 Tool allowlist, 입력 JSON Schema, 동일 입력 검증, cursor 인코딩/검증 |
| ToolContext / ToolProperties | 서버 principal에서 불변 userId/role/requestId/purpose 구성, 독립 활성화 flag |
| ToolDispatcher | 명시적 switch 호출, 입력/출력 상한, 4개 worker·대기열 없음, 전체 timeout과 안전한 오류 |
| ToolController / ToolResult | POST 진입점과 version/status/retrievedAt/source/data/error envelope |
| read/ToolReadFacade | 거래 JPA 읽기 전용 트랜잭션·2초 query timeout, 소유권·ADMIN 검사, 안전한 projection |
| receipt/ReceiptReader / ReadOnlyReceiptClient | DB 트랜잭션 종료 뒤 별도 HTTP client로 receipt/block 조회, 원문 제한·취소·순수 이벤트 검증 |
| ToolAudit / ToolConfiguration | payload 없는 감사 로그, RAG·AI DB와 독립적인 bean 구성 |

기존 도메인의 발급·주문·sender·정산·repository·거래 schema는 변경하지 않았다. 기존 EntityManager/JPA 모델과 시장 읽기 서비스를 사용하는 전용 조회 facade를 추가했다. bounded 목록 query도 facade 안에 두었다. SecurityConfig는 POST /api/ai/tools/*만 USER/ADMIN에 허용하며 그 밖의 /api/ai/**는 ADMIN-only를 유지한다.

AI_TOOLS_ENABLED의 기본값은 false다. application.yml과 backend/.env.example에 추가했으며 실제 로컬 .env는 자동 변경하지 않았다. AI_ENABLED·AI DB·OPENAI_API_KEY와 독립적으로 동작한다. 기존 RAG K 코드·설정·golden·승인된 9개 지식 문서와 manifest·색인은 변경하지 않았다. 따라서 재색인이 이번 Tool 활성화의 전제 조건은 아니다.

포트폴리오는 총량·잠금·가용량과 평가용 스냅샷의 관측 시각을 함께 반환한다. 같은 스냅샷 하나로 기존 PortfolioService와 같은 산식·반올림을 적용한다. 시장 기준 가격은 MARKET_REFERENCE_NOT_EXECUTION_PRICE로 표시한다. 주문에는 expectedOutputAmount와 실제 Trade를 구분하고 txHash는 tx/receipt 상세에만 포함한다.

견적의 expiredByTime은 조회만으로 저장 상태를 바꾸지 않는다. 견적 연결 주문의 소유자가 다르면 해당 주문 ID를 노출하지 않고 INCONSISTENT_LINK를 표시한다. 연결 부재는 NOT_LINKED이며 모의 거래나 실패라고 단정하지 않는다. REVIEW_REQUIRED는 transaction 상태로 표시한다. 목록은 REVIEW_REQUIRED 또는 WAITING_LONG 필터, createdAt/id cursor, 실제 DB의 limit+1 query를 사용한다.

receipt는 eth_getTransactionReceipt와 eth_blockNumber만 호출한다. RPC 주소·txHash를 요청 인자로 받지 않는다. 응답 hash를 DB 연결 hash와 대조한 뒤 블록/confirmation과 executionStatus, eventValidation을 반환한다. 순수 ContractEventParser를 재사용하며 event MATCH여도 DB 상태를 변경하지 않는다. raw logs, 서명, rawTransaction, 운영자 주소/키와 내부 예외 원문은 반환하지 않는다.

## 10. 호출 계약과 실행 예

backend/.env에 AI_TOOLS_ENABLED=true를 설정하고 서버를 재시작한다. 이번 작업에서 서버를 임의로 재기동하거나 사용자 환경 파일을 바꾸지는 않았다.

```http
POST /api/ai/tools/getOrder
Authorization: Bearer (기존 로그인으로 발급받은 토큰)
Content-Type: application/json

{"arguments":{"orderId":123}}
```

실제 본인의 orderId를 사용한다. getQuote는 {"arguments":{"quoteId":"0x…64자리 hex…"}}, 시장/포트폴리오는 {"arguments":{}}를 사용한다. listAbnormalOrders의 입력은 limit(1~20, 기본 10), filter(REVIEW_REQUIRED/WAITING_LONG), waitingSeconds(60~3600, 기본 300), cursor(이전 nextCursor)다. 명세는 ToolRegistry.inputSchema(toolName)으로 제공한다. 별도 schema API나 UI는 없다.

성공 응답 예(시각·ID는 설명용):

```json
{"tool":"getBlockchainTransaction","version":"1","status":"SUCCESS",
 "retrievedAt":"2026-09-30T00:00:00Z","source":"TRADING_DB",
 "data":{"orderId":123,"linkStatus":"NOT_LINKED","dbReadAt":"2026-09-30T00:00:00Z"},"error":null}
```

실패는 status=ERROR, data=null, source=NONE과 안전한 error 코드다. 미인증 401, 운영 권한 거부 403, 타인/미존재 404, 잘못된 입력 400, timeout 504, 비활성·busy·외부 장애 503으로 구분한다. 미등록 Tool은 TOOL_NOT_ALLOWED/404다. 인증 필터 단계의 오류는 기존 ApiErrorResponse 계약을 유지한다. 조회 성공 속의 NOT_LINKED/NOT_FOUND는 실행 실패가 아니다.

실제 상한: 입력 2KiB, 전체 JSON 출력 32KiB, RPC 수신 64KiB, DB 트랜잭션/query 2초, RPC 두 호출 합계 3초, dispatcher 대기 5초, worker 최대 4·대기열 없음. 중복 JSON key·후행 JSON·unknown field도 거부한다. 큰 RPC 응답은 수신 도중 subscription을 cancel하고, deadline/interrupt는 HTTP future를 취소한다. timeout으로 응답한 worker가 아직 끝나지 않았으면 새 작업을 받지 않아 미완료 호출이 무한 누적되지 않는다. 공유 JVM·거래 DB의 완전한 장애 격리나 인터넷 전 구간의 절대 응답 시간을 보장한다는 뜻은 아니다.

감사에는 서버 requestId/userId/role/tool/purpose/agentRunId/status/code/latency만 기록한다. Phase 4 purpose=READ_ONLY_TOOL_TEST, agentRunId=null이다. 모델 입력·arguments·data·loginId·JWT·원문 오류는 로그에 남기지 않는다.

## 11. 검증 결과 — 2026-09-30

신규 테스트는 backend/src/test/java/com/pricetrack/exchange/ai/tool에 있다.

| 테스트 | 수 | 확인한 내용 |
|---|---:|---|
| ToolIntegrationTest | 15 | 8개 Tool, JWT·USER/ADMIN·교차 사용자·직접 facade 인가, 비노출, 상태 불변, 견적 만료/소비·연결 손상, pagination, RPC 실패 후 모의 거래 |
| ToolDispatcherTest | 6 | flag·미인증, worker 상한·취소/복구, 출력 제한·오류 비노출, schema/입력, payload 없는 audit |
| ReadOnlyReceiptClientTest | 7 | 실제 로컬 HTTP의 성공/실패/미발견/이벤트 불일치/confirmation 부족/위조 hash, 비활성, 느린·큰 chunked 응답, 수신 중 cancel |
| ToolPostgresIntegrationTest | 2 | 전용 PostgreSQL의 5개 테이블 fixture 불변, 잠긴 SELECT 2초 취소·rollback·connection 재사용 |
| ToolAnvilIntegrationTest | 1 | 기존 Anvil 체결 receipt/event MATCH, DB SUBMITTED 유지, nonce·ETH 잔고·블록 높이 불변 |

신규 31개는 실제 PostgreSQL/Anvil opt-in까지 활성화해 모두 통과했다. 전용 exchange_tool_test DB를 새로 준비하고 테스트 소유 행만 정리했다. 기존 exchange DB·볼륨·배포 주소·운영자 nonce 원장은 변경하지 않았다. Anvil 검증은 최근 200블록의 기존 Bought/Sold 이벤트를 사용해 서명·배포·funding·broadcast 없이 수행했다. 장중 시세나 브라우저 매수/매도 재인수 결과와 구별한다.

전체 backend: 221개 중 215 통과, 6 skipped, 실패/오류 0. AI_PGVECTOR_TESTS, AI_TOOL_POSTGRES_TESTS, AI_TOOL_ANVIL_TESTS를 활성화했다. skipped는 기존 거래 Anvil 2개·견적 PostgreSQL 경합 1개·유료 AI 평가 3개이며 이번 신규 Tool 실연동과 구별한다. 외부 유료 AI API는 호출하지 않았다. forge 36개, 웹 30개, Vite build(87 modules)도 통과했다.

AI 장애 격리 기존 테스트 두 파일에 Tool 활성화 상태의 조회 성공을 보강했다. 실제 접속 불가능한 AI DB·가짜 provider 오류 후에도 시장/포트폴리오 Tool과 모의 거래가 동작하는지 확인했고, 마지막 추가 실행 3개 테스트도 모두 통과했다. 이는 위 전체 회귀 이후의 테스트 코드 보강이며 제품 코드는 바뀌지 않았다.

```powershell
# backend/ — 기본 테스트
.\gradlew.bat test --no-daemon
# 이미 준비한 전용 DB와 기존 Anvil 이벤트를 이용한 선택 검증
$env:AI_TOOL_POSTGRES_TESTS='true'
$env:AI_TOOL_ANVIL_TESTS='true'
.\gradlew.bat test --no-daemon --tests 'com.pricetrack.exchange.ai.tool.*'
```

PostgreSQL 선택 테스트는 exchange_tool_test 전용 DB(5432)의 exchange 테스트 접속을 사용한다. DB를 먼저 생성해야 하며 다른 DB로 옮겨서 실행하지 않는다. Anvil 선택 테스트는 8545에 최근 체결 이벤트가 없으면 실패한다. 테스트가 자동으로 거래하거나 체인을 초기화하지 않는다.

## 12. 검토와 남은 경계

아래는 Phase 4 완료 당시 기록이다. 사용자 승인 후 SELL 보완·지식/manifest/로컬 색인 갱신은 [후속 보고서](phase-4-knowledge-refresh.md)를 따른다. Agent 연결은 여전히 구현 전이다.

별도 review_ai_phase4 검토 결과는 발견된 필수 수정 없음이다. 신규 제품 12파일·테스트 6파일, 보안/설정·장애 격리 테스트·관련 문서를 독립 확인했다. 검토자가 HTTP/H2 15개·dispatcher 6개·로컬 RPC 7개를 직접 재실행해 28개 모두 통과했고 diff 검사도 통과했다. 실제 PostgreSQL/Anvil·전체 backend·forge·웹은 구현자의 실행 결과와 테스트 코드를 검토했으며 직접 재실행하지 않았다. 마지막 XML은 검토자의 28개 실행 결과로 교체되므로 위 전체 회귀 결과와 구분한다.

선택 보완 제안은 정상 fixture의 BUY 중심 구성을 보완하는 명시적 SELL 입력/출력 단위·Sold 이벤트 테스트다. 현재 SELL 분기 결함은 발견되지 않았으며 후속 테스트 보강으로 남긴다.

- Phase 4 Tool 호출은 독립적으로 검증됐다. RAG 답변 API가 Tool을 자동 호출하지는 않으므로 기존 LIVE_DATA_REQUIRED 응답 경계는 유지한다. Agent 연결·생성 답변의 Tool 오류 해석은 Phase 5다.
- 승인된 지식 corpus는 Phase 3 평가 시점 그대로다. 그 안의 AI 구현 단계 설명은 당시 기준이며 새 Tool 계약의 최신 기준은 이 문서다. Phase 5 연결 전 지식 문서의 구현 상태·권한 설명, manifest 버전/해시, 색인과 검색 평가를 함께 갱신해야 한다. 이전 hit@5 12/12를 미래 갱신 corpus의 측정값으로 재사용하지 않는다.
- 전체 회귀·실제 DB/RPC 검증은 로컬 환경 결과다. 새로운 Tool endpoint에 대한 브라우저 UI, 사용자별 장기 rate limit, 영속 audit 저장소, Agent loop는 이번 범위 밖이다.
- 로컬 .env는 변경하지 않아 사용자가 활성화하려면 AI_TOOLS_ENABLED=true와 서버 재시작이 필요하다. 비활성 상태는 TOOL_DISABLED로 명확히 응답한다.
