# 자소서 작성용 프로젝트 브리프 — Tokenized Market Simulator

> 작성 기준일: 2026-09-09  
> 용도: 자기소개서·이력서·면접 답변을 작성하는 사람 또는 AI 에이전트에게 전달하는 사실 기준 문서  
> 중요: `[계획]`과 `[담당 외]` 항목을 완료된 성과처럼 서술하지 않는다.

## 1. 30초 프로젝트 설명

Ethereum ERC-20 기반 모의 원화 `mKRW`로 삼성전자 기준 가격을 추종하는 모의 토큰 `mSEC`를 매수·매도하는 학습용 하이브리드 거래소다. Spring Boot와 PostgreSQL이 인증·사용자별 내부 원장·주문·조회·실시간 API를 담당하고, 스마트 컨트랙트가 Oracle 가격에 따른 토큰 발행·소각과 거래 정산을 담당한다. 백엔드는 온체인 트랜잭션을 비동기로 추적해 검증된 receipt만 DB 체결로 반영하고 STOMP WebSocket으로 결과를 전달한다.

이 프로젝트는 실제 삼성전자 주식이나 투자 상품이 아니며 실제 원화, 배당권, 의결권, 상환권을 제공하지 않는다.

## 2. 담당 범위

현재 본인 범위로 정리할 수 있는 영역은 다음과 같다.

- Solidity 스마트 컨트랙트와 Foundry 테스트
- Java 21 / Spring Boot 백엔드
- PostgreSQL 사용자·잔고·주문·체결·온체인 트랜잭션 모델
- web3j 기반 컨트랙트 조회·서명·전송·receipt reconciliation
- JWT 인증과 사용자별 WebSocket 메시지 격리
- Android 전달 전 브라우저 REST·STOMP 검증 도구
- 기술 설계, 테스트 전략, 실행 및 인수인계 문서

Android 앱의 실제 구현은 다른 담당자의 범위다. 자소서에서는 Android를 직접 구현했다고 쓰지 않고, “Android 담당자가 연결할 수 있도록 REST·STOMP 계약과 검증 도구를 제공했다”라고 표현한다.

## 3. 하이브리드 구조를 선택한 이유

완전한 온체인 서비스는 사용자에게 지갑·개인키·가스비·approve·트랜잭션 서명을 요구하며, 로그인·목록 조회·포트폴리오 집계·실시간 UI에는 비효율적이다. 반대로 모든 거래를 DB에서만 처리하면 ERC-20 발행·소각 규칙과 거래 실행의 독립적인 온체인 근거를 남기기 어렵다.

이 프로젝트는 다음 역할 분리를 선택했다.

```text
Spring Boot + PostgreSQL
├─ ID·비밀번호 로그인과 JWT
├─ 사용자별 모의 자산 내부 원장
├─ 주문 접수·상태·체결 조회
├─ 포트폴리오 계산
└─ 실시간 WebSocket 전달

Smart Contracts
├─ mKRW·mSEC ERC-20
├─ Oracle 기준 가격
├─ Vault 매수·매도와 수수료
├─ mSEC mint·burn 권한 강제
└─ receipt와 event 기반 정산 근거
```

현재 MVP는 사용자별 개인 지갑이 아니라 백엔드 운영자 통합 지갑이 온체인 거래를 대신 수행하는 수탁형 구조다. 따라서 사용자별 자산 소유권의 기준은 DB이며, 블록체인은 운영자 거래의 실행·정산 근거다. 사용자별 지갑과 Proof of Reserves는 아직 완료된 기능이 아니다.

## 4. 현재 완료된 구현

### 4.1 스마트 컨트랙트 `[완료]`

- `MockKRW`: 테스트용 ERC-20 모의 원화와 faucet
- `SamsungPriceTrackingToken`: Vault만 mint·burn 가능한 가격 추종 토큰
- `PriceOracle`: `1e8` 정밀도의 기준 가격과 갱신 시각 저장
- `ExchangeVault`: Oracle 가격 기반 매수·매도, 수수료, 유동성 검사
- 재진입 방지, SafeERC20, 관리자 권한, 0 입력·0 주소 검증
- 배포 스크립트, 운영자 준비 스크립트, 매수→가격 변경→매도 시나리오

### 4.2 백엔드와 DB `[완료]`

