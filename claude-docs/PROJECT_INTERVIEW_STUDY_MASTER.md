# Tokenized Market Simulator — 개발자 학습·기술면접 마스터

> 조사 기준: 2026-10-14, `integration/additional-features` HEAD `240cb3982d6c05724d6a89e99aed66788d77d697`.
> 읽기 전용 코드·Git·문서 조사와 이 문서 신규 작성만 수행했다. 이번 작업에서 테스트, 외부 API, 유료 AI, DB/RPC 조회, 환경 생성·변경을 실행하지 않았다.
> 과거 테스트 결과는 해당 실행의 기록이지 위 HEAD에서 이번에 다시 실행한 결과가 아니다. 기존 문서·제품 코드·테스트·인증정보는 수정하지 않았다.

## 0. 먼저 알아야 할 것과 학습 순서

이 프로젝트는 **삼성전자 기준 가격으로 모의 자산을 거래하는 중앙 운영형 하이브리드 플랫폼**이다. 사용자끼리 가격을 협상하는 거래소도, 실제 삼성전자 주식을 담보로 발행한 상품도 아니다.

첫 설명은 이렇게 시작하면 된다.

> 웹 사용자는 PostgreSQL에 기록된 자신의 모의 mKRW/mSEC로 주문합니다. 백엔드는 외부 시세 또는 합성 시세를 보고 주문별 가격 보고서를 서명합니다. 실제 체인 거래는 운영자 지갑이 Vault에 제출합니다. 백엔드는 성공 receipt뿐 아니라 예상한 이벤트까지 확인한 뒤 사용자 장부를 정산합니다. AI는 거래를 실행하지 않고 문서와 조회 사실로 상태를 설명합니다.

이 문장을 이해하려면 다음 순서로 읽는다.

1. 1~2장: 무엇을 만들었고 DB와 블록체인이 왜 함께 있는지.
2. 3.1~3.4: 인증, 모의 잔고, 시세, 서명 견적.
3. 3.5~3.8: BUY/SELL의 실제 실행·확정·복구.
4. 3.9~3.14: 실시간 UI, 조건부 주문, 두 대사, AI, 운영 준비.
5. 4~5장: 시간순 변경과 실제 문제 해결.
6. 6~8장: 면접 답변 연습, 검증 한계, 다음 코드 읽기 과제.

### 사실 표시 규칙

| 표시 | 의미 |
| --- | --- |
| 코드 확인 | 현재 소스에 존재하는 처리. 실행 성공과는 별개 |
| 과거 테스트 | 보고서·테스트 코드에 근거한 당시 실행 결과 |
| 독립 검토 | 별도 검토자의 확인 범위. 모든 테스트를 독립 재실행했다는 뜻은 아님 |
| 실환경 미검증 | 실제 공급자·운영 환경의 인수 증거가 아직 없음 |
| 설명/추론 | 코드를 이해하기 위한 일반 원리 또는 설계상 해석. 작성자의 개인 의도가 아님 |
| 확인 필요 | Git·문서만으로 확정할 수 없는 사실 |

**기여 표현:** 개발에는 AI 구현·검토 에이전트가 활용됐다. 저장소의 기능이 있다는 사실과 개발자가 그 코드를 독립적으로 직접 작성했다는 사실은 다르다. 면접에서는 본인이 실제 승인·판단·읽기·수정·시연한 범위만 1인칭으로 말한다. 커밋 author나 AI 작성 요약만으로 기여율을 산정하지 않는다. [AI 활용 경험](ai-development-experience.md) 6·10절과 [경력 브리프](career-project-brief.md)의 개인 기여 확인 항목을 참고한다.

## 1. 프로젝트 탄생과 요구사항

### 1.1 최초 확인 가능한 자료

Git 첫 커밋 `779cb9c`(2026-06-22)에는 `.gitignore`와 `졸업프로젝트 제안서_김성은, 서교빈.hwp`가 있었다. `fe8e3ea` 같은 날에 contracts/backend/android 모노레포와 `구현 계획.md`가 추가됐다. HWP 원문은 이번 조사에서 텍스트로 해독·대조하지 않았으므로 원 제안서의 정확한 문장·평가 기준·팀 역할은 **확인 필요**다. 아래 초기 요구사항은 읽을 수 있는 구현 계획 및 Git 이력에 근거한다.

[구현 계획](../구현%20계획.md) 1~8·18절의 목표는 ERC-20 기반 가격 추종 모의 토큰, 인증·주문·체결·포트폴리오·실시간 스트림·온체인 상태 추적이다. 최초에는 Android가 주 사용자 클라이언트였고, 시세는 시뮬레이션 → 과거 데이터 replay → 실제 API 순서의 확장안이었다.

기술적으로 CRUD를 넘어가려는 목표가 드러난다. 단순 게시물 저장과 달리 주문은 외부 시스템에 제출된 뒤 늦게 확정되고, 그동안 자산을 재사용하지 못하게 해야 한다. 이 비동기성과 두 저장소의 일관성이 프로젝트의 중심 학습 문제다.

### 1.2 블록체인을 선택한 이유와 그 한계

계획과 코드로 확인되는 목적은 다음과 같다.

- ERC-20의 발행·소각·승인·전송을 직접 다룬다.
- Solidity가 서명 가격과 거래 조건을 검증하고 같은 트랜잭션에서 결정론적으로 정산한다.
- DB 기록 외에 transaction/receipt/event라는 별도 검증 근거를 남긴다.
- Java 백엔드에서 RPC·서명·nonce·지연 확정·복구를 통합한다.

**블록체인이 삼성전자 가격의 진실을 알아내는 것은 아니다.** 외부 가격은 공급자와 price signer를 신뢰한다. 서명은 “이 키가 이 데이터를 승인했다”를 증명하며 “실제 시장의 절대 진실”을 증명하지 않는다. operator와 signer가 중앙화됐으므로 탈중앙 거래소라고 소개하면 틀린다.

또한 현재 실험 체인은 Anvil이다. 로컬 운영자가 체인을 초기화하거나 상태를 바꿀 수 있으므로 퍼블릭 Ethereum의 분산 신뢰·영구 기록 특성을 그대로 확보했다고 주장하지 않는다. 코드의 온체인 검증과 로컬 체인의 운영 신뢰는 별도다. [Oracle 설계](oracle-price-execution-design.md) 2·6절.

### 1.3 왜 하이브리드인가

온체인에는 operator/Vault의 토큰 이동과 서명 검증을, 오프체인에는 회원·권한·사용자별 할당량·주문 의도·조회/UI·AI를 둔다.

- 모든 사용자 조회와 화면 계산을 RPC로 하면 느리고 복잡하다. DB에서 본인 주문·평균 매수가·포트폴리오를 효율적으로 조회한다.
- 사용자별 키 관리 대신 서비스 인증과 operator 실행을 사용한다.
- 계약에는 검증·정산이라는 좁은 책임을 두고, 외부 시세 수집과 조건 평가·복구는 서버에서 처리한다.
- 그 대가로 DB와 체인이 한 트랜잭션으로 함께 commit되지 않는다. 상태 머신·원문 선저장·정산 멱등성·대사가 필요하다.

DB만으로 모의 거래는 가능하다. 실제 `DB_ONLY` 경로도 있다. 이 프로젝트에서 온체인의 의미는 “DB 없이만 가능한 경제 활동”보다는 **독립 실행 근거를 통한 검증·비동기 정산의 기술 학습**이다. 이 설명은 구현을 해석한 것이며 블록체인이 반드시 최저 비용 선택이었다는 실험 결과는 없다.

### 1.4 계획과 현재 구현의 차이

| 초기 계획·과거 표현 | 현재 판단·구현 | 근거 |
| --- | --- | --- |
| Android 중심 | 제품형 반응형 웹을 우선 검증, Android 실제 인수는 별도 | `7b178af`, [웹 제품화](web-product-ui.md) |
| 사용자 지갑 생성/연결 후보 | A: operator 통합 지갑 + 사용자 DB 장부 확정 | `c7bf4e8`, [보관 모델 비교](user-wallet-custody-architecture-review.md) |
| Oracle 저장 가격으로 거래 | 주문별 EIP-712 보고서 검증과 Vault 정산을 원자화 | `de9fa71`, `4962e11` |
| 단순 랜덤 시세 | 영속·결정적 Synthetic Market, Toss 유지, Kiwoom 추가 | `c6577aa`, `5e39dbf` |
| 지정가/예약 주문 제외 | Oracle 가격 조건부 주문 추가. 주문장·시간 지정 예약은 여전히 제외 | `c413079` |
| 무제한 DB Faucet | 일일·누적·전체 예산·멱등키 정책 | `a1d87c9` |
| 운영 대사 미래 후보 | 장부-체인 자산 대사·거래 전수 감사·정체 관측 구현 | `00046d7`, `eed6b2f`, `e489b3c` |
| AI 없음 | 승인 지식 RAG + read-only Tool + 제한 Agent/Skill + 자동 진단 | AI Phase 1~8 문서 |
| Sepolia/AWS 확장 후보 | 로컬 배포 준비·운영 검증 계획만. 실제 외부 배포 미완료 | [운영 계획](ec2-operational-validation-plan.md) |

“Oracle 가격 조건부”의 Oracle는 외부 기준 시세와 서명 보고서 흐름을 의미한다. 감시 worker가 온체인 `PriceOracle.priceE8`만 polling해서 가격 조건을 판단하는 구조는 아니다.

현재 제외/미구현: Google OAuth·이메일 인증·refresh token, 사용자별 온체인 소유권/직접 서명/CustodyVault, 사용자 간 주문장·부분 체결·시간 예약, 분산 nonce, Merkle PoR, 실제 금융 입출금·주식 상환, Sepolia 고정 공급 계약 구현·배포, AWS 운영 배포, Android 기기 인수. 서로 “완료”, “공식 제외”, “후속 후보”인지 구분한다.

## 2. 전체 시스템 아키텍처

### 2.1 구성도

```text
브라우저: Vite + JavaScript + lightweight-charts
  ├─ REST/JWT ──────────────── Spring Boot (Java 21)
  └─ STOMP: native/SockJS ────┤
                              ├─ 인증·주문·잔고·정산 ─ 거래 PostgreSQL
Toss / Kiwoom / Synthetic ────┤ 시장 스냅샷·과거 봉·가격 이벤트
                              ├─ price signer: 가격 보고서 EIP-712 서명
                              ├─ operator: 거래 원문 서명·전송
                              │    └─ web3j / HTTP JSON-RPC ─ Anvil EVM
                              │        ├─ MockKRW (mKRW)
                              │        ├─ SamsungPriceTrackingToken (mSEC)
                              │        ├─ PriceOracle (서명 검증·소비 기록)
                              │        └─ ExchangeVault (교환·mint/burn)
                              ├─ receipt polling → 이벤트 대조 → DB 정산
                              ├─ ADMIN 자산 대사 / 거래 감사 / 정체 관측
                              └─ AI Agent/Skill
                                   ├─ read-only Tool → 거래 DB/시세/RPC
                                   ├─ 승인 문서 → AI PostgreSQL + pgvector
                                   └─ embedding/LLM provider

배포 준비: Nginx HTTPS/WSS + Docker Compose + runtime secret 파일
```

그림의 DB 두 개는 목적이 다르다. 거래 DB의 장애는 거래에 직접 영향을 준다. AI DB/모델 장애는 AI 기능의 실패로 처리한다. 다만 같은 JVM/host이므로 CPU·메모리까지 완전히 격리한 마이크로서비스라고 주장하지 않는다.

### 2.2 디렉터리를 제품 책임으로 읽기

| 디렉터리 | 읽는 관점 |
| --- | --- |
| `contracts/src/` | EVM에 배포할 검증·자산 교환 프로그램 |
| `contracts/script/` | 배포·역할 연결·로컬 자금 준비·시나리오 실행 |
| `contracts/test/` | Foundry EVM의 단위·fuzz 검증 |
| `backend/src/main/java/com/pricetrack/exchange/` | 사용자 요청, DB 원장, 외부 시스템 연결 |
| `backend/src/main/resources/` | 서버 설정·schema·승인 Skill 정의 |
| `backend/src/test/` | Mock/H2, opt-in PostgreSQL/Anvil/실제 AI 테스트 |
| `tools/websocket-test-client/` | 이름은 테스트 도구지만 현재 제품형 웹. API/WS 기준 클라이언트 |
| `docs/ai-knowledge/` | 모델 검색에 승인된 정책 지식 9개. 개인정보·현재 잔고 아님 |
| `docs/ai/` | AI 설계·평가·운영 문서/manifest |
| `deployment/` | 배포 준비·복원·격리 테스트·read-only 시세 진단 도구 |
| `claude-docs/` | 사람·에이전트 공통 맥락, 설계와 실행별 완료/미검증 이력 |
| `android/` | 담당 외 클라이언트 소스와 계약 참고. 이번에 수정·빌드하지 않음 |

### 2.3 세 가지 정본

1. **사용자별 모의자산 정본:** 거래 PostgreSQL `user_balances`. 어떤 서비스 사용자가 얼마를 할당받았는지는 이 DB가 기록한다.
2. **실제 온체인 실행 정본:** 해당 체인의 transaction/receipt/event 및 ERC-20 state. operator가 어느 호출을 실행했고 토큰이 어떻게 바뀌었는지를 확인한다.
3. **규칙·지식 정본:** 코드·승인 문서/manifest. LLM의 답변은 정본이 아니다.

두 금융 기록을 합쳐 “DB는 단순 캐시”라고 하면 부정확하다. 체인에는 사용자 A/B의 서비스 잔고가 없어서 DB 없이 개별 포트폴리오를 재구성할 수 없다. 반대로 DB에 FILLED라고 적었다고 실제 체인 실행을 증명하지 못한다.

### 2.4 자산과 권한

| 요소 | 권한·역할 | 흔한 오해 |
| --- | --- | --- |
| MockKRW | 18 decimals ERC-20, 공개 `faucet()`가 호출자에게 mint, owner가 지급량 변경 | DB Faucet 한도가 온체인 mint도 제한한다는 해석 |
| mSEC | ERC-20 수량, minter만 mint/burn, 현재 Vault를 minter로 연결 | 토큰 자체가 외부 주가를 수집한다는 해석 |
| PriceOracle | EIP-712 domain, signer/consumer·TTL·quote 재사용 검증, 마지막 승인 가격 보관 | 외부 API나 가격 진실성의 탈중앙 검증기라는 해석 |
| ExchangeVault | BUY 입력 mKRW를 받고 mSEC 발행; SELL mSEC 소각 후 mKRW 지급 | 사용자끼리 호가 매칭하거나 자동 준비금을 만드는 계약이라는 해석 |
| operator | 온체인 executor, mKRW/mSEC 보유, allowance 제공, 거래 Gas 부담 | 사용자별 지갑이라는 해석 |
| price signer | 가격 보고서를 서명하는 별도 키 | 전송 nonce/Gas를 관리하는 키 또는 사용자 동의 서명이라는 해석 |
| JWT 사용자 | 본인 DB 원장 주문 및 조회 권한 | Ethereum 개인 키와 동일하다는 해석 |

Solidity 함수의 `msg.sender` 및 Bought/Sold의 `user`는 실제 서버 실행에서는 operator다. 계약 주석의 “사용자”와 웹 로그인 사용자를 같은 주체로 해석하면 안 된다.

### 2.5 기술 선택과 대안

- Java 21 / Spring Boot 3.3.4 / Security / JPA / STOMP / web3j 4.12.2 / JJWT 0.12.6: [실제 빌드](../backend/build.gradle). PostgreSQL 원장과 Java 트랜잭션, web3j ABI/RPC를 한 서버에 통합했다.
- Solidity 0.8.24 / Foundry / OpenZeppelin: ERC-20·ECDSA·EIP712·SafeERC20·ReentrancyGuard 활용. 커스텀 암호 구현을 줄인다. 외부 라이브러리를 쓴다고 독자 보안 감사가 완료된 것은 아니다.
- PostgreSQL 행 잠금: 계정 자산/견적·예산의 동시 수정 직렬화. 낙관 잠금·분산 캐시로 바꿀 수 있지만 그런 변경을 채택했다는 기록은 없다.
- Vite/JS 유지: 웹을 단순 debug 페이지에서 로그인→시장→거래→내역→포트폴리오→AI/ADMIN 흐름으로 제품화. UI 전환 과정에서 새 백엔드 기능을 임의 추가하지 않는 경계가 있었다.
- AI는 기존 Boot 내부 + 얇은 provider adapter + 별도 pgvector DB. Spring AI 도입만을 위한 Boot 업그레이드/별도 서비스 부담 대신 이 조합을 선택했다. [ADR-001](../docs/ai/adr/ADR-001-ai-runtime-boundary.md)의 당시 지원 버전 조사 근거는 역사적 기록이며 현재 최신 라이브러리 호환성 주장으로 쓰지 않는다.
- Kafka/Redis, 외부 검색 서버, Grafana/Prometheus는 현재 도입하지 않았다. Spring simple broker와 DB polling을 실제 사용한다. 적은 규모에 적합하다는 설계 해석은 가능하지만 운영 부하 측정으로 최적 규모를 확정한 것은 아니다.

### 2.6 기동 가능과 거래 가능의 차이

