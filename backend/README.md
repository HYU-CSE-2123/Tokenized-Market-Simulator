# Backend — Spring Boot (Java 21)

삼성전자 가격 추종 토큰 거래소의 REST API 및 WebSocket 서버입니다.

## 현재 구현 상태

- 자체 회원가입: `loginId`, `password`, `nickname`
- 자체 로그인 및 JWT 액세스 토큰 발급
- BCrypt 비밀번호 해시 저장
- JWT 인증 필터와 보호 API `GET /api/me`
- 통일된 JSON 오류 응답 및 입력값 검증
- 공개 시장 조회와 모의 가격·견적 계산
- 사용자별 mKRW·mSEC DB 잔고와 mKRW faucet
- 블록체인 비활성화 시 DB 기반 즉시 매수·매도, 주문·체결 내역 및 포트폴리오
- 선택적으로 활성화하는 web3j RPC 연결과 읽기 전용 컨트랙트 조회
- 블록체인 활성화 시 운영자 지갑의 buy/sell 서명·전송, receipt polling과 자동 체결
- 가격 시뮬레이터의 최신 가격을 `PriceOracle.updatePrice`로 동기화하고 `PriceUpdated` 이벤트를 가격 이력으로 저장
- 최신 시장 스냅샷과 Vault 명시 가격 견적으로 30초 유효 EIP-712 가격 보고서를 발급·전용 키로 서명하는 기반
- 활성화된 온체인 모드에서 서명 견적을 로그인 사용자에게 귀속해 DB에 저장하고 API에는 서명·executor를 제외한 안전한 메타데이터만 반환
- 블록체인 활성화 시 Oracle과 Vault를 직접 조회하는 온체인 매수·매도 견적
- 일반 WebSocket `/ws`와 브라우저 호환 SockJS `/ws-sockjs`, STOMP JWT 인증과 공개·개인 구독 통제
- 버전 있는 WebSocket 이벤트 envelope와 DB commit 이후에만 전송되는 공개·개인 이벤트 발행 기반
- 1초 주기 모의 가격과 모의·온체인 체결 결과를 공통 envelope로 공개 WebSocket topic에 발행
- 가격 소비 도메인이 공급자 구현을 직접 알지 않도록 `MarketPriceService`와 `MarketPriceProvider` 경계 적용
- 실제 시세 연동 전 기본 공급자인 `SimulatedPriceProvider`와 공급자 공통 가격 스냅샷 모델 적용
- `PRICE_PROVIDER=toss` 선택 시 토스증권 REST 초기 가격 이후 WebSocket으로 삼성전자 실시간 체결가 수신
- 공급자 공통 `1m`·`5m`·`15m`·`30m`·`1h`·`1d` 캔들 API와 시뮬레이션 OHLCV 저장
- 온체인 주문 대기·성공·실패와 포트폴리오 변경을 해당 사용자의 개인 queue에 발행
- Google 로그인과 이메일 인증을 위한 nullable 사용자 컬럼 준비

Google OAuth, 이메일 인증과 리프레시 토큰은 아직 구현하지 않았습니다. Phase 3은 온체인 조회·주문·정산·복구와 오라클 가격 동기화까지 구현됐습니다.

## 인증 API

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 불필요 | 자체 계정 생성 및 액세스 토큰 발급 |
| POST | `/api/auth/login` | 불필요 | 아이디·비밀번호 로그인 |
| GET | `/api/me` | Bearer JWT | 현재 사용자 조회 |

`loginId`는 영문자·숫자·`_`·`-`로 구성된 4~30자이며 대소문자를 구분하지 않습니다. 비밀번호는 8~72자입니다.

