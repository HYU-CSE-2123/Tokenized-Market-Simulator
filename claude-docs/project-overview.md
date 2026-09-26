# 프로젝트 개요 — 삼성전자 가격 추종 토큰 거래소

> 상세 목표와 Phase 기준의 원본은 루트의 `구현 계획.md`이며, 이 문서는 사람과 모든 개발 에이전트가 빠르게 현재 상태를 이해하기 위한 요약입니다.

## 프로젝트 목표

Ethereum ERC-20 기반 모의 원화(mKRW)로 삼성전자 기준 가격을 추종하는 교육용 합성자산 토큰(mSEC)을 사고파는 학습·포트폴리오용 거래소입니다.

## 상품 정의

2026-09-12 기준으로 mSEC의 상품 성격을 다음과 같이 확정했습니다.

> mSEC는 실제 삼성전자 주가 데이터를 참조해 가격 변동을 모사하는 교육용 합성자산 토큰이다. 실제 삼성전자 주식으로 담보되지 않으며 주주권, 배당권, 의결권 및 실제 원화 상환권을 제공하지 않는다. 모든 거래와 손익은 모의 자산인 mKRW로 처리한다.

- 현재 구현은 Toss 공급자의 실제 삼성전자 기준 가격을 사용하며, 자동 테스트와 외부 API 없는 개발 환경에서는 시뮬레이션 공급자를 선택할 수 있다.
- 실제 시세를 사용하더라도 mSEC가 실제 주식이나 규제된 투자상품으로 바뀌는 것은 아니다.
- mSEC 매수·매도 상대는 다른 사용자가 아니라 `ExchangeVault`이며, 가격은 사용자 수요·공급이 아니라 PriceOracle 값으로 결정된다.
- 운영자의 실제 삼성전자 주식 헤지는 없고 Vault의 모의 mKRW 유동성으로 환매하므로, 준비금·지급 능력 검증은 별도 보강 과제다.

## 모듈

- `contracts/`: Solidity 컨트랙트, Foundry 테스트, 배포·시나리오 스크립트
- `backend/`: Java 21 / Spring Boot REST API, DB, 인증, 향후 블록체인 연동
- `android/`: Kotlin / Jetpack Compose 클라이언트 스캐폴딩
- `claude-docs/`: 이름과 무관하게 Claude, Codex 등 모든 개발 에이전트와 사람이 공유하는 프로젝트 문서

## Phase

0 초기 설정 → 0.5 최소 온체인 시나리오 → 1 컨트랙트 → 2 백엔드 mock API → 3 web3j 연동 → 4 WebSocket·실제 시세·웹 검증 → 5 웹 MVP 마감·통합 검증 → 6 시연·문서화. Android는 별도 담당 범위로 최후순위에 둔다.

## 현재 상태 (2026-09-24)

