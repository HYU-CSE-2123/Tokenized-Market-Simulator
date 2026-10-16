---
title: "시세·장 상태·차트 데이터의 의미"
domain: market
type: policy
version: 3
status: active
minimum_role: USER
updated_at: 2026-10-13
---

# 시세·장 상태·차트 데이터의 의미

> 2026-10-13 통합 추가 기능 및 키움 기능 소스 기준 갱신. 기존9문서 역할·active 승인 경계를 유지하며 manifest의 버전/내용 해시와 일치할 때만 ingest한다. 기존 AI DB 재색인이나 최신 실환경 인수 완료를 의미하지 않는다.

## 정의와 공급자 역할

가격 공급자는 거래 계산·API·WebSocket이 공유하는 시장 스냅샷을 제공한다. Toss 모드는 REST로 초기 가격·전일 종가·장 일정을 조회하고 WebSocket으로 실시간 체결 tick을 반영한다. 과거 봉은 Toss 캔들 조회 경로를 사용한다. 공개 데모의 public profile은 SIMULATED 합성 시장으로 고정하며 Toss 호출은 비활성이다. Toss 실제 연동 코드는 별도 로컬 검증 경로로 유지한다.

## 공개 합성 시장의 경계

합성 가격·거래량·초기 이력은 결정적 seed/Clock 엔진이 자체 생성한다. 실제 삼성전자 시세의 복제나 replay가 아니며 사용자 주문량으로 가격을 정하지 않는다. volume은 가상 mSEC 수량이고 사용자 체결량이 아니다. public 신규 DB의 초기 이력은 최대2일, 긴 중단 catch-up은 없다. 전일 기준은 KST 날짜 경계의 마지막 합성 가격이다.

엔진 상태와 봉을 같은 거래 DB transaction에 저장하고 commit 뒤 현재 가격·WS를 공개한다. 재시작은 checkpoint에서 이어가며 seed 변경으로 이력을 덮어쓰지 않는다. REST 캔들의 asOf는 이미 포함한 합성 tick의 관측 시각 경계이며 eventId와 다른 역할이다. 공개 체인은 비공개 Anvil 테스트 체인이고 Sepolia 배포 완료를 뜻하지 않는다.

이는 구현 설명이며 현재 실행 중인 공급자나 현재 가격을 확정하는 정보가 아니다. 실제 값은 시장 API의 provider·observedAt·상태를 조회해야 한다.

## 시장 상태와 가격 신선도

| 필드/값 | 의미 |
|---|---|
| marketStatus OPEN / CLOSED | 거래 가능한 장 시간인지 |
| marketStatus UNKNOWN | 시장 상태를 확정하지 않는 값 |
| priceStatus INITIALIZING / SIMULATED | 초기화 상태 / 모의 공급자의 가격 |
| priceStatus LIVE | 공급자의 정상 가격 상태 판정 |
| priceStatus DEGRADED | 연결이나 관측 지연이 저하 기준에 해당 |
| priceStatus STALE | 개장 중 관측 지연이 오래된 가격 기준에 해당 |
| observedAt | 해당 가격의 관측 시각 |

Toss는 저장된 시장 일정과 현재 시각으로 OPEN/CLOSED를 판단한다. 개장 중에는 관측 나이와 연결 상태, 설정된 degraded/stale 기준으로 신선도를 판단한다. 휴장에서는 가격 상태를 LIVE로 반환할 수 있다. 따라서 CLOSED + LIVE는 모순이 아니며 거래 허용을 뜻하지 않는다.

## 거래 차단 조건

MarketPriceService.requireTradableSnapshot은 CLOSED와 STALE를 거부한다. DB-only의 기존 계약은 유지한다. 실제 공급자의 **신규 온체인 서명 발급**과 조건부 실행은 별도로 OPEN + LIVE와 관측 후5초/미래2초를 모두 요구한다. DEGRADED/UNKNOWN은 신규 실제 서명에 사용할 수 없다. 이미 발급된 견적의 소비·전송된 거래 복구·receipt 정산을 시장 상태 변화만으로 취소하지 않는다.

오류는 MARKET_CLOSED, PRICE_STALE 등이다. 모의 견적 계산 경로와 서명 견적 발급 경로는 같지 않으므로, 견적 화면에 수량이 표시됐다고 주문 가능 상태를 확정하지 않는다. 시장 상태 변경은 과거 체결을 취소하는 정책이 아니다.

## 과거 봉과 live tick

과거 봉은 일정 구간의 OHLCV이며 주문 가격 보고서가 아니다. 차트는 REST 과거 봉에 WebSocket tick을 결합해 현재 봉을 갱신한다. 지원 주기는 1m·5m·15m·30m·1h·1d다. 캔들 시간은 해당 봉 구간의 시작 경계로 해석하며 표시 시간대와 서버 Instant를 구분한다.

과거 페이지는 nextBefore로 조회한다. 휴장·무거래 구간은 연속적인 실시간 tick이 있는 것처럼 해석하지 않는다. 현재 캔들 종가, 발급된 견적 가격, 확정 체결가는 서로 다른 시점의 값일 수 있다. 미래 가격이나 투자 수익을 보장하지 않는다.

## 근거

## 키움 읽기 전용 공급자

PRICE_PROVIDER=kiwoom은 KRX 삼성전자005930 현재가·실시간 체결·분봉/일봉을 조회한다. Toss/Synthetic는 유지한다. 키움 가격 부호와 체결량 부호는 크기로 정규화하며 등락금액은 부호를 유지한다. 수량은 주식 체결량이지 서비스 사용자 mSEC 체결량이 아니다.

REST 현재가에는 체결 시각이 없어 초기 상태는 INITIALIZING/UNKNOWN이다. 실시간 체결과 같은 연결 세대의 명시적인0s 시장 상태를 확인해야 OPEN/LIVE로 사용할 수 있다. 재연결 또는 KST 날짜 변경 후 이전 OPEN을 재사용하지 않는다. 실제 키움 계정 연결·시장 상태 초기 수신·native 분봉 시간 labeling은 아직 미검증이며 Mock 성공을 실환경 인수로 설명하지 않는다. 공식 timestamp를 임의 보정하지 않으며 차트 경계 일치는 실제 연결 후 확인해야 한다.

## 갱신 근거

- [신규 서명 시장 경계](../../claude-docs/signed-market-eligibility-implementation.md)
- [키움 시세 구현·검증 한계](../../claude-docs/kiwoom-market-provider-implementation.md)

- [market/MarketPriceService.java](../../backend/src/main/java/com/pricetrack/exchange/market/MarketPriceService.java)
- [market/provider/toss/TossPriceProvider.java](../../backend/src/main/java/com/pricetrack/exchange/market/provider/toss/TossPriceProvider.java)
- [market/provider/toss/TossCandleProvider.java](../../backend/src/main/java/com/pricetrack/exchange/market/provider/toss/TossCandleProvider.java)
- [market/MarketCandleService.java](../../backend/src/main/java/com/pricetrack/exchange/market/MarketCandleService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