## 마켓·차트 API

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/api/markets/mSEC` | 현재가·전일 종가·시장 및 가격 상태 |
| GET | `/api/markets/mSEC/ticks` | 기존 가격 기록 최대 100개 |
| GET | `/api/markets/mSEC/candles?interval=1m&count=100` | 1분~1시간봉 또는 일봉 차트 |

캔들 `interval`은 `1m`, `5m`, `15m`, `30m`, `1h`, `1d`를 지원합니다. `before`에 ISO-8601 시각을 넘기면 과거 페이지를 조회하며 응답의 `nextBefore`를 다음 요청에 그대로 사용합니다. 캔들은 차트가 바로 사용할 수 있도록 `startedAt` 오름차순으로 반환됩니다.

| 주기 | 최대 `count` | 생성 방식 |
| --- | ---: | --- |
| `1m` | 200 | 공급자 1분봉 |
| `5m` | 100 | 1분봉 OHLCV 집계 |
| `15m` | 50 | 1분봉 OHLCV 집계 |
| `30m` | 30 | 1분봉 OHLCV 집계 |
| `1h` | 20 | 1분봉 OHLCV 집계 |
| `1d` | 200 | 공급자 일봉 |

`count`를 생략하면 100과 해당 주기의 최대치 중 작은 값을 사용합니다. 상위 분봉의 시가·종가에는 구간의 첫·마지막 1분봉을, 고가·저가에는 구간 극값을, 거래량에는 합계를 사용합니다.

```json
{
  "symbol": "mSEC",
  "interval": "1m",
  "provider": "TOSS",
  "candles": [{
    "startedAt": "2026-09-16T00:00:00Z",
    "open": "249000",
    "high": "250500",
    "low": "248500",
    "close": "250000",
    "volume": "35210",
    "closed": true
  }],
  "nextBefore": null
}
```

- Toss 모드는 공식 수정주가 캔들 API의 가격과 거래량을 사용합니다.
- Toss 1분봉의 `timestamp`는 봉 종료 경계이므로 내부 `startedAt`에서는 60초를 빼 시작 시각으로 정규화합니다. 그래야 WebSocket 체결 tick과 같은 봉에 병합됩니다.
- 시뮬레이션 모드는 `market_candles`에 1분봉·일봉만 갱신하고 상위 분봉은 조회 시 집계합니다. `volume`은 실제 거래량이 아닌 해당 구간의 가격 tick 개수입니다.
- 잘못된 주기·개수는 HTTP 400 `INVALID_CANDLE_QUERY`, mSEC 이외 심볼은 `UNSUPPORTED_SYMBOL`입니다.

## 모의 거래 API

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/wallet/faucet` | 1,000,000 mKRW 지급 |
| POST | `/api/orders/buy` | 현재 가격으로 mSEC 즉시 매수 |
| POST | `/api/orders/sell` | 현재 가격으로 mSEC 즉시 매도 |
| GET | `/api/orders` | 내 주문 목록 |
| GET | `/api/orders/{orderId}` | 내 주문 단건 조회 |
| GET | `/api/trades` | 내 체결 목록 |
| GET | `/api/portfolio` | 잔고·평균매수가·평가금액·미실현손익 조회 |

모든 거래 API에는 Bearer JWT가 필요합니다. 수수료는 컨트랙트와 동일한 0.1%입니다.

- `BLOCKCHAIN_ENABLED=false`: DB에서 즉시 체결하며 성공 주문은 `FILLED`
- `BLOCKCHAIN_ENABLED=true`: 입력 잔고를 잠그고 실제 트랜잭션을 전송한 뒤 HTTP 202와 `PENDING_ONCHAIN` 반환, scheduler가 이후 `FILLED/FAILED` 확정

성공 receipt는 Vault의 이벤트, 운영자 주소와 주문 입력값까지 일치해야 `FILLED` 처리됩니다. receipt는 성공했지만 이벤트가 이상하면 자산 잠금을 유지하고 blockchain transaction을 `REVIEW_REQUIRED`로 격리합니다.

## 패키지 구조

| 패키지 | 책임 |
| --- | --- |
| `auth` | 회원가입·로그인, JWT 발급·검증, 인증 필터 |
| `user` | 사용자 엔티티와 리포지토리 |
| `market` | 공급자 독립적인 현재가 진입점과 마켓 API |
| `market.model` | 가격·변동·관측 시각·시장 및 신선도 상태의 공통 모델 |
| `market.provider` | 시뮬레이션·실제 시세 구현이 따르는 가격 공급자 경계 |
| `market.provider.simulated` | 기본 개발·테스트용 랜덤 가격 공급자 |
| `market.provider.toss` | 토스증권 OAuth·REST 초기 가격과 WebSocket 실시간 체결 공급자 |
| `quote` | 매수·매도 견적 계산 |
| `wallet`, `order`, `trade`, `portfolio` | 모의 잔고·주문·체결·포트폴리오 |
| `blockchain` | 다른 도메인이 사용하는 블록체인 진입점 |
| `blockchain.config` | RPC·운영자·receipt·가격 동기화 설정 |
| `blockchain.contract` | Solidity ABI 호출과 이벤트 파싱 |
| `blockchain.transaction` | 서명·전송·nonce·트랜잭션 상태 저장 |
| `blockchain.reconciliation` | 미완료 트랜잭션 receipt 조회와 복구 조정 |
| `blockchain.settlement` | 주문·잔고·체결 및 Oracle 가격의 멱등 정산 |
| `blockchain.oracle` | 가격 시뮬레이터와 PriceOracle 동기화 정책 |
| `blockchain.support` | 토큰·가격 단위 변환과 공통 예외 |
| `websocket.config` | 일반 WebSocket·SockJS endpoint, STOMP broker와 JWT 인증·구독 통제 |
| `websocket.auth` | WebSocket session 사용자 Principal |
| `websocket.event` | destination 상수, 공통 이벤트 envelope와 가격·체결·주문·포트폴리오 payload |
| `websocket.publisher` | 도메인 서비스의 발행 요청을 transaction commit 후 STOMP broker로 전달 |
| `common` | 보안 설정, 헬스 체크, 공통 오류 처리 |