브라우저를 열거나 backend health가 응답해도 실제 Vault 거래가 가능한 것은 아니다. 새 환경의 준비 관계는 다음과 같다. 이것은 설명용 순서이며 이번 작업에서 실행하지 않았다.

```text
새 거래 DB/schema
새 Anvil RPC
 → MockKRW 배포 → mSEC 배포 → PriceOracle 배포 → ExchangeVault 배포
 → mSEC.minter=Vault / Oracle.authorizedConsumer=Vault / priceSigner 주소 확인
 → operator ETH·mKRW / Vault mKRW / operator→Vault allowance 준비
 → 해당 배포의 주소와 RPC·chainId·별도 operator/signer 키를 runtime에 연결
 → backend 기동 및 서명 설정 검증
 → 신규 0원 장부에서 불변 reserve baseline
 → 사용자 Faucet·새 quote·주문
```

실제 함수/설정 근거는 `contracts/script/Deploy.s.sol`, `PrepareOperator.s.sol`, [PriceReportStartupValidator](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportStartupValidator.java), `PriceReportIssuer.validateSignerConfiguration`, `BlockchainProperties`, `backend/src/main/resources/application.yml`이다. 웹은 새 backend URL, backend는 새 DB/RPC를 바라봐야 한다. DB 이름이나 chainId 하나만 같다고 격리가 보장되지 않는다.

읽기 위한 주요 설정 이름은 `PRICE_PROVIDER`, `BLOCKCHAIN_ENABLED`, `PRICE_REPORT_SIGNING_ENABLED`, `OPERATOR_PRIVATE_KEY`, `PRICE_SIGNER_PRIVATE_KEY` 및 네 컨트랙트 주소 이름이다. 실제 값은 공부 자료에 쓰거나 채팅에 공유하지 않는다. 기능별 reserve/audit/conditional/AI flags의 기본 disabled 여부와 실제 적용 설정은 실행 전 따로 확인한다. 키 이름이 존재하는 것과 온체인 권한이 맞는 것은 다르다.

**시작 검증과 주문 실행의 차이:** signer 설정 일치를 확인해 backend가 기동하더라도 operator 잔고/allowance/ETH와 Vault 유동성을 미래 모든 주문에 예약한 것은 아니다. DB 사용자 자산이 많아도 체인 준비 자원이 부족하면 거래는 실패하거나 전송 후 불확실 상태에 남을 수 있다.

## 3. 실제 코드 기반 기능별 해설

이 장의 Java 경로는 모두 `backend/src/main/java/com/pricetrack/exchange/` 아래다. 테스트는 `backend/src/test/java/com/pricetrack/exchange/` 아래를 기준으로 읽는다. 링크에는 전체 저장소 상대 경로를 적었다.

### 3.1 회원가입·로그인·JWT

**왜:** HTTP 호출마다 “누가 요청했고 무슨 권한인가”를 알아야 본인 주문·잔고와 ADMIN 조회를 구분할 수 있다.

**동작:** `AuthController → AuthService.signup/login → JwtTokenProvider`. 회원가입은 loginId 소문자 정규화, BCrypt 비밀번호 저장, 사용자 생성과 0원 mKRW/mSEC 잔고 초기화를 처리하고 토큰을 반환한다. API 기준 회원가입 후 별도 로그인 없이 토큰을 사용할 수 있다. 로그인은 해시를 복호화하지 않고 `PasswordEncoder.matches`로 비교한다.

후속 요청은 `Authorization: Bearer ... → JwtAuthenticationFilter → JWT 서명/만료 검증 → subject userId로 User 조회 → AuthenticatedUser/DB role → SecurityFilterChain`. JWT에 ADMIN이라고 적은 클라이언트 값을 신뢰하는 방식이 아니다. 현재 DB 역할을 읽기 때문에 과거 USER 토큰이라도 계정이 ADMIN으로 변경됐다면 현재 권한이 적용된다.

**관련 코드:** [AuthService](../backend/src/main/java/com/pricetrack/exchange/auth/AuthService.java), [JWT 발급](../backend/src/main/java/com/pricetrack/exchange/auth/JwtTokenProvider.java), [필터](../backend/src/main/java/com/pricetrack/exchange/auth/JwtAuthenticationFilter.java), [SecurityConfig](../backend/src/main/java/com/pricetrack/exchange/common/config/SecurityConfig.java). 인증 서비스의 트랜잭션과 `(user_id,symbol)` unique 제약은 잔고 초기화와 연결된다.

**실패:** 중복 ID·잘못된 비밀번호·잘못된 토큰·권한 부족을 API 오류로 반환한다. ADMIN endpoint는 USER에게 403, 익명은 401. 보안 처리의 `sendError`가 `/error` 재진입으로 403을 401로 바꾸던 문제를 JSON 응답 직접 작성으로 방지한다(현재 SecurityConfig 주석).

**검증:** `auth/AuthIntegrationTest`, `admin/AdminAccountIntegrationTest`, 기능별 security 테스트. 감사 E2E에서 “역할 변경 전 JWT는 계속 USER”라는 테스트 가정이 실패해 별도 USER fixture로 수정한 이력이 있다. 제품 인증을 바꾼 문제가 아니다. Google/email 필드·확장 여지는 실제 OAuth/이메일 검증 구현의 증거가 아니다.

### 3.2 사용자 장부·Faucet·포트폴리오

**왜:** DB 사용자의 거래 가능량을 기록하고 초기 모의자금을 제공한다. 체인에서 사용자별 소유권을 만드는 기능은 아니다.

**동작:** [UserBalance](../backend/src/main/java/com/pricetrack/exchange/wallet/UserBalance.java)의 `amount`는 총량, `lockedAmount`는 미완료 주문에 예약한 입력량, `available=amount-lockedAmount`다. 잠금은 amount를 즉시 차감하는 것과 다르다. 성공 정산에만 입력 총량을 차감하고 출력량을 더한다.

DB Faucet의 확정 정책:

- 지급 1회 1,000,000 mKRW.
- KST 달력 하루 1회, 계정 누적 5회/5,000,000 mKRW.
- 전체 누적 50,000,000 mKRW. 기간 예산·cooldown 없음.
- `Idempotency-Key` 필수, 기존 인증 계정 대상, ADMIN 읽기 전용.

`WalletController → FaucetService.credit → account quota lock → 성공 key replay 확인 → global budget lock → 정책/journal 검증 → mKRW balance lock → DB clock_timestamp → 지급/grant/counter/성공 claim → commit → 개인 portfolio 알림`. 잠금 대기 후의 DB 결정 시각을 쓰므로 23:59 요청이 자정 뒤 결정되면 그 결정 날짜로 판정한다. lockedAmount는 지급으로 바뀌지 않는다.

성공 요청만 `faucet_claims`에 fingerprint로 저장한다. 같은 사용자·키는 기존 성공을 반환하고 추가 지급·grant·WS를 만들지 않는다. 성공 replay는 현재 정책/한도 검사보다 먼저 처리한다. 응답 유실 후 새 키를 자동 생성하면 안 된다. replay의 잔고는 과거 값일 수 있으므로 현재 잔고는 portfolio를 조회한다.

**정본/DB:** `faucet_grants`가 실제 지급 journal. 추가 `faucet_policy_versions`, `faucet_account_usage`, `faucet_budget_usage`, `faucet_claims`. counter는 조회/동시성 보호용이지 대사에 더하는 두 번째 지급 자산이 아니다. 정책 이력·grant·claim은 PostgreSQL 변경 방지 trigger로 보호하고, DB 관리자까지 변경 불가능하다는 뜻은 아니다.

**코드:** [FaucetPolicy](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetPolicy.java), [FaucetService](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetService.java), [FaucetStore](../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetStore.java), [schema](../backend/src/main/resources/faucet-schema.sql), 웹 [faucet-panel](../tools/websocket-test-client/src/faucet-panel.js).

**실패:** 한도 초과·정책 hash 불일치·journal/counter 모순은 fail-closed. transaction rollback이면 잔고·grant·counter·claim 모두 원복, AFTER_COMMIT 알림도 폐기. 기존 데이터의 초기 누적 지급을 현재 잔고에서 추정하지 않는다. 신규 빈 원장이 우선 대상이며 기존 DB migration은 별도 승인 사항이다. 거절 통계는 프로세스-local이고 성공 journal처럼 영속 전체 횟수가 아니다.

**검증:** `FaucetPolicyTest`, `FaucetIntegrationTest`, `FaucetPostgresAnvilE2ETest`. 실제 PG 경쟁·예산·rollback·키 replay·자정·거래와 대사 호환 시나리오는 [구현 보고](faucet-policy-implementation.md). 최종 Phase A 독립 fixture에서도 해당 macro E2E 1 PASS. H2 결과와 실제 PG 행 잠금 결과를 구분한다.

포트폴리오는 [PortfolioService.portfolio](../backend/src/main/java/com/pricetrack/exchange/portfolio/PortfolioService.java): `tokenValue=amount×현재가격`, `costBasis=amount×averageBuyPrice`, `unrealizedProfit=tokenValue-costBasis`, `totalValue=mKRW+tokenValue`. 평균 매수가는 기존 비용+새 BUY 입력 전체 비용의 가중 평균이므로 매수 수수료 효과가 포함된다. 평가손익은 실현 수익·보장 상환액이 아니다. 잠긴 자산도 소유 총량에 포함되며 portfolio DTO 자체는 lockedAmount를 별도 반환하지 않는다.

### 3.3 시세 공급자·시장 상태·차트

**왜:** 거래 도메인이 특정 증권사 JSON·인증·소켓 규격에 의존하면 공급자 전환이 주문/정산 전체 변경으로 번진다.

**동작:** [MarketPriceProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/MarketPriceProvider.java)와 [MarketCandleProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/MarketCandleProvider.java) 뒤에 Toss/Kiwoom/SIMULATED를 둔다. `MarketPriceService.current()`는 공통 `MarketPriceSnapshot`을 제공한다. 가격, previousClose, change/rate, marketStatus, priceStatus, provider, observedAt을 함께 보므로 “숫자가 있음”과 “거래 가능한 최신 시세”를 구분한다.

시장 상태 OPEN/CLOSED/UNKNOWN과 시세 품질 LIVE/DEGRADED/STALE/INITIALIZING/SIMULATED는 서로 다른 축이다. CLOSED/STALE은 기존 일반 guard에서 차단한다. 신규 온체인 가격 보고서와 조건부 실행은 실제 공급자 **OPEN+LIVE+관측 5초 이내·미래 2초 이내**로 강화됐다. 저장된 quote 소비·복구·정산까지 같은 새 시세 검증을 다시 적용한 것은 아니다.

Toss는 REST 인증·현재가/시장 관련 정보·캔들과 WebSocket 체결을 연결한다. [TossPriceProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/toss/TossPriceProvider.java), `TossAuthClient`, `TossRealtimeClient`, `TossMarketDataClient`, `TossCandleProvider`를 읽는다. 장 상태를 임의 09:00~15:30 하드코딩으로 대체하지 않는다. 예전 실조회 기록에는 애프터마켓 캘린더 확인도 있다.

차트는 과거 REST OHLCV와 실시간 tick으로 현재 봉을 결합한다. 1m/5m/15m/30m/1h/1d를 지원하며 공급자의 native 주기와 로컬 집계 역할을 나눴다. [MarketCandleService](../backend/src/main/java/com/pricetrack/exchange/market/MarketCandleService.java), [MarketCandleAggregator](../backend/src/main/java/com/pricetrack/exchange/market/MarketCandleAggregator.java), 웹 `chart-time.js`·`candles.js`·`chart-controller.js`. UTC 저장/전달과 KST 표시를 분리한다. 한 분봉의 시간은 구간의 경계 label이며 vendor에 따라 의미가 달라 확인해야 한다.

**실패:** 시세 단절을 모의 가격 fallback으로 숨기지 않는다. 마지막 숫자를 참고 표시할 수 있어도 LIVE 거래 자격과 구분한다. 차트 history pagination·asOf·tick 중복 제거·세대 검증으로 응답 혼합을 줄이지만 vendor watermark가 없는 조회 시각을 절대적인 데이터 포함 경계로 해석하는 한계는 남는다.

**검증:** provider별 unit/Mock, `PriceReportIssuerTest`, 조건부 tests, 웹 chart tests/Chrome fixture. Toss 일부 실제 연결·시세 경계 검증 이력은 [phase-6-3](phase-6-3-acceptance.md)/[검증 기록](validation-2026-10-07.md). **최신 전체 기능의 실제 Toss 장중 BUY/SELL 인수와 실제 Kiwoom 시세 인수는 완료되지 않았다.**

### 3.4 주문별 EIP-712 서명 견적

**왜:** 예전에는 가격 updatePrice tx와 거래 tx가 별개여서 웹에서 본 가격과 Vault가 읽는 저장 가격이 달랐다. 블록을 빠르게 만드는 대신 “이 주문에 사용할 가격·조건”을 서명해서 같은 tx에서 검증/정산한다.

`QuoteController → PriceQuoteService.issue → PriceReportIssuer.issue → 선택 시장 snapshot 검증 → Vault.quoteBuyAtPrice/quoteSellAtPrice → chainId/domain/executor 확인 → PriceReportSigner.sign → price_quotes 저장 → 안전 DTO` 순서다. RPC 지연 후에도 관측 신선도를 다시 확인하고 실제 공급자의 연결/장 상태를 재확인한다. 이때 이미 계산한 고정 가격을 조용히 최신 가격으로 바꾸지 않는다.

| 보고서 필드 | 역할 |
| --- | --- |
| quoteId | 일회용 식별자(bytes32) |
| symbolHash / priceE8 | 지원 상품 및 가격(가격×10^8) |
| observedAt / validUntil | 관측 Unix seconds, `validUntil=observedAt+30` |
| side / inputAmount | BUY=0/SELL=1, ERC-20 18 decimals 정수 입력 |
| minimumOutput | 현재 Vault 가격·수수료 계산의 출력량. 현재 사용자가 임의 slippage를 설정하는 기능은 아님 |
| executor | operator. 다른 호출자가 보고서를 사용하지 못함 |
| domain | name/version/chainId/verifyingContract(Oracle)로 다른 체인/계약 replay 방지 |

**코드:** [PriceQuoteService](../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java), [발급기](../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java), `PriceReportEip712`, `PriceReportSigner`, [Solidity 타입](../contracts/src/PriceReportTypes.sol), [PriceOracle](../contracts/src/PriceOracle.sol).

사용자는 **quoteId만** 전달하고 보고서·서명은 백엔드 DB에서 읽는다. 사용자가 서명 키를 가지거나 payload를 직접 서명하지 않는다. 견적 행의 `PESSIMISTIC_WRITE` lock 후 본인 소유, ISSUED, 만료, 방향, 입력 wei를 비교한다. 타인 견적은 존재를 노출하지 않는 not-found. 주문 생성/자산 lock/CONSUMED/orderId 연결은 한 DB transaction이다.

Oracle는 Vault만 consume하게 하고 ECDSA signer, 상품, 양수 가격, TTL, 미래 오차, 만료, usedQuoteIds를 검증한다. 이후 Vault가 실패하면 EVM 전체 revert로 Oracle 사용 표시도 원복된다. 반면 **DB에서 이미 소비한 견적은 RPC/receipt 실패 후에도 재사용하지 않고 새 견적을 발급**한다. DB transaction과 EVM transaction은 같은 rollback 영역이 아니기 때문이다.

**검증:** Java/Solidity 공통 digest 벡터, signer/domain/변조/expiry/replay/min-output Foundry, `SignedQuoteIntegrationTest`, `PriceQuotePostgresConcurrencyIntegrationTest`, 실제 Anvil 거래 E2E. [Oracle 논의와 완료 기록](oracle-price-execution-design.md).

**보장 범위:** 유효한 보고서의 가격과 minimumOutput 조건으로 실행하거나 실패한다. 관측부터 채굴까지 가격 변화는 존재한다. “채굴 순간 최신 삼성전자 가격과 정확히 일치”나 “체결 보장”이라고 말하면 틀린다.

### 3.5 BUY/SELL와 입력 자산 잠금 — 실제 숫자로 이해

온체인 계산은 [ExchangeVault](../contracts/src/ExchangeVault.sol) `quoteBuyAtPrice/quoteSellAtPrice`. 기본 feeBps=10, 10/10,000=0.1%, owner 설정 상한 1,000bps=10%다. 실운영 fee는 현재 계약 값을 읽는다.

```text
BUY (입력은 수수료 포함 mKRW 총 지출액 K)
feeWei = floor(Kwei × feeBps / 10000)
tokenOutWei = floor((Kwei-feeWei) × 10^8 / priceE8)

SELL (입력은 mSEC 수량 S)
grossKrwWei = floor(Swei × priceE8 / 10^8)
feeWei = floor(grossKrwWei × feeBps / 10000)
krwOutWei = grossKrwWei-feeWei
```

예시 가정: 가격 75,000·fee 0.1%, BUY 750,000 mKRW → fee 750, mSEC 9.99. SELL 1 mSEC → gross 75,000, fee 75, 순수령 74,925 mKRW. Ether/Gas 비용은 이 mKRW 수수료와 다르고 operator가 부담한다.

**BUY 요청 순서:**

