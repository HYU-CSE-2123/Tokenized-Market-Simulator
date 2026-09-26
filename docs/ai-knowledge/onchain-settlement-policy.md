---
title: "온체인 체결과 DB 정산 확정"
domain: settlement
type: policy
version: 1
status: draft
minimum_role: USER
updated_at: 2026-09-27
---

# 온체인 체결과 DB 정산 확정

> 코드 기준: 30105a4. Phase 1 사용자 검토 대기 문서이며 아직 검색·임베딩 대상이 아니다.

## 정의와 필요성

체인 전송 응답은 체결 확정이 아니다. 성공 receipt도 혼자서는 사용자 잔고를 반영할 충분한 근거가 아니다. 예상 Vault 거래인지 검증하고 DB에 원자적으로 반영해야 한다.

## 정상 처리 순서

1. 매수는 입력 mKRW, 매도는 입력 mSEC를 내부 원장에서 잠근다.
2. 운영자 거래를 전송하고 PENDING_ONCHAIN을 기록한다.
3. receipt가 존재하고 설정된 requiredConfirmations를 충족할 때까지 기다린다.
4. 성공 receipt에서 예상 Vault 주소와 Bought/Sold 이벤트 종류를 찾는다.
5. 해당 이벤트가 정확히 하나이며 user가 운영자 주소이고 입력량이 주문과 같은지 검증한다.
6. DB 정산에서 주문 방향·입력·대기 상태·잠금 잔고 등을 확인한다.
7. 잔고 차감/지급·잠금 해제·Trade 생성·Order FILLED·BlockchainTransaction CONFIRMED를 하나의 DB 트랜잭션으로 반영한다.

확인 수는 latestBlock - receiptBlock + 1로 계산한다. 현재 설정을 조회하지 않고 특정 확인 수나 절대적인 재조직 불가능성을 보장하지 않는다.

## 실패·예외와 멱등성

receipt 없음이나 확인 수 부족은 대기다. 실패 receipt는 입력 자산 잠금을 풀고 주문/트랜잭션을 FAILED로 정산한다. 이벤트·정산 조건 불일치는 REVIEW_REQUIRED로 격리하며 잠금을 임의 해제하지 않는다.

이미 CONFIRMED인 거래 또는 FILLED 주문에 대한 성공 정산은 재반영하지 않는다. Trade의 orderId 유일성도 중복 체결을 막는다. 반대로 주문이 FILLED가 아닌데 Trade만 존재하면 불일치로 간주하고 자동 덮어쓰지 않는다. DB 트랜잭션 중 예외는 그 정산의 자산 변경도 되돌린다.

## 조회와 확인 경계

사용자는 주문 상태·체결 내역·포트폴리오를 함께 확인한다. 실제 체결 수량·수수료는 검증된 이벤트에 근거하며 현재 차트 가격으로 과거 체결을 다시 계산하지 않는다.

운영자가 확인할 대상은 연결 주문, 트랜잭션 상태, receipt·이벤트, 잠금액의 일치 여부다. 이 문서는 정책이며 특정 거래가 정산됐는지는 최신 인가 조회가 필요하다. AI의 강제 정산·잠금 해제 기능은 없다.

## 근거

- [blockchain/reconciliation/BlockchainReconciliationService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java)
- [blockchain/contract/ContractEventParser.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java)
- [blockchain/settlement/OnchainSettlementService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)