## 테스트

```powershell
cd backend
.\gradlew.bat test --no-daemon
```

테스트는 H2 인메모리 DB를 사용하며 PostgreSQL 없이 실행할 수 있습니다. 인증/JWT·거래 계산 단위 테스트와 MockMvc 기반 회원가입→faucet→매수→매도→포트폴리오 통합 흐름을 포함합니다.

## WebSocket 연결

- `/ws`: Android 등 네이티브 클라이언트용 표준 WebSocket + STOMP
- `/ws-sockjs`: 웹 브라우저 fallback용 SockJS + STOMP
- `/topic/markets/mSEC/price`, `/topic/markets/mSEC/trades`: 인증 없이 구독 가능한 공개 시장 destination
- `/user/queue/orders`, `/user/queue/portfolio`: JWT 인증 사용자만 구독 가능한 개인 destination

인증이 필요한 클라이언트는 STOMP `CONNECT` native header에 REST 로그인으로 받은 같은 JWT를 전달합니다.

```text
Authorization: Bearer <access-token>
```

토큰을 생략하면 공개 시장 구독만 가능하고, 잘못되거나 만료된 토큰을 보내면 연결을 거부합니다. 이 서비스는 서버 push 전용이므로 클라이언트의 STOMP `SEND`와 명시되지 않은 destination 구독도 거부합니다. 개발 기본 Origin은 `WEBSOCKET_ALLOWED_ORIGINS=*`이며 배포 환경에서는 실제 클라이언트 Origin 목록으로 제한해야 합니다.

모든 서버 이벤트는 다음 공통 envelope를 사용합니다.

```json
{
  "eventId": "중복 식별용 UUID",
  "version": 1,
  "type": "PRICE_UPDATED",
  "occurredAt": "2026-09-06T06:00:00Z",
  "data": {}
}
```

이벤트 종류는 가격 갱신, 공개 체결, 온체인 주문 대기·성공·실패, 포트폴리오 갱신으로 정의돼 있습니다. 현재 가격과 공개 체결 이벤트가 실제 서비스에 연결돼 있으며, 사용자별 주문·포트폴리오 연결은 Phase 4.4 범위입니다.

- `PRICE_UPDATED`: 시뮬레이션 또는 Toss의 각 새 가격 tick을 `/topic/markets/mSEC/price`로 즉시 발행
- `TRADE_EXECUTED`: 모의 거래 또는 온체인 정산으로 저장된 체결을 `/topic/markets/mSEC/trades`로 발행

가격 이벤트의 `data`는 다음 형식이다. 기존 `symbol`, `price`, `changeRate`는 유지하며 차트와 상태 표시에 필요한 필드를 추가했다. `observedAt`은 서버 발행 시간이 아니라 공급자가 가격을 관측한 시각이고, `updatedAt`은 기존 클라이언트 호환을 위해 같은 값을 제공한다. Toss의 `volume`은 해당 체결 수량이며 시뮬레이션에서는 tick 하나를 `1`로 센다. 따라서 Toss는 가격이 직전과 같아도 거래량 반영을 위해 이벤트를 발행한다.

```json
{
  "symbol": "mSEC",
  "price": 75100,
  "previousClose": 75000,
  "change": 100,
  "changeRate": 0.13333333,
  "volume": 12.5,
  "marketStatus": "OPEN",
  "priceStatus": "LIVE",
  "provider": "TOSS",
  "observedAt": "2026-09-16T01:23:45Z",
  "updatedAt": "2026-09-16T01:23:45Z"
}
```