- Phase 1 완료: 컨트랙트 4종, 배포·거래 시나리오, Foundry 테스트 20개 통과
- Phase 2.1-A 완료: 자체 계정 회원가입·로그인, BCrypt, JWT 인증 필터, `/api/me`, 공통 오류 응답 구현
- Phase 2.2 완료: 사용자별 DB 잔고, faucet, 즉시 매수·매도, 주문·체결 내역과 포트폴리오 구현
- Phase 2 마감 점검 완료: PostgreSQL 16 실제 연결, 스키마 최초·반복 적용, 핵심 HTTP 거래 흐름 검증
- DB 보존·관리자 기반 완료: 외부 Docker 볼륨, `USER`/`ADMIN` 역할, 환경 변수 기반 초기 관리자 생성
- 로컬 설정 표준화: Git 제외 `backend/.env`, 팀 공유용 `backend/.env.example`, Spring 선택적 로딩
- Phase 3.1 완료: 선택적 web3j 연결, 운영자 주소 파생, 컨트랙트 주소·배포 코드 검증, 오라클·수수료·잔고·allowance·견적 읽기
- Phase 3.2 완료: 운영자 통합 지갑 buy/sell 서명·전송, nonce·서명 원문·txHash 저장, 입력 잔고 잠금, `PENDING_ONCHAIN` 응답
- Phase 3.3 완료: receipt polling, `Bought/Sold` 이벤트 파싱, 멱등 잔고·체결 반영, 실패 잠금 해제, `SIGNED` 복구와 `REVIEW_REQUIRED` 격리
- Phase 3.4 완료: 모의 가격을 `PriceOracle.updatePrice` 트랜잭션으로 동기화하고, `PriceUpdated` 이벤트 확정 후 온체인 가격 이력을 저장
- 온체인 가격 동기화는 처리 중인 갱신이 있으면 중간 가격을 합치고 최신 가격 하나만 다음 트랜잭션으로 전송
- 블록체인 활성화 시 매수·매도 견적 API가 Vault와 Oracle의 실제 온체인 값을 사용
- Phase 3 완료: 읽기, 주문 전송, receipt 정산·복구, 오라클 가격 동기화까지 연결
- Phase 4.1 완료: 일반 WebSocket·SockJS endpoint, STOMP CONNECT JWT 인증, 공개 시장·개인 사용자 구독 경계와 client SEND 차단
- Phase 4.2 완료: 버전 있는 공통 이벤트 envelope, destination·payload 정의, DB `AFTER_COMMIT` 공개·개인 발행 기반
- Phase 4.3 완료: 모의 가격과 모의·온체인 체결을 공개 topic에 연결하고 체결 payload의 사용자 정보 비노출 보장
- Phase 4.4 완료: 사용자별 온체인 주문 대기·성공·실패와 자산 변경 후 포트폴리오를 개인 queue에 연결하고 사용자 격리 검증
- Phase 4 완료: 연결·JWT 인증, 공통 envelope, transaction-safe 발행, 공개 시장 및 개인 사용자 스트림 구현
- Phase 4.5 완료: Android 전달 전 REST·JWT·Native WebSocket·SockJS를 브라우저에서 확인하는 독립 Vite 테스트 클라이언트와 실행 체크리스트 추가
- Phase 4.6.1 완료: 가격 소비 코드를 공급자 구현에서 분리하고 실제 Toss 시세 연동을 위한 공통 스냅샷과 공급자 경계 추가
- Phase 4.6.2 완료: 설정으로 모의·토스 가격 공급자를 선택하고, 토스증권 OAuth 토큰 관리와 삼성전자 REST 초기 가격 조회 구현
- 사용자 로컬 Toss 자격 증명으로 OAuth와 삼성전자 초기 가격 REST 실연동 검증 완료(비밀값은 저장소·문서에서 제외)
- Phase 4.6.3 완료: Toss WebSocket `trade:kr:005930` 구독, keepalive·재연결과 실시간 가격 반영 구현
- Phase 4.6.4 완료: Toss 장 캘린더·전일 종가를 연동하고 마켓 API에 장 상태와 가격 신선도를 노출
- Phase 4.6.5 완료: Toss 장 마감·오래된 가격의 신규 주문 및 PriceOracle 반영을 차단하고 시뮬레이션 거래는 유지
- Phase 4.7.1 완료: 공급자 공통 1분봉·일봉 REST 계약과 Toss 캔들 변환·시뮬레이션 OHLCV 저장 구현
- Phase 4.7.2 완료: 공개 가격 WebSocket payload에 실제 관측 시각·시장/가격 상태·공급자·tick 거래량을 추가하고 동일 가격 체결도 발행
- Phase 4.7.3 완료: 브라우저 테스트 도구에 REST 과거 봉과 WebSocket 현재 봉을 결합한 1분·1일 캔들/거래량 차트 구현
- Phase 4.7.4 완료: Toss 1분봉 종료 경계를 시작 시각으로 정규화하고 5분·15분·30분·1시간 OHLCV 집계 및 웹 주기 전환 구현
- Phase 4.7.5 완료: 웹 차트를 왼쪽으로 이동하면 `nextBefore`로 과거 페이지를 이어 붙이고, 중복 요청·오래된 응답을 차단하며 기존 화면 위치를 유지
- Phase 5.1 완료: 주문별 EIP-712 가격 보고서 필드·도메인·위협 모델을 확정하고 Java·Solidity 공통 digest·서명자 테스트 벡터 구현
- Phase 5.2-A 완료: `PriceOracle`에 승인된 Vault 소비자, EIP-712 가격 서명·종목·30초 만료·2초 미래 오차·일회용 `quoteId` 검증과 관측/온체인 시각 분리 구현
- Phase 5.2-B 완료: `ExchangeVault`의 무서명 거래 경로를 제거하고 주문 방향·실행자·입력량·최소 수령량이 결합된 서명 가격 소비와 매수·매도 정산을 단일 트랜잭션으로 원자화
- Phase 5.3-A 완료: 백엔드가 5초 이내 시장 스냅샷과 Vault 명시 가격 견적으로 30초 유효 보고서를 만들고 전용 키로 EIP-712 서명하며 기동 시 온체인 서명자 일치를 검사하는 기반 구현
- Phase 5.3-B 완료: 서명 견적을 인증 사용자에게 귀속해 `price_quotes`에 저장하고 소유권·방향·입력량·만료·일회성 소비를 DB 잠금으로 검증하며 비밀 필드를 제외한 견적 API 제공
- Phase 5.3-C 완료: 주문의 `quoteId`로 서버 DB 보고서·서명을 복원하고 주문 생성·자산 잠금·견적 소비를 원자화했으며 web3j를 `buy/sell(PriceReport,bytes)` ABI로 전환
- Phase 5.4 완료: 브라우저 검증 도구에서 견적 발급과 주문 확정을 분리하고 서명 가격·수수료·최소 수령량·만료·주문 상태·최종 온체인 체결가를 표시하며 실제 PostgreSQL 동시 소비를 검증
- 주기적 `updatePrice` 제출은 제거하고, RPC 장애는 `SIGNED` 저장 전 주문 실패·잠금 해제와 저장 후 동일 raw transaction 복구로 구분
- IntelliJ의 저장소 루트 실행에서도 `backend/.env`를 읽도록 보강하고, 브라우저 차트 시간축은 UTC 원본을 유지한 채 한국 시간으로 표시
- Windows 예약 포트 범위와 충돌한 8080 대신 로컬 백엔드 기본 포트를 8082로 통일하고 웹 프록시·Android 주소도 같은 포트를 사용
- 백엔드 기본 테스트 전체 통과, Anvil 실제 읽기와 EIP-712 서명 견적·tuple ABI 주문 정산 선택 테스트 별도 통과
- 사용자 테이블에는 향후 Google 로그인·이메일 인증을 위한 `email`, `email_verified`, `google_sub`를 nullable로 준비했지만 관련 기능은 아직 없음