1. 웹이 입력 mKRW 금액으로 quote를 받는다. 확인 후 symbol·입력량·quoteId로 주문한다.
2. `OrderController → OrderService.buy → OnchainOrderService.buy`(blockchain enabled).
3. `OnchainOrderPreparationService.prepare`가 새 DB transaction에서 quote 검증, mKRW 행 lock, Order(ONCHAIN) 생성, available 확인, input lock, quote 소비를 commit한다.
4. `submitPrepared → BlockchainService.encodeBuy → BlockchainTransactionSender.submit`로 operator가 Vault에 제출한다.
5. Vault는 서명 검증 후 operator mKRW **입력 전체**를 Vault로 transferFrom하고 operator에게 mSEC를 mint한다.
6. receipt/event 정산 후 사용자 DB mKRW 입력 차감·잠금 해제·mSEC 출력 지급·Trade 생성·주문 FILLED.

**SELL 차이:** 준비 시 mSEC를 잠근다. Vault가 operator mSEC를 burn하고 Vault mKRW에서 **순지급액**을 operator에게 보낸다. DB 정산은 사용자 mSEC 입력 차감·잠금 해제·mKRW 순출력 지급. 가격이 상승해도 Vault 자금은 저절로 증가하지 않는다.

**코드:** [OrderService](../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java), [준비 서비스](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java), [전송 연결](../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java), `WalletService.getForUpdate`/balance repository.

**실패:** DB available 부족은 FAILED 이력을 보존하지만 자산을 잠그거나 quote를 소비하지 않는다. operator mKRW/allowance 부족 BUY, operator mSEC 부족 SELL, Vault mKRW 부족 SELL은 Gas 추정 또는 실제 EVM 실행에서 거부될 수 있다. DB 잔고 충분과 체인 지급 자원 충분은 별개다. 별도 자금 예약/자동충전은 없다.

**DB_ONLY:** blockchain disabled이면 `TradeCalculator`와 DB 즉시 정산을 사용하고 실행 유형을 DB_ONLY로 남긴다. 현재 온체인 입력 semantics는 유지하되 DB-only 수수료 HALF_UP과 EVM 정수 floor가 있어 미세 단위까지 같은 계산이라고 가정하지 않는다. DB-only `requireBalanceOrFail`는 현재 amount를 검사하고 온체인 prepare는 available을 검사한다. 일반 실행 설정에서 두 모드를 마음대로 섞어도 같은 안전 가정이 성립한다고 설명하지 않는다. 기존 과거 Order/Trade는 근거 없이 경로를 추정하지 않고 UNKNOWN으로 구분한다.

**검증:** `TradingIntegrationTest`, `OnchainTradingIntegrationTest`, `OnchainSettlementServiceTest`, 신규 Faucet/Conditional PG·Anvil E2E의 실제 BUY/SELL. 단위 테스트와 실제 채굴 성공은 다른 근거다.

### 3.6 web3j·nonce·raw transaction·txHash

**왜:** DB 주문을 EVM이 실행할 함수 호출로 바꾸고, 네트워크 응답을 잃어도 동일 실행을 추적해야 한다.

`ContractGateway`는 ABI 규칙으로 9필드 tuple과 bytes signature를 encode한다. web3j는 RPC 조회와 거래 서명에 쓰는 Java 라이브러리이지 블록체인 서버가 아니다. Anvil은 실제 RPC/EVM 테스트 노드, Forge는 컴파일·테스트·스크립트, Cast는 CLI 조회/호출 도구다.

[BlockchainTransactionSender.submitInternal](../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java):

```text
operator address
 → RPC chainId / pending nonce / gasPrice
 → 실제 calldata estimateGas ×120%
 → RawTransaction(value=0, destination=Vault, data=ABI)
 → operator 키로 chainId 포함 서명
 → signed bytes의 hash를 txHash로 사전 계산
 → BlockchainTransactionPersistence.saveSigned (독립 commit)
 → eth_sendRawTransaction
 → 반환 hash 비교 → markSubmitted (독립 commit)
```

nonce는 operator 주소의 거래 순번이다. 코드의 `synchronized`는 **한 JVM**에서 submit/recover를 직렬화한다. DB `(sender_address,nonce)` unique는 충돌 감지 방어지만 여러 서버의 안전한 nonce 할당 알고리즘을 대체하지 않는다.

raw transaction은 재전송 가능한 서명 원문이라 민감한 실행 자료다. API·AI·일반 문서에 노출하지 않는다. txHash는 RPC가 만들어 주기 전에도 서명 bytes로 계산 가능하다.

**실패 경계:** SIGNED 저장 전 estimate/RPC 실패는 기존 준비 Order의 lock 보상 대상. SIGNED 기록이 있으면 전송 여부가 불명확할 수 있으므로 실패 응답만 보고 unlock하지 않는다. 원문·nonce·hash를 유지한 복구를 우선한다.

**검증:** `blockchain/transaction/` unit tests, `BlockchainTransactionAnvilIntegrationTest`, 조건부 E2E의 응답 절단 proxy. “RPC 호출 timeout을 Mock으로만 던짐”과 “Anvil에 전송 성공 후 응답을 끊음”을 구분한다.

### 3.7 Receipt·Event 검증과 DB 정산

**왜:** RPC가 txHash를 반환한 것은 접수이지 체결 확정이 아니다. receipt success도 예상 주문과 일치하는 자산 결과인지 검증해야 한다.

[BlockchainReconciliationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java)는 enabled 상태에서 기본 1초 fixed-delay로 SIGNED/SUBMITTED를 생성순 조회한다. SUBMITTED receipt가 없거나 확인 수가 부족하면 대기한다. 기본 requiredConfirmations=1이며 테스트에서 2 확인 대기도 검증했다.

- 실패 status receipt: 이벤트 출력으로 성공 정산하지 않고 입력 lock만 해제, Order/BlockchainTransaction FAILED.
- 성공 status receipt: [ContractEventParser.parse](../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java)가 기대 Vault 주소·Bought/Sold topic·정확히 1개 이벤트·operator·입력량을 비교한다.
- 이후 [OnchainSettlementService.settleSuccess](../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)는 transaction/Order 행 잠금, pending 상태·자산 총량/lock 검증, 자산 변경·Trade·FILLED/CONFIRMED를 한 DB transaction으로 반영한다.
- 완료 상태 guard와 Trade order unique 제약으로 같은 receipt를 반복 반영하지 않는다. 상태는 미완료인데 Trade가 이미 있으면 자동 덮어쓰지 않고 모순으로 취급한다.

**정산 검증과 전수 감사는 범위가 다르다.** 현재 실시간 정산 parser가 Oracle PriceReportConsumed·역사 signer/fee·전체 raw calldata까지 전부 대조한다고 설명하면 과장이다. 그 확장 대조는 3.11의 AuditValidator에 있다.

**REVIEW_REQUIRED:** 예상 이벤트/원장 일관성 검증이 실패하면 BlockchainTransaction을 검토 필요로 격리하고 잠금을 유지한다. `OrderStatus` enum에는 REVIEW_REQUIRED가 없다. 일반 Order는 pending으로 남을 수 있고 ConditionalOrder의 REVIEW_REQUIRED는 별도 state다. 전송의 RuntimeException 전부를 REVIEW_REQUIRED로 바꾸는 것도 아니다.

**검증:** parser·settlement unit, reconciliation Anvil 및 ConditionalPostgresAnvilE2ETest. 반복 정산·실패 receipt·입력 변조 검토 필요·lock 유지 등의 실제 fixture 근거가 있다. 익명 RPC를 무조건 신뢰하지 않는 비교를 하지만 악의적 RPC가 모든 증거를 일관되게 조작하는 경우까지 방어했다고 주장하지 않는다.

#### 세 상태 모델을 한 주문에 겹쳐 보기

| 진행 단계 | 일반 Order | BlockchainTransaction | 사용자 입력 자산 |
| --- | --- | --- | --- |
| 견적만 발급 | 아직 없음 | 없음 | 잠그지 않음 |
| 준비 transaction commit | REQUESTED | 아직 없음 | input 잠김, amount 그대로 |
| 서명 원문 commit | REQUESTED | SIGNED | 잠금 유지 |
| RPC 제출 기록 | PENDING_ONCHAIN | SUBMITTED | 잠금 유지 |
| 확인 수 충족·이벤트 검증·정산 | FILLED | CONFIRMED | 입력 차감/잠금 해제, 출력 지급 |
| 실패 receipt 정산 | FAILED | FAILED | 입력 총량 유지, 잠금 해제 |
| 성공 근거가 기대와 모순 | 보통 PENDING_ONCHAIN 유지 | REVIEW_REQUIRED | 자동 변경·해제하지 않음 |
| SIGNED 전에 전송 준비 실패·보상 | FAILED | 없음 | 잠금 해제, 소비된 quote는 새 견적 필요 |

조건부 의도는 일반 Order가 생기기 전 WAITING/PREPARING, 연결 후 EXECUTING이며 그 Order/transaction 결과를 보고 최종 상태를 기록한다. 하나의 status 필드로 세 영역의 의미를 모두 표현하는 구조가 아니다.

### 3.8 재시작 복구와 장기 정체 관측

**복구:** SIGNED 거래는 `recoverSigned`로 txHash 조회한다. 있으면 제출 상태를 복원하고, 없으면 **동일 raw**를 재전송한다. 이미 알려진 거래 응답은 처리 가능하나 다른 오류는 다음 polling에서 재시도한다. 새 nonce로 다시 서명하지 않는다. SUBMITTED는 receipt를 계속 확인한다.

서버 재시작 시 DB의 미완료 상태가 남는 덕분에 복구가 가능하다. 하지만 체인까지 유실됐을 때 재전송이 원래 실행 이력을 되살리는 것은 아니다. DB와 chain state를 함께 보존해야 한다.

**관측:** [ObservationRecorder](../backend/src/main/java/com/pricetrack/exchange/blockchain/observation/ObservationRecorder.java)가 기존 복구 호출의 결과만 비동기로 기록한다. 추가 RPC를 넣지 않는다. `SIGNED_RECOVERY/RECEIPT_CHECK`, 시작/완료 시각, 고정 resultCode의 최신 완료 snapshot을 transactionId별로 유지한다. queue100·writer1·별도 제한 pool, 저장 실패/drop best-effort이다. 오래된 시도는 최신 snapshot을 덮지 않는다.

[TransactionObservationService](../backend/src/main/java/com/pricetrack/exchange/blockchain/observation/TransactionObservationService.java)의 ADMIN 목록/상세는 SIGNED/SUBMITTED 대기 **300초 이상 LONG_WAIT**. 299/300/301 경계, 적용 threshold와 관측시각, execution Order/conditional 연결, UNRECORDED/최신 완료 시도를 표시한다. 목록에서 txHash/nonce를 빼고 ADMIN 상세에서만 제공한다. Order 링크 없는 시스템 거래나 손상 링크도 숨기지 않는다.

**실패 의미:** RECEIPT_NOT_FOUND는 아직 receipt를 못 찾았다는 관측이고 “영구 미채굴”이 아니다. ATTEMPT_UNAVAILABLE은 ETH 부족이라는 진단이 아니다. LONG_WAIT는 FAILED/REVIEW_REQUIRED로 강제 종료하거나 잠금을 푸는 정책이 아니다. 저장 실패가 복구/정산 예외를 덮어쓰지 않게 보호한다. shutdown 중 metadata가 유실될 수 있고 recordedCount는 전체 RPC 호출 수가 아니다.

**검증:** ObservationIntegration/Security/Recorder/Recovery tests, 실제 조건부·관측 PG/Anvil macro E2E 및 **별도 JVM** `ObservationRestartPostgresTest`. 후자는 영속 snapshot을 SQL 초기화 없이 다시 읽는 검증이며 완전한 host crash/전체 네트워크 chaos를 뜻하지 않는다. [관측 완료 기록](transaction-stall-observability-implementation.md).

### 3.9 STOMP 공개·개인 이벤트와 웹 상태 복구

**왜:** 서버가 가격·체결·주문 변화를 알려주면 매번 REST를 polling하지 않고 화면을 갱신할 수 있다. 그러나 메시지를 받았다는 사실이 영속 정산을 대신하지는 않는다.

HTTP handshake `/ws`와 `/ws-sockjs`는 허용하고, STOMP CONNECT의 native Authorization으로 JWT를 검증한다. 비로그인도 공개 가격·체결 topic을 구독할 수 있지만 개인 queue는 인증 principal이 있어야 한다. 일반 사용자에게 임의 destination과 클라이언트 SEND를 허용하지 않는다.

| 목적 | 클라이언트 구독 destination |
| --- | --- |
| 가격 | `/topic/markets/mSEC/price` |
| 서비스의 모의 체결 | `/topic/markets/mSEC/trades` |
| 본인 주문 | `/user/queue/orders` |
| 본인 포트폴리오 | `/user/queue/portfolio` |
| 본인 조건부 의도 | `/user/queue/conditional-orders` |

서버는 `convertAndSendToUser`로 principal 이름과 private destination을 연결한다. 공개 체결은 실제 삼성전자 시장 체결이 아니라 이 서비스의 모의 주문 정산 결과이며 개인정보 최소화 DTO를 사용한다.

`도메인 service → WebSocketEventPublisher → Spring 내부 WebSocketDelivery → WebSocketDeliveryListener.deliver(AFTER_COMMIT) → SimpMessagingTemplate` 순서다. DB transaction 안에서는 commit 후만 발행, rollback이면 발행하지 않는다. transaction 밖의 이벤트는 fallbackExecution으로 즉시 처리한다. Synthetic는 별도로 DB tick/state commit 후 공개한다.

**코드:** [STOMP 인가](../backend/src/main/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptor.java), [commit 후 전송](../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/WebSocketDeliveryListener.java), `WebSocketEvent`/`WebSocketDestinations`/public·private publishers, 웹 [websocket.js](../tools/websocket-test-client/src/websocket.js)·[recovery.js](../tools/websocket-test-client/src/recovery.js)·`state.js`/`main.js`.

**실패:** WS는 영속 메시지 큐가 아니다. commit 성공 후 전송 실패·프로세스 종료로 메시지가 유실될 수 있다. outbox/Kafka exactly-once를 구현하지 않았다. 웹은 1~30초 지수 backoff와 연결/요청 generation, eventId 중복 제거, 재연결 후 REST 재조회로 현재 상태를 복원한다. 로그아웃/계정 전환 뒤 도착한 옛 응답은 현재 화면에 적용하지 않는다. API·WS 결과의 “부분 복구”도 표시한다.

**검증:** WebSocketEventPublisherTest, WebSocketAuthInterceptorTest, WebSocketConnectionIntegrationTest, WalletWebSocketIntegrationTest, 조건부 실제 STOMP 격리 및 웹 unit/Chrome desktop/mobile fixture. “Chrome 실행” 자체가 실제 DB/Anvil/증권사 연결 증명은 아니다. SockJS는 브라우저 호환 fallback이고 Android native STOMP에 필수는 아니다. 두 endpoint가 두 개의 별도 원장을 만드는 것도 아니다.

### 3.10 Oracle 가격 조건부 BUY/SELL

**왜:** 사용자가 목표 기준 가격에 도달할 때 주문을 시도하고 싶어도 30초 견적을 며칠 미리 저장할 수 없다. 장기 의도와 짧은 실행을 분리해야 한다.

조건은 BUY `priceE8<=targetPriceE8`, SELL `priceE8>=targetPriceE8`. 입력 의미는 기존 그대로 BUY=mKRW 총 입력, SELL=mSEC 입력이다. 조건은 정수 priceE8로 비교한다. 사용자끼리 매칭·독립 가격 형성·부분 체결·시간 지정 예약이 아니다.

**동작:**

```text
POST 의도(목표가격·입력량·만료·멱등키), 자산 미예약
 → WAITING
 → worker claim: OPEN/LIVE/fresh·가격 조건·만료 확인
 → PREPARING
 → 외부 RPC/서명 작업은 의도·balance lock 밖에서 새 quote 발급
 → prepare: 의도/owner/cancel/expiry·현재조건·견적가격조건 재검사
 → 기존 Order 준비 + 자산 lock + quote 소비 + executionOrderId 연결 원자 commit
 → EXECUTING
 → 기존 submitPrepared / raw 복구 / receipt 정산
 → SUCCEEDED / FAILED / REVIEW_REQUIRED
```

등록 때 lock하지 않은 이유는 장기 대기 자산을 묶지 않고 등록 시점부터 실행까지 유동성을 예약하지 않으려는 선택이다. 대신 조건 충족 때 다른 주문이 잔고를 사용했다면 실패할 수 있다. “조건 충족=체결”이 아니다.

**상태:** WAITING, PREPARING, EXECUTING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, REVIEW_REQUIRED. 기존 Order와 별도 엔터티이며 executionOrderId unique로 연결한다. 조건부 WAITING을 Trade나 온체인 성공으로 기록하지 않는다. PREPARING이어도 실행 Order 연결 전에 취소 가능하지만 연결 후에는 취소/만료로 잠금을 풀지 않는다.

**코드:** [ConditionalOrder](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrder.java), [Service](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderService.java)의 `create/claim/issue/prepare/defer/refresh`, [Worker](../backend/src/main/java/com/pricetrack/exchange/conditional/ConditionalOrderWorker.java)의 `poll/evaluate`, [설계](oracle-conditional-orders-design.md), [구현](oracle-conditional-orders-implementation.md).