공통 envelope `version`은 계속 `1`이다. 기존 destination·event type·필드를 제거하지 않는 하위 호환 payload 확장이기 때문이다.

공개 체결 payload에는 시장 정보만 포함하며 `userId`, `orderId`, `txHash`는 노출하지 않습니다. DB를 변경하는 체결 서비스가 발행을 요청하면 실제 메시지는 transaction commit 후에만 전송되고 rollback 시 폐기됩니다. 따라서 클라이언트가 아직 저장되지 않은 체결을 먼저 받지 않습니다.

개인 주문 이벤트는 실제 DB 상태가 확정되는 지점에서 발행합니다.

- `ORDER_PENDING_ONCHAIN`: RPC 제출 기록과 주문의 `PENDING_ONCHAIN`, `txHash`가 함께 저장된 후
- `ORDER_FILLED`: 모의 즉시 체결 또는 성공한 온체인 receipt 정산 후
- `ORDER_FAILED`: 잔고 부족 주문 기록 또는 실패한 온체인 receipt 정산 후
- `PORTFOLIO_UPDATED`: faucet, 모의 체결, 온체인 성공 정산, 실패 정산의 자산 잠금 해제 후

개인 이벤트는 사용자 DB ID로 라우팅되므로 다른 사용자의 `/user/queue/...` 구독에는 전달되지 않습니다. 현재 구현되지 않은 취소 흐름의 `CANCELED`와 운영 검토 상태인 `REVIEW_REQUIRED`는 실패 알림으로 잘못 표현하지 않고 발행하지 않습니다. WebSocket 메시지는 재전송을 보장하는 영속 로그가 아니므로 앱 재연결 후에는 REST API로 주문과 포트폴리오 최신 상태를 다시 조회해야 합니다.

Android 연동 전 실제 브라우저에서 REST·JWT·Native WebSocket·SockJS 흐름을 확인하려면 [`tools/websocket-test-client`](../tools/websocket-test-client)를 사용합니다. 개발 프록시가 요청을 이 서버로 전달하므로 테스트를 위해 REST CORS를 개방할 필요가 없습니다.

### Anvil 실제 연동 테스트

기본 테스트에서는 Anvil 연동 테스트를 건너뜁니다. Anvil에 `Deploy.s.sol`을 배포한 후 다음 환경 변수를 설정하면 web3j가 실제 체인·컨트랙트를 읽는 테스트를 실행할 수 있습니다.

```powershell
$env:BLOCKCHAIN_INTEGRATION_TESTS = "true"
$env:RPC_URL = "http://127.0.0.1:8545"
$env:MOCK_KRW_ADDRESS = "배포 결과 주소"
$env:MSEC_ADDRESS = "배포 결과 주소"
$env:PRICE_ORACLE_ADDRESS = "배포 결과 주소"
$env:EXCHANGE_VAULT_ADDRESS = "배포 결과 주소"
$env:OPERATOR_PRIVATE_KEY = "Anvil 운영자 개인키"
.\gradlew.bat test --no-daemon --tests '*BlockchainAnvilIntegrationTest'
```

이 테스트는 chain ID와 배포 코드, 운영자 주소, 오라클 가격, 수수료, 잔고·allowance와 매수 견적을 실제 RPC로 조회하고 소액 buy를 실제 서명·전송합니다.

### 운영자 지갑 준비

온체인 주문 전에 운영자 지갑에 테스트 mKRW와 Vault allowance가 필요합니다. 컨트랙트 배포 주소와 운영자 키를 환경 변수로 설정한 후 실행합니다.

```powershell
cd contracts
$env:MOCK_KRW_ADDRESS = "배포 결과 주소"
$env:EXCHANGE_VAULT_ADDRESS = "배포 결과 주소"
$env:OPERATOR_PRIVATE_KEY = "Anvil 운영자 개인키"
forge script script/PrepareOperator.s.sol --rpc-url http://127.0.0.1:8545 --broadcast
```

스크립트는 운영자에게 1,000,000 mKRW를 faucet으로 지급하고 Vault에 최대 allowance를 설정합니다. 실제 자산이나 운영 네트워크용 스크립트가 아닙니다.

## 로컬 실행

