# Phase 6.3 실제 환경 종단간 인수 체크리스트

> 최초 작성: 2026-09-27  
> 상태: 진행 중 — Toss 장중 정상 거래와 장애 복구 수동 시연 대기

## 목적

실제 Toss 시세가 Spring Boot의 서명 견적과 PostgreSQL 주문 상태를 거쳐 Anvil의 `ExchangeVault`에서 정산되고, REST·WebSocket 결과가 웹 기준 클라이언트에 일관되게 표시되는지 확인한다. 자동 통합 테스트와 실제 브라우저 시연 결과를 구분해 기록한다.

## 환경 기준

- PostgreSQL: Docker Compose `exchange-postgres`, 로컬 포트 5432
- Anvil: Docker Compose `exchange-anvil`, 로컬 RPC 8545
- 백엔드: Spring Boot, `http://127.0.0.1:8082`
- 웹 기준 클라이언트: `tools/websocket-test-client`
- 시세 공급자: Toss, 삼성전자 `005930`
- 비밀키와 Toss 자격 증명은 `backend/.env`에서만 읽으며 문서나 로그에 기록하지 않는다.

## 2026-09-27 실행 결과

| 구간 | 확인 내용 | 결과 |
|---|---|---|
| 인프라 | PostgreSQL healthy, Anvil 실행, 백엔드 health `UP` | 통과 |
| 실제 시세 | 시장 API가 `provider=TOSS`, 가격과 관측 시각을 반환 | 통과 |
| 휴장 정책 | 휴장 상태에서 인증 사용자의 견적 발급을 `409 MARKET_CLOSED`로 거부 | 통과 |
| 인증·DB | 실제 PostgreSQL 사용자 회원가입/JWT/`GET /api/me` | 통과 |
| 견적 경합 | 동일 견적 동시 소비 시 한 요청만 성공 | 통과 |
| 온체인 정산 | EIP-712 서명 보고서로 실제 Anvil Vault 매수·매도 및 receipt 처리 | 통과 |
| 컨트랙트 회귀 | Foundry 테스트 36개 | 통과 |
| 백엔드 회귀 | Gradle 전체 테스트 | 통과 |
| 웹 회귀 | Vitest 테스트 30개 | 통과 |
| 실제 Toss 장중 매수·매도 | 브라우저에서 Toss → 서명 견적 → Anvil → DB → WebSocket 전체 연결 | 대기 — 휴장 |
| 장애 복구 시연 | 백엔드/RPC 중단 후 재연결·REST 복구 및 주문 최종 상태 확인 | 대기 |

검증용 PostgreSQL 사용자는 테스트 후 해당 사용자 ID에 연결된 견적·잔고·사용자 행만 삭제했다. 기존 사용자 데이터와 Docker 볼륨은 삭제하지 않았다.

## 장중 정상 거래 인수 절차

1. Docker PostgreSQL과 Anvil, Spring Boot, 웹 기준 클라이언트를 실행한다.
2. 시장 패널에서 `provider=TOSS`, `marketStatus=OPEN`, `priceStatus=LIVE`와 최근 관측 시각을 확인한다.
3. 로그인한 사용자에게 faucet을 지급하고 포트폴리오의 mKRW 증가를 확인한다.
4. 매수 견적을 발급해 `quoteId`, 서명 가격, 수수료, 최소 수령량과 만료 시각을 확인한다.
5. 같은 `quoteId`로 주문을 확정하고 `REQUESTED/PENDING_ONCHAIN → FILLED` 상태, 주문 이벤트와 포트폴리오 이벤트를 확인한다.
6. 주문·체결 내역에서 입력량, 체결량, 체결 가격, transaction hash를 확인하고 Anvil receipt와 대조한다.
7. 보유 mSEC로 매도 견적과 주문을 같은 방식으로 실행해 mSEC 감소와 mKRW 증가를 확인한다.
8. 사용한 견적을 다시 제출했을 때 재사용이 거부되는지 확인한다.

## 장애 복구 인수 절차

1. 웹이 연결된 상태에서 백엔드를 중단해 연결 끊김과 지수 백오프 재시도를 확인한다.
2. 백엔드를 다시 실행해 WebSocket 재연결 뒤 시장·차트·주문·체결·포트폴리오 REST 복구 결과를 확인한다.
3. 새 주문 전 Anvil RPC를 사용할 수 없게 한 뒤 주문 실패, 자산 잠금 해제와 견적/주문 상태를 확인한다.
4. 트랜잭션 저장 이후 receipt 확인만 지연되는 경우 Anvil 복구 뒤 동일 트랜잭션의 최종 정산과 중복 체결 방지를 확인한다.

Anvil 재시작 방식은 체인 상태를 초기화할 수 있다. RPC 장애 시연은 컨테이너를 제거하거나 새 체인으로 재배포하는 방식이 아니라, 현재 체인 상태를 보존할 수 있는 중단 방법을 먼저 확인한 뒤 수행한다.

## 완료 조건

- 표의 두 대기 항목을 실제 실행 결과로 교체한다.
- 정상 매수·매도에서 웹 표시, PostgreSQL 주문·체결, Anvil receipt가 일치한다.
- 장애 복구에서 사용자 자산이 이중 반영되거나 영구 잠기지 않는다.
- 제품 코드 변경이 생기면 관련 자동 테스트를 추가하고 별도 검토를 완료한다.
- `implementation-log.md`와 `project-overview.md`의 Phase 6.3 상태를 완료로 갱신한다.
