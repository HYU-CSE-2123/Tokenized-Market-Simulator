# backend — Spring Boot (Java 21)

삼성전자 가격 추종 토큰 거래소 백엔드. 기획서 §10~13.

## 기술 스택
Java 21 · Spring Boot 3.3 · Spring Web/Security/Data JPA · PostgreSQL · WebSocket(STOMP) · web3j · JWT

## 패키지 구조 (`com.pricetrack.exchange`)
| 패키지 | 책임 |
| --- | --- |
| `auth` | 회원가입/로그인, JWT 발급·검증 |
| `user` | 사용자 엔티티/리포지토리 |
| `wallet` | 지갑 주소 발급/연결 |
| `market` | 현재가, 가격 시뮬레이터(§8.1) |
| `quote` | 매수/매도 견적 계산(§12.3) |
| `order` | 주문 생성·상태(REQUESTED→PENDING_ONCHAIN→FILLED/FAILED) |
| `trade` | 체결 내역 |
| `portfolio` | 포트폴리오 평가/손익 |
| `blockchain` | web3j 온체인 연동(§16 Phase 3) |
| `websocket` | STOMP 설정/이벤트(§13) |
| `common` | 헬스 체크, 보안 설정 |

> 현재는 컴파일·기동 가능한 **스캐폴딩**이다. 대부분의 비즈니스 로직은 `UnsupportedOperationException` 또는 TODO 로 표시되어 Phase 2~4 에서 채운다.

## 빌드 & 테스트
```bash
./gradlew build          # 테스트는 H2 인메모리로 실행 (Postgres 불필요)
```

## 실행
```bash
# 1) 루트에서 Postgres 기동
docker compose up -d postgres

# 2) 백엔드 기동
cd backend && ./gradlew bootRun

# 3) 헬스 체크
curl http://localhost:8080/api/health
# {"status":"UP", ...}
```

## 주요 환경 변수 (application.yml)
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `RPC_URL`,
`MOCK_KRW_ADDRESS`, `MSEC_ADDRESS`, `PRICE_ORACLE_ADDRESS`, `EXCHANGE_VAULT_ADDRESS`, `OPERATOR_PRIVATE_KEY`