- 자체 ID·비밀번호 회원가입·로그인, BCrypt, JWT 인증
- `USER`·`ADMIN` 역할과 환경 변수 기반 초기 관리자
- 사용자별 mKRW·mSEC DB 잔고, faucet, 견적, 주문, 체결, 포트폴리오
- PostgreSQL 외부 Docker volume을 통한 데이터 보존
- 블록체인 비활성 시 개발·테스트용 DB mock 거래
- 블록체인 활성 시 실제 Vault 주문과 비동기 상태 관리

### 4.3 web3j 온체인 연동 `[완료]`

- RPC·chain ID·컨트랙트 배포 코드·주소 검증
- Oracle 가격, Vault 수수료, 잔고, allowance, 매수·매도 견적 조회
- 운영자 통합 지갑의 buy·sell 서명과 전송
- nonce 직렬화 및 RPC 전송 전 raw transaction·예상 txHash 선저장
- 주문 입력 자산 잠금과 `PENDING_ONCHAIN` 응답
- receipt polling과 `Bought`·`Sold` 이벤트 파싱
- 성공 시 잔고·Trade·주문·트랜잭션을 하나의 DB transaction으로 반영
- 실패 시 자산 잠금 해제, 이상한 이벤트는 `REVIEW_REQUIRED`로 격리
- 서버 재시작 후 `SIGNED` 거래 조회 및 같은 raw transaction 재전송
- 가격 시뮬레이터 값을 Oracle에 동기화하고 확정 이벤트를 가격 이력으로 저장
- 처리 중 가격 갱신이 있을 때 중간 값을 합쳐 최신 값만 후속 전송하는 coalescing

### 4.4 WebSocket `[완료]`

- Native STOMP `/ws`와 브라우저 fallback SockJS `/ws-sockjs`
- STOMP `CONNECT`의 JWT 검증과 사용자 DB ID 기반 Principal
- 공개 가격·체결 topic과 개인 주문·포트폴리오 queue 분리
- 허용 destination whitelist 및 클라이언트 `SEND` 차단
- `eventId`, `version`, `type`, `occurredAt`, `data` 공통 envelope
- DB transaction의 `AFTER_COMMIT`에서만 메시지 전송, rollback 시 폐기
- 공개 체결 payload에서 `userId`, `orderId`, `txHash` 제거
- 서로 다른 JWT 사용자의 개인 queue 격리

### 4.5 브라우저 검증 도구 `[완료·사용자 확인]`

- Vite·순수 JavaScript·STOMP.js·SockJS로 독립 테스트 클라이언트 구현
- 회원가입·로그인·faucet·견적·매수·매도·주문·포트폴리오 REST 호출
- Native WebSocket과 SockJS 연결 선택
- 공개 가격·체결, 개인 주문·포트폴리오 JSON 실시간 표시
- Vite proxy로 백엔드 REST CORS를 불필요하게 개방하지 않음
- SockJS의 Node식 `global` 참조로 UI 초기화가 중단되는 문제를 `globalThis` 매핑으로 해결
- 실제 브라우저에서 REST 거래와 WebSocket 이벤트 수신을 사용자가 확인함

## 5. 정량적으로 확인된 검증

- Solidity Foundry 테스트: 20개 통과
- 백엔드 전체 자동 테스트: 69개 중 66개 통과, 선택적 Anvil 테스트 3개 기본 실행에서 skipped, 실패·오류 0개
- Anvil 선택 통합 테스트: 실제 컨트랙트 읽기, buy 서명·전송·정산, Oracle 가격 갱신과 이력 저장 검증
- 실제 STOMP 통합 테스트: 공개 가격·체결, JWT 개인 queue, SockJS, 사용자 간 메시지 격리 검증
- Vite production build: 76개 모듈 변환 성공
- npm audit: 설치된 79개 패키지 기준 알려진 취약점 0개

테스트 개수는 이후 코드가 변경되면 최신 테스트 결과로 갱신해야 한다.

## 6. 핵심 문제와 해결 경험

### 6.1 온체인 비동기성과 사용자 응답

문제:

- HTTP 요청 성공과 블록체인 체결 성공은 같은 시점이 아니다.
- 트랜잭션이 pending 또는 실패인데 주문을 즉시 `FILLED`로 처리하면 DB와 체인이 불일치한다.

해결:

- `REQUESTED → PENDING_ONCHAIN → FILLED/FAILED` 상태를 분리했다.
- 주문 입력 자산을 먼저 잠그고 HTTP 202를 반환했다.
- scheduler가 receipt와 Vault 이벤트를 검증한 후 최종 상태를 반영했다.

