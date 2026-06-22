# 프로젝트 개요 — 삼성전자 가격 추종 토큰 거래소

> 단일 출처: 리포 루트 `구현 계획.md`. 본 문서는 요약/미러본이다.

## 한 줄 정의
Ethereum ERC-20 기반으로 **삼성전자 기준 가격을 추종하는 모의 토큰(mSEC)**을 모의 원화(mKRW)로 매수·매도하는 **현물 모의 거래소**. 실제 주식·배당·의결권·상환권과 무관한 **학습/포트폴리오용** 프로젝트.

졸업프로젝트 — 한양대 컴퓨터소프트웨어학부 (서교빈: 백엔드/컨트랙트, 김성은: Android). 지도교수 유민수.

## MVP 범위 (현물 즉시 매수·매도)
- 오라클 가격 기반 시장가 매수/매도 (주문장·지정가 없음)
- 회원가입/로그인(JWT), 모의 원화 faucet, 현재가·견적·주문·체결·포트폴리오
- WebSocket 실시간 가격/체결 스트림
- **제외(향후 확장 §17)**: 선물, 레버리지, 증거금, 강제청산, 실물 연동, 실제 시세 API

## 모듈 구조 (모노레포)
```
contracts/  Foundry — MockKRW, SamsungPriceTrackingToken, PriceOracle, ExchangeVault
backend/    Spring Boot (Java 21) — com.pricetrack.exchange.*
android/    Kotlin + Jetpack Compose — data/domain/presentation
claude-docs/  Claude 컨텍스트 미러
docker-compose.yml, README.md
```

## 기술 스택
- **컨트랙트**: Solidity, Foundry(forge), OpenZeppelin. 가격은 1e8 정밀도.
- **백엔드**: Java 21, Spring Boot 3.x, Spring Web/Security/Data JPA, PostgreSQL, WebSocket/STOMP, web3j, JWT. 패키지: auth/user/wallet/market/quote/order/trade/portfolio/blockchain/websocket/common.
- **Android**: Kotlin, Jetpack Compose, Navigation, ViewModel/StateFlow, Retrofit/OkHttp, kotlinx.serialization, DataStore. 클린 아키텍처.

## 컨트랙트 요약 (§9)
- `MockKRW` — ERC-20 + `faucet()`
- `SamsungPriceTrackingToken` (mSEC) — ERC-20 + `mint`/`burn` (Vault만 minter)
- `PriceOracle` — `priceE8`, `updatedAt`, `updatePrice()`, `getPrice()`
- `ExchangeVault` — `buy(krwAmount)`, `sell(tokenAmount)`, 수수료, `Bought`/`Sold` 이벤트

## 핵심 난점 (§18)
- 온체인 트랜잭션 비동기성 → 주문 상태 분리(REQUESTED→PENDING_ONCHAIN→FILLED/FAILED)
- DB ↔ 온체인 일관성 → `blockchain_transactions` + reconciliation job
- 견적 시점 vs 체결 시점 가격 차이 → MVP는 견적 참고용, 체결은 실행 시점 오라클 가격

## Phase (§16)
0 초기세팅 → 0.5 컨트랙트 최소검증(매수→가격변경→매도→잔고확인) → 1 컨트랙트 → 2 백엔드 API(mock) → 3 web3j 연동 → 4 WebSocket → 5 Android → 6 시연/문서화

## 현재 상태 (2026-06-22)
모노레포 뼈대(컴파일 가능 보일러플레이트) 구축 단계. 컨트랙트 도구 Foundry, 백엔드 Java 21 확정.
