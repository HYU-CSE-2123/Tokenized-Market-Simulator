---
title: "서명 견적의 소유권과 유효성"
domain: trading
type: policy
version: 1
status: draft
minimum_role: USER
updated_at: 2026-09-27
---

# 서명 견적의 소유권과 유효성

> 코드 기준: 30105a4. Phase 1 사용자 검토 대기 문서이며 아직 검색·임베딩 대상이 아니다.

## 정의와 이유

서명 견적은 특정 가격과 주문 조건을 결합한 일회용 약속이다. 사용자가 확인한 가격을 같은 거래에서 검증·정산하기 위해 사용한다. 예전처럼 Oracle 저장 가격을 주기적으로 갱신한 뒤 그때그때 읽어 체결하는 방식이 아니다.

## 정상 발급 조건

온체인 견적은 선택된 공급자의 시장 스냅샷에서 발급한다. Toss 모드에서는 실제 시세를 사용한다. CLOSED 또는 STALE는 거부한다. 발급기는 별도로 관측 후 5초 이내, 미래 오차 2초 이내를 RPC 조회 전후에 검사한다.

| PriceReport 필드 | 의미 |
|---|---|
| quoteId | 일회용 식별자 |
| symbolHash | 지원 종목 해시 |
| priceE8 | 가격 × 10의 8승 정수 |
| observedAt / validUntil | 관측 시각 / 만료 시각(epoch seconds) |
| side | BUY=0, SELL=1 |
| inputAmount | 매수 mKRW 또는 매도 mSEC 입력량(토큰 최소 단위) |
| minimumOutput | 서버가 계산한 최소 수령량(토큰 최소 단위) |
| executor | 백엔드 운영자 지갑 주소 |

보고서 전체를 EIP-712로 서명하며 도메인에는 체인 ID와 Oracle 주소가 포함된다. 가격 서명 키와 거래 전송 키는 분리한다. 사용자 소유권은 보고서의 executor가 아니라 서버 DB의 userId로 검증한다.

## 30초와 최소 수령량

validUntil은 **observedAt + 30초**다. 발급 후 무조건 30초가 남는 것이 아니다. 이 기간은 오래된 가격 재사용을 제한하기 위한 현재 구현 정책이며 체인 전송 완료 보장은 아니다. 백엔드와 Oracle은 현재 시각이 validUntil보다 늦으면 거부한다.

minimumOutput은 해당 가격·입력량·당시 Vault 수수료로 계산한 출력량이다. 사용자가 지정한 임의 허용 편차가 아니다. Vault 실행 시 실제 출력이 이 값보다 작으면 되돌린다. 견적 이후 현재가가 움직여도 서명된 가격을 최신가로 자동 교체하지 않는다.

## 소유권·일회성 소비

보고서와 signature는 백엔드 DB에 보관한다. 클라이언트에는 quoteId와 안전한 가격·수량·시각 정보가 반환되며, 주문은 quoteId와 symbol·입력량을 보낸다. 원문 보고서와 서명을 클라이언트에게 받아 신뢰하지 않는다.

서버는 소유권·ISSUED 상태·만료·방향·입력량을 확인하고, 견적 행 잠금 아래 주문 생성·자산 잠금·CONSUMED 처리를 묶는다. 같은 견적은 한 번만 소비한다. DB 소비와 체인의 usedQuoteIds는 서로 다른 경계의 이중 방어다.

## Oracle과 Vault의 검증

Oracle은 승인된 소비자, 비어 있지 않은 quoteId, 종목·양수 가격, 정확한 30초 창, 미래 오차·만료, 재사용, 승인 서명자를 검증한다. Vault는 방향·executor·양수 입력량·최소 수령량·유동성을 검증하고 Oracle 소비와 자산 이동을 하나의 체인 트랜잭션으로 수행한다.

## 실패와 사용자 확인

타인의 견적과 없는 견적은 PRICE_QUOTE_NOT_FOUND다. 만료·이미 소비됨·방향/입력 불일치 등은 PRICE_QUOTE_UNAVAILABLE이다. 새 견적을 확인하고 주문해야 하며 실패한 견적의 재사용을 보장하지 않는다.

특히 준비 완료 후 서명 거래 저장 전 실패하면 주문 실패·자산 잠금 해제는 가능하지만 소비된 견적을 ISSUED로 되돌리지 않는다. 잔고 부족으로 준비에 실패한 경우는 견적을 소비하지 않는다. CONSUMED는 체결 완료를 뜻하지 않는다.

## 근거

- [quote/PriceQuoteService.java](../../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java)
- [blockchain/oracle/PriceReportIssuer.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReportIssuer.java)
- [blockchain/oracle/PriceReport.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/oracle/PriceReport.java)
- [order/OnchainOrderPreparationService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderPreparationService.java)
- [PriceOracle.sol](../../contracts/src/PriceOracle.sol)
- [ExchangeVault.sol](../../contracts/src/ExchangeVault.sol)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)