### 환경 설정 파일

```powershell
Copy-Item .env.example .env
```

- `backend/.env`: 실제 로컬 값과 비밀정보를 저장하며 Git에서 제외됩니다.
- `backend/.env.example`: 팀원이 공유하는 변수 목록이며 실제 비밀번호·개인키는 넣지 않습니다.
- Spring 설정은 `backend/`에서 Gradle로 실행할 때의 `.env`와 저장소 루트에서 IntelliJ로 실행할 때의 `backend/.env`를 모두 선택적으로 탐색합니다.
- OS 환경 변수가 같은 이름으로 설정돼 있으면 `.env`보다 OS 환경 변수가 우선합니다.
- `JWT_SECRET`, `ADMIN_PASSWORD`, `OPERATOR_PRIVATE_KEY`, `PRICE_SIGNER_PRIVATE_KEY`는 `.env`에서 직접 입력하고 주석을 해제합니다.
- `.env`가 없어도 기본값으로 기동할 수 있지만 관리자는 생성되지 않습니다.

```powershell
docker compose up -d postgres
cd backend
.\gradlew.bat bootRun
```

주요 환경 변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `WEBSOCKET_ALLOWED_ORIGINS`, `BLOCKCHAIN_ENABLED`, `RPC_URL`, `MOCK_KRW_ADDRESS`, `MSEC_ADDRESS`, `PRICE_ORACLE_ADDRESS`, `EXCHANGE_VAULT_ADDRESS`, `OPERATOR_PRIVATE_KEY`, `PRICE_REPORT_SIGNING_ENABLED`, `PRICE_SIGNER_PRIVATE_KEY`.

### 가격 보고서 서명 설정

Phase 5.3-A의 가격 보고서 발급 기능은 기본적으로 비활성화되어 기존 주문 경로에 영향을 주지 않는다. 활성화할 때는 거래 전송 키와 다른 가격 전용 키를 설정한다.

```properties
PRICE_REPORT_SIGNING_ENABLED=true
PRICE_SIGNER_PRIVATE_KEY=<PriceOracle.priceSigner 주소의 개인키>
```

활성화 상태로 서버가 시작되면 전용 키에서 파생한 주소와 온체인 `PriceOracle.priceSigner()`를 비교한다. 키가 없거나 형식이 잘못됐거나 주소가 다르면 서버 기동을 중단한다. 개인키는 API 응답·로그·문서에 기록하지 않는다.

발급기는 선택된 가격 공급자의 스냅샷이 5초 이내인지 검사하고, Vault의 `quoteBuyAtPrice` 또는 `quoteSellAtPrice` 결과를 `minimumOutput`으로 고정한다. 보고서는 관측 시각부터 정확히 30초 동안 유효하며 현재 단계에서는 아직 REST 견적이나 주문 전송 경로에 노출되지 않는다.

Phase 5.3-B부터 위 두 설정과 `BLOCKCHAIN_ENABLED=true`가 모두 적용되면 `POST /api/quotes/buy`, `POST /api/quotes/sell`이 로그인 사용자 소유의 서명 견적을 발급한다. 응답에는 `quoteId`, `minimumOutputAmount`, `observedAt`, `validUntil`, `status`가 추가되며 개인 서명과 온체인 executor는 서버 내부 `price_quotes`에만 저장한다. 기능이 비활성화된 모의·기존 견적 응답에는 새 nullable 필드를 직렬화하지 않는다.

`price_quotes` 상태는 `ISSUED → CONSUMED` 또는 `ISSUED → EXPIRED`다. 소비 서비스는 사용자·방향·입력량·만료를 확인하고 DB 비관적 잠금으로 동일 견적의 동시 재사용을 막는다. 실제 주문 요청에서 `quoteId`를 필수로 받고 새 Vault ABI로 보내는 연결은 Phase 5.3-C에서 활성화한다.

### 가격 공급자 선택

기본값인 `PRICE_PROVIDER=simulated`는 기존 1초 주기 모의 가격을 사용하며 토스증권 자격 증명이 필요하지 않습니다. 실제 삼성전자 초기 가격을 조회하려면 다음 값을 설정합니다.

