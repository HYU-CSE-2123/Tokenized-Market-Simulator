# 취업용 프로젝트 사실 원본 — Tokenized Market Simulator

> 작성 기준일: 2026-10-04 (AI Phase 1~8 완료. 사용자 확인에 따라 취업용 원본 정리)
> 용도: 자기소개서·이력서·면접 답변을 작성하는 사람 또는 AI 에이전트에게 전달하는 사실 기준 문서  
> 사용법: 프로젝트 마스터의 지식 자료에 이 파일 전체를 최신 원본으로 추가한다. 다른 문서 없이도 아래 사실·수치·제한을 구분해 사용할 수 있으며, 근거 경로는 저장소에서 추가 확인할 때 사용한다.
> 중요: 구현 완료, 미구현 계획, 구현됐지만 아직 검증하지 않은 시나리오를 구분한다. 프로젝트 성과와 개인의 직접 수행·기여 비율도 동일시하지 않는다.

## 현재 상태 한눈에 보기

| 구분 | 현재 사실 |
| --- | --- |
| 구현 완료 | 백엔드·컨트랙트·Toss 시세/차트·웹 기준 클라이언트, AI Phase 1~8의 RAG·조회 Tool·제한 Agent·Skill·자동 진단·관측 |
| 구현 완료 / 인수 미완료 | 웹 거래·복구 기능은 구현됐지만 실제 Toss 장중 전체 시연과 백엔드/RPC 장애 복구 수동 인수는 대기(거래소 Phase 6.3) |
| 미구현 / 담당 외 | Android 앱 완성·기기 검증은 별도 담당 범위. 저장소에는 스캐폴딩이 있음 |
| 미구현 / 확장 후보 | Google 로그인·이메일 인증·예약/지정가 주문·사용자별 지갑·준비금 대사/Proof of Reserves·Sepolia 배포 |
| 검증 한계 | AI 상태/receipt fixture 평가를 실제 운영 장애 평가로 볼 수 없으며, 다중 서버 chaos·자연어 완전 진실성·운영 SLA는 검증하지 않음 |

거래소 Phase와 AI Phase는 별도 번호다. **AI Phase 8 완료는 거래소 Phase 6.3의 수동 인수나 Android 구현 완료를 뜻하지 않는다.**

## 1. 30초 프로젝트 설명

Ethereum ERC-20 기반 모의 원화 `mKRW`로 삼성전자 실시간 기준 가격을 추종하는 모의 토큰 `mSEC`를 매수·매도하는 학습용 하이브리드 거래소다. Spring Boot와 PostgreSQL이 인증·사용자별 내부 원장·견적·주문·조회·실시간 API를 담당하고, 스마트 컨트랙트가 백엔드 전용 가격 서명과 일회용 견적을 검증해 토큰 발행·소각과 거래를 원자적으로 정산한다. Toss 증권 API의 현재가·시장 상태·과거 캔들을 수신하며, 백엔드는 온체인 트랜잭션을 비동기로 추적해 검증된 receipt만 DB 체결로 반영하고 STOMP WebSocket으로 결과를 전달한다. 브라우저 기준 클라이언트에서는 로그인부터 견적·거래·포트폴리오·실시간 다중 주기 차트와 연결 복구까지 MVP 흐름을 확인할 수 있다.

여기에 승인 문서 검색과 인가된 조회 사실을 결합하는 AI 보조 진단 모듈을 추가했다. 8개 read-only Tool, 3개 고정 Skill, `REVIEW_REQUIRED` 이후의 자동 분석과 ADMIN 진단 이력을 제공하지만 거래·잔고 수정이나 자동 복구를 실행하지 않는다.

이 프로젝트는 실제 삼성전자 주식이나 투자 상품이 아니며 실제 원화, 배당권, 의결권, 상환권을 제공하지 않는다.

기술 스택: Java 21, Spring Boot/Spring Security, JPA, PostgreSQL, web3j, Solidity/Foundry/Anvil, STOMP/SockJS, Vite/JavaScript, Docker Compose. AI는 Spring Boot 내부 provider adapter와 별도 PostgreSQL+pgvector, `text-embedding-3-small`(1536차원), `gpt-5.6-terra`를 사용한다. Spring AI·Kafka·Redis는 사용하지 않는다.

## 2. 담당 범위

취업 자료에서 다룰 프로젝트 작업 범위는 다음과 같다. 이는 저장소에 구현된 영역 목록이며, 모든 코드를 본인이 직접 작성했다거나 공식 기여 비율을 입증하는 목록은 아니다. 개발 에이전트의 구현·테스트·검토 지원을 활용했으므로 본인이 직접 수행한 문제 정의·설계 판단·검토·실행·시연 범위는 별도로 확인한다.

- Solidity 스마트 컨트랙트와 Foundry 테스트
- Java 21 / Spring Boot 백엔드
- PostgreSQL 사용자·잔고·주문·체결·온체인 트랜잭션 모델
- web3j 기반 컨트랙트 조회·서명·전송·receipt reconciliation
- JWT 인증과 사용자별 WebSocket 메시지 격리
- 실제 시세 REST·WebSocket 연동과 OHLCV 집계
- 전체 MVP 흐름을 실행하는 브라우저 REST·STOMP 검증 클라이언트
- 기술 설계, 테스트 전략, 실행 및 인수인계 문서
- 별도 pgvector 지식 검색, 권한 기반 read-only Tool·Agent·Skill, 운영 이벤트 자동 진단과 AI 평가/보안/관측성

