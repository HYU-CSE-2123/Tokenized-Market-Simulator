---
title: "상태·오류 코드 해석"
domain: support
type: reference
version: 2
status: active
minimum_role: USER
updated_at: 2026-10-13
---

# 상태·오류 코드 해석

> 2026-10-13 통합 추가 기능 및 키움 기능 소스 기준 갱신. 기존9문서 역할·active 승인 경계를 유지하며 manifest의 버전/내용 해시와 일치할 때만 ingest한다. 기존 AI DB 재색인이나 최신 실환경 인수 완료를 의미하지 않는다.

## 해석 원칙

아래는 현재 코드에 정의된 주요 값이다. HTTP 오류 코드, DB 상태, Solidity custom error는 서로 다른 종류이며 같은 코드 체계를 가정하지 않는다. 오류 메시지만으로 특정 주문의 최종 체결 여부를 단정하지 않는다.

## HTTP 오류

| 코드 | HTTP | 사용자에게 설명할 의미 |
|---|---|---|
| LOGIN_ID_ALREADY_EXISTS | 409 | 이미 사용하는 로그인 ID |
| INVALID_CREDENTIALS | 401 | 로그인 정보 불일치 |
| INVALID_TOKEN | 401 | 유효하지 않은 토큰 |
| USER_NOT_FOUND | 404 | 사용자를 찾을 수 없음 |
| INSUFFICIENT_BALANCE | 409 | 사용할 수 있는 잔고 부족 |
| BALANCE_NOT_INITIALIZED | 409 | 잔고 초기화 상태 확인 필요 |
| ORDER_NOT_FOUND | 404 | 본인에게 조회 가능한 주문 없음 |
| UNSUPPORTED_SYMBOL | 400 | 지원하지 않는 종목 |
| OPERATOR_NOT_READY | 409 | 운영자 거래 준비 상태 확인 필요 |
| BLOCKCHAIN_UNAVAILABLE | 503 | 블록체인 설정·호출 경로 확인 필요 |
| MARKET_CLOSED | 409 | 현재 시장 정책상 신규 거래 불가 |
| PRICE_STALE | 503 | 오래된 가격으로 거래 불가 |
| INVALID_CANDLE_QUERY | 400 | 캔들 조회 조건 오류 |
| PRICE_QUOTE_NOT_FOUND | 404 | 본인에게 조회 가능한 견적 없음 |
| PRICE_QUOTE_UNAVAILABLE | 409 | 누락·만료·소비됨·주문 조건 불일치 등 |
| VALIDATION_FAILED | 400 | 요청 필드 검증 실패 |

이 표는 GlobalExceptionHandler의 매핑이다. 보안 필터·STOMP 거부·예상하지 못한 예외까지 모두 이 표와 같은 응답을 반환한다는 뜻은 아니다. BLOCKCHAIN_UNAVAILABLE 하나로 원인을 RPC 다운으로 특정할 수도 없다.

## 저장 상태

- Order: REQUESTED, PENDING_ONCHAIN, FILLED, FAILED, CANCELED. CANCELED 취소 API는 미구현이다.
- BlockchainTransaction: CREATED, SIGNED, SUBMITTED, CONFIRMED, FAILED, REVIEW_REQUIRED. enum의 CREATED가 모든 정상 전송에 영속 중간 단계로 남는다고 가정하지 않는다.
- PriceQuote: ISSUED, CONSUMED, EXPIRED. 만료 스케줄러가 자동 EXPIRED로 바꾸는 것이 아니므로, 저장 상태 ISSUED라도 validUntil이 지났으면 사용 불가다.
- 시장: MarketStatus는 UNKNOWN/OPEN/CLOSED, PriceStatus는 INITIALIZING/LIVE/DEGRADED/STALE/SIMULATED다. 시장 개장 여부와 가격 상태는 다른 축이다.

REVIEW_REQUIRED는 트랜잭션 격리다. 운영자는 이벤트와 정산 원장 불일치를 확인해야 하지만 USER에게 운영자 원시 로그를 노출하지 않는다. 자세한 복구 절차는 관리자용 지식으로 분리한다.

## Solidity custom error 예

| 오류 | 의미 |
|---|---|
| ReportExpired / InvalidValidityWindow / ObservationFromFuture | 보고서 시간 조건 위반 |
| QuoteAlreadyUsed | 온체인 견적 재사용 |
| InvalidPriceSigner / UnauthorizedConsumer | 서명자 또는 소비자 불일치 |
| InvalidSymbol / InvalidQuoteId / InvalidPrice | 보고서 기본값 검증 실패 |
| InvalidReportSide / InvalidExecutor | Vault 거래 방향·실행자 불일치 |
| InvalidMinimumOutput / MinimumOutputNotMet | 최소 수령량 조건 불충족 |
| ZeroAmount / InsufficientLiquidity | 입력량 또는 Vault 지급 유동성 문제 |

custom error가 위 이름 그대로 REST code로 변환되는 구현은 아니다. EVM revert는 체인 거래 전체를 되돌리며, 백엔드는 전송 단계 또는 receipt 확인 결과에 따라 주문 상태를 처리한다.

## 확인 순서

사용자는 입력 조건·시장 상태·견적 만료와 본인 주문 REST 결과를 확인한다. 운영자 원인 진단은 권한 있는 조회로 거래 상태·실패 단계를 확인해야 한다. 문서에는 특정 사용자의 잔고, txHash, receipt 또는 현재 장애 여부를 저장하지 않는다.

## 추가 기능의 상태와 한계

조건부 의도는 WAITING/PREPARING/EXECUTING/SUCCEEDED/FAILED/CANCELLED/EXPIRED/REVIEW_REQUIRED다. 기존 Order의 CANCELED enum과 조건부 CANCELLED를 혼동하지 않는다. 조건부 취소 API는 구현됐지만 이미 실행된 즉시 주문의 강제 취소 API는 아니다.

장부-체인/전수 대사의 MATCH·MISMATCH·INCONCLUSIVE는 주문 체결 상태가 아니다. 전수 대사의 coverage가 불완전하거나 근거가 부족하면 일부 MATCH를 전수 MATCH로 설명하지 않는다. LONG_WAIT는 기본300초 정체 경고이며 실패 확정이나 REVIEW_REQUIRED 변경이 아니다. 기록 없는 마지막 관측은 UNRECORDED다.

Faucet 오류는 FAUCET_INVALID_KEY(400), FAUCET_DAILY_LIMIT(429), FAUCET_LIFETIME_LIMIT/FAUCET_BUDGET_EXHAUSTED(409), FAUCET_POLICY_UNAVAILABLE/FAUCET_ACCOUNTING_INCONSISTENT(503) 등이다. KIWOOM_UNAVAILABLE(503)은 정제된 시세 조회 실패이며 특정 인증/네트워크 원인을 단정하지 않는다. 신규 서명 거절은 기존 견적 오류 경계를 유지한다.

## 근거

- [common/exception/GlobalExceptionHandler.java](../../backend/src/main/java/com/pricetrack/exchange/common/exception/GlobalExceptionHandler.java)
- [order/OrderStatus.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderStatus.java)
- [quote/PriceQuoteStatus.java](../../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteStatus.java)
- [blockchain/transaction/BlockchainTransactionStatus.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionStatus.java)
- [PriceOracle.sol](../../contracts/src/PriceOracle.sol)
- [ExchangeVault.sol](../../contracts/src/ExchangeVault.sol)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