### 6.2 RPC 응답 유실과 중복 전송

문제:

- 트랜잭션은 체인에 전달됐지만 서버가 응답을 받기 전에 종료될 수 있다.
- 새 nonce로 다시 서명하면 같은 주문이 두 번 실행될 수 있다.

해결:

- RPC broadcast 전에 nonce·raw transaction·예상 txHash를 독립 transaction으로 저장했다.
- 재시작 시 체인에서 txHash를 먼저 조회하고, 없다면 저장된 동일 raw transaction만 재전송했다.

### 6.3 DB commit과 실시간 알림 순서

문제:

- DB transaction이 rollback됐는데 WebSocket으로 성공 알림이 먼저 전송될 수 있다.

해결:

- 도메인 서비스와 STOMP broker 사이에 내부 이벤트 경계를 만들었다.
- `AFTER_COMMIT` listener로 commit된 데이터만 전송하고 rollback 이벤트는 폐기했다.
- 유실 가능한 WebSocket은 알림으로 한정하고 REST·DB를 최종 상태 기준으로 정했다.

### 6.4 운영자 nonce 충돌

문제:

- 주문과 Oracle 갱신이 같은 운영자 지갑을 동시에 사용하면 같은 nonce를 선택할 수 있다.

해결:

- 단일 백엔드 프로세스 안에서 서명·전송 경로를 직렬화했다.
- 다중 인스턴스 전환 시 분산 nonce lock이 필요하다는 한계를 문서화했다.

### 6.5 공개·개인 실시간 데이터 경계

문제:

- 공개 체결 스트림에 사용자·주문·txHash를 포함하면 거래 추적 정보가 노출된다.
- STOMP 연결만 성공하면 다른 사용자의 queue를 구독할 위험이 있다.

해결:

- 공개 topic과 JWT 개인 queue를 분리하고 destination whitelist를 적용했다.
- 개인 queue를 변경 가능한 login ID가 아닌 사용자 DB ID Principal로 라우팅했다.
- 공개 체결 payload에는 시장 정보만 포함했다.

## 7. 설계상 의도적으로 제한한 범위

- 실제 주식·원화·투자 기능이 아닌 모의 거래
- 실제 한국거래소 시세 대신 75,000원에서 시작해 기본 1초마다 -0.3%~+0.3% 변하는 시뮬레이터
- 주문장·지정가·예약 주문이 아닌 Oracle 가격 기반 시장가 거래
- 사용자별 온체인 지갑이 아닌 운영자 통합 지갑과 DB 내부 원장
- 견적 시점과 실행 시점 가격이 달라질 수 있으며 현재 slippage protection 없음
- WebSocket은 메시지 영속성을 보장하지 않으며 재연결 후 REST로 재동기화
- 단일 운영자 nonce lock은 단일 백엔드 인스턴스 기준
- Google OAuth, 이메일 인증, 리프레시 토큰은 MVP 이후

제한사항을 숨기지 말고, MVP 복잡도를 통제하면서 확장 지점을 분리한 설계 판단으로 설명한다.

## 8. 앞으로의 계획

아래 항목은 아직 완료되지 않았다. 자소서에서는 “추후 구현했다”가 아니라 “다음 단계로 설계·검증할 계획”으로만 사용한다.

### 8.1 백엔드·블록체인 보강 `[계획·논의 중]`

- 수탁형 하이브리드 구조와 블록체인의 정산 근거 역할을 제안서에 명확히 반영
- 모든 `FILLED` 주문과 확정 온체인 transaction의 1:1 대응을 조회 가능한 형태로 정리
- DB 사용자 자산 합계와 운영자 온체인 자산을 비교하는 준비금 reconciliation 검토
- 사용자별 faucet 횟수·한도와 운영자 mKRW 준비 정책 결정
- REST 시장 가격의 실제 `changeRate`와 마지막 `updatedAt` 제공
- 차트용 가격 tick sampling·저장 정책 결정
- 포트폴리오의 총·사용 가능·잠긴 잔고 및 수익률 표시 검토
- 본인 주문의 온체인 block number·확정 시각·실패 사유 조회 API 검토

### 8.2 클라이언트 인수인계 `[계획]`