worker는 단일 executor·프로세스 gate이고 due batch20, 상태 변경 후 next evaluation 5초다. process 재시작 시 pre-order PREPARING을 재평가하며, 이미 연결된 execution Order는 기존 원문/receipt에 맡긴다. prepare 후 SIGNED 증거 없이 중단된 연결은 PRE_SUBMIT_INTERRUPTED로 검토 필요, 무조건 새 Order를 만들지 않는다.

**실패:** 시세·조건·견적이 바뀌거나 만료된 준비는 rollback 후 WAITING/EXPIRED 등으로 돌아간다. 잔고 부족은 terminal 실패, execution Order가 FAILED면 자동 재주문하지 않는다. 전송 불명확은 EXECUTING, 검증 모순은 REVIEW_REQUIRED. 자산 lock 대기 이후에도 조건과 quote TTL을 다시 검사한다. 기본 disabled이며 public profile에서는 비활성, Synthetic 허용은 전용 conditional-fixture + flag + localhost 격리 DB/RPC guard에 한정한다.

**검증:** ConditionalOrderIntegrationTest, ConditionalPostgresAnvilE2ETest, 웹 conditional-panel tests. 실제 PG/Anvil에서 BUY/SELL·동시 worker·취소 경쟁·동일 raw 복구·만료 receipt·두 대사 호환을 검증했다. 독립 검토에서 최신 시세 조건 재검사와 UI pending 고착 결함 2건을 찾아 보완했고 재검토 필수 수정 없음. 실제 장중 조건 평가와 장시간·다중 프로세스 실행은 미검증이다. Android 계약은 [별도 문서](conditional-orders-android-integration.md), Android 수정/빌드 완료는 아니다.

### 3.11 장부-체인 자산 대사와 거래 전수 감사

두 기능은 이름이 비슷하지만 질문이 다르다. 기존 receipt reconciliation은 **미완료 거래를 정산**하는 업무다. 다음 두 ADMIN 기능은 **읽기·비교 결과만 저장**하며 잔고/Order/체인을 보정하지 않는다.

#### A. 장부-체인 자산 대사: 수량 변화가 설명되는가

**왜:** 사용자 DB 지급·거래 결과와 관리 중 온체인 자산 변화가 서로 설명되는지 확인한다. 단순 `사용자 mKRW 합=operator mKRW`는 DB Faucet과 독립 준비 자금 때문에 처음부터 성립하지 않는다.

신규 0원 장부·거래 없음 상태에서 온체인 준비 자금과 역할을 확인하고 불변 baseline을 만든다. chainId/주소/operator, blockNumber/blockHash, DB 시각, executionId를 함께 고정한다. 초기 공급·allowance·Vault 유동성을 준비한 **이후**, 사용자 DB Faucet/주문 **이전**에 기준점을 만든다. 이미 사용한 원장에 현재 상태를 정상 기준점으로 덮어쓰는 기능은 없다.

**계산식:** 모든 값은 같은 18 decimals 정수로 비교한다. ΔK/ΔS는 **ONCHAIN 확정 Trade만**의 사용자 방향 순변화다.

```text
사용자 mKRW 기대값 = 본인 FaucetGrant 합 - 모든 BUY 입력 + 모든 SELL 순출력
사용자 mSEC 기대값 = 모든 BUY 출력 - 모든 SELL 입력

ΔK = -ONCHAIN BUY 입력 합 + ONCHAIN SELL 순출력 합
ΔS = +ONCHAIN BUY 출력 합 - ONCHAIN SELL 입력 합

operator mKRW = baseline.operator mKRW + ΔK
Vault mKRW = baseline.Vault mKRW - ΔK
operator mSEC = baseline.operator mSEC + ΔS
mSEC totalSupply = baseline supply + ΔS
mKRW totalSupply = baseline supply (기준점 뒤 추가 mint/소각 없음 가정)
Vault mSEC = baseline 값
operator+Vault mKRW 합 = baseline 합
```

DB_ONLY 거래는 사용자 journal에 반영하되 체인 Δ에는 넣지 않는다. BUY mKRW 입력은 수수료 포함 전체, SELL mKRW는 net이어서 대사에서 fee를 다시 빼면 잘못된다. lockedAmount는 amount의 일부라 더하지 않는다. 미정산/검토 거래가 있으면 확정 대사 조건을 만족하지 못한다.

**숫자 예시(설명용, 실제 운영 설정 아님):** baseline에서 operator mKRW 10,000,000, Vault mKRW 10,000,000, operator mSEC 0이고 사용자 DB는0이라고 하자. DB Faucet으로 사용자 A에게1,000,000을 지급해도 operator/Vault는 각각10,000,000 그대로다. A가750,000 BUY해9.99mSEC를 받으면 operator mKRW9,250,000, Vault10,750,000, operator mSEC9.99가 된다. 사용자 DB는 mKRW250,000/mSEC9.99다. 서로 절대 잔고가 달라도 위 기준점+변화식으로 MATCH가 가능하다. 이것이 사용자 합계와 operator 잔고를 직접 동일시하지 않는 이유다.

**consistent cut:** `ReserveStore.cut`은 짧은 REPEATABLE_READ read-only snapshot이며 balance FOR UPDATE나 RPC를 하지 않는다. 체인은 명시 block+hash로 읽는다. 끝에 다시 DB fingerprint·head/hash·canonical을 비교한다. 조회 중 DB/블록 변화면 MISMATCH를 확정하지 않고 INCONCLUSIVE(CUT_CHANGED). 분산 snapshot을 완전히 만든 것이 아니라 **안정되지 않은 비교를 거부하는 보수적 방식**이다. 바쁜 원장에서 판정 불가가 자주 나올 수 있다.

**코드:** [ReserveService](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveService.java), [ReserveStore](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveStore.java), [ReserveCalculator](../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveCalculator.java), `RpcReserveChainReader`, 웹 `reserve-panel.js`. 표준 결과 MATCH/MISMATCH/INCONCLUSIVE 외 의존성 조회 실패는 UNAVAILABLE로 구분한다.

**유동성과 분리:** operator mKRW·allowance·mSEC, Vault mKRW, operator ETH를 읽기 전용 참고 정보로 제공한다. ETH 잔고만으로 미래 Gas 가능 여부를 보증하지 않고 READ_ONLY_NOT_ESTIMATED를 표시한다. 배분 참고 비율은 PoR/준비금 보증이 아니다. 대사 MATCH와 모든 미래 SELL 지급 능력은 다르다. 외부 공개 MockKRW mint·Vault 송금은 사용자 journal과 무관한 체인 변화로 불일치를 만들 수 있다.

**검증:** ReserveServiceTest/IntegrationTest/ReservePostgresAnvilE2ETest. 실제 PostgreSQL writer 변화와 신규 Anvil block으로 INCONCLUSIVE, 정상/불일치, 불변 baseline, ETH 정보·거래 불변을 확인했다. writer는 첫 cut transaction이 끝난 뒤 잔고+grant 또는 Trade.created_at metadata를 commit한 재현이다. 여러 쿼리 사이 writer를 끼워 넣은 MVCC visibility 실험이나 실제 정산 금액 경쟁·고부하 검증까지 수행한 것은 아니다. [최종 보완 기록](reserve-reconciliation-final-validation.md). 기존 데이터 UNKNOWN·과거 state 미보존은 임의 추론 없이 검증 불가다.

#### B. 거래 전수 감사: 각 DB 거래가 실제 chain 증거와 대응하는가

**왜:** 합계가 맞아도 A의 거래를 B에게 기록하거나 일부 이벤트를 빼먹을 수 있다. Order/Trade/Quote/BlockchainTransaction과 실제 tx/receipt/Vault·Oracle event를 양방향으로 확인한다.

ADMIN 수동 실행 → 별도 single worker → DB snapshot/chain cut → Order별 연결·금액/방향/실행유형 검증 → chain tx/receipt/raw calldata/nonce/hash 비교 → Vault 출력·수수료/가격 비교 → Oracle quoteId/관측·만료/signer 비교 → 기준점 이후 이벤트 스캔으로 DB 없는 실행도 확인 → cut 재확인 → 불변 감사 이력.

`AuditValidator.inspect`는 과거 signer와 fee 근거를 사용한다. 과거 설정을 못 읽으면 현재 signer/fee로 추론하지 않고 HISTORICAL_CONFIG_UNAVAILABLE 등으로 표시한다. raw가 없으면 비교 한계를 기록하고 가능한 RPC 근거를 쓴다. 같은 블록 안 설정 변경·RPC의 역사 상태/로그 정확성은 남은 검증 범위다.

**개별 vs 전체:** 개별 MATCH/MISMATCH/INCONCLUSIVE 수와 `dbComplete/chainComplete/cutStable/environmentVerified` coverage를 분리한다. 일부 증거 조회 실패·미완료/UNKNOWN·스캔 부족이면 전체 MATCH가 아니다. 개별 모순이 보여도 전체 coverage 불완전하면 전체 INCONCLUSIVE일 수 있다. “전수”는 기준점 이후·지원 대상·실행 한도 안의 범위를 뜻한다.

**코드:** [AuditService](../backend/src/main/java/com/pricetrack/exchange/tradeaudit/AuditService.java), [AuditValidator](../backend/src/main/java/com/pricetrack/exchange/tradeaudit/AuditValidator.java), `AuditStore`·`RpcAuditChain`, 웹 `trade-audit-panel.js`. 기본 timeout30초(허용1~120), RPC calls500(허용1~10000), DB/log 크기 한도 등으로 자원 사용을 제한한다. bounded 선언이 모든 socket/historical RPC 지연에서 엄밀한 전체 wall-clock 종료를 증명하지는 않는다.

**검증:** AuditUnit/Receipt/SourceBudget/Budget/SecurityTest, TradeAuditPostgresAnvilE2ETest. 고아 Trade/tx/Quote, 기록 모순, signer/fee, cut 변화, 양방향 event/coverage와 자산 대사 호환. 마지막 고아 Quote 수정 포함 최종 통합 실행은 [감사 구현 기록](trade-full-audit-implementation.md), 후속 Phase A 독립 audit fixture PASS. 대규모 활성 원장·reorg·악의적 RPC 누락·운영 성능은 미검증.

### 3.12 Synthetic Market

**왜:** 공개 데모에서 실시간 국내 시세의 표시·재배포 권한을 임의 가정할 수 없었고, 시장이 닫혀도 동작하는 무외부 의존 demo가 필요했다. 단순 난수 한 번보다 자연스러운 변동·거래량·재시작 연속성이 필요했다.

[SyntheticMarketEngine.next](../backend/src/main/java/com/pricetrack/exchange/market/provider/simulated/SyntheticMarketEngine.java)는 seed·step·시간과 이전 price/activity/trend로 새 가격과 가상 volume을 만든다. Gaussian 변동, 완만한 activity/trend, 양수 가격 guardrail을 사용한다. 외부 실제 주가·사용자 BUY/SELL은 입력이 아니다. 같은 초기 상태/seed/시간 순서에서 재현 가능하지만 시간 간격이 다르면 결과도 달라질 수 있다.

[SimulatedPriceProvider.advance](../backend/src/main/java/com/pricetrack/exchange/market/provider/simulated/SimulatedPriceProvider.java)는 엔진 state 행 lock, 봉/state 저장을 REQUIRES_NEW transaction으로 commit한 뒤 AtomicReference snapshot과 WS를 갱신한다. rollback된 tick이 견적이나 화면에 보이지 않도록 한다. millisecond timestamp와 기존 eventId/bucket을 활용하고 simulationId/sequence/watermark의 새로운 외부 계약은 추가하지 않았다.

previousClose는 KST 일 경계에서 마지막 생성 가격을 기준으로 갱신한다. 상태의 engineVersion/seed 불일치면 자동 reset하지 않는다. bootstrap은 0~2일, 기본0의 축소 범위다. 장시간 중단분을 전부 replay하지 않고 재시작 후 한 bounded live step만 생성하므로 그 기간의 history 공백은 남을 수 있다. 실제 삼성전자 움직임 복제·실거래량·KRX 제도 모델이 아니다.

**공개 경계:** public profile은 SIMULATED 전용으로 Toss 호출을 막고, 웹 배포의 정적 SIMULATED·합성 안내를 사용한다. 원본 HTML에는 hidden 안내가 있고 `tools/websocket-test-client/vite.config.js`는 `PUBLIC_DEMO=true` 빌드에서 그 hidden을 제거한다. Dockerfile의 build arg/production Compose와 함께 읽는다. 그래서 API 장애 전에도 공개 빌드에서는 안내가 보인다. 단순 원본 HTML 한 줄만 보고 모든 mode에서 강제 표시된다고 설명하지 않는다.

**실패·검증:** persistence/version/Clock/캔들/public guard unit, `SimulatedPersistenceIntegrationTest`, `SyntheticMarketPostgresAnvilE2ETest`의 새 PG/Anvil 서명 BUY/SELL·STOMP. [축소 설계/검증](synthetic-market-design.md)과 [배포 준비](deployment-preparation.md). 자연스러움의 정량 금융 통계 검증이나 실제 시장 예측력이 확보된 것은 아니다.

### 3.13 AI RAG·Tool·Agent·Skill·자동 진단

이 용어는 같은 기능의 이름이 아니라 책임이 다른 단계다.

#### RAG: 승인 규칙을 찾아 설명

**왜:** LLM 기억만으로 프로젝트 규칙을 설명하면 오래된 설계와 현재 계약을 섞을 수 있다.

`docs/ai-knowledge/` 9개 문서 + `ingest-manifest.json`의 active/version/hash/minimum_role 검증 → Markdown 청크 → embedding → 별도 pgvector DB 색인 → 질문 vector 검색 → 허용 근거로 답변. `indexVersion`은 corpus fingerprint로 현재 승인 내용과 일치해야 한다. 문서 변경이 자동 외부 재색인을 뜻하지 않는다.

현재 default: text-embedding-3-small 1536차원, chat model 설정 gpt-5.6-terra, 1200 **UTF-8 byte** 청크 상한, overlap60, similarity0.25, 충분한 후보 `max(40, topK×8)` 검색 뒤 최종 top5에서 문서당2개. `AI_CHUNK_TOKENS` 이름을 실제 tokenizer 계량으로 해석하지 않는다. 모델명은 저장소 설정/과거 실행 기록이며 현재 외부 가용성·최적성은 이번에 조사하지 않았다.

**코드:** [KnowledgeLoader](../backend/src/main/java/com/pricetrack/exchange/ai/knowledge/KnowledgeLoader.java), `MarkdownChunker`·`PgKnowledgeStore`·`EvidenceSelector`, [AuthorizedKnowledgeRetrieval](../backend/src/main/java/com/pricetrack/exchange/ai/retrieval/AuthorizedKnowledgeRetrieval.java), [RagService](../backend/src/main/java/com/pricetrack/exchange/ai/RagService.java). USER/ADMIN·Skill domain 필터는 SQL 후보 검색 단계에 적용한다. Basic RAG API는 ADMIN-only 유지.

**실패:** 승인/hash/색인 불일치, 근거 부족, provider 장애는 안전 실패/INSUFFICIENT_EVIDENCE. 문서만으로 “현재 내 잔고/주문 체결 여부”를 답하지 않고 LIVE_DATA_REQUIRED 경계가 있다. citation ID 유효성은 자연어 모든 문장이 참이라는 보증이 아니다.

**검증·선택 근거:** Phase2 baseline hit@5 10/12. 낮은 threshold만·문서 다양성만·청크 개선을 분리 실험하고 K 조합을 채택했다. 당시 golden12/12, MRR0.8819, 직접근거12/12, 기존 성공10 회귀0. Hybrid/RRF는 식별자6/8→8/8을 개선했지만 golden MRR0.8819→0.8542 및 직접근거12→11로 낮아 기본 경로로 채택하지 않았다. 무관 후보5/8과 실제 무관 오답0/8은 별개다. [검색 실험](../docs/ai/phase-3-retrieval.md).

두 실패의 원인을 알아야 개선 수치가 의미 있다. 30초 견적 질문의 정답 문서는 similarity 약0.2968로 1위였지만 기존 threshold0.3에서 탈락했다. 소켓 복구 질문은 기대 청크가7위/9위이고 상위5개 중4개가 주문 문서여서 다양성이 부족했다. 문장 중간 overlap과 설명 없는 링크 청크도 직접 근거 품질을 낮췄다. PostgreSQL simple FTS에 한국어 질문 전체를 넣으면 두 질문 모두0건이라 keyword 검색만으로 한국어 문제를 해결한다고 가정하지 않았다.

#### Tool: 현재 사실을 권한 범위 안에서 읽기

**왜:** 정책 문서는 현재 가격·사용자 자산·receipt를 포함하지 않는다. 인가된 현재 상태 조회가 필요하다.

고정8개: getOrder/getQuote/getBlockchainTransaction/getReceiptSummary/getMarketStatus/getCurrentReferencePrice/getPortfolio/listAbnormalOrders(ADMIN). `ToolDispatcher → ToolRegistry → ToolReadFacade/ReadOnlyReceiptClient`. userId/role은 JWT principal, order/quote target 소유권은 서버가 검증한다. 모델이 SQL/URL/임의 Tool을 생성해 실행하지 않는다.

**코드:** [ToolDispatcher](../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolDispatcher.java), [ToolReadFacade](../backend/src/main/java/com/pricetrack/exchange/ai/tool/read/ToolReadFacade.java), [Tool 보안 문서](../docs/ai/TOOL_SECURITY_MODEL.md).