Android 앱의 실제 구현은 다른 담당자의 범위이며 현재 개발 우선순위에서도 제외되어 있다. 백엔드·컨트랙트의 모든 기능을 먼저 웹에서 검증하고, 자소서에서는 Android를 직접 구현했다고 쓰지 않고 “Android 담당자가 연결할 수 있도록 REST·STOMP 계약과 웹 검증 클라이언트를 제공했다”라고 표현한다.

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
├─ Oracle의 EIP-712 서명·종목·유효 시간·재사용 검증과 최근 승인 가격 기록
├─ Vault의 방향·executor·입력량·최소 수령량 검증과 매수·매도 정산
├─ mSEC mint·burn 권한 강제
└─ receipt와 event 기반 정산 근거
```

현재 MVP는 사용자별 개인 지갑이 아니라 백엔드 운영자 통합 지갑이 온체인 거래를 대신 수행하는 수탁형 구조다. 따라서 사용자별 자산 소유권의 기준은 DB이며, 블록체인은 운영자 거래의 실행·정산 근거다. 사용자별 지갑과 Proof of Reserves는 아직 완료된 기능이 아니다.

## 4. 현재 완료된 구현

### 4.1 스마트 컨트랙트 `[완료]`

- `MockKRW`: 테스트용 ERC-20 모의 원화와 faucet
- `SamsungPriceTrackingToken`: Vault만 mint·burn 가능한 가격 추종 토큰
- `PriceOracle`: EIP-712 가격 보고서의 승인 서명자·종목·30초 유효 시간과 `quoteId` 일회성 사용을 검증하고 마지막 승인 가격·관측 시각을 기록
- `ExchangeVault`: 보고서의 방향·executor·입력량·최소 수령량을 거래 의미에 맞게 검사하고, 검증된 `PriceReport + signature`로만 수수료·유동성 확인과 매수·매도 정산 수행
- 재진입 방지, SafeERC20, 관리자 권한, 0 입력·0 주소 검증
- 배포 스크립트, 운영자 준비 스크립트, 서명 견적 매수·매도 시나리오
- `updatePrice()`로 저장한 가격과 무서명 호출을 사용하는 Vault 거래 경로 제거. 관리용 `updatePrice()`와 참고용 최근 가격 상태는 남아 있지만 `buy/sell` 체결에는 사용하지 않음

### 4.2 백엔드와 DB `[완료]`

- 자체 ID·비밀번호 회원가입·로그인, BCrypt, JWT 인증
- `USER`·`ADMIN` 역할과 환경 변수 기반 초기 관리자
- 사용자별 mKRW·mSEC DB 잔고, faucet, 견적, 주문, 체결, 포트폴리오
- 인증 사용자에게 귀속된 30초 유효 서명 견적 저장과 소유권·방향·입력량·상태·만료 검증
- 사용자는 서명 원문이 아닌 `quoteId`만 제출하고, 서버가 DB의 가격 보고서와 서명을 복원해 주문 실행
- 견적 소비·주문 생성·입력 자산 잠금을 하나의 DB transaction으로 처리하고 동일 견적 동시 소비 차단
- PostgreSQL 외부 Docker volume을 통한 데이터 보존
- 블록체인 비활성 시 개발·테스트용 DB mock 거래
- 블록체인 활성 시 실제 Vault 주문과 비동기 상태 관리

### 4.3 web3j 온체인 연동 `[완료]`

- RPC·chain ID·컨트랙트 배포 코드·주소 검증
- Vault 수수료, 잔고, allowance와 명시 가격 기준 매수·매도 견적 조회
- 가격 보고서 서명 키와 거래 전송용 운영자 키 분리 및 기동 시 온체인 서명자 일치 검증
- 운영자 통합 지갑의 `buy/sell(PriceReport, signature)` 서명과 전송
- nonce 직렬화 및 RPC 전송 전 raw transaction·예상 txHash 선저장
- 주문 입력 자산 잠금과 `PENDING_ONCHAIN` 응답
- receipt polling과 `Bought`·`Sold` 이벤트 파싱
- 성공 시 잔고·Trade·주문·트랜잭션을 하나의 DB transaction으로 반영
- 실패 시 자산 잠금 해제, 이상한 이벤트는 `REVIEW_REQUIRED`로 격리
- 서버 재시작 후 `SIGNED` 거래 조회 및 같은 raw transaction 재전송
- RPC 전송 전 실패는 주문·견적·잠금을 함께 복구하고, raw transaction 저장 후 장애는 동일 원문 재전송으로 복구

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
- 견적 발급과 주문 확정을 분리하고 서명 가격·수수료·최소 수령량·만료·최종 온체인 체결가 표시
- 주문·체결 내역 조회와 Android MVP 기능 대응 완료
- WebSocket 단절 시 1~30초 지수 백오프로 재연결하고 시장·차트·주문·체결·포트폴리오를 REST로 재동기화
- 계정 전환·인증 만료·오래된 비동기 응답에서 다른 사용자의 상태나 이전 연결 이벤트가 섞이지 않도록 연결 세대 격리

### 4.6 실제 시장 데이터와 차트 `[완료]`

- 설정으로 시뮬레이션과 Toss 시장 데이터 공급자를 교체하는 공통 인터페이스
- Toss OAuth 토큰 관리와 삼성전자 초기 현재가 REST 조회
- Toss WebSocket `trade:kr:005930` 구독, keepalive·재연결 및 실시간 체결가 반영
- 시장 개장·마감·휴장 상태와 가격 신선도를 API에 노출
- 장 마감·휴장 또는 오래된 가격일 때 신규 견적 발급 차단. 온체인 주문은 유효한 `quoteId`가 필수이므로 새 거래 진입도 차단
- `1m`, `5m`, `15m`, `30m`, `1h`, `1d` OHLCV 캔들 API
- Toss 1분봉의 종료 시각을 내부 봉 시작 시각으로 정규화하고 상위 분봉 집계
- REST 과거 봉과 WebSocket tick을 결합하는 KST 실시간 캔들·거래량 차트
- 주기 변경·재연결 중 오래된 REST 응답과 실시간 tick이 섞이지 않도록 요청 세대별 격리

### 4.7 서명 가격 기반 원자적 거래 `[완료]`

- 사용자가 확인한 Toss 시장 스냅샷으로 백엔드가 30초 유효 `PriceReport` 생성
- 전용 가격 키로 EIP-712 서명하고 견적 소유 사용자와 함께 PostgreSQL에 저장
- 클라이언트에는 가격·수수료·예상/최소 수령량·만료·`quoteId`를 제공하되 전체 보고서·서명 원문은 제공하지 않음. 주문에는 서명 원문 대신 `quoteId`를 제출하게 해 서버가 소유권과 사용 상태를 통제
- 서버가 DB row lock으로 견적을 한 번만 소비하고 주문·자산 잠금과 함께 원자적으로 반영
- 같은 트랜잭션에서 Oracle은 서명자·종목·유효 시간·재사용을, Vault는 executor·방향·입력량·최소 수령량을 검증한 뒤 정산
- 실제 PostgreSQL 동시성 테스트에서 같은 견적의 이중 소비 차단 확인

### 4.8 웹 MVP·계약·인수 문서 `[구현 완료]`

- Android MVP가 요구하는 인증·시장·차트·faucet·포트폴리오·견적·주문·내역·체결 기능을 웹 기준 클라이언트에서 제공
- REST·JWT·STOMP 계약, 기능 대응표, 이벤트 payload·재연결 원칙, 실행 절차와 실제 환경 인수 체크리스트 작성
- 실제 환경 경계·휴장 정책 및 자동 회귀의 실행 범위는 5절, 남은 수동 인수는 8절로 분리

### 4.9 AI 조회·진단 확장 `[AI Phase 1~8 완료]`

- 기존 Spring Boot 내부 모듈과 얇은 provider adapter, 거래 DB/JPA와 별도의 PostgreSQL+pgvector/volume을 사용한다. Spring AI·Kafka·Redis·외부 검색 서버는 도입하지 않았다.
- 승인 지식 9개를 role/domain·version/content hash/manifest/indexVersion으로 관리하고, Markdown 제목·문장 경계 청크와 text-embedding-3-small 1536차원 semantic 검색을 구현했다. 채택 설정은 1200바이트 청크·similarity 0.25·최종 Top-5에서 문서당 최대 2개다. Hybrid/RRF는 비교 실험이며 기본 경로가 아니다.
- gpt-5.6-terra 기반 KNOWLEDGE/STATE/MIXED Agent가 승인 정책과8개 읽기 Tool의 관측 사실을 결합한다. USER 본인 조회·ADMIN 운영 조회를 서버에서 강제하고 Tool4/검색1/모델2·40초·worker2로 제한한다.
- settlement-debugging, signed-quote-diagnosis, market-availability-diagnosis의3개 고정 Skill/registry/handler와 안전한 단계 trace를 구현했다.
- 커밋된 REVIEW_REQUIRED를 독립 polling하고 AI DB fingerprint unique·claim/lease/quota로 자동 settlement-debugging과 ADMIN 이력을 연결한다. 자동 복구·거래·잔고 변경은 없다.
- 종료 marker의 결과·actor·txHash·claim 메타 TTL을 기본 7일로 통일하고, 중복 방지용 SHA-256 fingerprint와 대상/종료 상태 marker는 보존한다. 전체 기록 삭제나 익명화 완료를 뜻하지 않는다.
- 고정 label의 프로세스 내 관측 집계와 ADMIN 조회 API, 질문·문서·Tool 주입 및 secret canary 테스트, 최종 6개 AI 문서를 작성했다. 집계는 재시작 시 초기화되며 별도 모니터링 서버나 영속 관측 저장소가 아니다.
- RAG 단독 API는 ADMIN 전용, Agent/Skill은 USER 본인 데이터와 ADMIN 운영 조회를 구분한다. 웹에는 최소 ADMIN 진단 패널이 있지만 독립 챗봇 UI는 없다.
- 자동 진단은 구현·격리 검증 완료이나 기본 비활성이다. Phase 8에서는 실제 로컬 `.env` 변경·운영 진단 schema 초기화·자동 진단 활성화를 하지 않았다. 실제 공급자 최종 평가와 별도 검토 근거는 [AI 평가 보고서](../docs/ai/EVALUATION_REPORT.md)에 있다.
- 진단 state/receipt fixture와 실제 모델/embedding/pgvector 평가를 운영 Toss·실사용자 장애 검증으로 서술하지 않는다. 완전한 자연어 정확성·무손실 polling·프로덕션 SLA를 보장하지 않는다.

## 5. 정량적으로 확인된 검증

### 5.1 최신 실행 결과 (2026-10-03 AI Phase 8)

| 검증 | 결과 | 해석 경계 |
| --- | --- | --- |
| 백엔드 전체 회귀 | 334개 중 322 통과·12 skip·실패/오류 0 | 실제 AI/거래 PostgreSQL·Anvil 조회 opt-in 포함. skip은 통과가 아님 |
| 별도 유료 AI 평가 | Java opt-in 테스트 2개 통과 | 위 skip 중 KnowledgeRefresh/Phase8Live를 별도 실행. 나머지 조건부 10개는 이 실행에서 미실행 |
| 컨트랙트 | Foundry 36개 통과 | 테스트 EVM 검증이며 Sepolia 배포 실적이 아님 |
| 웹 | 단위 테스트 34개 통과·Vite build 성공 | 최신 AI ADMIN 패널의 브라우저 수동 인수와 구분 |
| 독립 검토 | 필수 수정 사항 없음·격리 테스트 34개 직접 통과 | 검토자는 전체 suite·공유 DB/Anvil·유료 평가를 재실행하지 않고 raw 평가를 대조 |

이번 취업용 문서 점검에서는 테스트나 유료 평가를 다시 실행하지 않았다. 위 수치는 해당 날짜의 실행 기록이며 현재 XML은 후속 표적 테스트 결과로 대체될 수 있다.

### 5.2 AI 검색·응답 평가

- 동일 golden 12개에서 hit@5: Phase 2 **10/12 → 채택 조합 12/12**. MRR@5: **0.7778 → 0.8819**. Phase 8도 hit/MRR와 직접 답변 근거 **12/12**를 유지했다.
- 무관 질문 8개 중 검색 후보가 나온 질문은 **5/8**, 최종 오답은 **0/8**. 이는 무관 문서 검색이 전혀 없다는 뜻이 아니다.
- 정확 식별자 검색 hit **6/8**, USER 권한 제한 검색 **3/3**, 핵심 질문 `ANSWERED` **2/2**.
- 실제 공급자 대표 요청 **8/8 기대 결과**: 정상 응답 6개(`ANSWERED` 2·`PARTIAL` 4)와 안전 거부 2개. `PARTIAL`은 불확실성을 남긴 정상 진단이며 모든 질문에 완전한 답을 했다는 뜻이 아니다.
- 모델·embedding·pgvector·AI 이력은 실제 경로, 거래 state/receipt는 격리 H2·receipt fixture다. Agent 지연 7표본 중앙값 6.130초·nearest-rank p95 7.726초는 소규모 표본이지 운영 SLA나 성능 개선률이 아니다.
- 유한한 golden·공격·canary 평가이므로 검색 성공률을 전체 답변 정확도나 모든 질문의 안전성으로 바꾸어 서술하지 않는다.

### 5.3 이전 거래소 Phase에서 확인한 실제 연동

- 실제 Anvil의 EIP-712 견적·buy/sell·Vault 정산·receipt 처리, PostgreSQL 동일 견적 동시 소비, 실제 STOMP 공개/개인 메시지·SockJS·사용자 격리 검증 기록이 있다.
- 실제 Toss 현재가·시장 상태·여섯 캔들 주기·과거 봉 페이지 탐색·WebSocket 실시간 갱신을 연동했다.
- 2026-09-27 실제 환경 점검에서 Toss 시세 반환과 휴장 견적 `409 MARKET_CLOSED` 차단을 확인했다. Toss 장중 가격으로 웹→견적→Anvil→DB→WebSocket을 모두 잇는 수동 인수는 아직 대기다.
- 위 기록을 2026-10-03 전체 suite가 모든 broadcast·동시성·실사용자 시나리오를 다시 실행한 결과로 합치지 않는다.

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

### 6.4 운영자 nonce 직렬화와 키 역할 분리

문제:

- 여러 주문이 같은 운영자 거래 전송 지갑을 동시에 사용하면 같은 nonce를 선택할 수 있다.

해결:

- 단일 백엔드 프로세스 안에서 거래 서명·전송 경로를 직렬화했다.
- 가격 보고서 서명 키는 온체인 트랜잭션을 보내지 않는 별도 키로 분리해 가격 인증과 거래 실행 권한의 책임을 나눴다.
- 다중 인스턴스 전환 시 분산 nonce lock이 필요하다는 한계를 문서화했다.

### 6.5 공개·개인 실시간 데이터 경계

문제:

- 공개 체결 스트림에 사용자·주문·txHash를 포함하면 거래 추적 정보가 노출된다.
- STOMP 연결만 성공하면 다른 사용자의 queue를 구독할 위험이 있다.

해결:

- 공개 topic과 JWT 개인 queue를 분리하고 destination whitelist를 적용했다.
- 개인 queue를 변경 가능한 login ID가 아닌 사용자 DB ID Principal로 라우팅했다.
- 공개 체결 payload에는 시장 정보만 포함했다.

### 6.6 화면 가격과 온체인 체결 가격의 시간차

문제:

- 주기적으로 Oracle 가격만 갱신하면 사용자가 화면에서 확인한 실시간 가격과 실제 트랜잭션 체결 가격이 달라질 수 있다.
- 클라이언트가 전체 보고서·서명을 직접 보유하는 bearer 구조는 타인 견적 도용과 replay 공격면을 넓히고, 서버가 견적 소유권과 상태를 통제하기 어렵게 한다.

해결:

- 주문마다 Toss 시장 스냅샷, 방향, executor, 입력량, 최소 수령량, 만료와 `quoteId`를 묶은 EIP-712 가격 보고서를 생성했다. EIP-712 서명은 전체 필드의 변조를 탐지한다.
- 가격 서명 키와 거래 전송 키를 분리하고, 클라이언트는 `quoteId`만 제출하게 해 서버가 DB에서 소유 사용자와 원본 보고서·서명을 복원하도록 했다.
- 백엔드 DB row lock과 컨트랙트의 일회용 `quoteId` 검증을 함께 적용해 DB와 온체인 양쪽에서 재사용을 차단했다.
- Oracle이 서명·종목·유효 시간·재사용을 검사하고 Vault가 거래 조건을 검사한 뒤 자산 정산까지 하나의 트랜잭션에서 실행해 검증 후 가격이 바뀌는 경계를 제거했다.

결과:

- 사용자가 확인한 유효 견적이 온체인 체결 조건으로 그대로 사용되며, 오래되거나 변조되거나 이미 사용한 견적은 정산 전에 거부된다.

### 6.7 WebSocket 단절과 상태 복구

문제:

- WebSocket 메시지는 영속 로그가 아니므로 연결이 끊긴 동안 주문·체결·포트폴리오 이벤트를 놓칠 수 있다.
- 재연결 요청이 겹치거나 계정이 바뀌면 오래된 비동기 응답이 새 사용자 화면을 덮을 수 있다.

해결:

- 1초부터 최대 30초까지 지수 백오프로 재연결하고, 연결 후 REST에서 시장·차트·주문·체결·포트폴리오를 다시 조회했다.
- 연결 세대와 요청 세대를 부여해 이전 소켓의 늦은 이벤트와 오래된 REST 응답을 버렸다.
- 인증 만료 시 재연결을 멈추고 JWT·견적·개인 화면 상태를 초기화했다.

## 7. 설계상 의도적으로 제한한 범위

- 실제 주식·원화·투자 기능이 아닌 모의 거래
- 실제 삼성전자 기준 가격은 Toss 공급자로 연동하지만, 외부 API가 필요 없는 개발·자동 테스트에서는 75,000원에서 시작하는 시뮬레이션 공급자를 사용
- 주문장·지정가·예약 주문이 아닌, 실제 시장 스냅샷으로 발급한 주문별 서명 견적을 Vault가 검증·정산하는 즉시 거래
- Oracle은 마지막으로 승인된 가격을 참고용 상태로 기록하지만, Vault 거래는 그 저장값을 읽어 체결하지 않는다. 체결가는 사용자가 확정한 주문별 EIP-712 서명 견적에 고정
- 사용자별 온체인 지갑이 아닌 운영자 통합 지갑과 DB 내부 원장
- 최소 수령량과 만료가 서명 보고서에 결합돼 서버가 산정한 견적 조건의 변조를 차단
- WebSocket은 UI 실시간 반영 수단이며 메시지 영속성을 보장하지 않으므로 재연결 후 최종 상태는 REST로 다시 동기화
- 단일 운영자 nonce lock은 단일 백엔드 인스턴스 기준
- Google OAuth, 이메일 인증, 리프레시 토큰은 MVP 이후

제한사항을 숨기지 말고, MVP 복잡도를 통제하면서 확장 지점을 분리한 설계 판단으로 설명한다.

## 8. 미완료·미검증·향후 확장

2026-10-06 후속 구현: 공개 simulated 시장을 선택해 결정적 가격/가상 수량·DB checkpoint·재시작 연속성·commit 이후 공개·public Toss 비활성/합성 UI를 보강하고 통합 검증·독립 검토를 마쳤다. 실제 시장 복제/replay가 아니며 공개 데모의 가격은 삼성전자 시세를 추종하지 않는다. Toss 실제 연동은 보존한다. 전체 backend346 중333통과/13skip, PostgreSQL+격리 Anvil의 합성 서명 매수/매도·실제STOMP 검증, 웹48·fixture smoke를 통과했다. 독립 검토는 필수 수정 없음이며 별도로 backend15/웹48을 실행했다. 실제 공개 배포·장시간 중단 catch-up·운영 장애 검증 완료를 뜻하지 않는다. 선택 보완·미검증은 [합성 시장 기록](synthetic-market-design.md)에서 확인하며 기존 날짜별 수치는 당시 이력으로 유지한다.

완료 기능은 4절, 실행한 검증은 5절에만 정리한다. 아래 항목은 남은 작업이며 채택·일정이 확정되지 않은 확장 후보도 포함한다.

### 8.1 구현됐지만 남은 인수·검증 `[미검증]`

- 거래소 Phase 6.3: 실제 Toss 장중 가격으로 브라우저 매수·매도 전체 흐름과 백엔드/RPC 장애 복구 수동 인수
- AI: 운영 Toss·실사용자 장애 진단, 자동 진단의 실제 Anvil 성공 MATCH, 최신 ADMIN 패널 브라우저 수동 인수
- 프로세스 강제 종료·다중 서버 장기 chaos, 장기 SLA, 모든 주입/secret 패턴과 자유 문장 진실성
- 기본 비활성 자동 진단의 실제 로컬 활성화·운영 준비 확인. 구현 테스트 통과와 상시 운영 실적은 구분

### 8.2 추가 기능과 산출물 `[미구현·확장 후보]`

- 모든 `FILLED` 주문과 확정 온체인 transaction의 운영 대사 화면. 기존 receipt reconciliation·AI 진단 이력과 별개
- DB 사용자 자산 합계와 운영자 온체인 준비금 비교, Proof of Reserves·Merkle root·사용자 proof
- 지정가·예약 주문, 취소·만료 및 장기간 자산 잠금
- 사용자별 직접 지갑·WalletConnect, 다중 인스턴스용 분산 nonce lock
- 사용자 Google OAuth·이메일 인증·계정 연결·리프레시 토큰. Toss 공급자 OAuth는 이미 구현된 별도 기능
- faucet 횟수·한도와 운영자 모의 mKRW 준비금 정책 고도화
- 최종 장중 시연 자료·화면 캡처·영상, 실제 모바일 담당자 인수 결과/피드백
- 선택적 Sepolia 배포·explorer 연결. 현재 검증 체인은 로컬 Anvil이며 배포 완료로 쓰지 않음

아키텍처·DB·REST·WebSocket·컨트랙트 설명, 실행 절차, 계약·예제 payload·웹 기준 클라이언트 및 AI 최종6문서는 이미 작성했다. 남은 것은 해당 문서를 바탕으로 한 최종 시연·실제 담당자 인수이지 문서/계약을 처음 만드는 작업이 아니다.

### 8.3 Android `[담당 외·최후순위]`

- Android 앱 완성은 현재 담당 범위의 백엔드·컨트랙트 MVP 완료 조건에 포함하지 않는다.
- REST·JWT·Native STOMP 계약과 재연결 원칙은 웹 기준 클라이언트와 문서로 제공한다.
- 실제 Android UI 구현·기기 검증·담당자 인수는 별도이며 완료했다고 쓰지 않는다.

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
| 외부 API 연동 | Toss REST·WebSocket 인증, 현재가·시장 상태·캔들 수신, 공급자 장애와 가격 신선도 처리 |
| 실시간 데이터 | REST 과거 봉과 WebSocket tick 병합, OHLCV 다중 주기 집계, UTC 저장·KST 표시 |
| 협업·인수인계 | 역할별 패키지·주석·공통 문서, Android와 독립적으로 실행 가능한 웹 검증 클라이언트 |
| AI 시스템 안전성 | 승인 지식·권한/domain 검색, read-only Tool, 고정 Skill, 호출 예산·관측·자동 진단 중복/불명확 실행 차단 |

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

### 소재 D — 실시간 화면 가격과 온체인 체결의 일치

- Situation: 주기적으로 온체인 가격을 갱신하는 구조에서는 사용자가 확인한 실시간 시세와 트랜잭션 실행 시점의 가격이 어긋날 수 있었다.
- Task: 클라이언트가 가격이나 서명을 조작하지 못하게 하면서 확인한 견적을 체결 조건으로 보장해야 했다.
- Action: 방향·입력량·최소 수령량·실행자·30초 만료를 묶은 EIP-712 가격 보고서를 전용 키로 서명하고, 사용자는 `quoteId`만 제출하게 했다. Oracle은 서명·유효 시간·재사용을, Vault는 거래 조건을 검증하며 DB row lock까지 적용해 재사용을 이중 차단했다.
- Result: 견적 검증과 Vault 정산이 한 온체인 트랜잭션에서 원자적으로 실행되고, 실제 PostgreSQL 경합 및 Anvil 통합 테스트에서 중복 소비 방지와 서명 정산을 확인했다.

### 소재 E — 근거와 권한을 분리한 AI 진단

- Situation: 문서 검색만으로 현재 주문·잔고·receipt를 설명하면 오래된 지식이나 추측을 실제 거래 상태로 오인할 수 있었다.
- Task: 정책 근거와 현재 관측 사실을 결합하되 모델에게 거래 권한이나 임의 조회 권한을 주지 않아야 했다.
- Action: 승인 지식·manifest/hash·role/domain 검색, 8개 조회 Tool, 고정 Skill과 호출 예산을 적용했다. 거래 DB와 AI DB를 분리하고 커밋된 운영 이벤트 이후 독립 polling으로 분석하며, 불명확한 실행은 자동 재호출하지 않았다.
- Result: 동일 golden 검색 hit@5를 10/12에서 12/12로 개선해 유지했다. 실제 공급자 대표 8개 요청에서 불확실성 유지와 안전 거부를 포함한 기대 결과를 확인했고, 권한·거래 불변성·주입/canary 및 독립 검토를 통과했다. 운영 정확도나 자동 복구 완료를 주장하지 않는다.

## 11. 바로 사용할 수 있는 서술 초안

### 이력서 한 줄

> Spring Boot·PostgreSQL과 Solidity 스마트 컨트랙트를 연동한 수탁형 하이브리드 모의 거래소를 구현하고, 온체인 주문의 비동기 상태·멱등 정산·재시작 복구·JWT 기반 실시간 이벤트 전달 구조를 설계했습니다.

### 짧은 자소서 문단

> 단순 CRUD를 넘어 외부 시스템의 불확실성을 견디는 백엔드를 경험하고자 ERC-20 가격 추종 토큰 거래소를 개발했습니다. 블록체인 트랜잭션은 HTTP 요청과 동시에 끝나지 않기 때문에 주문을 `PENDING_ONCHAIN`으로 분리하고 입력 자산을 잠갔으며, receipt와 컨트랙트 이벤트를 검증한 후에만 잔고와 체결을 반영했습니다. 또한 RPC 응답 유실에 대비해 서명 원문과 txHash를 전송 전에 저장하고 동일 원문을 재전송하는 복구 흐름을 설계했습니다. 실시간 알림은 DB commit 이후에만 발행해 데이터와 화면의 불일치를 방지했습니다.

### 협업 강조 문단

> 모바일 담당자가 서버 내부 구현을 알지 못해도 연결할 수 있도록 REST·JWT·STOMP 계약을 분리하고, Native WebSocket과 SockJS를 직접 시험하는 브라우저 검증 도구를 만들었습니다. 공개 시장 데이터와 사용자별 개인 알림을 분리하고 실제 두 사용자의 메시지 격리를 검증했으며, 코드 위치·실행 절차·설계 판단과 제한사항을 공통 문서에 기록해 인수인계 비용을 줄였습니다.

### 외부 시세·실시간 차트 강조 문단

> 시뮬레이션 가격에 한정됐던 거래소를 실제 삼성전자 기준 가격과 연결하기 위해 시장 데이터 공급자 경계를 설계하고 Toss REST·WebSocket을 연동했습니다. 시장 상태와 가격 신선도에 따라 견적과 주문 발급을 통제했으며, 과거 1분봉을 공통 OHLCV 모델로 정규화해 5분·15분·30분·1시간 봉으로 집계했습니다. 브라우저에서는 REST 과거 봉과 실시간 체결 tick을 결합하고, 재연결이나 주기 변경 중 비동기 응답이 섞이지 않도록 요청 세대를 격리했습니다.

### 서명 견적·거래 안전성 강조 문단

> 사용자가 확인한 견적과 블록체인 체결 가격의 차이를 방지하기 위해 주문별 EIP-712 서명 견적을 설계했습니다. 백엔드는 방향·입력량·최소 수령량·실행자·만료를 가격 보고서에 묶어 전용 키로 서명하고, 클라이언트가 가격·수수료 등 견적 정보를 확인한 뒤 서명 원문 대신 `quoteId`로 주문하도록 했습니다. 견적 소비·주문 생성·자산 잠금을 하나의 DB 트랜잭션으로 처리하고, Oracle의 서명·만료·재사용 검증과 Vault의 거래 조건·정산을 하나의 온체인 트랜잭션으로 묶었습니다. 이는 견적 가격을 보호하는 방식이며 체결 순간의 최신 시장가를 보장하는 방식은 아닙니다.

### AI 조회·진단 강조 문단

> 거래 데이터를 추측하지 않는 AI 진단을 목표로 승인 문서 기반 RAG와 권한 검증된 read-only Tool을 결합했습니다. 역할·domain 검색 경계와 고정 Skill, 호출 예산을 서버에서 강제하고, 거래 DB와 별도 AI DB를 분리해 분석 실패가 거래 정산에 동기 전파되지 않도록 했습니다. 검색 품질은 동일 golden 12문항에서 hit@5 10/12에서 12/12로 개선했고, 실제 공급자 평가에서는 완전 답변뿐 아니라 근거 부족을 밝힌 PARTIAL과 안전 거부도 구분해 검증했습니다.

위 초안의 1인칭 표현은 개인 기여 확인 후 사용한다. 저장소에 존재하는 구현을 설명할 수 있다는 사실만으로 모든 작업을 직접 수행했다고 쓰지 않는다.

## 12. 자소서 작성 에이전트 지침

1. 지원 직무와 문항을 먼저 확인하고 관련 역량만 선택한다.
2. 수치가 필요하면 이 문서의 검증 수치만 사용하고 임의의 사용자 수·성능 향상률·매출을 만들지 않는다.
3. Android 앱, 준비금 reconciliation, 사용자별 지갑, Sepolia 배포를 완료했다고 쓰지 않는다. 사용자가 확인한 가격을 주문별 서명 견적으로 고정하고 만료·재사용·서버 산정 최소 수령량을 온체인에서 검증하는 기능은 완료된 성과로 구분한다.
4. “탈중앙화 거래소”라고 표현하지 않는다. 현재 구조는 운영자 통합 지갑과 DB 원장을 사용하는 수탁형 하이브리드 거래소다.
5. 실제 삼성전자 주식·실제 원화·투자 서비스처럼 표현하지 않는다.
6. DB mock 거래는 개발·테스트 경로이며 최종 온체인 흐름과 구분한다.
7. 팀 규모, 개발 기간, 본인의 공식 직책과 기여 비율은 이 문서에 근거가 없으므로 사용자에게 확인한다.
8. Toss 시세 연동과 웹 차트는 완료 기능이지만 실제 삼성전자 주식 거래 기능이라고 표현하지 않는다.
9. 향후 계획은 문항이 개선점·입사 후 계획·확장성을 요구할 때만 사용한다.
10. Phase 6.3의 자동 경계 검증은 완료됐지만 실제 Toss 장중 브라우저 매수·매도와 장애 복구 수동 인수는 완료됐다고 쓰지 않는다.
11. AI Phase 1~8은 완료됐지만 거래 자동 실행/복구나 투자 조언이 아닌 조회·설명·진단이다. 검색 hit와 답변 정확도, fixture 평가와 운영 장애 검증을 구분한다. Hybrid는 비교 실험이며 서비스 기본 경로가 아니다. Spring AI/별도 모니터링 서버/독립 챗봇 UI를 구현했다고 쓰지 않으며 자연어 정확도100%를 주장하지 않는다.
12. 거래 receipt reconciliation은 완료 기능이고 사용자 자산/온체인 준비금 reconciliation은 미구현이다. 서로 다른 작업을 같은 이름으로 합치지 않는다.
13. 사용자 Google OAuth는 미구현이고 Toss 공급자 OAuth는 완료다. 자동 진단 구현·테스트와 실제 운영 활성화도 구분한다.
14. 개발 에이전트 지원과 본인의 실제 기여를 구분하고, 수동 시연·팀 협업·직접 작성 범위를 확인하지 않은 채 만들어 쓰지 않는다.

## 13. 추가로 사용자에게 확인해야 할 정보

자소서를 최종 작성하기 전에 다음 정보를 받아야 한다.

- 지원 회사와 직무
- 자기소개서 문항과 글자 수
- 프로젝트 기간
- 팀 인원과 역할 분담
- 본인의 공식 담당 역할
- 개발 에이전트 지원을 포함한 실제 작업 방식과 본인이 직접 결정·작성·검토·검증한 범위
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
- `claude-docs/phase-6-3-acceptance.md`: 완료된 실제 환경 경계 점검과 대기 중인 수동 인수
- `docs/ai/AI_ARCHITECTURE.md`: 최종 AI 실행 구조와 거래/AI DB 격리
- `docs/ai/RAG_KNOWLEDGE_GUIDE.md`: 승인 지식·manifest/hash·채택 검색 설정
- `docs/ai/TOOL_SECURITY_MODEL.md`: 고정 Tool·권한·주입/secret·TTL 경계
- `docs/ai/SKILL_GUIDE.md`: 3개 고정 진단 절차와 trace/사실/해석 구분
- `docs/ai/EVALUATION_REPORT.md`, `docs/ai/phase-8-results.json`: 최신 실행별 수치·독립 검토·미검증
- `docs/ai/RUNBOOK.md`: 명시 초기화·활성화·장애 해석과 운영 제한

### 사실을 확인할 주요 코드 위치

- `contracts/src/PriceOracle.sol`, `ExchangeVault.sol`: 서명 보고서 검증·원자적 정산. `contracts/test/`: 단위·fuzz 검증
- `backend/src/main/java/com/pricetrack/exchange/quote/`: 견적 응답·DB 저장·소유권/일회성 소비
- `backend/src/main/java/com/pricetrack/exchange/blockchain/`: 보고서 서명·전송 선저장·receipt 정산/복구
- `backend/src/main/java/com/pricetrack/exchange/ai/`: provider·retrieval·tool·agent·skill·diagnosis·observability
- `backend/src/test/java/com/pricetrack/exchange/ai/`: 권한·검색·진단·관측·공급자 평가. 현재 XML과 과거 전체 실행 수치는 구분
- `docs/ai-knowledge/`, `docs/ai/ingest-manifest.json`, `backend/src/main/resources/ai/skills/`: 승인 지식·Skill 정의
- `tools/websocket-test-client/`: 실행 가능한 웹 거래/차트·복구 기준 클라이언트와 ADMIN 진단 패널