- 웹에서 블록체인 활성 모드의 `PENDING_ONCHAIN → FILLED/FAILED` 종단간 재검증
- REST 요청·응답, 오류, JWT, STOMP destination과 JSON을 통합한 클라이언트 연동 가이드
- Android 에뮬레이터 `10.0.2.2`, 실제 기기 PC IP, 개발용 cleartext 설정 안내
- Android 담당자에게 Native `/ws` 기준 계약과 재연결 후 REST 재동기화 원칙 전달

### 8.3 최종 마감 `[계획]`

- 현재 코드로 Solidity·백엔드·Anvil·PostgreSQL 전체 회귀 테스트
- 로컬 Anvil 기반 회원가입→faucet→온체인 매수→가격 변화→매도 시연
- 아키텍처·API·컨트랙트·실행 절차 최종 문서
- Android 담당 결과와 합친 최종 시연 자료·화면 캡처·영상
- Sepolia 배포와 explorer 연결은 시간·테스트 ETH 상황에 따른 선택 범위

### 8.4 MVP 이후 확장 `[계획]`

- 지정가·예약 주문, 취소·만료, 입력 자산 선잠금
- `minTokenOut`, `maxKrwIn`, `deadline` 기반 slippage protection
- 사용자별 직접 지갑과 WalletConnect
- Proof of Reserves, 잔고 Merkle root와 사용자 proof
- 실제 또는 과거 삼성전자 시세 연동
- Google OAuth·이메일 인증·계정 연결·리프레시 토큰
- 다중 서버 인스턴스용 분산 nonce lock

## 9. 자소서용 핵심 역량 매핑

| 강조 역량 | 프로젝트 근거 |
| --- | --- |
| 문제 정의 | 즉시 끝나지 않는 온체인 주문을 동기 HTTP 거래처럼 처리하면 안 된다는 문제를 상태 모델로 분리 |
| 백엔드 설계 | 인증·주문·체결·원장·포트폴리오·실시간 이벤트 도메인 구성 |
| 데이터 일관성 | 자산 행 잠금, 입력 자산 lock, 멱등 receipt 정산, unique 제약, `AFTER_COMMIT` 이벤트 |
| 장애 복구 | raw transaction 선저장과 `SIGNED` 재시작 복구 |
| 보안 | BCrypt·JWT, STOMP 인증, 사용자 queue 격리, 공개 payload 정보 최소화 |
| 블록체인 | ERC-20, Oracle, Vault, ABI, web3j, nonce, receipt, event parsing |
| 테스트 | Foundry 단위·fuzz, Spring 단위·통합, 실제 Anvil·STOMP 선택 통합 테스트 |
| 협업·인수인계 | 역할별 패키지·주석·공통 문서, 실행 가능한 브라우저 검증 클라이언트 |

## 10. STAR 소재

### 소재 A — 비동기 온체인 정산

- Situation: 매수 API 응답과 블록체인 확정 사이의 시간차로 주문·잔고가 어긋날 수 있었다.
- Task: 중복 체결과 자산 재사용을 막으면서 사용자가 진행 상태를 확인하게 해야 했다.
- Action: 입력 자산을 잠그고 주문 상태를 분리했으며 receipt 이벤트를 주문 입력과 대조한 뒤 하나의 DB transaction으로 정산했다. 재실행에 대비해 멱등 조건도 적용했다.
- Result: 주문이 `PENDING_ONCHAIN`을 거쳐 검증 후에만 `FILLED/FAILED`가 되고, 반복 reconciliation에도 체결과 잔고가 한 번만 반영되는 테스트를 통과했다.

### 소재 B — 장애 복구 가능한 트랜잭션 전송

- Situation: RPC 응답을 받기 전 서버가 종료되면 전송 여부를 알 수 없어 같은 주문을 중복 실행할 위험이 있었다.
- Task: 외부 노드 장애와 프로세스 재시작에도 같은 거래를 안전하게 복구해야 했다.
- Action: broadcast 전에 서명 원문·nonce·txHash를 저장하고, 재시작 시 txHash를 조회한 뒤 필요할 때만 동일 raw transaction을 재전송했다.
- Result: 새 트랜잭션을 만들지 않고 동일 txHash로 복구하는 흐름을 구축하고 실제 Anvil 통합 테스트로 확인했다.

### 소재 C — DB와 WebSocket 일관성

