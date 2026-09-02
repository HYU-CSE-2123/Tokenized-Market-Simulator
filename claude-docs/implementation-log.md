# 구현 로그 — Phase 0: 모노레포 뼈대

> 단일 출처: 리포 루트 `구현 계획.md`. 본 문서는 **실제로 구축된 코드 상태**를 기록한다.
> 작성: 2026-06-22 (Phase 0 모노레포 뼈대 완료 시점)

## 요약
세 모듈(contracts / backend / android)의 **컴파일 가능한 보일러플레이트**를 구축. 세 모듈 모두 빌드·테스트 통과. 핵심 기능 일부는 실제 동작하고, 나머지는 Phase별 `TODO`/`UnsupportedOperationException`/`TODO()`로 표시.

| 모듈 | 검증 명령 | 상태 |
| --- | --- | --- |
| contracts | `forge test -vv` | ✅ 6개 테스트 통과 |
| backend | `./gradlew test` | ✅ 컨텍스트 로드 통과(H2) |
| android | `./gradlew assembleDebug` | ✅ APK 생성, 경고 0 |

---

## 1. contracts/ (Foundry)

### 구현된 컨트랙트 (`src/`)
- **MockKRW.sol** — ERC-20(mKRW) + `faucet()`(1,000,000 지급, owner가 `setFaucetAmount`). OZ `ERC20`/`Ownable` 상속.
- **SamsungPriceTrackingToken.sol** — ERC-20(mSEC) + `mint`/`burn`. `minter`(ExchangeVault)만 호출 가능(`onlyMinter`), owner가 `setMinter`.
- **PriceOracle.sol** — `priceE8`(1e8 정밀도), `updatedAt`, `updatePrice`(onlyOwner), `getPrice`. 0 가격 거부.
- **ExchangeVault.sol** — 핵심 정산:
  - `buy(krwAmount)`: mKRW 전액 수취(수수료 포함, Vault 유동성 적립) → mSEC mint
  - `sell(tokenAmount)`: mSEC burn → 수수료 차감 후 mKRW 지급. Vault 유동성 부족 시 `InsufficientLiquidity` revert
  - `quoteBuy`/`quoteSell`(view), `feeBps`(기본 10=0.1%, `setFeeBps` 최대 1000), `Bought`/`Sold` 이벤트
  - `nonReentrant`, `SafeERC20` 사용

### 배포/테스트
- `script/Deploy.s.sol` — 4개 배포 + Vault를 mSEC minter 등록 + 초기가 75,000원
- `test/Exchange.t.sol` — 6개: faucet, oracle, buy mint, **full 매수→가격상승(75k→80k)→매도→잔고증가**, 가격변경→수량변화, 유동성부족 revert. 잔고 시드는 `deal` 치트코드 사용(MockKRW는 초기 발행 없음).

### 의존성
`lib/`(gitignore): OpenZeppelin **v5.1.0**, forge-std. solc **0.8.24**.

### 계산식 (mKRW·mSEC 모두 18 decimals)
- 매수: `tokenOut = (krwAmount - fee) * 1e8 / priceE8`
- 매도: `krwOut  = (tokenAmount * priceE8 / 1e8) - fee`
- 검증 예: 750,000 mKRW @75,000 → 10 mSEC / 10 mSEC @80,000 → 800,000 mKRW

---

## 2. backend/ (Spring Boot 3.3.4, Java 21)

패키지 `com.pricetrack.exchange` (Gradle, `./gradlew`).

### 실제 동작
- **common/HealthController** — `GET /api/health` → `{status:UP,...}`
- **market/MarketController** — `GET /api/markets`, `/api/markets/{symbol}`
- **market/PriceSimulator** — `@Scheduled` 1초 주기, 75,000원 초기·±0.3% 변동, `/topic/markets/mSEC/price` 브로드캐스트(기획서 §8.1)
- **quote/QuoteController** — `POST /api/quotes/buy|sell` 실제 계산(수수료 0.1%, 컨트랙트 feeBps와 일치)
- **websocket/WebSocketConfig** — STOMP `/ws` 엔드포인트, `/topic`·`/queue`·`/user` 브로커(§13)
- **common/config/SecurityConfig** — stateless, csrf off, MVP 단계 permitAll
- **auth/JwtTokenProvider** — jjwt 토큰 발급/검증(동작), 필터 연결은 미완
- **user/User + UserRepository** — JPA 엔티티(users 테이블)

### 스텁(미구현, Phase 표기)
- auth/AuthController(signup/login/me), order/OrderController, portfolio/PortfolioController → `501 UnsupportedOperationException`
- wallet/WalletService, trade/TradeService, blockchain/BlockchainService(web3j `latestBlockNumber`만), blockchain/BlockchainProperties(`app.blockchain.*` 바인딩)

### 리소스
- `application.yml` — Postgres(env override), JWT, 가격주기, `app.blockchain.*`(컨트랙트 주소). `ddl-auto: none` + `schema.sql` 실행
- `schema.sql` — §11 DDL 6개 테이블(users/assets/price_ticks/orders/trades/blockchain_transactions) + 초기 자산(mKRW/mSEC)
- `src/test/resources/application.yml` — 테스트는 **H2 인메모리**(ddl-auto create-drop, schema.sql 비활성) → Postgres 없이 `./gradlew build` 통과
- order/OrderStatus enum: REQUESTED→PENDING_ONCHAIN→FILLED/FAILED/CANCELED (§18.1)

