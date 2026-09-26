# AI Phase 0 — 코드베이스 감사

> 작성: 2026-09-27 / 기준 커밋: `c6b0ec9`
> 범위: 코드 조사, 기존 테스트 기준 확인, 실행 경계 제안. AI 구현·의존성·DB·API 변경 없음.

## 1. 현재 상태와 조사 기준

거래소 Phase 5 및 6.1·6.2는 완료, 6.3은 실제 Toss 장중 웹 거래와 장애 복구 수동 인수 대기다. Android 인수 패키지 6.4도 남아 있다. AI Phase 번호는 기존 거래소 Phase와 별개다.

입력 지침은 `claude-docs/AI_AGENT_RAG_IMPLEMENTATION_MASTER_GUIDE.md`다. 해당 파일은 사용자가 제공했으며 수정하지 않았다. 머리말의 `career-project-brief(5).md` 대신 저장소의 `claude-docs/career-project-brief.md`와 실제 코드를 대조했다. 역사적 구현 로그·주석보다 현재 실행 코드를 우선했다.

## 2. 기술·DB 기준

| 항목 | 확인값·근거 |
|---|---|
| Java / Boot | Java 21 / Spring Boot 3.3.4 — `backend/build.gradle` |
| Build | Gradle wrapper 8.10.2 — `backend/gradle/wrapper/gradle-wrapper.properties` |
| 주요 라이브러리 | web3j 4.12.2, JJWT 0.12.6, Spring MVC/Security/JPA/WebSocket |
| 실제 의존성 해석 | runtimeClasspath 보고서 성공. Spring Framework 6.1.13, Jackson 2.17.2, PostgreSQL JDBC 42.7.4 확인 |
| DB | Compose `postgres:16`, 외부 볼륨 `exchange_postgres-data` |
| 스키마 | `backend/src/main/resources/schema.sql`; `ddl-auto=none`, `spring.sql.init.mode=always` |
| 변경 관리 | 재실행 가능한 CREATE/ALTER/INDEX SQL. Flyway/Liquibase 및 AI 의존성 없음 |
| 기본 테스트 DB | `backend/src/test/resources/application.yml`: H2 PostgreSQL 모드, create-drop, schema.sql 실행 안 함 |
| 웹 | `tools/websocket-test-client`, Vite/JavaScript, REST·Native STOMP·SockJS |

주요 테이블은 `users`, `assets`, `user_balances`, `orders`, `price_quotes`, `trades`, `blockchain_transactions`, `price_ticks`, `market_candles`다. `price_quotes.order_id`, `trades.order_id`, `blockchain_transactions.order_id`의 unique 조건과 트랜잭션 sender/nonce unique 조건을 확인했다. 사용자 소유권 확인은 서버 조회 조건으로 강제하며 단순 ID 존재 여부로 대체할 수 없다.

기존 DB와 Docker 볼륨을 이번에 변경하지 않았다. AI 지식 테이블은 향후 `ai_` namespace 등으로 분리하고 기존 SQL 관리 방식을 따른다. 운영 원장을 임베딩 저장소로 복제하지 않는다.

## 3. 패키지와 주요 확장 위치

아래 경로는 `backend/src/main/java/com/pricetrack/exchange/` 기준이다. 각 entity의 Repository는 같은 도메인 패키지에 있다.