## 다음 개발 후보

Phase 3, Phase 4와 실제 Toss 가격 공급자, 장·가격 가용성 정책, 공급자 공통 캔들 API 및 과거 탐색이 가능한 브라우저 실시간 차트까지 구현했습니다. Phase 5.1에서 주문별 서명 가격 계약을 확정하고 Phase 5.2-A/B에서 `PriceOracle` 검증과 `ExchangeVault` 원자적 정산을 구현했으며 Phase 5.3-A/B/C에서 발급·서명·사용자 소유 견적과 실제 주문 전송을 연결했습니다. Phase 5.4에서는 브라우저의 명시적 견적 확인·주문 확정 UX와 PostgreSQL 동시 소비 검증까지 완료했습니다. Android 구현은 별도 담당 범위로 최후순위에 둡니다.

## 핵심 설계 원칙

- 컨트랙트 트랜잭션은 비동기이므로 주문 상태를 `REQUESTED → PENDING_ONCHAIN → FILLED/FAILED`로 분리합니다.
- DB 기록과 온체인 결과의 불일치는 `blockchain_transactions` 및 reconciliation 작업으로 다룹니다.
- 온체인 체결 가격은 사용자가 확인한 30초 유효 서명 견적의 가격이며 Vault가 같은 트랜잭션에서 서명·만료·재사용을 검증합니다.
