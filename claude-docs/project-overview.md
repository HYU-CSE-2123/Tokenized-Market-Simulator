# 프로젝트 개요 — 삼성전자 가격 추종 토큰 거래소

> 상세 목표와 Phase 기준의 원본은 루트의 `구현 계획.md`이며, 이 문서는 사람과 모든 개발 에이전트가 빠르게 현재 상태를 이해하기 위한 요약입니다.

## 프로젝트 목표

Ethereum ERC-20 기반 모의 원화(mKRW)로 삼성전자 기준 가격을 추종하는 모의 토큰(mSEC)을 사고파는 학습·포트폴리오용 거래소입니다. 실제 주식, 원화, 배당, 의결권과는 관계가 없습니다.

## 모듈

- `contracts/`: Solidity 컨트랙트, Foundry 테스트, 배포·시나리오 스크립트
- `backend/`: Java 21 / Spring Boot REST API, DB, 인증, 향후 블록체인 연동
- `android/`: Kotlin / Jetpack Compose 클라이언트 스캐폴딩
- `claude-docs/`: 이름과 무관하게 Claude, Codex 등 모든 개발 에이전트와 사람이 공유하는 프로젝트 문서

## Phase

0 초기 설정 → 0.5 최소 온체인 시나리오 → 1 컨트랙트 → 2 백엔드 mock API → 3 web3j 연동 → 4 WebSocket → 5 Android → 6 시연·문서화

## 현재 상태 (2026-08-28)

- Phase 1 완료: 컨트랙트 4종, 배포·거래 시나리오, Foundry 테스트 20개 통과
- Phase 2.1-A 완료: 자체 계정 회원가입·로그인, BCrypt, JWT 인증 필터, `/api/me`, 공통 오류 응답 구현
- Phase 2.2 완료: 사용자별 DB 잔고, faucet, 즉시 매수·매도, 주문·체결 내역과 포트폴리오 구현
- Phase 2 마감 점검 완료: PostgreSQL 16 실제 연결, 스키마 최초·반복 적용, 핵심 HTTP 거래 흐름 검증
- DB 보존·관리자 기반 완료: 외부 Docker 볼륨, `USER`/`ADMIN` 역할, 환경 변수 기반 초기 관리자 생성
- 로컬 설정 표준화: Git 제외 `backend/.env`, 팀 공유용 `backend/.env.example`, Spring 선택적 로딩
- Phase 3.1 완료: 선택적 web3j 연결, 운영자 주소 파생, 컨트랙트 주소·배포 코드 검증, 오라클·수수료·잔고·allowance·견적 읽기
- Phase 3.2 완료: 운영자 통합 지갑 buy/sell 서명·전송, nonce·서명 원문·txHash 저장, 입력 잔고 잠금, `PENDING_ONCHAIN` 응답
- Phase 3.3 완료: receipt polling, `Bought/Sold` 이벤트 파싱, 멱등 잔고·체결 반영, 실패 잠금 해제, `SIGNED` 복구와 `REVIEW_REQUIRED` 격리
- 백엔드 기본 테스트 36개 통과, Anvil 읽기·전송·정산 선택 테스트 2개 별도 통과
- 사용자 테이블에는 향후 Google 로그인·이메일 인증을 위한 `email`, `email_verified`, `google_sub`를 nullable로 준비했지만 관련 기능은 아직 없음

## 다음 개발 후보

Phase 3.3 온체인 주문 정산까지 완료되었습니다. 다음은 Phase 3.4 가격 시뮬레이터의 `PriceOracle.updatePrice` 동기화이며, 그 뒤 Phase 4 WebSocket 주문·체결 알림으로 진행합니다. Google OAuth, 이메일 인증과 계정 연결, 리프레시 토큰은 별도 설계 승인 후 진행합니다.

## 핵심 설계 원칙

- 컨트랙트 트랜잭션은 비동기이므로 주문 상태를 `REQUESTED → PENDING_ONCHAIN → FILLED/FAILED`로 분리합니다.
- DB 기록과 온체인 결과의 불일치는 `blockchain_transactions` 및 reconciliation 작업으로 다룹니다.
- MVP 체결 가격은 견적 요청 시점이 아니라 실제 실행 시점의 오라클 가격을 따릅니다.