| 패키지 / 파일 | 현재 책임 | AI 연결 시 경계 |
|---|---|---|
| `auth/AuthController`, `AuthService`, `JwtTokenProvider`, `JwtAuthenticationFilter` | 가입·로그인·JWT·DB 사용자 복원 | 기존 인증 재사용 |
| `common/config/SecurityConfig` | stateless HTTP 인증 | AI entry와 ADMIN 인가 추가 위치 |
| `user/User`, `UserRole`, `UserRepository` | 사용자·역할 | 모델이 userId/role을 결정하지 못하게 함 |
| `admin/AdminAccountInitializer` | 초기 관리자 생성 | 관리자 조회 API가 이미 있다는 뜻은 아님 |
| `quote/PriceQuote`, `PriceQuoteService`, `QuoteController` | 견적 발급·소유권·잠금·소비 | 기존 견적 조회 전용 서비스 후보 |
| `order/Order`, `OrderService`, `OrderController` | 주문·본인 조회·모의 거래 | 본인 주문 조회 재사용 |
| `order/OnchainOrderPreparationService`, `OnchainOrderService` | 원자적 준비·보상·전송 연결 | mutation이므로 Tool 등록 금지 |
| `trade/Trade`, `TradeService`, `TradeController` | 체결·본인 내역 | 읽기 서비스 재사용 |
| `wallet/UserBalance`, `WalletService`; `portfolio/PortfolioService` | 내부 원장·faucet·평가 | 포트폴리오 조회만 공개 |
| `blockchain/oracle/PriceReportIssuer`, `PriceReportSigner`, `PriceReportEip712` | 스냅샷·보고서·전용 키 서명 | 발급·서명 Tool 금지 |
| `blockchain/transaction/BlockchainTransaction`, `BlockchainTransactionSender`, `BlockchainTransactionPersistence` | nonce·서명 원문 저장·전송·복구 | DTO 조회만 허용, Entity 노출 금지 |
| `blockchain/reconciliation/BlockchainReconciliationService` | receipt polling·확정·격리 | 강제 실행 Tool 금지 |
| `blockchain/contract/ContractEventParser`; `blockchain/settlement/OnchainSettlementService` | 이벤트 검증·멱등 정산 | 조회와 정산 실행을 분리 |
| `market/MarketController`, `MarketPriceService`, `MarketCandleService` | 시장·가격·차트 조회 | 공통 읽기 경계 |
| `market/provider/toss/*`, `market/provider/simulated/*` | 실제·모의 공급자 | Agent가 공급자를 직접 호출하지 않음 |
| `websocket/publisher/WebSocketDeliveryListener` | commit 이후 알림 | 이벤트 영속성은 보장하지 않음 |

## 4. 인증·인가

`JwtTokenProvider`는 HMAC 키로 user ID subject, loginId, 발급·만료 시각을 서명하고 검증한다. `JwtAuthenticationFilter`는 Bearer 토큰의 user ID로 DB 사용자를 다시 읽어 `AuthenticatedUser(userId, loginId, role)`와 `ROLE_USER/ROLE_ADMIN` authority를 만든다. 역할은 LLM 입력이나 요청 body에서 받지 않는다.

`SecurityConfig`는 health·auth·WebSocket handshake와 GET markets를 공개하고 나머지는 인증을 요구한다. 현재 ADMIN-only HTTP matcher나 AI 경계는 없다. 주문은 `OrderService.findOne` → `OrderRepository.findByIdAndUserId`, 체결과 포트폴리오는 principal userId로 제한한다. ADMIN 토큰으로 기존 본인 조회 API를 호출해도 자동으로 타인 데이터 조회 권한이 생기지 않는다.

미래 Tool 어댑터에는 서버가 만든 불변 사용자 컨텍스트를 전달하고, 권한 검사를 거친 DTO만 모델에 준다. 비동기 실행에서는 ThreadLocal SecurityContext가 자동 전파된다고 가정하지 않는다. 내부 서비스 호출은 HTTP 필터를 다시 거치지 않으므로 별도 소유권·역할 검사가 필수다.

## 5. 실제 거래 호출 흐름