- Situation: 실시간 알림이 DB commit보다 먼저 나가면 사용자가 존재하지 않는 체결을 볼 수 있었다.
- Task: 빠른 알림을 유지하면서 rollback된 상태가 외부에 노출되지 않게 해야 했다.
- Action: 공통 versioned envelope와 내부 delivery event를 만들고 `AFTER_COMMIT` listener에서 공개·개인 STOMP 메시지를 전송했다.
- Result: commit 전 미전송, rollback 시 폐기, 사용자별 queue 격리를 자동 테스트와 실제 endpoint 테스트로 검증했다.

## 11. 바로 사용할 수 있는 서술 초안

### 이력서 한 줄

> Spring Boot·PostgreSQL과 Solidity 스마트 컨트랙트를 연동한 수탁형 하이브리드 모의 거래소를 구현하고, 온체인 주문의 비동기 상태·멱등 정산·재시작 복구·JWT 기반 실시간 이벤트 전달 구조를 설계했습니다.

### 짧은 자소서 문단

> 단순 CRUD를 넘어 외부 시스템의 불확실성을 견디는 백엔드를 경험하고자 ERC-20 가격 추종 토큰 거래소를 개발했습니다. 블록체인 트랜잭션은 HTTP 요청과 동시에 끝나지 않기 때문에 주문을 `PENDING_ONCHAIN`으로 분리하고 입력 자산을 잠갔으며, receipt와 컨트랙트 이벤트를 검증한 후에만 잔고와 체결을 반영했습니다. 또한 RPC 응답 유실에 대비해 서명 원문과 txHash를 전송 전에 저장하고 동일 원문을 재전송하는 복구 흐름을 설계했습니다. 실시간 알림은 DB commit 이후에만 발행해 데이터와 화면의 불일치를 방지했습니다.

### 협업 강조 문단

> 모바일 담당자가 서버 내부 구현을 알지 못해도 연결할 수 있도록 REST·JWT·STOMP 계약을 분리하고, Native WebSocket과 SockJS를 직접 시험하는 브라우저 검증 도구를 만들었습니다. 공개 시장 데이터와 사용자별 개인 알림을 분리하고 실제 두 사용자의 메시지 격리를 검증했으며, 코드 위치·실행 절차·설계 판단과 제한사항을 공통 문서에 기록해 인수인계 비용을 줄였습니다.

## 12. 자소서 작성 에이전트 지침

1. 지원 직무와 문항을 먼저 확인하고 관련 역량만 선택한다.
2. 수치가 필요하면 이 문서의 검증 수치만 사용하고 임의의 사용자 수·성능 향상률·매출을 만들지 않는다.
3. Android 앱, 준비금 reconciliation, 사용자별 지갑, Sepolia 배포를 완료했다고 쓰지 않는다.
4. “탈중앙화 거래소”라고 표현하지 않는다. 현재 구조는 운영자 통합 지갑과 DB 원장을 사용하는 수탁형 하이브리드 거래소다.
5. 실제 삼성전자 주식·실제 원화·투자 서비스처럼 표현하지 않는다.
6. DB mock 거래는 개발·테스트 경로이며 최종 온체인 흐름과 구분한다.
7. 팀 규모, 개발 기간, 본인의 공식 직책과 기여 비율은 이 문서에 근거가 없으므로 사용자에게 확인한다.
8. 향후 계획은 문항이 개선점·입사 후 계획·확장성을 요구할 때만 사용한다.

## 13. 추가로 사용자에게 확인해야 할 정보

자소서를 최종 작성하기 전에 다음 정보를 받아야 한다.

- 지원 회사와 직무
- 자기소개서 문항과 글자 수
- 프로젝트 기간
- 팀 인원과 역할 분담
- 본인의 공식 담당 역할
- 가장 강조하고 싶은 경험 하나
- 실제로 본인이 발표·시연한 범위
- Android 담당자와 협업한 결과 및 피드백
- 준비금 검증 등 향후 보강 기능의 최종 채택 여부

## 14. 근거 문서

- `구현 계획.md`: 최초 제안서와 MVP 목표
- `claude-docs/project-overview.md`: 현재 Phase 요약
- `claude-docs/implementation-log.md`: Phase별 구현·검증 기록
- `코드 구조 및 역할.md`: 컨트랙트와 백엔드 코드 해설
- `backend/README.md`: 실행 환경, REST·WebSocket, 온체인 연동 방법
- `tools/websocket-test-client/README.md`: 브라우저 연동 검증 절차
