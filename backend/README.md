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
- Google 로그인과 이메일 인증을 위한 nullable 사용자 컬럼 준비

Google OAuth, 이메일 인증과 리프레시 토큰은 아직 구현하지 않았습니다. Phase 3.3은 온체인 주문 전송 후 receipt와 `Bought`/`Sold` 이벤트를 확인해 주문·잔고·체결을 자동 확정합니다.

## 인증 API

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 불필요 | 자체 계정 생성 및 액세스 토큰 발급 |
| POST | `/api/auth/login` | 불필요 | 아이디·비밀번호 로그인 |
| GET | `/api/me` | Bearer JWT | 현재 사용자 조회 |

`loginId`는 영문자·숫자·`_`·`-`로 구성된 4~30자이며 대소문자를 구분하지 않습니다. 비밀번호는 8~72자입니다.

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
| `market` | 현재가와 가격 시뮬레이션 |
| `quote` | 매수·매도 견적 계산 |
| `wallet`, `order`, `trade`, `portfolio` | 모의 잔고·주문·체결·포트폴리오 |
| `blockchain` | web3j 읽기·전송, receipt polling, 이벤트 파싱, 멱등 정산·복구 |
| `websocket` | STOMP 설정과 가격 스트림 |
| `common` | 보안 설정, 헬스 체크, 공통 오류 처리 |

## 테스트

```powershell
cd backend
.\gradlew.bat test --no-daemon
```

테스트는 H2 인메모리 DB를 사용하며 PostgreSQL 없이 실행할 수 있습니다. 인증/JWT·거래 계산 단위 테스트와 MockMvc 기반 회원가입→faucet→매수→매도→포트폴리오 통합 흐름을 포함합니다.

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
- OS 환경 변수가 같은 이름으로 설정돼 있으면 `.env`보다 OS 환경 변수가 우선합니다.
- `JWT_SECRET`, `ADMIN_PASSWORD`, `OPERATOR_PRIVATE_KEY`는 `.env`에서 직접 입력하고 주석을 해제합니다.
- `.env`가 없어도 기본값으로 기동할 수 있지만 관리자는 생성되지 않습니다.

```powershell
docker compose up -d postgres
cd backend
.\gradlew.bat bootRun
```

주요 환경 변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `BLOCKCHAIN_ENABLED`, `RPC_URL`, `MOCK_KRW_ADDRESS`, `MSEC_ADDRESS`, `PRICE_ORACLE_ADDRESS`, `EXCHANGE_VAULT_ADDRESS`, `OPERATOR_PRIVATE_KEY`.

`BLOCKCHAIN_ENABLED`의 기본값은 `false`입니다. 기존 DB 모의 거래만 사용할 때는 그대로 두며, Phase 3 web3j 기능을 사용할 때 `true`로 바꿉니다. 활성화 후 연결 검증을 호출하면 RPC, 개인키, 네 컨트랙트 주소와 실제 배포 코드를 엄격히 검사합니다.

온체인 주문에서는 `user_balances.locked_amount`가 처리 중인 입력 자산을 나타냅니다. 사용 가능 잔고는 `amount - locked_amount`입니다. 성공 이벤트 확정 시 입력 잔고 차감·출력 잔고 추가·Trade 생성이 하나의 DB transaction으로 처리되고, 실패 receipt는 잠금만 해제합니다.

receipt 처리 설정은 `BLOCKCHAIN_RECEIPT_POLL_INTERVAL_MS`(기본 1000), `BLOCKCHAIN_RECEIPT_INITIAL_DELAY_MS`(기본 1000), `BLOCKCHAIN_REQUIRED_CONFIRMATIONS`(기본 1)입니다. 서버 재시작 후 `SIGNED` 기록은 체인 존재 여부를 확인하고, 필요하면 저장된 동일 raw transaction을 재전송합니다.

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