1. `QuoteController`의 POST buy/sell → `PriceQuoteService.issue` → `PriceReportIssuer.issue`.
2. `MarketPriceService.requireTradableSnapshot`이 CLOSED/STALE를 거부한다. 발급기는 관측 후 5초 이내, 미래 오차 2초 이내인지 RPC 견적 조회 전후에 확인한다.
3. `BlockchainService.quoteAtPrice`로 Vault 명시 가격 견적을 계산한다. `minimumOutput`은 이때 계산한 출력량이다. `validUntil = observedAt + 30초`이며 발급 시각 +30초가 아니다.
4. chain ID·Oracle 주소의 EIP-712 도메인과 보고서를 전용 가격 키로 서명한다. 운영자 주소가 executor다. 사용자 귀속 보고서·서명을 `price_quotes`에 저장한다.
5. 클라이언트는 서명 원문 대신 quoteId를 사용한다. 실제 주문 body에는 symbol과 입력량도 있으며 서버가 보고서와 일치 여부를 확인한다.
6. `OrderService.buy/sell`도 현재 CLOSED/STALE를 직접 검사한다. 이후 `OnchainOrderService` → `OnchainOrderPreparationService.prepare(REQUIRES_NEW)`가 견적 행 잠금·소유권·만료·방향·입력량 검증, 잔고 잠금, 주문 생성, 견적 CONSUMED를 함께 커밋한다.
7. 잔고 부족이면 FAILED 주문만 기록하고 견적을 소비하지 않는다. 준비 완료 뒤 SIGNED 저장 전 전송 실패는 `failIfNotSigned`가 FAILED 처리·잠금 해제하며, 이미 소비한 견적은 ISSUED로 복구하지 않는다.
8. `BlockchainTransactionSender.submit`은 단일 프로세스 synchronized로 nonce를 다루고 gas estimation·거래 서명 후 `saveSigned(REQUIRES_NEW)`로 raw transaction·txHash를 RPC broadcast보다 먼저 저장한다.
9. 제출 확인 시 `markSubmitted`가 transaction SUBMITTED와 order PENDING_ONCHAIN을 반영한다. SIGNED 이후 장애는 원문을 유지하고 reconciliation이 같은 raw transaction을 복구한다.
10. `contracts/src/PriceOracle.sol`은 소비자·서명·종목·시간·재사용을 검증하고 승인 가격을 기록한다. `ExchangeVault.sol`은 방향·executor·입력량·최소 출력량 및 유동성을 검사해 같은 트랜잭션에서 정산한다. 관리용 updatePrice 함수는 남지만 buy/sell은 그 저장 가격으로 체결하지 않는다.
11. reconciliation은 SIGNED/SUBMITTED만 조회한다. receipt·confirmation 확인 후 `ContractEventParser`가 Vault·이벤트 종류·운영자·입력량 등을 검증하고 정산 서비스가 DB 잔고·Trade·주문·트랜잭션을 원자적으로 확정한다.

## 6. 상태와 진단 트리거

- OrderStatus: REQUESTED, PENDING_ONCHAIN, FILLED, FAILED, CANCELED. CANCELED enum 존재만으로 취소 API 구현을 뜻하지 않는다.
- BlockchainTransactionStatus: CREATED, SIGNED, SUBMITTED, CONFIRMED, FAILED, REVIEW_REQUIRED.
- PriceQuoteStatus: ISSUED, CONSUMED, EXPIRED. 만료 시각이 지나도 조회만으로 DB 상태가 자동 EXPIRED가 되는 것은 아니다. 진단 DTO는 저장 상태와 현재 시각 기준 만료 여부를 구분해야 한다.

`BlockchainReconciliationService`는 `EventValidationException` 또는 `SettlementConsistencyException`을 잡아 `OnchainSettlementService.markReviewRequired`를 호출한다. 대표 원인은 이벤트 수·송신자·입력 불일치, 정산 상태·잠금 잔고 불일치, 이미 존재하는 체결 등이다. 일시적 RPC 오류와 receipt 미발견은 그 자체로 REVIEW_REQUIRED가 아니다.

격리 시 transaction 상태·오류 메시지만 변경하고 order 상태와 자산 잠금을 유지한다. 현재 전용 진단 이벤트나 AI 실행은 없다. AI Phase 7에서 transaction 격리 commit 이후 분석 이벤트를 연결하는 최소 확장이 필요하다. 자동 분석은 정산 복구를 실행하지 않으며, 실패가 원래 트랜잭션에 전파되지 않도록 별도 실행·저장을 사용한다. 중복 기준은 transaction ID와 상태 전환 식별자를 실제 모델에 맞춰 결정한다. 현재 존재하지 않는 state version을 있는 것으로 가정하지 않는다.

## 7. 재사용 가능한 조회와 추가 후보

| 기존 REST | 정보 / 권한 | Tool 활용 |
|---|---|---|
| GET `/api/me` | 현재 인증 사용자 | 인증 확인, 식별 정보는 최소화 |
| GET `/api/markets`, `/api/markets/{symbol}` | 공개 가격·장 상태·가격 상태·공급자·observedAt | getMarketStatus/currentReferencePrice |
| GET `/api/markets/{symbol}/ticks`, `/candles` | 공개 이력·캔들 | 필요 질문에 한정 |
| GET `/api/orders`, `/api/orders/{orderId}` | 본인 주문 | getOrder; 응답에 quoteId·transaction 상태는 없음 |
| GET `/api/trades` | 본인 체결 | 체결 확인 |
| GET `/api/portfolio` | 본인 잔고·평가 | getPortfolio |