**실패:** 존재/타인 소유 정보 누출 차단, timeout·BUSY·의존성 오류 고정 code, raw/signature/key/원문 RPC 오류 제외. Tool API는 외부 모델/AI DB 없이도 동작 가능하다. 전수 감사·Faucet 관리 등 모든 최신 ADMIN API를 자동으로 Tool에 넣은 것은 아니다.

**검증:** ToolIntegration/Postgres/Anvil tests, USER/ADMIN/타인 데이터·secret canary·거래 불변. 실제 RPC 조회와 fixture 사실 비교 범위는 AI Phase4 문서로 구분한다.

#### Agent: 어떤 근거를 결합할지 제한적으로 조정

**왜:** 질문이 “규칙 설명”인지 “현재 상태 조회”인지 “현재 상태+정책 해석”인지 다르다.

[AgentService](../backend/src/main/java/com/pricetrack/exchange/ai/agent/AgentService.java)는 KNOWLEDGE/STATE/MIXED/CLARIFY/UNSUPPORTED route를 사용한다. 입력은 question·optional target·skillId, 사용자가 userId/role/임의 실행 plan을 전달하지 않는다. target preflight→허용 route→Tool→RAG→모델 해석→facts pointer/value/citation 검증. STATE는 Tool 사실 위주이며 RAG/종합 응답 모델을 생략하는 경로다. 그렇다고 모든 STATE 요청의 모델 호출 수가 항상0인 것은 아니다(분류 모델이 사용될 수 있음).

run 전체40초, worker2, Tool4/검색1/모델2 예산을 공유한다. timeout으로 요청을 반환해도 취소에 응하지 않는 작업이 실제 종료할 때까지 worker 용량을 유지한다. 응답의 `toolEvidence`는 관측 사실, `answer`는 해석, `uncertainties`는 부족/오류다. PARTIAL은 실패를 숨긴 ANSWERED가 아니라 제한을 드러내는 결과다.

**검증:** AgentService/Integration/Anvil/AuthorizedKnowledgeRetrieval/provider tests 및 과거 실제 모델 평가. injection으로 ADMIN 승격·거래 실행·SQL 접근을 요청해도 서버 경계를 변경하지 못하게 한다. 자유 문장 완전 진실성이나 prompt injection 전면 해결을 주장하지 않는다.

#### Skill: 반복 진단을 고정 순서로 실행

**왜:** 매번 LLM에게 진단 단계를 자유 생성하게 하면 누락·권한·예산·재현성이 불안정하다.

registry의 hash/version/role/domain/target ceiling과 코드 handler로 세 절차만 실행한다.

- settlement-debugging: ADMIN/orderId, 주문→견적→전송→receipt→정책.
- signed-quote-diagnosis: USER/ADMIN/quoteId, 본인 견적과 연결 상태 진단.
- market-availability-diagnosis: USER/ADMIN, 시장·기준 가격·정책.

**코드:** [SkillRegistry](../backend/src/main/java/com/pricetrack/exchange/ai/skill/SkillRegistry.java), [SkillRunner](../backend/src/main/java/com/pricetrack/exchange/ai/skill/SkillRunner.java), `backend/src/main/resources/ai/skills/`, [Skill 안내](../docs/ai/SKILL_GUIDE.md). Agent 예산 공유, 새 Tool·단계·shell 실행 금지. trace에는 step/result/evidenceRefs/관측시각이 있어 답변을 검증할 수 있다. definition 오류면 기능 비활성이지 거래 전체 중단이 아니다.

**검증:** SkillRegistry/Service/API tests, 실제 모델 대표3 Skill 평가. CRLF checkout 결함은 제품 parser 변경이 아닌 테스트 fixture 정규화·양 형식 회귀로 해결한 이력이 있다(5장).

#### 자동 진단: 커밋된 REVIEW_REQUIRED를 독립 관찰

**왜:** 운영자가 검토 필요 거래를 찾고 설명 근거를 모으는 반복 작업을 보조한다. 자동 복구/자동 거래가 목적이 아니다.

[DiagnosisSourceReader](../backend/src/main/java/com/pricetrack/exchange/ai/diagnosis/DiagnosisSourceReader.java)는 거래 DB의 REVIEW_REQUIRED BUY/SELL을 읽는다. [DiagnosisCoordinator](../backend/src/main/java/com/pricetrack/exchange/ai/diagnosis/DiagnosisCoordinator.java)는 별도 scanner/dispatcher로 AI DB enqueue/claim, 서버 ADMIN actor·연결·현재대상 재검사, 고정 settlement-debugging 실행, 안전한 결과 저장을 수행한다.

초기 안전값 polling5초/batch50/queue100/UTC일20/결과7일. fingerprint unique·lease·quota·자동 worker gate로 중복/자원 사용을 제한한다. 실행0 BUSY만 제한 재예약, 이미 실행했는지 모르는 lease 만료는 자동 재호출하지 않는다. trade DB와 AI DB 분산 transaction은 없다. dedup marker를 남기고 결과·metadata TTL을 정리한다. SHA-256 fingerprint는 원문 txHash 노출을 줄이는 식별자이지 법적 익명화 증명은 아니다.

**실패·한계:** 기본 자동 진단 disabled. AI 장애는 거래 정산의 동기 호출 경로 밖에서 처리하지만 공유 JVM 자원 위험은 남는다. polling 사이 사라진 이벤트의 무손실 보장 없음. 일반 FILLED 성공 거래를 전부 자동 분석하는 기능이 아니다. “성공 MATCH 자동 진단 인수”는 REVIEW_REQUIRED 대상에 성공 receipt 근거를 가진 별도 시나리오이며 아직 실제 통합 인수 완료가 아니다.

**검증:** DiagnosisCoordinator/Source/PGStore/EndToEnd/API tests, AutomaticAgentAdmissionTest, Phase7/8 실제 모델 평가의 state/receipt는 H2 fixture. 당시 대표8개 기대결과8/8(ANSWERED2/PARTIAL4/안전거부2)은 실사용자·실시장 장애 정확도100%가 아니다. 최신 9문서 업데이트 이후 paid 색인/동일 검색·답변 회귀는 NOT_RUN이다. [AI 평가](../docs/ai/EVALUATION_REPORT.md), [최신 지식 변경](kiwoom-transition-final-report.md).

### 3.14 백업·복원·격리 테스트 환경

**왜:** 거래 DB만 복원하면 txHash/nonce/주소가 가리키는 체인이 없을 수 있다. AI 지식 버전과 deployment release도 같은 실행 기준으로 추적해야 한다.

배포 준비 Compose는 private PostgreSQL 두 개와 persistent Anvil state를 사용한다. backend non-root/read-only image, Nginx HTTPS/WSS/rate limit, runtime secret 파일, 별도 volume/data root와 backup/restore runbook을 마련했다. 실제 AWS 자원·DNS·HTTPS 공개 운영 인수와 로컬 구성 검증을 구분한다. 현재 AWS 실험 방향은 EC2 최소 구성·수동 secret이며 SSM을 반드시 구축했다고 설명하지 않는다.

[ops.py.backup](../deployment/ops.py)은 허용 project/root/mount를 검사하고 웹/backend ingress를 멈춰 미완료/잠금 여부를 확인한 후 DB dump와 chain/release checkpoint를 묶는다. checksum은 파일 무결성이지 두 원장의 논리 일치 보증이 아니다.

restore는 새 `exchange-restore-*` project·빈 data root에만 가능, 기존 root 덮어쓰기 금지. 양 DB 목표 DB에서 실제 SQL이 되는지 bounded readiness 후 각 dump 직전에 재확인한다. `pg_restore`는 한 번만 실행, stderr는 private 별도 파일에 보존, 실패한 부분 복원 위 retry를 하지 않는다. ingress 자동 공개 없이 code/nonce/receipt/domain/원장/index를 확인한다.

**코드:** [실제 SQL probe](../deployment/postgres-readiness.sh), `ops.py`의 `wait_sql_ready/restore_dump`, [RUNBOOK](../deployment/RUNBOOK.md), `compose.yml`·`anvil-entrypoint.sh`·`local-smoke.py`·`restore-regression.py`. 스크립트를 임의 실행하지 않는다.

**검증:** 새 빈 restore root 3/3·local-smoke16/16 및 독립 검토 기록은 [2026-10-07 검증](validation-2026-10-07.md). Windows helper19 PASS/2 POSIX skip와 Linux19/19 구분. 실제 AWS RTO/RPO·장기 부하/장애는 미검증.

**Phase A 격리:** `deployment/phase-a.py`, `phase_a_clock.py`, `PhaseAClockProbe.java`, test helper `support/PhaseAFixture.java`. 신규 runId·PG/Anvil·포트·키·Docker project/network/volume를 사용하며 source hash/image/ownership/접속 identity/clock preflight를 검사한다. 기존 `.env` 자동 import와 고정 dev 포트 접속을 차단한다. 독립 E2E마다 새 fixture, restart 검증만 성공 conditional 원장을 별도 JVM에서 읽는다. 실패 데이터는 reset해 성공시키지 않고 보존한다.

### 3.15 Toss 유지 + Kiwoom 추가: 전환의 정확한 의미

**왜:** 외부 공급자 선택을 확장하되 주문·Oracle·AI Tool·웹 캔들 계약을 유지하려는 요구였다. Toss 제거·실제 주식 주문 전환이 아니다. broker 변경의 사업적 동기나 Toss 서비스 장애가 결정 원인이었다는 사실은 기록만으로 추정하지 않는다.

`PRICE_PROVIDER=kiwoom`으로 별도 구현을 선택한다. [KiwoomRestClient](../backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRestClient.java)는 인증과 읽기 TR ka10001/ka10080/ka10081, [KiwoomRealtimeClient](../backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomRealtimeClient.java)는 LOGIN 성공→REG(005930 0B/시장 0s), PING, token/재연결을 관리한다. 가격 부호의 절댓값, 양의 개별·누적 거래량, 등락 부호, KST 시각을 정규화한다.

`KiwoomProtocol.time`은 날짜 없는 HHmmss를 수신일 KST로 변환하면서 5초 지연/미래2초 경계를 검사한다. REST 현재가에 관측시각이 없으므로 bootstrap을 INITIALIZING/UNKNOWN으로 두고 LIVE로 승격하지 않는다. `0B` regular(290=2)만으로 `0s` OPEN을 추론하지 않는다.

[KiwoomPriceProvider](../backend/src/main/java/com/pricetrack/exchange/market/provider/kiwoom/KiwoomPriceProvider.java)는 최신 연결 generation의 시장 확인만 적용한다. 누적 거래량으로 같은 초 다른 체결과 duplicate를 구분하고, 이전 연결의 늦은 이벤트를 버린다. 분봉 `cntr_tm`은 native KST→UTC 그대로 보존하며 Toss식 종료→시작 shift를 추정하지 않는다. 실제 시작/종료 label과 asOf/tick 합치는 경계는 미검증이다.

**실패:** 인증·token/HTTP/WS 오류는 안전한 code로 처리하고 원문/token을 로그에 남기지 않는다. UNKNOWN/DEGRADED/STALE을 실제 OPEN/LIVE로 대체하지 않는다. 토큰·재연결·rate-limit의 실제 vendor 동작은 Mock 통과만으로 보증하지 않는다.

**검증:** Kiwoom REST/protocol/realtime/price/candle Mock19개 + KIWOOM 조건부 H2/Mock1개, 기존 backend/Web/Foundry, Synthetic 실제 E2E의 계약 호환. 기능 `5e39dbf`→통합 `cffe471`. 최신 Phase A 5/5도 **Synthetic**, 실제 키움 E2E가 아니다.

별도 [읽기 전용 Python 진단](kiwoom-readonly-diagnostics.md)은 제품 서버·DB·Anvil과 분리되어 LOGIN/REG/0s/0B, generation, native1m/OHLCV/asOf를 허용 필드만 기록한다. 기본 외부 OFF, private credential, 명시 호출 한도, 승인 후 초기 REST4/WS1/180초. Mock26/26·Phase A guard15/15·독립 재검토 필수 수정 없음. 사용자 키 입력 후 real mode/ACL/존재/endpoint만 로컬 점검됐고 **실제 인증·시세 호출0회**인 것이 조사 기준 최신 상태다. 키 값·원본 credential 경로 내용은 이번 조사에서 읽지 않았다. [실제 인수 준비](kiwoom-live-acceptance-preparation.md).

## 4. 개발 흐름과 주요 의사결정 — Git 시간순

날짜는 `git log --reverse --date=short`의 기록이다. 실제 작업 시간이 모두 커밋 날짜에 해당한다거나 중간에 활동이 없었다는 해석은 하지 않는다. 상세 Phase 구분은 거래소 Phase와 AI Phase를 섞지 않는다.

| 시기 / 커밋 | 당시 상태·계기 | 대안·선택과 실제 변경 | 검증·한계의 근거 |
| --- | --- | --- | --- |
| 06-22 `779cb9c`, `fe8e3ea`, `5f8c7dd` | 제안서·모노레포, 체인 최소 동작 먼저 확인 | contracts/backend/android 뼈대, Anvil 시나리오 | 초기 구현 로그·contracts test/script. backend 최종 기능이 이때 완료된 것은 아님 |
| 08-25 `f2bbb1b` | Phase1 계약 테스트 보강 | 계약의 정합성/입력·권한·수학 검증 | implementation-log Phase1. 후속36개와 당시 테스트 수는 다름 |
| 08-26 `7285d14`, `6be2522` | 사용자 인증·DB 모의 거래 필요 | 자체 ID/password·BCrypt/JWT, DB-only 거래·local DB/admin | Auth/Trading tests. Google/email은 후속으로 남김 |
| 08-28~09-02 `b1b81f3`, `211530a`, `6cff906`, `8f90707` | 단순 Solidity 시연을 서버 주문에 연결 | web3j read→submit→receipt settlement→push price sync | 당시 Phase3 기록. 현재 push 신규 제출은 제거됨 |
| 09-03 `b1cb07e`, `1608bac` | 커진 blockchain 영역 이해/검증 | 역할별 패키지 분리·주석 | 당시 리팩터링 검증 기록. 모든 함수에 충분한 해설이 있다는 뜻은 아님 |
| 09-06~12 `451ad0f`~`e0a3fab` | 실시간 가격·사용자 상태 UI 필요 | STOMP JWT/public-private·AFTER_COMMIT, 브라우저 기준 검증 | WS/개인 격리 테스트. 영속 event queue는 없음 |
| 09-13 `a0ddeb8` | AI 작업 품질/승인 경계 관리 | 공통 지침·별도 검토 역할·기록 절차 | AGENTS/README/reviewer, CI 강제·자동 실행 엔진 아님 |
| 09-14~16 `93b985f`, `ab591cb`, `a7cf0f8`, `79fa626`, `bc26f88` | 시뮬레이션 밖 실제 참고 시세 연동 | provider 경계, Toss REST→WS·장 상태/신선도 | Toss 연결·시장 검증 이력. 최신 전체 장중 인수는 미완료 |
| 09-16~25 `f4aa5ca`, `a70ad68`, `34797ba`, `1167088`, `bc3da38` | 가격 숫자뿐 아니라 차트 필요·UTC/KST/history 문제 | provider candle·실시간 tick·시각/봉 정렬·다중 주기·점진 history | chart tests·실제 API 일부 검증과 fixture 구분 |
| 09-26 `0782890`~`4a770b1` | push Oracle와 현실 표시 가격 불일치 | 고정 report/domain·Oracle 검증·Vault 원자 정산·백엔드 서명·user quote | [Oracle 설계](oracle-price-execution-design.md), Java/Solidity vector·Foundry·견적 tests |
| 09-27 `4962e11`, `8090213` | 클라이언트 보고서 신뢰/복구 일관성 필요 | quoteId만 제출·DB 복원·일회 소비/lock, signed ABI 연결 | PG 견적 경합/Anvil, 웹 경계. 사용자 직접 서명 아님 |
| 09-27 `9742570`, `f8ab0eb` | Android 인수 전에 웹 기능·재연결 기준 마련 | 기능 coverage·REST 상태 복구·generation | 웹/STOMP 검증. Android 완성/실기기 인수 아님 |
| 09-27 `30105a4`, `cca4893`, `0e822d8`, `ba090cf` | 코드 감사→승인 지식→RAG 기술 선택 | 내부 adapter + AI DB 격리, 9문서·manifest·1536 embedding | AI Phase0~2, 실제 baseline hit10/12 |
| 09-28 `07a36d0` | golden2 실패·청크/threshold 문제 | 독립 실험 후 K 채택, Hybrid 비교 미채택 | hit/MRR/직접근거/오탐/권한 평가. 식별자2실패 남음 |
| 10-01 `9a0df88`, `5f114db` | 문서만으로 현재 상태 설명 불가·반복 진단 | 8 read-only Tool + 제한 route + 고정3 Skill | AI Phase4~6. repository/SQL/거래 실행을 모델에 주지 않음 |
| 10-02~03 `8547b99`, `7a7f045` | 운영 REVIEW_REQUIRED 후 안전한 분석 필요 | polling/claim/fingerprint/TTL + 보안·최소 관측 | 실제 provider 평가/fixture, 안전 거부. 실제 자동 MATCH 미검증 |
| 10-05 `7b178af` | debug/JSON 중심 웹의 설명력 부족 | 기존 Vite/JS·REST 계약 유지하며 제품형 route/상태/반응형 | [제품 UI](web-product-ui.md). 신규backend 임의 확장 없음 |
| 10-06 `c6577aa` | 공개 실제 시세 사용 권한·demo 품질 | 실제 가격 복제 대신 synthetic engine·영속·commit 공개 | 엔진/PG-Anvil/WS·public guard. catch-up 제외 |
| 10-07 `1e05c0b` | 외부 배포 전 운영 준비·복원 차단 | production compose/image/proxy/secret·SQL readiness·stderr 보존 | local-smoke16·restore3·독립 검토. 실제 AWS 미생성 |
| 10-08 `b7cb0f0` | 기존 DB와 빈 Anvil의 관계가 맞지 않음 | 옛 환경 보존, 신규 격리 acceptance baseline | state/receipt read-only 조사·격리 준비. 실제 Toss/AI 인수 대기 |
| 10-09 `00046d7` | DB/온체인 자산 비교 필요 | executionMode·grant·immutable baseline·stable cut·liquidity | PG 동시성/Anvil·ETH 참고·CRLF 회귀, 최초31skip 유지 |
| 10-10 `eed6b2f`→`2a5d8d3` | 합계 비교만으로 개별 실행 누락을 찾기 어려움 | 양방향 receipt/Oracle/Vault audit·history/coverage | 고아 Quote 최종 수정·통합 E2E/독립 검토 |
| 10-11 `a1d87c9`→`7b46833` | DB 모의자금 정책·중복 지급 제어 | 확정 금액·KST/quota/global budget·멱등성/atomic journal | 실제PG 경쟁·예산·rollback·통합 회귀. chain 공급 별개 |
| 10-12 `c413079`→`d8dea3e` | 목표가격 조건 요구 | intent vs execution 분리·새 quote·execution 시 lock | 최신 시세 재검사/UI 보완, PG/Anvil·WS·회귀 |
| 10-12 `c7bf4e8` | 지갑 소유권과 준비금 보증 표현 오해 | A 모델 확정, 문구/표시 테스트만 | 실제 ETH 부족은 미재현, 사용자별 지갑 제외 |
| 10-12 `e489b3c`→`7bf459c` | SIGNED/SUBMITTED 정체를 ADMIN이 알아야 함 | 최신 완료 snapshot·300초 LONG_WAIT·read-only API/UI | PG/Anvil/별도JVM. 자동 실패/잠금해제 안 함 |
| 10-13 `2455d66`, `bebfe11`, `76e8bf0`→`cfbd625` | 통합 인수 PhaseA·시각 차이로 차단 |격리/clock guard, 사용자 동기화 후 새 fixture 검증 | 이전409 정확 revert 미확정. 제품2초/TTL 안 바꿈 |
| 10-13 `966fa76`→`a626d7a` | 즉시 신규 서명과 조건부 시장 품질 차이 | provider 독립 OPEN+LIVE+fresh issuer 강화 | 신규 발급만, 기존 소비/복구/DB-only 유지 |
| 10-13 `5e39dbf`→`cffe471` | Kiwoom 추가 요구 | 읽기 공급자·same generation 상태, 9지식/manifest 갱신 | Mock19+조건부1, backend451/35·web84·forge36. paid재색인 없음 |
| 10-14 `72915fd`→`6ed6d5a`, `a3237f9`, `860885c` | subnet overlap·Engine500 때문에 최종 E2E 중단 | 신규 네트워크 후보만 bounded retry, Engine 정상 뒤 새5종 | 실제5/5·clock4PASS·충돌3회회복·독립 증거 검토 |
| 10-14 `a772d37`→`3ca4945`, `d5311f7`, `240cb39` | 실제키 사용 전 좁은 관찰 필요 | standalone whitelist/limits/private 진단·로컬 credential preflight | 진단26+guard15·독립 재검토. realAPI0/장중대기 |