### 주요 의존성
spring-boot-starter web/security/data-jpa/websocket/validation, postgresql, **web3j 4.12.2**, **jjwt 0.12.6**, lombok. test: H2.

---

## 3. android/ (Kotlin, Jetpack Compose)

패키지 `com.pricetrack.exchange`. AGP **8.7.2**, Kotlin **2.0.21**, compileSdk **35**, minSdk 26.

### presentation (실행 가능, placeholder UI)
- `MainActivity` → `AppNavigation`(NavHost) → Login/Signup/Market/Trade/Portfolio/History/MyPage
- 하단 탭 네비게이션(Market·Trade·Portfolio·History·MyPage), Login에서 진입
- 각 화면 §14 UI 요소를 placeholder 텍스트로 표기, Trade는 매수/매도 탭 구성
- `theme/PriceTrackTheme`(Material3)

### domain
- `model/Models.kt` — Market, PriceTick, Quote, Order, Trade, Portfolio(금액은 String) + OrderSide/OrderStatus enum
- `repository/` — Auth/Market/Order/Portfolio 인터페이스(Flow 사용)
- `usecase/` — GetMarket/ObservePrice/GetBuyQuote/BuyToken/SellToken/GetPortfolio

### data
- `remote/ApiServices.kt` — Retrofit Auth/Market/Quote/Order/Portfolio + `@Serializable` DTO
- `remote/NetworkModule.kt` — Retrofit/OkHttp + kotlinx-serialization 컨버터, `BuildConfig.BASE_URL`(에뮬 `10.0.2.2:8080`)
- `websocket/SocketClients.kt` — Market/Order STOMP 클라이언트 스텁(`emptyFlow`)
- `repository/RepositoryImpls.kt` — Auth/Market은 매핑 구현, Order/Portfolio는 `TODO()`(Phase 5)

### 주요 의존성
compose-bom 2024.10.00, material3 + icons-extended, navigation-compose, lifecycle-viewmodel-compose, retrofit 2.11 + okhttp 4.12 + kotlinx-serialization 1.7.3, datastore.

> `local.properties`(gitignore)에 `sdk.dir` 필요.

---

## 4. 인프라 / 루트
- `docker-compose.yml` — postgres:16(healthcheck) + anvil(foundry). **한글 경로 때문에 `docker compose -p exchange` 로 프로젝트명 명시 필수.**
- `README.md` — 구조·실행법·면책 문구(§19.1)
- `.gitignore` — HWP 제안서, OS/env (빌드 산출물은 모듈별 .gitignore)

---

## 5. 환경/도구 (이 PC 기준)
- JDK 21: `brew install openjdk@21` → `/opt/homebrew/opt/openjdk@21`. 기본 JDK는 17이라 **빌드 시 `JAVA_HOME` 지정 필요**.
- Foundry: `brew install foundry`(`foundry.paradigm.sh` DNS 차단으로 공식 설치 스크립트 불가) → `/opt/homebrew/bin/{forge,anvil,cast}` v1.7.1
- Gradle: `brew install gradle`(wrapper 생성용). 각 모듈은 wrapper 8.10.2 사용.
- Android SDK: `~/Library/Android/sdk`, platform-35.

## 6. 임의 결정값 (기획서 미명시 — 변경 시 합의 필요)
- 수수료율 **0.1%(10 bps)** — §12.3 예시에서 역산, 컨트랙트·백엔드 동일
- JWT 만료 **1시간**, 가격 시뮬레이터 주기 **1초**(§8.1 "1초 또는 3초")
- OZ v5.1.0 / solc 0.8.24 / Spring Boot 3.3.4 / AGP 8.7.2·Kotlin 2.0.21·compileSdk 35

## 7. 미검증 항목
- **Docker 데몬 미실행**으로 `docker compose up` + 백엔드 `bootRun`(실 Postgres)은 미검증. `./gradlew build`의 H2 컨텍스트 테스트로 앱 wiring은 검증됨.

---

# Phase 0.5: 최소 동작 검증 (온체인) — 완료

> 작성: 2026-06-22. 기획서 §0.5 검증 시나리오를 **실제 로컬체인(Anvil)에서** 실행하여 가격 추종 매수/매도 구조의 기술적 타당성을 입증.

## 추가된 것
- `contracts/script/Scenario.s.sol` — 배포 + Vault 유동성 시드 + 사용자 매수 → 오라클 75k→80k → 매도 → 잔고 검증을 한 번에 broadcast. `require()`로 §0.5 성공 기준 강제. Anvil 기본 계정 #0(owner)/#1(user) 사용(env override 가능).

## 실행 방법
```bash
cd contracts
anvil &                                              # 로컬체인(31337)
forge script script/Scenario.s.sol --rpc-url local --broadcast
```