```properties
PRICE_PROVIDER=toss
TOSS_API_BASE_URL=https://openapi.tossinvest.com
TOSS_SYMBOL=005930
TOSS_CONNECT_TIMEOUT=2s
TOSS_READ_TIMEOUT=5s
TOSS_WEBSOCKET_URL=wss://openapi-ws.tossinvest.com/ws/v1
TOSS_WEBSOCKET_PING_INTERVAL=60s
TOSS_WEBSOCKET_RECONNECT_INITIAL_DELAY=1s
TOSS_WEBSOCKET_RECONNECT_MAX_DELAY=30s
TOSS_PRICE_DEGRADED_AFTER=15s
TOSS_PRICE_STALE_AFTER=60s
TOSS_REFERENCE_REFRESH_CRON=0 5 0 * * *
TOSS_CLIENT_ID=토스증권-client-id
TOSS_CLIENT_SECRET=토스증권-client-secret
```

- 토스 개발자 콘솔에 서버의 외부 IP가 허용 IP로 등록돼 있어야 합니다.
- `TOSS_SYMBOL`은 mSEC가 다른 종목을 추종하는 사고를 막기 위해 삼성전자 코드 `005930`만 허용합니다.
- 연결·응답 timeout의 기본값은 각각 2초·5초이며 Spring `Duration` 형식(`500ms`, `2s` 등)으로 조정할 수 있습니다.
- Client Credentials 액세스 토큰은 만료 전에 재발급하며, API가 401을 반환하면 캐시를 폐기하고 한 번만 다시 요청합니다.
- `toss` 모드는 기동 완료 시 REST로 첫 가격을 확보합니다. 자격 증명 누락, 인증 실패, 허용 IP 오류 또는 잘못된 시세 응답이 있으면 기동을 실패시켜 모의 가격을 실제 가격으로 오인하지 않게 합니다.
- REST 초기 가격 확보 후 Toss WebSocket에서 `trade:kr:005930`을 구독합니다. 구독 ACK를 확인한 연결만 정상으로 간주하고 60초마다 `PING`을 전송합니다.
- 연결이 끊기면 1초부터 최대 30초까지 지수 백오프와 jitter를 적용해 새 토큰으로 재연결하고 전체 구독을 다시 선언합니다.
- 검증된 최신 체결만 스냅샷에 반영하며 과거·동일 시각 체결은 무시합니다. 가격이 바뀌면 기존 `/topic/markets/mSEC/price`로 앱에 전달하고 기존 Oracle 동기화도 최신 가격을 읽습니다.
- Toss 시세 채널은 공급자 정책상 유실 가능한 최신값 우선 스트림입니다. 장 운영시간 판정, 지연·오래된 가격의 거래 차단과 Oracle 반영 정책은 후속 단계입니다.
- 시작 시 국내 장 캘린더와 수정주가 일봉을 함께 조회해 전 영업일 종가를 기준 가격으로 사용합니다. 프리·정규·애프터 세션 중 하나면 `OPEN`, 그 외와 휴장일은 `CLOSED`입니다.
- 과거 1분봉·일봉은 Toss 캔들 API에서 조회하므로 우리 백엔드가 꺼져 있던 구간도 서버 재기동 후 다시 불러올 수 있습니다.
- 장중에는 마지막 관측 후 15초부터 `DEGRADED`, 60초부터 `STALE`이며 WebSocket이 재연결 중이어도 `DEGRADED`입니다. 장 마감·휴장 시 마지막 공식 가격은 시간 경과만으로 오래된 가격이 되지 않습니다.
- 캘린더와 전일 종가는 매일 KST 00:05에 갱신하며 실패하면 마지막 정상 참조 데이터를 유지합니다.
- `GET /api/markets/mSEC`는 `price`, `previousClose`, `change`, `changeRate`, `marketStatus`, `priceStatus`, `provider`, `observedAt`을 반환합니다. 기존 Android 호환용 `updatedAt`은 `observedAt`과 같은 값으로 유지합니다.
- Toss 모드에서는 `CLOSED`이면 HTTP 409 `MARKET_CLOSED`, 장중 `STALE`이면 HTTP 503 `PRICE_STALE`로 매수·매도를 주문 생성 전에 거부합니다. `DEGRADED`는 60초 유예 범위라 거래할 수 있고 시뮬레이션 모드는 24시간 거래할 수 있습니다.
- `CLOSED`·`STALE` 가격은 PriceOracle에도 새로 제출하지 않습니다. 이미 제출된 온체인 주문과 가격 트랜잭션의 receipt 정산은 계속 처리합니다.
- 장이 닫혀도 마켓·견적·포트폴리오·주문 및 체결 내역 조회는 계속 사용할 수 있습니다.

