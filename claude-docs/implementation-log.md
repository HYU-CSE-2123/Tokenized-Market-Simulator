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
- **Docker 데몬 미실행**으로 `docker compose up` + 백엔드 `bootRun`(실 Postgres) + 컨트랙트 로컬 배포는 미검증. `./gradlew build`의 H2 컨텍스트 테스트로 앱 wiring은 검증됨.

## 다음 단계 (기획서 §16)
Phase 0.5 컨트랙트 로컬 배포·시나리오 실행 → Phase 1 컨트랙트 마감 → Phase 2 백엔드 API(mock) → Phase 3 web3j 연동 → Phase 4 WebSocket → Phase 5 Android → Phase 6 시연/문서화.