POST `/api/quotes/*`는 서명 견적을 새로 저장하므로 조회 Tool로 재사용하지 않는다. faucet·주문·로그인/가입 역시 Tool allowlist에서 제외한다. 현재 `MarketController.market`은 단일 mSEC 응답이므로 Tool 입력 종목은 지원값으로 제한해야 한다.

AI Phase 4의 추가 읽기 경계 후보(아직 API·메서드명 확정 아님):

- 소유권을 검증한 기존 견적 요약 조회: observedAt, validUntil, 상태, 방향·입력량·최소 출력량·연결 주문. signature 제외.
- 주문에서 연결한 transaction 요약: 상태·시각·txHash·정제된 오류 유형. rawTransaction 및 내부 예외 원문 제외.
- 권한 확인한 transaction에서 파생한 receipt/event 요약. 모델이 임의 RPC URL이나 타인의 txHash를 지정하지 못하게 한다.
- ADMIN-only 비정상 transaction 및 연결 주문의 제한된 목록·상세. 페이지 크기와 필터 상한 필요.

내부 AI 모듈 채택 시 모든 조회에 HTTP endpoint를 새로 만들 필요는 없다. 기존 읽기 서비스를 감싸고 부족한 조회만 인가된 facade로 추가한다. Agent/LLM은 repository에 직접 접근하지 않는다. 어댑터가 호출할 수 있는 것은 등록된 읽기 함수뿐이다.

## 8. 실행 경계·Spring AI·pgvector

추천은 [ADR-001](adr/ADR-001-ai-runtime-boundary.md)의 기존 Spring Boot 내부 AI 모듈이다. Spring AI 대신 최소 공급자 어댑터를 사용하는 대안을 제안하며, 호환성 대안의 사용자 승인 전에는 구현하지 않는다.