## 검증 결과 (온체인 실행 + cast 독립 조회)
- 배포: MockKRW / mSEC / PriceOracle / ExchangeVault 4개 — `ONCHAIN EXECUTION COMPLETE & SUCCESSFUL`
- 매수(750,000 mKRW @75k): user mSEC = **9.99**(~10 ✓), mKRW 1,000,000→250,000
- 가격 75k→80k 변경 후 매도: user mSEC = **0**, 수령 mKRW = **798,400.8**(~800k ✓)
- user 최종 mKRW = **1,048,400.8** — 매수 직전(1,000,000) 대비 **+48,400**(가격 상승분 반영 ✓)
- cast 독립 조회: Vault 코드 2569바이트 배포, oracle priceE8 = 8e12(=80,000e8), feeBps 10, user mSEC 0
- **성공 기준 충족**: buy 시 mKRW↓·mSEC↑, sell 시 mSEC↓·mKRW↑, 가격 변경이 정산에 반영, 전체 require 통과

> 명제 입증: "오라클 기준 가격에 따라 ERC-20 price-tracking token을 매수·매도할 수 있다" → 이후 백엔드/DB/WebSocket/Android 연동의 토대 확보.

## 다음 단계 (기획서 §16)
Phase 1 컨트랙트 마감 → Phase 2 백엔드 API(mock) → Phase 3 web3j 연동 → Phase 4 WebSocket → Phase 5 Android → Phase 6 시연/문서화.

---

# Phase 1: 스마트 컨트랙트 마감 — 완료

> 작성 및 검증: 2026-08-21

## 구현
- `ExchangeVault` 생성자에서 mKRW, mSEC, PriceOracle의 0 주소를 거부하도록 `InvalidAddress` 오류를 추가했다.
- `SamsungPriceTrackingToken.setMinter`에서 0 주소를 거부하도록 `InvalidMinter` 오류를 추가했다.
- 기존 핵심 거래 테스트 6개를 유지하고 접근 제어, 0 입력, 수수료 상한, 이벤트, 잘못된 배포 주소 테스트를 추가했다.
- 매수·매도 견적 계산식에 대해 각각 256회 입력을 생성하는 fuzz 테스트를 추가했다.

## 테스트 범위
- 핵심 흐름: faucet, 오라클 가격, 매수 mint, 가격 상승 후 전량 매도, 가격별 수량 변화, Vault 유동성 부족
- 접근 제어: 오라클 owner, mSEC minter/owner, Vault owner 수수료 설정
- 입력 경계: 0 가격, 0 매수·매도, 0 주소 의존성/minter, 최대 수수료 10%와 초과 거부
- 이벤트: `FaucetClaimed`, `PriceUpdated`, `MinterUpdated`, `FeeBpsUpdated`, `Bought`, `Sold`
- fuzz: `quoteBuy`, `quoteSell` 수수료 및 결과 수량 계산식

## 검증 환경
- Windows 11
- Foundry **1.5.1-stable**
- solc **0.8.24**
- OpenZeppelin Contracts **v5.1.0**
- Anvil chain ID **31337**

## 검증 결과
```text
forge test -vv
20 passed, 0 failed, 0 skipped
fuzz: quoteBuy 256 runs, quoteSell 256 runs
```

- `Deploy.s.sol` 독립 broadcast: 컨트랙트 4종 배포 및 mSEC minter 연결 성공
- `Scenario.s.sol` broadcast: `ONCHAIN EXECUTION COMPLETE & SUCCESSFUL`
- 750,000 mKRW @75,000 매수 → 9.99 mSEC
- 오라클 80,000으로 갱신 후 전량 매도 → 798,400.8 mKRW 수령
- 사용자 최종 잔고 1,048,400.8 mKRW, mSEC 0

## 참고
- `forge fmt --check`는 기존 Solidity 파일의 CRLF 줄바꿈을 모두 LF로 바꾸는 차이를 보고했다. 기능과 무관한 전면 줄바꿈 변경을 피하기 위해 이번 작업에서는 적용하지 않았다.
- Foundry와 테스트 의존성은 `contracts/lib/`에 설치되며 해당 디렉터리는 Git에서 제외된다.

## 다음 단계
- Phase 2 백엔드 기본 API: 인증/JWT → JPA 도메인 → 가격·견적 → DB 기반 mock 주문·체결·포트폴리오 순서로 진행한다.

---

# Phase 2.1-A: 자체 로그인과 JWT 인증 — 완료

> 구현 및 검증: 2026-08-25

## 구현

- 자체 회원가입 입력을 `loginId`, `password`, `nickname`으로 확정했다.
- 로그인 아이디는 trim 및 소문자 정규화하며, BCrypt로 비밀번호를 해시해 저장한다.
- 회원가입과 로그인 성공 시 1시간 유효한 JWT 액세스 토큰을 발급한다.
- Bearer JWT 인증 필터를 Spring Security 체인에 연결하고 `GET /api/me`를 보호한다.
- 중복 아이디, 잘못된 자격 증명, 잘못된 토큰, 입력 검증 오류를 일관된 JSON 형식으로 응답한다.
- `users`에 향후 기능용 nullable `email`, `email_verified`, `google_sub`를 준비했다.

## 결정

- 이번 범위에서는 이메일을 받지 않으며 자체 계정과 Google 계정을 자동 연결하지 않는다.
- Google OAuth 및 이메일 검증이 구현되기 전에는 이메일 동일성만으로 계정을 연결하지 않는다.
- 리프레시 토큰은 이번 범위에서 제외하고 액세스 토큰만 사용한다.