대안의 근거가 없는 행은 커밋 결과를 설명하는 것이지 당시 작성자의 마음이나 특정 경쟁 기술을 거절한 이유를 꾸며낸 것이 아니다. 예를 들어 “처음부터 Redis보다 PostgreSQL이 성능 우수해서 선택”이라는 성능 근거는 확인되지 않았다.

### 4.1 A/B/C 보관 모델을 비교한 뒤 A를 유지한 판단

[보관 모델 기술 검토](user-wallet-custody-architecture-review.md)는 A 현재 operator+DB, B 운영자 위탁+계약 사용자별 소유관계, C 사용자 지갑+직접/위임 서명을 비교했다. 후속 승인으로 A를 유지했다.

- A는 인증·조건부 주문·복구·지금까지의 receipt/대사를 보호한다. 장부 위탁 신뢰와 사용자별 온체인 소유권 부재가 한계다.
- B는 사용자별 계약 기록을 추가하지만 신규 custody contract·입출금/배분·migration·대사 재정의가 필요하다.
- C는 사용자 통제·서명을 제공하지만 키·gas·allowance·위임/취소·조건부 실행과 operator nonce 모델까지 바뀐다.

모의자산 목표에서 B/C로만 해결해야 하는 필수 요구가 없다는 승인 방향이다. 사용자에게 주소만 생성해 주고 실제 자산/권한을 넣지 않는 것은 C 구현이 아니다. A를 택했다고 자산 감사가 불필요한 것도 아니다.

### 4.2 승인·검토·통합의 개발 방식

공통 문서 확인→설계/범위 승인→독립 기능 브랜치·격리 fixture→구현/회귀→별도 검토→필수 수정/재검토→안전 소스 commit→integration merge→병합 소스 새 회귀를 사용한다. 전체 과정이 항상 자동 강제되는 CI는 아니다.

별도 worktree는 소스/브랜치 변경을 보존 acceptance와 분리하는 수단이지 새로운 원장 격리 자체는 아니다. DB/RPC/volume/secret도 따로 지정해야 한다. 검토자는 같은 지침의 별도 역할이며 구현자 설명만 믿지 않고 코드/테스트/문서를 확인하지만 놓치는 오류는 있을 수 있다. 검토 범위와 실제 독립 실행 여부를 결과에 남긴다.

## 5. 트러블슈팅 — 무엇을 실제로 발견했나

### 5.1 RPC 응답 유실과 중복 거래 위험

- **유형:** 코드가 대비하는 장애 + 실제 격리 proxy 재현.
- **현상:** 노드가 raw를 실행했는데 응답이 끊기면 서버는 전송 성공을 모르고 SIGNED로 남을 수 있다.
- **잘못된 대안:** HTTP 실패를 Order 실패로 보고 unlock하거나, 새 nonce로 재주문. 이미 실행된 거래와 중복 위험.
- **선택:** raw/nonce/expectedHash를 broadcast 전에 독립 commit. hash 조회 후 동일 raw 재전송.
- **근거:** sender/persistence/reconciliation 코드; ConditionalPostgresAnvilE2ETest의 실제 Anvil 전송 후 응답 절단, [조건부 구현](oracle-conditional-orders-implementation.md)·[정체 관측](transaction-stall-observability-implementation.md).
- **한계:** 영구 RPC 장애·ETH 부족·미채굴은 자동 종결되지 않을 수 있다. 실제 장기 ETH 부족을 재현했다고 쓰지 않는다.

### 5.2 Receipt polling의 중복 정산

- **유형:** 반복 실행을 전제로 한 정합성 위험 및 회귀 테스트.
- **문제:** 같은 receipt가 여러 번 돌아오면 잔고와 Trade가 반복 반영될 수 있다.
- **선택:** transaction/Order lock + 완료 상태 guard + Trade unique + 자산·상태 단일 commit.
- **검증:** settlement tests와 PG/Anvil E2E의 반복/복구 assertion. 운영에서 실제 고객 이중 지급 사고가 있었다는 기록은 없다.
- **한계:** “외부 호출 exactly-once”가 아니라 같은 거래·정산 재실행의 멱등성이다. 분산 nonce·악의적/불완전 chain 근거는 별개.

### 5.3 DB commit보다 STOMP가 먼저 나가는 문제

- **유형:** 설계상 잠재 위험, commit/rollback fixture로 검증.
- **문제:** 잔고 변경 후 commit 전 알림을 보내면 rollback돼도 사용자에게 성공이 보인다.
- **선택:** Spring TransactionalEventListener AFTER_COMMIT. Synthetic는 TransactionTemplate 반환 후 snapshot/WS 공개.
- **검증:** WS publisher/rollback tests 및 실제 STOMP 개인 구독. 실제 사용자의 ghost 체결 사고 기록과 혼동하지 않는다.
- **한계:** commit 후 메시지 유실은 아직 가능. REST 재동기화는 현재 상태 복구이지 모든 과거 메시지 재생은 아니다.

### 5.4 견적 재사용·가격 변조·push 가격 경합

- **유형:** 사용자 요구에 따른 설계 문제, 변조/경합 테스트.
- **문제:** 실시간 표시와 별도 push Oracle 가격 차이, 보고서 재사용/타인 견적 소비 가능성.
- **대안:** 허용 가격 차이를 UX에만 표시하거나 push주기 단축보다 주문별 pull report로 전환.
- **선택:** EIP-712 domain/조건/TTL/executor, user-owned DB quote lock/소비, chain usedQuoteIds, Vault 원자 정산.
- **근거:** `0782890`~`4962e11`, [Oracle 설계](oracle-price-execution-design.md). 계약 성공 뒤 실제 해킹 사고가 있었다는 증거는 없다.
- **한계:** quote 가격과 채굴 순간 시장 가격 차이는 없어지지 않는다. PriceOracle.updatePrice 함수는 여전히 owner 참고 경로로 존재하나 buy(uint256)/sell(uint256) 및 신규 push 거래 경로는 제거됐다.

### 5.5 PostgreSQL 복원 readiness

- **유형:** 실제 로컬 restore 실패 + 안전 진단/검증 보강.
- **문제:** 기존 실패 stderr가 충분히 보존되지 않아 readiness race를 원인으로 확정할 수 없었다.
- **선택:** 먼저 private stderr 저장, 목표 DB TCP SELECT1 healthcheck, 양 DB bounded SQL readiness, dump마다 직전 재확인. pg_restore retry/임의 초기 sleep으로 우회하지 않음.
- **검증:** 새 빈 root 3회 restore, local16 checks·Linux/Windows helper 및 독립 검토. [2026-10-07 보고](validation-2026-10-07.md).
- **한계:** 보강 후 성공은 최초 실패가 반드시 readiness race였다는 확정 증거가 아니다. 실제 AWS 장애 복원/RTO/RPO 미검증.

### 5.6 개발 Anvil 상태 유실과 DB 이력 불일치

- **유형:** 기존 개발 환경 read-only 조사에서 확인한 실제 불일치.
- **증거:** 당시 chainId31337/block0, 설정 계약 eth_getCode 모두 빈값, 대표 DB receipt 없음. 개발 DB에는 과거 UPDATE_PRICE 22,603건, 보존 state들의 해시 교집합0. 배포 broadcast 주소 일치만으로 state 복원 불가.
- **선택:** 기존 환경을 덮어쓰지 않고 신규 격리 acceptance baseline. 기존 DB raw 일괄 재전송·같은 주소 재배포로 “복구 완료” 처리하지 않음.
- **근거:** [체인-state 조사](development-chain-state-audit.md), [신규 준비](isolated-acceptance-baseline.md), `b7cb0f0`.
- **한계:** 언제/어떤 재시작이 유실을 만들었는지는 미확정. 발견한 파일 외 외부 백업 존재 여부는 단정하지 않는다. 과거 UPDATE_PRICE를 현재 서명 BUY/SELL 성공 근거로 세지 않는다.

### 5.7 WebSocket 재연결과 늦은 응답

- **유형:** 분리 연결/비동기 UI 위험, fixture 회귀.
- **문제:** 끊긴 동안 개인 이벤트를 놓치고, 옛 소켓/REST 결과가 새 계정·새 route 화면을 덮을 수 있다.
- **선택:** backoff+generation+REST snapshot 복구+eventId dedup+logout 취소. 개인 구독은 JWT/소유권 경계.
- **근거:** `f8ab0eb`, 웹 websocket/recovery/state, STOMP 테스트.
- **한계:** global broker/다중 backend 이벤트 순서·무손실 replay가 아니다. 실제 증권사 공급자 WS의 재연결 문제와 사용자 웹 WS는 별도 연결이다.

### 5.8 Windows checkout CRLF와 SkillRegistryTest

- **유형:** 일반 Windows checkout에서 재현된 테스트 결함.
- **문제:** 제품 registry는 CRLF 정규화 후 hash하지만 테스트의 변조 fixture가 newline 형식에 의존해 예상 거부 경로를 정확히 재현하지 못했다. build resource 수동 정규화 후 조건부 통과 기록도 별도로 있었다.
- **선택:** 테스트 helper에서 LF 정규화, 정상 registry LF/CRLF 두 형식 assertion. 제품 Skill 의미를 바꾸지 않음.
- **검증:** processResources 실제 실행·build의3 Skill CRLF 확인 상태에서 전체377:346PASS/31SKIP/0FAIL, 수동 정규화/제외 없음. [reserve 최종 검증](reserve-reconciliation-final-validation.md).
- **한계:** 최초 실패/조건부 통과를 삭제하지 않고 최종 보완 뒤 통과와 구분. 최신전체35SKIP은 이후 테스트 추가에 따른 다른 분모다.

### 5.9 시간대 컬럼·H2 날짜 경계

- **유형:** 조건부 실제 PG E2E와 H2 회귀에서 발견한 결함/fixture 문제.
- **증거:** PG 신규 의도 시각이 JDBC/SQL cast에서9시간 이동해 만료 assertion 실패. 이후 H2 Faucet 날짜 경계도 session timezone 차이로 실패.
- **선택:** 신규 conditional 시각 컬럼 WITH TIME ZONE, H2·공통 test session UTC. H2에 PG schema를 그대로 쓰려던 시도는 ON CONFLICT 미지원으로 철회. 기존 운영 DB/Faucet 정책을 임의 수정하지 않음.
- **검증:** 실패 fixture 보존→fresh PG/Anvil 및 H2·전체452/418PASS/34SKIP 재검증. [조건부 구현](oracle-conditional-orders-implementation.md).
- **한계:** timezone 표현 오류와 Windows/Docker 물리 clock offset은 다른 원인이다.

### 5.10 Windows/JVM와 Docker/PG clock 차이

- **유형:** Phase A 사전 점검으로 반복 측정된 실제 실행 환경 문제.
- **증거:** Windows/JVM은 서로 비슷하고 Docker/WSL/PG가 수초 앞서는 구간, Linux 벽시계 역행 관측. 왕복 지연·불확실성 포함 preflight가2초 기준을 차단했다.
- **선택:** 제품 미래2초·OracleTTL을 넓히지 않고 읽기 진단. 전역 Docker/WSL restart·NTP 변경을 에이전트가 강행하지 않음. 사용자 Windows 시간 동기화 후 새 clock fixture→preflight→새 업무 E2E.
- **검증:** 사용자 조치 후 clock5+업무4 PASS, 필수5E2E PASS; 이후 최신 별도5PASS도 확보. [Phase A 보고](phase-a-integration-acceptance-report.md) 9~10절.
- **한계:** 최초 Faucet/audit API409의 정확한 estimateGas revert는 확인되지 않았다. 동기화 후409 미재발을 모든 과거409의 시각 원인 확정으로 쓰지 않는다. NTP source/stratum만으로 항상2초 이내를 보장하지 않는다.

### 5.11 Docker subnet overlap / Linux Engine500

- **유형:** 환경 생성 중 실제 장애, 제품 거래 실패와 별개.
- **문제:** 신규 fixture network create의 overlap, 다른 session의 Docker version/image API500.
- **선택:** 기존 네트워크 삭제/변경 없이 overlap stderr에만 최대8개 중복 없는 subnet 후보를 재시도. 권한/일반 오류/timeout 불명확은 즉시 중단. Engine500이면 Server 정상 확인 전 테스트를 실행하지 않음.
- **검증:** Python15/15 + 독립 검토. 정상 Engine 후 새5E2E PASS에서 실제 overlap3회 후보 교체 회복. 소진8/다른 오류 중단은 Mock 근거다. [subnet 보고](phase-a-subnet-retry-validation.md), `72915fd`→`6ed6d5a`.
- **한계:** 기존Engine500의 내부 원인/자동 해결을 확정하지 않는다. 환경 준비 실패를 주문 FAIL로 기록하지 않는다.

### 5.12 Kiwoom reconnect 이후 옛 OPEN 상속

- **유형:** 실제 API 사고가 아니라 독립 코드 검토에서 발견한 잠재 결함.
- **문제:** 새 연결0s가 오지 않았는데 이전 연결의 당일 OPEN이 그대로 유효할 수 있었다.
- **선택:** marketGeneration==connectionGeneration일 때만 시장 상태 사용, G+1 새0s 전 UNKNOWN. 늦은 G 프레임 거부.
- **검증:** Kiwoom Mock18→19, 전체450→451PASS(35SKIP 유지), 새 Synthetic conditional/별도JVM PASS, 재검토 필수 수정 없음. [키움 구현](kiwoom-market-provider-implementation.md).
- **한계:** 실제 장중 REG 후0s 최초 제공 여부는 아직 미확인. 0s time20이 개장시각으로 고정돼 오면 제품5초 조건과 충돌할 가능성이 있지만 **실측 전 가설**이다. native 분봉 timestamp 시작/종료/asOf도 아직 가설 비교 대상.