`TOSS_CLIENT_SECRET`은 실제 `.env` 또는 배포 환경 Secret에만 저장하고 저장소에는 커밋하지 않습니다.

`BLOCKCHAIN_ENABLED`의 기본값은 `false`입니다. 기존 DB 모의 거래만 사용할 때는 그대로 두며, Phase 3 web3j 기능을 사용할 때 `true`로 바꿉니다. 활성화 후 연결 검증을 호출하면 RPC, 개인키, 네 컨트랙트 주소와 실제 배포 코드를 엄격히 검사합니다.

온체인 주문에서는 `user_balances.locked_amount`가 처리 중인 입력 자산을 나타냅니다. 사용 가능 잔고는 `amount - locked_amount`입니다. 성공 이벤트 확정 시 입력 잔고 차감·출력 잔고 추가·Trade 생성이 하나의 DB transaction으로 처리되고, 실패 receipt는 잠금만 해제합니다.

receipt 처리 설정은 `BLOCKCHAIN_RECEIPT_POLL_INTERVAL_MS`(기본 1000), `BLOCKCHAIN_RECEIPT_INITIAL_DELAY_MS`(기본 1000), `BLOCKCHAIN_REQUIRED_CONFIRMATIONS`(기본 1)입니다. 서버 재시작 후 `SIGNED` 기록은 체인 존재 여부를 확인하고, 필요하면 저장된 동일 raw transaction을 재전송합니다.

가격 동기화 설정은 `BLOCKCHAIN_PRICE_SYNC_ENABLED`(기본 false), `BLOCKCHAIN_PRICE_SYNC_INTERVAL_MS`(기본 3000), `BLOCKCHAIN_PRICE_SYNC_INITIAL_DELAY_MS`(기본 3000)입니다. 블록체인 기능과 가격 동기화를 모두 명시적으로 활성화하면 백엔드가 최신 모의 가격을 Oracle에 전송합니다. 이전 가격 갱신이 처리 중이면 새 트랜잭션을 계속 만들지 않고, 완료된 뒤 그 시점의 최신 가격만 전송합니다. 확정된 `PriceUpdated` 이벤트는 `price_ticks`에 `ONCHAIN_ORACLE` 출처로 한 번만 저장됩니다.

로컬 기본값인 3초 주기는 학습·시연용입니다. 장시간 서버를 켜둘 때는 트랜잭션 수가 빠르게 늘 수 있으므로 주기를 늘리거나 `BLOCKCHAIN_PRICE_SYNC_ENABLED=false`로 중지할 수 있습니다.

### 초기 관리자 계정

관리자 비밀번호가 설정된 경우에만 서버 시작 시 관리자 계정을 생성합니다. 일반적으로 `backend/.env`에 설정합니다.

```properties
ADMIN_LOGIN_ID=admin
ADMIN_PASSWORD=직접-지정한-8자-이상-비밀번호
ADMIN_NICKNAME=Admin
```

환경 변수를 현재 PowerShell 세션에서 직접 지정하는 방식도 사용할 수 있습니다.

```powershell
$env:ADMIN_PASSWORD = "직접-지정한-8자-이상-비밀번호"
.\gradlew.bat bootRun
```

- 계정이 이미 존재하면 다시 만들거나 비밀번호를 덮어쓰지 않습니다.
- 비밀번호는 BCrypt 해시로만 저장됩니다.
- 생성된 계정의 역할은 `ADMIN`이며 일반 가입 계정은 `USER`입니다.
- `ADMIN_PASSWORD`가 없으면 관리자 계정 초기화를 건너뜁니다.
- 운영 환경에서는 환경 변수 대신 배포 환경의 Secret 관리 기능을 사용하는 것을 권장합니다.

PostgreSQL만 종료하거나 다시 시작할 때는 루트에서 다음 명령을 사용합니다.

```powershell
docker compose -p exchange stop postgres
docker compose -p exchange up -d postgres
docker compose -p exchange ps
```

데이터는 외부 Docker 볼륨 `exchange_postgres-data`에 유지됩니다. Compose 외부 볼륨이므로 `docker compose down -v`도 이 볼륨을 삭제하지 않습니다. 단, Docker Desktop이나 `docker volume rm exchange_postgres-data`로 직접 삭제하면 복구할 수 없으므로 주의합니다.