## 검증

```text
cd backend
.\gradlew.bat test --no-daemon
13 passed, 0 failed
```

- 단위 테스트: 회원가입 정규화·BCrypt 저장, 중복 아이디, 로그인 성공·실패, JWT 생성·검증·만료·서명 오류
- 통합 테스트: 회원가입 → 인증된 내 정보 조회 → 로그인, 입력 검증, 중복 아이디, 잘못된 비밀번호와 토큰, 무인증 접근 차단

## 남은 작업

- Phase 2 mock 주문·체결·포트폴리오
- Google OAuth 및 검증된 이메일 기반의 명시적 계정 연결
- 이메일 인증과 리프레시 토큰 정책

---

# Phase 2.2: DB 기반 모의 거래 — 완료

> 구현 및 검증: 2026-08-26

## 구현

- 가입 시 사용자별 `mKRW`, `mSEC` 잔고 행을 생성하며, 기존 사용자는 `schema.sql` 기동 시 누락 잔고를 보완한다.
- `POST /api/wallet/faucet`으로 호출당 1,000,000 mKRW를 지급한다.
- 현재 `PriceSimulator` 가격과 컨트랙트와 동일한 0.1% 수수료를 사용해 DB에서 매수·매도를 즉시 체결한다.
- 주문, 체결, 잔고 변경을 하나의 트랜잭션으로 처리하고 잔고 행에 비관적 쓰기 잠금을 적용한다.
- 성공 주문은 `FILLED`, 잔고 부족 주문은 잔고·체결 변경 없이 `FAILED`로 기록한다.
- 주문·체결 내역과 mKRW/mSEC 잔고, 평균매수가, 평가금액, 미실현손익을 조회한다.
- 다른 사용자의 주문은 존재 여부가 노출되지 않도록 404로 응답한다.

## API

- `POST /api/wallet/faucet`
- `POST /api/orders/buy`, `POST /api/orders/sell`
- `GET /api/orders`, `GET /api/orders/{orderId}`
- `GET /api/trades`
- `GET /api/portfolio`

모든 API는 JWT 인증이 필요하다.

## 검증

```text
cd backend
.\gradlew.bat test --no-daemon
19 passed, 0 failed
```

- 계산 단위 테스트: 0.1% 수수료가 적용된 매수·매도 수량
- 통합 테스트: 회원가입 → faucet → 매수 → 포트폴리오 → 전량 매도 → 주문·체결 조회
- 예외 테스트: 잔고 부족의 `FAILED` 기록과 체결 미생성, 0 수량, 미지원 심볼, 다른 사용자 주문 차단

## 다음 작업

- Phase 2 마감 점검 및 PostgreSQL 실제 기동 검증
- Phase 3 web3j를 통한 주문의 온체인 비동기 처리
- Google OAuth·이메일 인증·리프레시 토큰은 별도 승인 후 구현

---

# Phase 2 마감 점검: PostgreSQL 실환경 검증 — 완료

> 검증: 2026-08-26

## 환경

- Docker Desktop
- PostgreSQL `postgres:16` 컨테이너 `exchange-postgres`
- DB/사용자: `exchange` / `exchange`
- 포트: `localhost:5432`
- 영속 볼륨: `exchange_postgres-data`

## 검증 결과

- PostgreSQL healthcheck `healthy`, `pg_isready` 연결 성공
- Spring Boot가 PostgreSQL JDBC/Hikari로 연결되고 `schema.sql`을 적용해 7개 테이블 생성
- `continue-on-error: false` 상태에서 기존 DB에 재기동 성공: 스키마 반복 적용 가능
- HTTP 실검증: 회원가입 → faucet → 매수 → 포트폴리오 → 주문·체결 조회 성공
- DB 직접 조회: 사용자 1, 잔고 2, 주문 1, 체결 1 및 금액·수수료 저장 확인
- 검증용 `pgtest...` 사용자와 연관 데이터는 삭제했으며 PostgreSQL 컨테이너만 실행 상태로 유지
- 미사용 Spring 기본 `UserDetailsService` 자동 설정을 제외해 임시 비밀번호 경고 제거
- 전체 H2 자동 테스트: `19 passed, 0 failed`

## 실행 명령

```powershell
docker compose -p exchange up -d postgres
docker compose -p exchange ps
cd backend
.\gradlew.bat bootRun
```

PostgreSQL 데이터는 볼륨에 유지되며 `docker compose -p exchange stop postgres`로 안전하게 중지할 수 있다.

---

# Phase 2 운영 기반 보강: DB 보존과 초기 관리자 — 완료

> 구현 및 검증: 2026-08-26

## 구현