### 5.13 추가로 설명할 가치가 있는 검토·테스트 사례

- 조건부 준비: 새 quote가 조건에 맞아도 현재 시세가 이미 이탈한 경우를 독립 검토에서 발견. 초기/lock 대기 뒤 현재조건 재검사 및 BUY/SELL1e-8 경계 회귀. 실제 시장 오체결 사고와 구분.
- 조건부 UI: 확정 validation400 뒤 pending key가 남아 재입력 불가. 알려진 확정 거절만 key 해제, 네트워크/5xx/불명확 응답은 key 보존. 성공 여부를 모른 채 새 key를 만들지 않는 원칙.
- 감사: 고아 Quote를 정상 Order에 연결한 것처럼 처리하면 누락. 최종 고아 판정과 coverage를 보완하고 통합 소스에서 재실행.
- AI 관측: usage null 자동 unboxing이 원 provider 오류를 덮는 테스트 발견 결함. 원 오류 보존·best-effort 관측으로 수정. [AI Phase8 평가](../docs/ai/EVALUATION_REPORT.md).
- 진단 도구: 설치된 websockets를 Mock 테스트가 직접 import해 `python -S`에서2ERROR. sys.modules 가짜 모듈로 바꾸고26/26 재실행·독립 재검토. 제품 거래 코드는 바꾸지 않음.

## 6. 기술면접 준비

### 6.1 1분 설명 — 외우기 전에 흐름을 이해

> 모의 원화와 삼성전자 기준 가격 추종 토큰을 거래하는 웹 플랫폼입니다. 사용자 자산은 PostgreSQL 장부에 기록하고 온체인 거래는 operator 통합 지갑으로 실행합니다. 서버가 최신 시세를 기반으로 주문 조건을 EIP-712 서명하고, 사용자는 quoteId만 제출합니다. 입력 자산을 먼저 잠근 뒤 서명 원문과 nonce/hash를 저장하고 전송합니다. receipt와 Vault 이벤트를 확인한 뒤 DB 자산과 체결을 한 번만 반영하고 commit 이후 개인 알림을 보냅니다. 조건부 주문·두 대사·정체 관측을 추가했고, AI는 승인 정책과 read-only 조회 사실을 결합하는 보조 진단입니다. 실제 주식 거래·사용자 지갑·최신 실시장 전체 인수는 별도 범위입니다.

이 설명에는 “제가 전부 직접 구현”이라는 기여 주장을 넣지 않았다. 개인 역할을 덧붙일 때에는 사용자가 승인한 범위 결정과 본인이 실제 수행한 작업을 확인한다.

### 6.2 알아야 할 CS 용어 — 이 코드의 사용 위치

| 용어 | 쉬운 설명 | 프로젝트 사례 |
| --- | --- | --- |
| transaction/원자성 | 여러 변경이 함께 성공/실패 | Faucet 지급 전체, receipt DB 정산, EVM Oracle+Vault |
| MVCC/snapshot | 읽는 동안 하나의 DB 버전을 보기 | ReserveStore/AuditStore REPEATABLE_READ |
| pessimistic lock | 수정할 행을 먼저 잠그기 | quote 소비, balance, Faucet quota/budget |
| optimistic version | 읽은 버전이 여전히 맞는지 검사 | ConditionalOrder의 @Version. 이것만으로 외부 실행 exactly-once 아님 |
| deadlock/lock order | 서로의 잠금을 기다리는 교착 | Faucet quota→budget→balance 순서 고정 |
| idempotency | 같은 작업을 다시 해도 추가 효과 없음 | Faucet 성공 key, 같은 raw 복구, 반복 정산 guard |
| uncertain outcome | 성공인지 실패인지 응답만으론 모름 | sendRaw 응답 유실, Faucet 응답 유실 |
| state machine | 가능한 상태·전이를 제한 | Order/BlockchainTransaction/Conditional 세 모델 |
| eventual consistency | 외부 확정과 DB 반영 사이 지연 | SUBMITTED→receipt→FILLED, UI REST 복구 |
| isolation vs durability | 동시에 섞이지 않기 vs 재시작 후 남기기 | 행 lock vs signed/snapshot persistence |
| backpressure/bounded queue | 처리량을 넘는 요청을 제한 | Tool/Agent worker, observation queue100 |
| timeout vs cancellation | 기다림 종료와 실제 작업 종료는 다름 | AI timeout 후 worker가 계속 점유할 수 있음 |
| authentication/authorization | 신원 확인 vs 그 신원 권한 확인 | JWT 검증 후 DB role·target 소유권 |
| WebSocket/STOMP/SockJS | 연결/메시지 규약/브라우저 fallback | 공개 topic·개인 user queue |
| outbox | DB 변경과 보낼 이벤트를 함께 영속화 | 현재 미도입. AFTER_COMMIT과 다름 |
| UTC/timezone/Clock | 동일 시점과 로컬 달력 표현을 구분 | quote Instant, KST Faucet date, engine test Clock |
| schema migration | 기존 데이터를 보존하며 구조/의미 전환 | UNKNOWN legacy·Faucet 기존 journal 승인 필요 |

### 6.3 블록체인 용어 — 이 프로젝트에서 쓰는 의미

| 용어 | 쉬운 설명 / 실제 용도 |
| --- | --- |
| EVM/contract | 체인이 실행하는 코드. sol 파일이 블록체인 자체는 아님 |
| address | 계정·배포 코드 식별자. DB userId와 다름 |
| private key | 서명 권한. operator 거래 키와 price signer 키를 분리 |
| RPC | 노드에 JSON 요청을 보내는 인터페이스. backend→Anvil |
| chainId | 체인 서명 domain 구분. 같은31337이 같은 state/history를 뜻하지 않음 |
| ABI/calldata | 함수/인수를 bytes로 encode하는 계약. ContractGateway |
| EIP-712 | 타입·domain이 있는 데이터 서명. transaction 서명과 별개 |
| nonce | 주소별 거래 순번. 단일 operator sender 직렬화 |
| raw transaction | 서명된 실행 bytes. DB 선저장·동일 재전송 |
| txHash | raw bytes의 해시. 접수/실행 여부를 조회하는 식별자 |
| gasLimit/gasPrice | 실행량 상한/단가. ETH 비용, mKRW 거래 fee와 다름 |
| receipt | 채굴된 실행 결과/status/block/log. 없음은 실패 확정 아님 |
| event/topic | 계약 실행 중 남긴 log와 분류 hash. 예상 Vault 주소/인수 대조 |
| confirmation/reorg | 포함 후 블록 확인 수 / 체인 canonical 변경 가능성 |
| ERC-20 decimals | 수량을 정수로 표현하는 배율. 토큰18, 가격8과 구분 |
| allowance/approve | Vault가 operator mKRW를 transferFrom할 수 있는 한도 |
| mint/burn | 공급량을 늘려 지급 / 토큰을 없애 공급량 감소 |
| Ownable/minter/consumer | 관리 권한 / mSEC 발행·소각권 / Oracle report 소비권 |
| PoR | 보유자산·부채/소유권 증명을 설계하는 별도 영역. 현재 대사 MATCH의 동의어 아님 |

### 6.4 예상 질문과 코드에 맞는 답변 방향

**Q1. 왜 DB만으로 만들지 않고 블록체인을 썼나요?**

DB-only 모의 거래도 가능하다고 인정한다. 목적은 계약 검증·토큰 이동이라는 별도 실행 근거와 비동기 정산 통합 학습이다. 중앙 operator·signer·로컬 Anvil 신뢰 한계를 먼저 말하고 퍼블릭 탈중앙 보장을 주장하지 않는다. 단순 비용 대비 우수성은 측정하지 않았다.

**Q2. 사용자는 ERC-20을 갖고 있나요?**

아니다. DB의 모의 할당량이며 실제 토큰은 operator/Vault에 있다. 소유권과 정합성은 다른 개념이다. 사용자 주소 필드나 두 원장 합계 일치가 자기 통제 지갑을 뜻하지 않는다.

**Q3. DB commit과 블록체인 transaction은 어떻게 원자화했나요?**

원자화하지 않았다. 각각의 원자성을 이용해 준비 commit→signed commit→외부전송→receipt DB 정산으로 나눈다. 불확실성이 있는 단계에서 같은 raw를 복구하고 자동 보상이 안전한 범위를 제한한다. XA/분산 transaction 구현이라고 말하지 않는다.

**Q4. RPC timeout이면 실패 처리하면 되지 않나요?**

전송은 성공했을 수 있다. SIGNED 이전 실패와 이후 불확실성을 구분해 이후에는 unlock/재주문하지 않고 expectedHash로 조회한다. actual Anvil 전송 후 응답 절단 검증을 설명한다.

**Q5. exactly-once 거래/정산을 보장하나요?**

동일 raw의 동일 txHash·nonce, quote 일회 사용, DB 정산 guard/unique로 중복 효과를 제한한다. 모든 외부 통신·다중서버·이벤트 전달의 exactly-once는 보장하지 않는다. 성공 key replay와 uncertain outcome은 분리한다.

**Q6. Oracle와 현재 가격 차이를 완전히 없앴나요?**

별도 push 상태를 읽는 문제는 없앴다. 충분히 최신의 보고서를 거래 tx 안에서 검증해 그 가격으로 정산한다. 관측→채굴의 시간차와 신뢰할 signer는 남는다. TTL30초는 관측 시각부터다.

**Q7. 사용자 min-output을 서버가 정하면 무슨 보호인가요?**

현재 온체인 가격·fee로 계산한 결과를 서명 필드에 결합해 수수료/출력 감소나 변조를 거부한다. 사용자 지정 slippage UI나 가격 최적화는 아니다. 조건부는 목표가격까지 새 quote 정수 비교로 검증한다.

**Q8. 조건부 주문이 곧 지정가 주문인가요?**

외부 기준 시세가 목표에 도달하면 Vault 실행을 시도하는 조건부 의도다. 주문장·사용자 매칭이 아니고 부분 체결도 없다. fresh quote로 실행하며 잔고/유동성/시장 상태/채굴 때문에 실패할 수 있다.

**Q9. 대기 중 자산을 미리 안 잠근 이유는?**

장기 대기 자산을 예약하지 않는 승인 설계다. 자산 재사용 자유와 체결 시 잔고 부족 가능성의 trade-off를 설명한다. 실행 준비 시 잠금·의도 연결·취소 경합을 원자적으로 처리하고 미확정 실행 뒤에는 취소로 풀지 않는다.

**Q10. 자산 대사가 MATCH면 준비금 충분한가요?**

아니다. baseline 이후 설명 가능한 수량 변화가 맞다는 뜻이다. Faucet은 DB만 늘리고 가격 상승은 Vault 자금을 늘리지 않는다. 유동성은 operator 자산/allowance/ETH와 Vault 지급 자원을 별도로 관찰한다.

**Q11. 거래가 진행 중이면 두 대사가 잘못 MISMATCH를 만들지 않나요?**

DB snapshot/고정 blockHash를 읽고 끝에 source/head/canonical을 재확인한다. 변화/미완료/증거 부족이면 INCONCLUSIVE. busy 상태에서 판정 불가가 많아질 수 있는 보수적 비용을 인정한다.

**Q12. 현재 signer/fee를 조회해 과거 거래를 검증해도 되나요?**

아니다. historical 근거가 필요하다. 감사는 과거 signer·fee가 없는 거래를 현재 설정으로 정상 판정하지 않는다. 동일 블록 내 설정 변경·historical RPC 보장 등의 추가 한계도 밝힌다.

**Q13. 왜 Kafka/Redis/분산 nonce가 없나요?**

현재 단일 backend·bounded polling/simple broker 구조다. 동기 DB lock/단일 sender를 쓰며 distributed support는 미구현이다. 없는 기술의 필요성을 부정하거나 성능 측정 없이 최적 선택이라 단정하지 않는다. scale-out 요구가 생기면 nonce coordinator·event durability·worker ownership을 별도 설계해야 한다.

**Q14. AI가 숫자를 틀리면 어떻게 하나요?**

상태는 read-only Tool에서 받고 facts/citation을 서버 근거와 대조한다. 근거 부족은 PARTIAL/유보, Tool 권한은 JWT/소유권이다. 모델 자유 문장의 진실성을 완벽히 증명하지 못해 rubric과 한계를 기록한다. 실제 과거 대표8개는 작은 fixture 표본이다.

**Q15. RAG에서 왜 Hybrid를 안 썼나요?**

기술 이름보다 동일 golden set 품질을 봤다. threshold·diversity·chunk 개선 K가12/12·MRR0.8819·직접근거12/12, Hybrid는 식별자 이득 대신 golden MRR/직접근거 회귀. 기본은 semantic K, Hybrid는 실험 기록이다.

**Q16. 실제 AI provider 평가를 했나요? 최신도 똑같나요?**

과거 Phase2~8에 실제 embedding/model 평가를 했다. 최신 추가 기능/키움 지식9문서·manifest 갱신 이후에는 paid 재색인·검색 회귀를 하지 않았다. 예전12/12를 최신 corpus 품질로 주장하지 않는다.

**Q17. Mock 통과와 E2E 통과를 어떻게 구분하나요?**

H2/Mock은 계약·정책·deterministic 실패 검증, 실제 PG는 행 lock/time/schema, 실제 Anvil은 서명·채굴·receipt 검증이다. Chrome fixture는 실제 DOM·반응형이지만 vendor/chain 종단간은 아니다. opt-in35SKIP는 PASS에 더하지 않는다.

**Q18. 재시작 복구가 됐는데 왜 Anvil state 유실은 복구 못했나요?**

서버 프로세스 재시작과 chain state 소실은 다른 사건이다. raw/hash가 있어도 원래 배포·storage·nonce·receipt·사용견적 이력을 되살리지 못한다. chainId/주소가 같아도 history 일치가 아니다.

**Q19. 키움은 실제로 연결됐나요?**

현재 소스는 Mock검증·통합됐고 local secret preflight만 완료, broker auth/live 호출0회다. 장중0s 최초 제공·reconnect·분봉/asOf를 승인 후 관찰해야 한다. 신규 키를 넣었다는 사실은 인증/시세 인수 완료가 아니다.

**Q20. 본인이 어떤 코드를 작성했나요?**

AI 지원을 명시하고 본인이 실제 선택·승인·검토·수정·설명할 수 있는 부분을 구체적으로 구분한다. 기능 전체를 독립 수작업 개발했다고 주장하지 않는다. 예시 흐름을 코드 없이 설명하고 테스트의 기대값을 직접 계산해 보는 준비가 필요하다.

### 6.5 면접관이 비판할 수 있는 지점

1. **중앙화:** operator·signer가 강한 신뢰 지점. DB 원장 조작이나 가격 signer compromise에 대한 완전한 외부 검증은 없음.
2. **로컬 체인:** Anvil 인수는 퍼블릭 체인의 finality·운영 보안을 증명하지 않음.
3. **유동성 모델:** DB Faucet·수익이 실제 지급 자금과 독립. 모의서비스 한계, 자동 상환 보증 없음.
4. **단일 서버:** synchronized nonce·single conditional worker. scale-out하면 기존 안전 가정이 깨짐.
5. **정체 종료:** 동일 raw 복구는 중복 방지에 좋지만 ETH/RPC/채굴 문제로 lock이 오래 남을 수 있음. LONG_WAIT는 관측이지 해결이 아님.
6. **cut/coverage 비용:** 안정된 head·DB가 필요한 대사는 활발한 원장에서 INCONCLUSIVE가 많을 수 있음. 큰 원장은 한도를 넘음.
7. **RPC 신뢰·reorg:** bounded canonical 확인만으로 악의적 provider·모든 reorg·historical 상태 문제를 해결하지 못함.
8. **이벤트 durability:** AFTER_COMMIT과 REST 복구는 outbox나 영속 이벤트 재생이 아님.
9. **외부 시세 실측:** Kiwoom 0s와 native봉/asOf는 아직 실측 전. provider 교체가 끝났다고 발표하기에는 인수 필요.
10. **AI 격리/정확성:** DB는 분리하지만 JVM/host shared. 유한 rubric·현재수치 검증만으로 자연어 전체 진실성 보증 불가.
11. **schema/운영:** additive schema init·fresh-ledger activation, legacy UNKNOWN/migration 승인 경계. 대규모 무중단 migration/운영 SLA 실적 없음.
12. **보안·인증 후속:** OAuth/email/refresh·실제 외부 배포 attack surface는 미완료. 테스트 canary·code review는 전문 보안 감사와 다름.

방어적으로 “다 해결했다”고 답하지 말고 지금의 범위·보호장치·남은 가정·다음 승인 조건을 설명한다. 제한을 의도적으로 채택한 것과 미완료를 혼동하지 않는다.

## 7. 검증 상태를 정확하게 읽는 법

### 7.1 실행별 대표 증거

