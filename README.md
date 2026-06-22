# 삼성전자 가격 추종 토큰 거래소 (Price-Tracking Token Exchange)

Ethereum ERC-20 기반으로 **삼성전자 기준 가격을 추종하는 모의 토큰(mSEC)**을 모의 원화(mKRW)로 매수·매도하는 **현물 모의 거래소**. 안드로이드 앱 · Spring Boot 백엔드 · 스마트 컨트랙트를 연동한 종합 프로젝트.

> 단일 출처(상세 기획): [`구현 계획.md`](구현%20계획.md)

## ⚠️ 면책 (기획서 §19.1)
본 프로젝트는 학습 및 포트폴리오 목적의 모의 거래 시스템입니다.
본 프로젝트의 토큰은 실제 삼성전자 주식, 배당권, 의결권, 상환권을 나타내지 않습니다.
모든 거래는 테스트넷 또는 로컬체인에서 이루어지며, 실제 투자 또는 금융거래 기능을 제공하지 않습니다.

## 모노레포 구조
| 디렉터리 | 내용 | 스택 |
| --- | --- | --- |
| [`contracts/`](contracts) | 스마트 컨트랙트 (mKRW, mSEC, PriceOracle, ExchangeVault) | Solidity, **Foundry** |
| [`backend/`](backend) | REST/WebSocket API, 온체인 연동 | **Java 21**, Spring Boot 3.3 |
| [`android/`](android) | 모바일 클라이언트 | Kotlin, Jetpack Compose |
| [`claude-docs/`](claude-docs) | 프로젝트 컨텍스트 문서 (Claude 메모리 미러) | — |

## 아키텍처
```
[Android / Compose] --REST/WebSocket--> [Spring Boot] --JPA--> [PostgreSQL]
                                              |
                                          web3j/RPC
                                              v
                            [Ethereum (Anvil 로컬 / Sepolia)]
                   MockKRW · SamsungPriceTrackingToken · PriceOracle · ExchangeVault
```

## 빠른 시작

### 1. 스마트 컨트랙트
```bash
cd contracts
forge build && forge test          # 매수→가격변경→매도 시나리오 검증
anvil &                            # 로컬체인
forge script script/Deploy.s.sol --rpc-url local --broadcast --private-key <KEY>
```

### 2. 인프라 (PostgreSQL · Anvil)
```bash
docker compose -p exchange up -d   # 한글 경로 → 프로젝트명 -p exchange 명시 필요
```

### 3. 백엔드
```bash
cd backend && ./gradlew bootRun
curl http://localhost:8080/api/health
```

### 4. 안드로이드
```bash
cd android && ./gradlew :app:assembleDebug   # 또는 Android Studio 로 실행
```

## 사전 요구 도구
- **Foundry** (`brew install foundry`) — 이 환경에서는 `foundry.paradigm.sh` DNS 차단으로 brew 설치 사용
- **JDK 21** (`brew install openjdk@21`)
- **Docker** (PostgreSQL, Anvil 컨테이너)
- **Android SDK** (platform 35)

## 구현 단계 (기획서 §16)
Phase 0 세팅 → 0.5 컨트랙트 최소검증 → 1 컨트랙트 → 2 백엔드 API → 3 web3j 연동 → 4 WebSocket → 5 Android → 6 시연/문서화

현재: **Phase 0.5 완료** — 모노레포 뼈대(세 모듈 빌드/테스트 통과) + 컨트랙트를 로컬체인(Anvil)에 배포해 매수→가격변경→매도 흐름 온체인 검증 완료.

```bash
# Phase 0.5 재현
cd contracts && anvil &
forge script script/Scenario.s.sol --rpc-url local --broadcast
```