- 기존 데이터가 들어 있는 `exchange_postgres-data` 볼륨을 새 볼륨으로 교체하지 않고 Compose 외부 볼륨으로 선언했다.
- 외부 볼륨은 `docker compose down -v`의 삭제 대상이 아니며 컨테이너 재생성 후에도 같은 데이터를 사용한다.
- `users.role`에 `USER`, `ADMIN` 역할을 추가하고 기존 사용자 기본값을 `USER`로 설정했다.
- JWT 인증 시 DB의 역할을 `ROLE_USER`, `ROLE_ADMIN` Spring Security 권한으로 변환한다.
- `ADMIN_PASSWORD`가 설정된 경우 애플리케이션 시작 시 초기 관리자와 mKRW/mSEC 잔고를 생성한다.
- 관리자 로그인 아이디는 소문자로 정규화하고 비밀번호는 BCrypt로 저장한다.
- 동일 아이디가 이미 있으면 계정, 역할, 비밀번호, 잔고를 덮어쓰지 않는다.
- 설정한 관리자 아이디가 기존 `USER` 계정과 충돌하면 잘못된 권한 상태를 숨기지 않고 서버 기동을 실패시킨다.

## 관리자 설정

```powershell
$env:ADMIN_LOGIN_ID = "admin"
$env:ADMIN_PASSWORD = "8자 이상의 직접 지정한 비밀번호"
$env:ADMIN_NICKNAME = "Admin"
cd backend
.\gradlew.bat bootRun
```

`ADMIN_PASSWORD`가 비어 있으면 초기화를 수행하지 않는다. 비밀번호는 문서, 코드, 로그에 기록하지 않으며 운영 환경에서는 Secret 저장소를 사용한다.

## 검증

- H2 전체 자동 테스트: `21 passed, 0 failed`
- 일반 회원가입 결과 `role=USER`
- 관리자 초기화 결과 `role=ADMIN`, 평문과 다른 BCrypt 해시, 초기 잔고 2개 확인
- 초기화 로직 재실행 및 실제 PostgreSQL 재기동 후 관리자 1명·잔고 2개 유지
- 기존 외부 볼륨 재적용 후 기존 스키마 7개 유지 및 PostgreSQL healthcheck `healthy`
- 실제 검증용 `postgres_admin_test` 계정과 잔고는 검증 후 삭제

## 팀원 참고 위치

- 실행·관리자 설정: `backend/README.md`
- 현재 Phase 상태: `claude-docs/project-overview.md`
- 상세 작업·결정·검증 이력: `claude-docs/implementation-log.md`의 현재 섹션
- 프로젝트 문서 운영 규칙: `claude-docs/README.md`

---

# Phase 2 운영 기반 보강: 로컬 `.env` 설정 — 완료

> 구현 및 검증: 2026-08-26

## 구현

- `backend/.env`를 로컬 실행 설정 파일로 만들고 기존 `.gitignore` 제외 상태를 확인했다.
- 실제 비밀번호·JWT secret·개인키는 기본 파일에서 활성화하지 않고 사용자가 직접 입력하도록 주석 처리했다.
- `backend/.env.example`을 Git에 포함되는 팀 공유 템플릿으로 추가했다.
- Spring Boot `application.yml`에서 `optional:file:.env[.properties]`로 `.env`를 선택적으로 불러온다.
- OS 환경 변수는 Spring 설정 우선순위에 따라 `.env` 값보다 우선한다.
- 테스트는 `src/test/resources/application.yml`의 H2·테스트 JWT 설정을 사용해 로컬 `.env`와 분리한다.

## 파일 위치

- 실제 로컬 설정: `backend/.env` — Git 제외
- 공유 템플릿: `backend/.env.example` — Git 포함
- 로딩 설정: `backend/src/main/resources/application.yml`
- 사용 방법: `backend/README.md`

## 검증

- `git check-ignore`: `backend/.env` 제외 확인
- `backend/.env.example`: Git 추적 가능 확인
- H2 전체 자동 테스트: `21 passed, 0 failed`
- 실제 `.env`로 Spring Boot 및 PostgreSQL 연결, `/api/health=UP` 확인
- `ADMIN_PASSWORD`가 주석된 상태에서 관리자 미생성 확인
- 검증용 백엔드는 종료하고 PostgreSQL 컨테이너만 `healthy` 상태로 유지
- 로컬 관리자 실제 생성 확인: `admin`, `role=ADMIN`, 초기 잔고 2개, 로그인 및 JWT 발급 성공
- `.env` properties 로딩에서 한글 닉네임 인코딩 문제를 확인해 공유 기본값을 ASCII `Admin`으로 변경하고 DB 값도 보정

---

# Phase 3.1: web3j 읽기 연동 기반 — 완료

> 구현 및 검증: 2026-08-28

## 구현

- `BLOCKCHAIN_ENABLED`를 추가해 기존 DB 모의 거래는 Anvil 없이 계속 실행되도록 했다.
- Spring이 관리하고 종료하는 단일 `Web3j` Bean과 읽기 전용 `ContractGateway`를 추가했다.
- chain ID, 최신 블록, 운영자 주소와 네 컨트랙트 주소의 실제 배포 코드 존재 여부를 검증한다.
- `PriceOracle.getPrice`, `ExchangeVault.feeBps`, `quoteBuy`, `quoteSell`, ERC-20 `balanceOf`, `allowance`를 ABI로 호출한다.
- 누락·잘못된 주소, 개인키, RPC 및 빈 ABI 응답을 구체적인 설정 오류로 변환한다.
- `.env.example`, 테스트 설정과 백엔드 실행 문서를 새 활성화 플래그에 맞게 갱신했다.