| 증거 시점·대상 | 실제 기록 결과 | 의미/한계 |
| --- | --- | --- |
| reserve 최종 회귀 | backend377 total/346PASS/31SKIP, 실제 PG/Anvil 별도 macro PASS | Windows CRLF 정상, PG cut 경쟁. 현재 전체35SKIP 분모와 다름 |
| Faucet 통합 | backend436/403PASS/33SKIP, web66/forge36, 실제 Faucet suite9/9·audit/reserve79PASS/1SKIP | suite 내 단위/H2/실제 macro를 다시 고유 총수에 합산하지 않음 |
| 조건부 통합 `d8dea3e` | backend452/418PASS/34SKIP, web77/forge36, fresh H214+실제 macro1 PASS | 시세Synthetic, 실장/Android/paidAI 아님 |
| 관측 통합 `7bf459c` | backend462/427PASS/35SKIP, web84/forge36, 새 conditional macro1+별도JVM1 PASS | 실제ETH부족·장시간RPC장애 미검증 |
| 키움 통합 `cffe471` | backend486/451PASS/35SKIP, web84/forge36/Chrome fixture PASS | 실제PG 추가검증은 Faucet1PASS 뒤subnet BLOCKED, 다른3NOT_RUN인 당시 이력 |
| 최신 Phase A 입력 `a317f67` | Faucet/reserve/audit/conditional·observation/별도JVM **5/5PASS, FAIL/ERROR/SKIP0** | 신규4업무fixture, Synthetic·AI disabled. broker 실환경 아님 |
| 진단 통합 `3ca4945` | stdlib진단26/26·기존PhaseAguard15/15·CLI Mock replay PASS | 실제HTTPS/WSS·인증0, backend/web/forge/E2E 새 반복 없음 |
| AI Phase3 K | goldenhit12/12, MRR0.8819, 직접근거12/12, 무관오답0/8 | 과거 corpus의 고정소규모 비교. 최신9문서 결과 아님 |
| AI Phase8 실제provider | 대표8개 기대결과8/8, ANSWERED2/PARTIAL4/안전거부2 | model/embedding/AI DB 실제, 상태/receipt H2fixture. 실운영 정확도100% 아님 |

**최신 통합 코드의 제품 파일은 마지막5종 검증 뒤 standalone 진단·문서만 추가됐지만**, 그 사실이 위 HEAD에서 제품 전체를 다시 실행했다는 의미는 아니다. 현재 문서는 기존 보고서/XML 요약·소스 assertion을 조사한 것으로 원본 private runtime 증거를 재개봉하지 않았다.

근거: [reserve 최종](reserve-reconciliation-final-validation.md), [Faucet](faucet-policy-implementation.md), [조건부](oracle-conditional-orders-implementation.md), [관측](transaction-stall-observability-implementation.md), [키움 최종](kiwoom-transition-final-report.md), [최신 Phase A](phase-a-subnet-retry-validation.md), [AI 평가](../docs/ai/EVALUATION_REPORT.md).

### 7.2 독립 검토를 과장하지 않기

- reserve 최종: 검토자 코드·PG/Anvil 경합 구조·CRLF·문서 확인, 웹7개 직접 실행. 실제PG 전체E2E/전체Gradle/forge/Chrome은 구현자 실행 근거.
- 조건부: 첫 검토 필수2개 발견→수정→재검토 필수 없음. 검토자 웹패널11개 직접 실행·XML 대조, DB/RPC 직접 실행 아님.
- 관측: 신규 웹6개 직접 실행·코드/전체XML 대조, 실제 fixture/runtime 미접근.
- 키움: 세대 OPEN 결함 지적→수정→재검토 필수 없음, 당시 broker/테스트 독립 재실행 없음.
- subnet: 검토자Python15개 직접 실행. 최신5종은 안전JSON/XML 증거 대조, Docker·실제E2E를 별도로 재실행한 것은 아님.
- AI Phase8: 검토자 격리34개 직접 실행·문서/raw 평가 대조. 유료provider/전체334/forge/web 독립 재실행 아님.
- standalone 진단: 첫 stdlib test 의존성 결함→수정→첫 재검토 필수 없음, 검토자`python -S`26+15+Mock 직접 실행. 실credential/실API/Windows ACL 생성 검증은 아님.

이번 **학습 문서 작성**은 코드 동작 변경이 없는 조사라 새 별도 검토자를 호출하지 않았다. 기존 독립 검토 기록을 소개한 것이며 이 문서 자체의 독립 검토 완료를 주장하지 않는다.

### 7.3 미구현과 미검증을 따로 기억

**구현되어 있고 격리 검증이 있으나 실환경 인수 남음:** 최신 기능의 실제 Toss 장중 BUY/SELL/조건부→Oracle/Anvil/DB/개인WS/포트폴리오, 실제Kiwoom LOGIN/REG/0s/0B/native봉/asOf·재연결, 최신 AI corpus 재색인/검색평가·실제 거래결과 Agent/Skill/자동 MATCH·ADMIN, 실제 부족ETH·장기RPC/프로세스kill·Linuxcloud부하/장애/복원.

**코드 준비/문서만 있고 운영 완료 아님:** production Compose/image/proxy·secret·backup/restore 로컬검증, AWS 생성·도메인/HTTPS외부 운영, Android 계약/기기 검증. 최신 전체 최종인수 PhaseD PASS 선언은 없음.

**미구현/공식 제외:** Google/email/refresh, distributed nonce, Merkle PoR, Sepolia 고정공급 variant(공급 S0/operator O0/Vault V0 미확정), 사용자지갑/직접서명/CustodyVault/호가매칭/부분체결/시간예약/실제 금융거래. 사용자 지갑과 호가는 A모델 확정으로 현재 범위 밖이며 “다음에 꼭 구현해야 하는 미완료”로 표현하지 않는다.

### 7.4 과거 문서와 최신 코드가 충돌하는 곳

기존 문서는 이번에 고치지 않았으며 충돌을 여기에 적는다.

| 기존 문서·위치 | 과거 문구 | 현재 사실 |
| --- | --- | --- |
| career-project-brief 7·8.2·12.3/12 | 즉시거래만, faucet/자산대사/거래전수 미구현 | 10-09~12 추가 기능은 구현·통합·격리검증 완료. 실제실장/운영검증 한계는 여전히 남음 |
| 구현 계획 5.1/7/9.4 | 사용자별 지갑 후보·사용자 토큰 이동·push price | 상단A 확정과 현행 operator/서명보고서 우선. 원 목표/옛 해설은 역사로 읽기 |
| Oracle 설계 1·9~13 | 현재 push 구조·미확정 항목·후속 연동 대기 | 해당 당시 기록. 14절/실제코드에 quoteId/새ABI/push신규제출 제거 완료 |
| PriceOracle 주석/updatePrice | owner가 최신 가격을 갱신 | 함수는 존재하지만 현재 Vault 체결은 report 소비 가격. updatePrice만으로 unsigned 거래 못 함 |
| JwtTokenProvider TODO | SecurityFilterChain 연결 예정 | 실제 SecurityConfig가 필터를 등록하고 인증 경로 동작 |
| backendREADME AI 구간 | AI UI 없음/Phase8 승인대기 | 제품웹 main/diagnosis에 질문·ADMIN진단UI 있음. 당시Phase 보고와 사용자후속 승인 기록을 함께 읽기 |
| AI_ARCHITECTURE/EVALUATION/RAG_KNOWLEDGE_GUIDE | 과거지식/청크수·evaluation 결과 | 5e39dbf에서9문서hash/version 갱신. 새색인/실모델검색평가는 미실행. 전역최신평가처럼 쓰지 않기 |
| README/implementation-log | Faucet설계대기·지갑확장후보·PhaseA BLOCKED 등 | 여러시점 이력이 공존. 뒤 완료기록과Git/source를 우선하고 실패이력은 유지 |
| asset-custody-final-check 3 | SIGNED정체관측 후속후보 | e489b3c로관측 구현. 자동 안전종결/충전은 미구현 그대로 |
| sepolia 옛 대안 목록 | 발행variant 선택대기 | A최초고정공급 방향확정. 수치/구현/배포는 미승인 |
| Kiwoom provider/preparation 제목 | 진행중/실제키없음 | Mock통합완료,240cb39 local real credential검사. broker인증/시세호출은0 |
| synthetic 설계 선택보완 | 최초API실패시 정적표시 미구현 | 배포준비 public빌드정적표시 후속 보강. 원본HTML hidden과배포경로 구분 |

**면접용 자료의 중요한 교정:** 10월8일 경력 브리프 이후 추가된 두 대사/Faucet/조건부/관측/시장공통정책/Kiwoom을 반영하되, 최신 실환경 완료로 승격시키지 않는다. 과거 문서 문장 하나만 복사하지 말고 이 표와 실제 소스로 확인한다.

## 8. 코드 읽기·면접 연습 과제와 근거 지도

### 8.1 직접 설명하며 확인할 과제

1. 온체인 준비에서 UserBalance amount100/locked30일 때 BUY80이 가능한지 계산한다. available70이므로 안 된다. lock과 차감의 차이를 말한다.
2. BUY750,000·price75,000·fee10bps 결과9.99를 손으로 계산하고 wei/priceE8 정수식을 따라간다.
3. 가격 보고서가 관측 t0에 발급되고 RPC가10초 걸려 실행될 때 TTL이 발급 HTTP 시간이 아닌 observedAt+30이라는 점을 말한다.
4. prepare 성공→estimate 실패 / saveSigned 성공→응답 유실 / receipt 실패 / event 불일치의 각 상태·lock을 그린다.
5. recovery에서 새 nonce를 쓰지 않는 이유를 raw bytes/hash 연결로 설명한다.
6. AFTER_COMMIT가 rollback ghost 알림은 막지만 commit 뒤 유실은 못 막는 것을 설명한다.
7. Faucet100만을 받기 전후 operator 온체인 잔고가 왜 같은지 말한다. 두 대사식에 counter를 더하지 않는다.
8. 조건부 WAITING 중 다른 거래로 잔고가 없어지면 발생할 일, EXECUTING 뒤 취소를 거절하는 이유를 말한다.
9. 자산 MATCH·거래 개별 MATCH·전체 coverage 불완전·유동성 부족이 동시에 가능한 예를 만든다.
10. USER가 타인 quoteId와 ADMIN Skill을 보내면 principal/target/registry 각 경계에서 어떻게 막는지 소스를 따라간다.
11. RAG hit12/12와 답변 정확도100%의 차이, 최신 corpus가 다르면 재평가가 필요한 이유를 말한다.
12. localhost Anvil에 같은 chainId/같은 주소로 재배포해도 기존 DB txHash와 일치하지 않는 이유를 말한다.

코드를 읽다가 확인해야 할 사항은 기능을 추측해 채우지 말고 별도 질문으로 남긴다. 이 과제는 학습용이며 실제 주문/환경 변경을 실행하라는 지시가 아니다.

### 8.2 다음 코드 읽기 순서

| 순서 | 파일 / 확인할 핵심 |
| --- | --- |
| 1 | AuthService → JwtAuthenticationFilter → SecurityConfig: 주체와 권한 |
| 2 | UserBalance → WalletService → FaucetService/FaucetStore: 잠금과 지급 정본 |
| 3 | MarketPriceService → MarketDataEligibility → PriceReportIssuer: 일반조회 vs 신규서명 품질 |
| 4 | QuoteController → PriceQuoteService → OnchainOrderPreparationService: 사용자견적 소비/원자준비 |
| 5 | PriceReportTypes/PriceOracle/ExchangeVault → ContractGateway: 동일타입·실제executor·원자정산 |
| 6 | BlockchainTransactionSender → Persistence: 어떤시점에서 복구증거가durable인가 |
| 7 | BlockchainReconciliationService → ContractEventParser → OnchainSettlementService: 성공근거·멱등성·검토격리 |
| 8 | WebSocketAuthInterceptor/DeliveryListener → 웹websocket/recovery/state: 개인격리·commit·늦은응답 |
| 9 | ConditionalOrderService/Worker: 장기의도 vs 단기실행 |
| 10 | ReserveService/Calculator → AuditService/Validator: 수량 vs 개별실행·coverage |
| 11 | ObservationRecorder/Store/Service: 업무와best-effort관측 분리 |
| 12 | SyntheticMarketEngine/SimulatedPriceProvider → KiwoomProtocol/Provider: 결정적모의 vs vendor실측 |
| 13 | KnowledgeLoader/AuthorizedKnowledgeRetrieval → ToolDispatcher → AgentService → SkillRunner → DiagnosisCoordinator: 규칙·사실·해석 경계 |
| 14 | ops.py/phase-a.py와 관련 테스트: 복원·격리·시각·증거 보존 |

### 8.3 근거 문서의 역할

- [구현 계획](../구현%20계획.md): 목표/초기제안과후속결정. 지금의모든완료기능을자동열거하는파일은아님.
- [구현 로그](implementation-log.md): 단계별설계·테스트·검토·한계의누적이력. 같은테스트의여러실행수치를합치지않기.
- [백엔드-체인 학습](backend-blockchain-learning-guide.md): 초기실행흐름과정본설명,후속기능은본마스터와함께읽기.
- [Oracle 설계](oracle-price-execution-design.md): push문제→서명가격원자화,역사구간과완료구간구분.
- [보관 모델 비교](user-wallet-custody-architecture-review.md)/[최종 점검](asset-custody-final-check.md): A선택과실제유동성/소유권한계.
- [reserve 구현](reserve-reconciliation-implementation.md)/[최종 보완](reserve-reconciliation-final-validation.md): 불변기준점·수량식·실제cut경합·31skip이력.
- [거래 감사](trade-full-audit-implementation.md): 개별판정/전체coverage·고아Quote최종통합.
- [Faucet](faucet-policy-implementation.md)/[Android 계약](faucet-android-integration.md): 지급정책·멱등성·동시성·연동영향.
- [조건부](oracle-conditional-orders-implementation.md)/[Android 계약](conditional-orders-android-integration.md): 상태/새quote/취소·복구·실행별결과.
- [정체 관측](transaction-stall-observability-implementation.md): latest완료시도·LONG_WAIT·관측한계.
- [Synthetic](synthetic-market-design.md)/[배포 준비](deployment-preparation.md)/[복원 검증](validation-2026-10-07.md): 실제데이터아닌합성,로컬운영검증.
- [개발체인 조사](development-chain-state-audit.md)/[격리 acceptance](isolated-acceptance-baseline.md): 옛자료와새환경의관계. 지금조사에서기존state를다시조회하지않음.
- [AI 아키텍처](../docs/ai/AI_ARCHITECTURE.md)/[평가](../docs/ai/EVALUATION_REPORT.md)/[검색 실험](../docs/ai/phase-3-retrieval.md)/[보안](../docs/ai/TOOL_SECURITY_MODEL.md): 실제근거·권한·표본·모델사용량과한계.
- [최종 인수 계획](final-integration-acceptance-plan.md)/[PhaseA 보고](phase-a-integration-acceptance-report.md)/[최신5종](phase-a-subnet-retry-validation.md): 무과금Fixture vs실시세/paidAI vs최종판정.
- [키움 구현](kiwoom-market-provider-implementation.md)/[최종 통합](kiwoom-transition-final-report.md)/[읽기진단](kiwoom-readonly-diagnostics.md): Mock완료와real접속대기,지식9문서최신화/paidindex미실행.
- [AI 개발 경험](ai-development-experience.md): 에이전트/사람역할,승인·검토하네스와실제사례.

### 8.4 출처 재확인 방법 — 모두 읽기 전용

```text
git log --reverse --date=short --format="%h %ad %s"
git show --stat <해당 commit>
git show <과거 commit>:<파일 경로>
git diff <과거 commit> <기준 commit> -- <대상 파일>
rg "핵심 함수|상태명|정책값" backend/src/main contracts/src
rg --files backend/src/test contracts/test tools/websocket-test-client/test
```

Git 조회는 해당 worktree에서 수행한다. 권한 문제 해결을 위해 전역 설정을 임의 변경하지 않는다. 테스트/배포 명령은 README/RUNBOOK의 격리·opt-in 전제를 먼저 이해한 뒤 별도 승인 범위에서만 실행한다.

### 8.5 이번 문서의 조사·작성 한계

- Git 이력·관련 소스/테스트 경로·설계/완료/검토 기록을 대조했다. 저장소의 모든 라인을 완전 감사했다는 주장은 아니다.
- 최초 HWP 원문·팀원 역할·개인 직접 작성 범위·발표/실제 사용자 성과는 확인 필요다.
- 기존 private runtime/credentials/DB/Anvil/state에 접속하거나 실행 증거를 새로 생성하지 않았다. 기존 보고서의 수치를 이번 테스트 실행으로 바꾸지 않았다.
- 모델/증권사/API의 현재 약관·가격·상품사양을 새로 인터넷 조회하지 않았다. 여기에 쓰인 공급자·모델 내용은 저장소 설정과 당시 기록이며 실제 접속 권한·유료 비용은 향후 인수 승인에서 확인한다.
- 이번 수정은 이 학습 파일1개뿐이다. 기존 경력 브리프/README/AI 문서의 오래된 표현은7.4에 차이를 남기고 원문을 보존했다.

이 문서를 읽은 뒤의 목표는 멋진 문장을 외우는 것이 아니라, **한 사용자의 입력에서 어떤 정본·잠금·서명이 만들어지고 어느 증거에서 확정되는지, 실패했을 때 무엇을 알고 무엇을 모르는지를 코드로 설명하는 것**이다.