공식 문서 확인(2026-09-27): [Spring AI 1.0](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html)은 Boot 3.4.x/3.5.x, [현행 Spring AI 2.0](https://docs.spring.io/spring-ai/reference/getting-started.html)은 Boot 4.0.x/4.1.x를 지원한다. 현재 3.3.4에 그대로 적용하는 것은 확인한 지원 조합이 아니다. 1.1 문서는 이번 조회에 실패했으므로 지원 여부를 추정하지 않는다. 기존 프로젝트에 Spring AI를 설치하거나 호환성 빌드를 수행하지 않았다.

[pgvector 공식 문서](https://github.com/pgvector/pgvector)는 PostgreSQL 13 이상과 PG16 Docker 이미지를 제공한다. 현 Compose는 일반 postgres:16이며 vector 설치·DB 활성화 여부는 이번에 실DB로 확인하지 않았다. 도입 시 PG16 호환 이미지/설치, 백업·복구 리허설, 외부 볼륨 보존 후 DB별 extension 활성화가 필요하다. 이 단계에서 이미지·볼륨·DB를 변경하지 않는다.

AI schema/테이블은 운영 데이터와 분리한다. 임베딩 모델·차원·LLM 공급자·요금·예산 상한은 Phase 2 전에 사용자와 선택한다. 한국어 설명과 상태 코드가 섞인 문서는 키워드 분석·정확 용어 검색 품질을 별도 평가한다. Phase 2에서는 ADMIN-only 테스트 경계 또는 USER용 문서만 사용하는 제한으로 Phase 3 권한 필터 이전 노출을 막는다.

## 9. 테스트 baseline

2026-09-27 실제 재실행 결과:

| 명령 (각 모듈 디렉터리) | 결과 |
|---|---|
| backend: `.\gradlew.bat test --no-daemon --rerun-tasks` | XML 집계 146개 중 143개 통과, 3개 skipped, 실패·오류 0 |
| backend: `.\gradlew.bat dependencies --configuration runtimeClasspath --no-daemon` | 성공, 신규 dependency 없음 |
| contracts: `forge test --summary` | 36개 통과 |
| tools/websocket-test-client: `npm test` | Node test runner 30개 통과 |
| tools/websocket-test-client: `npm run build` | Vite 성공, 87 modules |

웹 빌드는 최초 샌드박스 상위 경로 접근 실패 후 승인된 사용자 권한 재실행으로 통과했다. 의존성 조회도 Gradle 캐시 접근 제한 후 재실행했다. backend HTML 보고서는 `backend/build/reports/tests/test/index.html`, XML은 `backend/build/test-results/test/`이며 다음 테스트 실행으로 바뀔 수 있다.

이번에 실행하지 않은 선택 테스트:

- `BLOCKCHAIN_INTEGRATION_TESTS=true`: `com.pricetrack.exchange.blockchain.BlockchainAnvilIntegrationTest`, `com.pricetrack.exchange.blockchain.reconciliation.BlockchainTransactionAnvilIntegrationTest`. RPC·현재 배포 주소·분리된 테스트 키가 필요하고 실제 체인 변경을 일으킨다.
- `POSTGRES_INTEGRATION_TESTS=true`: `com.pricetrack.exchange.quote.PriceQuotePostgresConcurrencyIntegrationTest`. 기존 schema와 DB 접속 설정이 필요하고 테스트용 견적을 생성·삭제한다.
- 위 환경을 준비한 뒤 backend에서 `.\gradlew.bat test --no-daemon --rerun-tasks --tests <정확한 클래스명>`으로 선택 실행한다. 자리표시자에는 실제 클래스명을 넣으며 비밀값은 출력하지 않는다.

기존 거래소 Phase 6.3 기록에는 실제 Anvil·PostgreSQL 통과 이력이 있으나 이번 재실행 결과와 구분한다. Toss 장중·장애 수동 인수를 완료로 변경하지 않는다. 기본 H2 테스트 통과는 PostgreSQL DDL/pgvector 검증을 대신하지 않는다.

## 10. 문서와 코드 차이 및 후속 검증

| 문서/예시 표현 | 실제 코드에 맞는 해석 |
|---|---|
| Order → REVIEW_REQUIRED | BlockchainTransaction 격리; Order 상태·자산 잠금 유지 |
| 견적 생성 후 30초 | observedAt +30초. createdAt과 구분 |
| quoteId만 전달 | 서명/report 대신 quoteId, 실제 주문 body의 symbol·입력량도 검증 |
| RPC 실패 시 견적 복구 | SIGNED 전 보상은 주문 실패·자산 잠금 해제, 소비 견적은 유지 |
| 휴장 시 견적만 차단 | OrderService도 직접 현재 CLOSED/STALE 검사 |
| 모든 WS 전송은 AFTER_COMMIT | DB 안 이벤트는 commit 후, 가격 등 DB 밖 이벤트는 fallbackExecution 즉시 전송 |
| updatePrice 제거 | 주기적 백엔드 제출·무서명 Vault 거래 제거. 관리 함수·과거 UPDATE_PRICE 복구는 남음 |
| 지침의 expiresAt | 현재 필드명 validUntil |

기존 문서를 일괄 수정하지 않고 이 감사에 차이를 남긴다. AI Phase 1의 9개 지식 문서는 위 기준으로 작성하고 코드 경로·상태·예외를 대조한다. Phase 4에서는 교차 사용자 접근, ADMIN-only, 임의 txHash 조회, 민감필드 비노출을 검사한다. Phase 5~8에서는 Tool 실패·충돌 근거·문서 주입·호출 상한·중복 분석을 시나리오로 평가한다. 테스트 결과를 근거로 신뢰 범위를 설명하며 환각 부재를 일반적으로 보장하지 않는다.

## 11. 변경·검토·다음 단계

산출물은 이 감사 보고서와 ADR, 공통 문서 목록·구현 로그의 연결 기록이다. 제품 코드·테스트 코드·dependency·schema·환경 파일 변경은 없다. 읽기 전용 조사와 문서 작업이므로 공통 지침의 생략 허용에 따라 독립 검토 에이전트는 호출하지 않았으며 자체 코드 대조와 테스트 기준 검증을 수행했다.

AI Phase 0 조사 완료. 런타임 대안은 사용자 검토 대기다. 다음 AI Phase 1은 승인 후 운영 지식 문서만 작성하며 embedding·Tool·Agent 구현으로 넘어가지 않는다.