## 결정

- Phase 3.1은 읽기 전용 기반으로 한정하고 기존 `OrderService`의 DB 즉시 체결은 변경하지 않았다.
- 블록체인 설정 기본값은 비활성화다. Phase 3 기능을 명시적으로 사용할 때만 설정과 연결을 검증한다.
- Java wrapper 생성물을 저장하는 대신 현재 필요한 ABI 읽기 함수만 작은 gateway로 캡슐화했다. Phase 3.2 쓰기 함수와 이벤트 정의도 같은 경계에 추가한다.
- 개인키로부터 공개 운영자 주소만 파생하며 개인키를 API 응답이나 로그에 노출하지 않는다.

## 검증

```text
.\gradlew.bat test --no-daemon
27 passed, 0 failed (Anvil 선택 테스트 제외)

forge script script/Deploy.s.sol --rpc-url http://127.0.0.1:8545 --broadcast ...
ONCHAIN EXECUTION COMPLETE & SUCCESSFUL

.\gradlew.bat test --no-daemon --tests '*BlockchainAnvilIntegrationTest'
1 passed, 0 failed
```

- 실제 Anvil chain ID 31337 및 배포된 컨트랙트 4개의 bytecode 존재 확인
- 오라클 `75,000 × 1e8`, 수수료 `10 bps` 조회
- 750,000 mKRW 매수 견적 `9.99 mSEC`, 수수료 `750 mKRW` 조회
- PostgreSQL 컨테이너를 기존 외부 볼륨으로 재기동해 healthcheck `healthy`와 데이터 보존을 확인
- 실제 PostgreSQL 설정으로 Spring Boot 기동 및 `/api/health=UP` 확인
- 기존 관리자 `admin` 1명과 관리자 잔고 2행이 재기동 후에도 유지됨을 확인

## 남은 작업

- Phase 3.2 운영자 통합 지갑의 approve 및 `ExchangeVault.buy/sell` 전송
- 주문을 즉시 `FILLED`하지 않고 `REQUESTED → PENDING_ONCHAIN`으로 전환
- Phase 3.3 receipt polling, 이벤트 파싱, 멱등 체결·잔고 반영과 reconciliation
- Phase 3.4 가격 시뮬레이터와 `PriceOracle.updatePrice` 동기화

---

# Phase 3.2: 운영자 지갑 온체인 주문 전송 — 완료

> 구현 및 검증: 2026-08-29

## 구현

- `user_balances.locked_amount`를 추가해 pending 주문의 입력 자산과 사용 가능 잔고를 분리했다.
- `blockchain_transactions`에 `order_id`, sender, nonce, signed raw transaction, 제출 시각을 추가했다.
- 블록체인 활성화 시 매수·매도 견적과 운영자 온체인 잔고·allowance를 확인한다.
- 입력 잔고를 잠그고 주문을 `REQUESTED`로 커밋한 후 운영자 트랜잭션을 생성한다.
- pending nonce, 현재 gas price와 estimate gas 20% buffer를 사용해 legacy transaction을 서명한다.
- 서명 원문과 사전 계산한 txHash를 별도 트랜잭션으로 먼저 저장한 뒤 RPC에 전송한다.
- RPC가 같은 txHash를 반환하면 blockchain transaction을 `SUBMITTED`, 주문을 `PENDING_ONCHAIN`으로 변경하고 HTTP 202를 반환한다.
- 프로세스 내부 전송을 직렬화하고 `(sender_address, nonce)` unique 제약으로 nonce 중복을 방어한다.
- `PrepareOperator.s.sol`로 개발 운영자에게 1,000,000 mKRW 지급과 Vault 최대 allowance 설정을 자동화했다.
- 블록체인 비활성화 시 기존 Phase 2 DB 즉시 체결 동작을 유지한다.

## 장애 안전성

- 주문·잔고 잠금은 온체인 전송 전에 독립 커밋된다.
- 서명된 원문·nonce·txHash도 broadcast 전에 독립 커밋된다.
- RPC 연결이 끊겨도 서명 기록과 잠긴 잔고가 남아 Phase 3.3 reconciliation에서 같은 트랜잭션을 재전송할 수 있다.
- Phase 3.2에서는 receipt 성공·실패를 최종 반영하지 않으므로 주문과 잔고는 의도적으로 pending/locked 상태에 머문다.

## 검증

- 백엔드 기본 단위·통합 테스트 `31 passed, 0 failed`(Anvil 선택 테스트 2개 skipped): 잔고 잠금, HTTP 202, txHash 및 `SUBMITTED` 저장, 18 decimals 변환, ABI selector 검증 포함
- 선택 Anvil 테스트 `2 passed, 0 failed`: 읽기 연동 및 실제 서명·buy 전송
- 실제 PostgreSQL 스키마 반복 적용: 기존 관리자와 잔고 보존, 신규 컬럼·인덱스 생성 확인
- 실제 HTTP 매수 100,000 mKRW: `202 PENDING_ONCHAIN`, 1.332 mSEC 견적, nonce 7, receipt block 6 `status=1`
- 실제 HTTP 매도 1 mSEC: `202 PENDING_ONCHAIN`, 74,925 mKRW 견적, nonce 8, receipt `status=1`
- 검증용 사용자·주문·트랜잭션·잔고 DB 기록은 삭제했으며 온체인 로컬 테스트 거래만 Anvil에 남았다.
- Foundry: `20 passed, 0 failed`

