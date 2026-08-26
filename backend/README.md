# Backend — Spring Boot (Java 21)

삼성전자 가격 추종 토큰 거래소의 REST API 및 WebSocket 서버입니다.

## 현재 구현 상태

- 자체 회원가입: `loginId`, `password`, `nickname`
- 자체 로그인 및 JWT 액세스 토큰 발급
- BCrypt 비밀번호 해시 저장
- JWT 인증 필터와 보호 API `GET /api/me`
- 통일된 JSON 오류 응답 및 입력값 검증
- 공개 시장 조회와 모의 가격·견적 계산
- Google 로그인과 이메일 인증을 위한 nullable 사용자 컬럼 준비

Google OAuth, 이메일 인증, 리프레시 토큰, 지갑, 주문·체결·포트폴리오 및 web3j 연동은 아직 구현하지 않았습니다.

## 인증 API

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 불필요 | 자체 계정 생성 및 액세스 토큰 발급 |
| POST | `/api/auth/login` | 불필요 | 아이디·비밀번호 로그인 |
| GET | `/api/me` | Bearer JWT | 현재 사용자 조회 |

`loginId`는 영문자·숫자·`_`·`-`로 구성된 4~30자이며 대소문자를 구분하지 않습니다. 비밀번호는 8~72자입니다.

## 패키지 구조

| 패키지 | 책임 |
| --- | --- |
| `auth` | 회원가입·로그인, JWT 발급·검증, 인증 필터 |
| `user` | 사용자 엔티티와 리포지토리 |
| `market` | 현재가와 가격 시뮬레이션 |
| `quote` | 매수·매도 견적 계산 |
| `wallet`, `order`, `trade`, `portfolio` | 후속 Phase 구현 영역 |
| `blockchain` | Phase 3 web3j 연동 영역 |
| `websocket` | STOMP 설정과 가격 스트림 |
| `common` | 보안 설정, 헬스 체크, 공통 오류 처리 |

## 테스트

```powershell
cd backend
.\gradlew.bat test --no-daemon
```

테스트는 H2 인메모리 DB를 사용하며 PostgreSQL 없이 실행할 수 있습니다. 인증 서비스 단위 테스트, JWT 단위 테스트, MockMvc 기반 회원가입→로그인→내 정보 통합 흐름을 포함합니다.

## 로컬 실행

```powershell
docker compose up -d postgres
cd backend
.\gradlew.bat bootRun
```

주요 환경 변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `RPC_URL`, `MOCK_KRW_ADDRESS`, `MSEC_ADDRESS`, `PRICE_ORACLE_ADDRESS`, `EXCHANGE_VAULT_ADDRESS`, `OPERATOR_PRIVATE_KEY`.