## 남은 작업

- Phase 3.3 receipt polling과 `Bought/Sold` 이벤트 파싱
- 성공 시 잠긴 입력 차감, 출력 잔고·평균매수가·trade 생성 및 `FILLED` 전환
- 실패 시 잔고 잠금 해제와 주문·트랜잭션 `FAILED` 전환
- 서버 재시작 후 `SIGNED`/`SUBMITTED` 재처리와 멱등성 검증
- Phase 3.4 온체인 오라클 가격 갱신

---

# Phase 3.3: receipt 기반 온체인 정산과 복구 — 완료

> 구현 및 검증: 2026-08-29

## 구현

- scheduler가 `SIGNED`, `SUBMITTED` blockchain transaction을 설정 가능한 주기로 조회한다.
- `SUBMITTED` receipt가 요구 confirmation 수를 만족할 때 성공·실패를 처리한다.
- 성공 receipt에서 Vault의 `Bought`/`Sold` 이벤트를 ABI로 파싱하고 Vault 주소, 운영자 주소, 주문 입력 수량을 검증한다.
- 매수 성공 시 잠긴 mKRW를 차감하고 mSEC·평균매수가를 갱신하며, 매도 성공 시 mSEC를 차감하고 순 mKRW를 지급한다.
- 주문·잔고·Trade·blockchain transaction을 하나의 DB transaction에서 `FILLED`/`CONFIRMED`로 반영한다.
- 실패 receipt는 전체 잔고를 변경하지 않고 입력 자산 잠금만 해제한 뒤 `FAILED`로 전환한다.
- receipt 성공과 이벤트가 일치하지 않으면 자산 잠금을 유지하고 `REVIEW_REQUIRED`로 격리한다.
- `trades.order_id` unique 제약과 비관적 주문·트랜잭션 잠금으로 같은 receipt의 중복 체결을 방지한다.
- 재기동 후 `SIGNED` txHash가 체인에 없으면 저장된 raw transaction을 재전송하고, 이미 있으면 `SUBMITTED`로 복구한다.

## 설정

- `BLOCKCHAIN_RECEIPT_POLL_INTERVAL_MS` 기본 1초
- `BLOCKCHAIN_RECEIPT_INITIAL_DELAY_MS` 기본 1초
- `BLOCKCHAIN_REQUIRED_CONFIRMATIONS` 기본 1
- 로컬 `.env`와 공유 `.env.example`에 설정을 반영했다.

## 검증

- 기본 백엔드 테스트: `36 passed, 0 failed`, Anvil 선택 테스트 2개 skipped
- Anvil 선택 테스트: `2 passed, 0 failed`; 실제 buy 서명·전송 후 receipt·이벤트 파싱, `FILLED`, 잔고·Trade 반영 확인
- 이벤트 단위 테스트: `Bought` 파싱 및 주문 입력 불일치 거부
- 정산 통합 테스트: 매수 성공 중복 호출 시 1회만 반영, 매도 실패 중복 호출 시 잠금만 1회 해제
- 복구 단위 테스트: 체인에 없는 `SIGNED` raw transaction 재전송 및 `SUBMITTED` 복구
- 실제 PostgreSQL·HTTP·Anvil 매수 100,000 mKRW: `PENDING_ONCHAIN → FILLED`, 1.332 mSEC, block 11
- 이어서 1.332 mSEC 매도: `PENDING_ONCHAIN → FILLED`, 99,800.1 mKRW 수령, block 12
- 왕복 후 Trade 2건, mSEC 0, 최종 mKRW 999,800.1로 양쪽 수수료 반영 확인
- 검증용 사용자·잔고·주문·체결·blockchain transaction은 모두 삭제했다.
- Solidity 회귀 테스트: `20 passed, 0 failed`

## 다음 작업

- Phase 3.4 백엔드 가격 시뮬레이터와 `PriceOracle.updatePrice` 온체인 동기화
- Phase 4 WebSocket으로 주문 상태·체결 결과 사용자별 발행
- 운영 환경에서는 다중 인스턴스용 분산 nonce lock과 `REVIEW_REQUIRED` 운영자 처리 API가 추가로 필요하다.

---

# Phase 3.4: 오라클 가격 동기화 — 완료

> 구현 및 검증: 2026-09-01

## 구현

- 가격 시뮬레이터의 최신 가격을 8자리 소수 정수로 변환해 `PriceOracle.updatePrice` 트랜잭션으로 전송한다.
- 주문이 없는 시스템 트랜잭션도 기록할 수 있도록 `blockchain_transactions.order_id`의 nullable 구조를 활용하고 `UPDATE_PRICE` 타입과 목표 가격을 추가했다.
- 가격 갱신도 주문과 같은 운영자 nonce·서명 원문·txHash 저장 및 `SIGNED` 복구 경로를 사용한다.
- 처리 중인 `UPDATE_PRICE`가 있으면 중간 가격을 별도 전송하지 않고, 완료 후 당시 최신 시뮬레이터 가격 하나만 전송하는 coalescing 방식을 적용했다.
- 트랜잭션 전송 전에 운영자 주소가 Oracle owner인지 확인하고, 현재 온체인 가격과 목표 가격이 같으면 갱신을 생략한다.
- receipt의 `PriceUpdated` 이벤트에서 Oracle 주소와 가격을 검증한 뒤 트랜잭션을 `CONFIRMED`로 확정한다.
- 확정 가격을 `price_ticks`에 `ONCHAIN_ORACLE` 출처로 멱등 저장하며 blockchain transaction과 unique 관계를 둔다.
- 블록체인 활성화 시 견적 API가 로컬 계산 대신 Oracle 현재가와 Vault의 `quoteBuy`/`quoteSell` 결과를 반환한다.
- `/api/market/ticks`는 저장된 최신 가격 이력 최대 100개를 반환한다.

## 설정

- `BLOCKCHAIN_PRICE_SYNC_ENABLED`: 가격 동기화 활성화 여부
- `BLOCKCHAIN_PRICE_SYNC_INTERVAL_MS`: 반복 주기, 기본 3000ms
- `BLOCKCHAIN_PRICE_SYNC_INITIAL_DELAY_MS`: 시작 지연, 기본 3000ms
- 로컬 `.env`와 공유용 `.env.example`에 설정을 반영했다.

## 검증

- 백엔드 H2 전체 자동 테스트 통과: 가격 단위 변환, coalescing, 이벤트 파싱, 멱등 가격 이력 저장 포함
- Anvil 선택 통합 테스트 통과: 실제 `updatePrice` 서명·전송, receipt reconciliation, Oracle 가격과 DB 가격 이력 일치
- 실제 PostgreSQL·Spring Boot·Anvil 실행 검증: 주기적 `UPDATE_PRICE`가 순차 확정되고 동시에 처리 중인 갱신은 최대 1건임을 확인
- 온체인 견적 API가 Oracle 현재 가격과 Vault 수수료·수량을 반환하고 최신 `ONCHAIN_ORACLE` tick과 가격이 일치함을 확인
- 가격 동기화가 실행 중인 상태에서 실제 매수 주문이 `PENDING_ONCHAIN → FILLED`로 정산돼 공유 nonce 경로가 충돌하지 않음을 확인
- Solidity 회귀 테스트: `20 passed, 0 failed`

## 다음 작업

- Phase 4 WebSocket 가격·주문 상태·체결 알림
- 장기 운영 전 가격 갱신 주기와 트랜잭션 비용 정책 결정
- 다중 백엔드 인스턴스 도입 시 분산 nonce lock 추가

---

# Phase 3 정리: 블록체인 패키지와 설계 주석 — 완료

> 리팩터링 및 검증: 2026-09-03

## 변경 범위

- 기존 단일 `blockchain` 패키지를 `config`, `contract`, `transaction`, `reconciliation`, `settlement`, `oracle`, `support` 역할로 분리했다.
- `BlockchainService`는 주문·견적·가격 동기화가 사용하는 공개 진입점으로 상위 패키지에 유지했다.
- 주문 접수와 DB 잔고 잠금을 담당하는 `OnchainOrderService`, `OnchainOrderPreparationService`는 주문 도메인에 유지했다.
- 운영 코드와 같은 하위 패키지로 관련 단위 테스트를 이동해 package-private 접근 범위를 불필요하게 넓히지 않았다.
- 핵심 클래스에 코드 해설보다 장애 안전성, 상태 전이, 멱등성, nonce 직렬화와 가격 coalescing의 이유를 설명하는 주석을 추가했다.
- 각 하위 패키지에 `package-info.java`를 추가해 디렉터리 책임을 코드에서 바로 확인할 수 있게 했다.
- 실제 설정과 다르던 README의 가격 동기화 기본값을 `false`로 바로잡았다.

## 보존한 동작

- REST API, DB 스키마, 환경 변수 이름과 기본 실행 흐름은 변경하지 않았다.
- 운영자 통합 지갑, 주문 자산 잠금, 서명 원문 선저장, receipt reconciliation과 Oracle 동기화 정책을 그대로 유지했다.

## 검증

- Java 운영 코드와 테스트 코드 컴파일 성공
- 백엔드 전체 자동 테스트: 45개 중 42개 통과, Anvil 선택 테스트 3개 skipped, 실패·오류 0개
- Anvil 선택 통합 테스트 3개 통과: 컨트랙트 읽기, 실제 buy 서명·전송·정산, 실제 `updatePrice`와 가격 이력 저장
- 현재 리팩터링 코드로 Spring Boot를 재기동해 `/api/health=UP` 확인
- 실제 PostgreSQL·HTTP·Anvil 매수 검증: 임시 사용자의 1,000 mKRW 주문이 `PENDING_ONCHAIN → FILLED`로 전환되고 잔고·평균매수가·평가금액에 반영됨
- 검증용 사용자·잔고·주문·체결·주문 트랜잭션을 삭제한 뒤 임시 사용자 0, 잠긴 잔고 0, `FAILED`/`REVIEW_REQUIRED` 0 확인
- Solidity 회귀 테스트: `20 passed, 0 failed`
