---
title: "온체인 체결과 DB 정산 확정"
domain: settlement
type: policy
version: 2
status: active
minimum_role: USER
updated_at: 2026-10-13
---

# 온체인 체결과 DB 정산 확정

> 2026-10-13 통합 추가 기능 및 키움 기능 소스 기준 갱신. 기존9문서 역할·active 승인 경계를 유지하며 manifest의 버전/내용 해시와 일치할 때만 ingest한다. 기존 AI DB 재색인이나 최신 실환경 인수 완료를 의미하지 않는다.

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

## DB Faucet와 두 대사

DB Faucet은1회100만mKRW, KST 하루1회·누적5회/500만·서비스 총5000만의 정액 지급이다. DB 결정 시각을 사용하고 account quota→global budget→balance 순서로 잠근다. 잔고·FaucetGrant·사용량·성공 멱등 기록은 하나의 DB transaction이며 lockedAmount는 바꾸지 않는다. 원본 키는 저장하지 않고 fingerprint로 성공 replay를 조회한다. 정책/사용량/실제 지급 journal 불일치는 지급을 차단하며 자동 보정하지 않는다.

장부-체인 자산 대사는 FaucetGrant·Trade와 사용자 장부 및 불변 체인 기준점 이후의 ONCHAIN 자산 변화를 비교한다. DB_ONLY/UNKNOWN을 온체인 실행으로 추정하지 않는다. Faucet 사용량 counter를 자산에 중복 합산하지 않는다. 지급만으로 operator/Vault 공급·allowance·ETH가 늘어나지 않는다.

전수 대사는 DB Order/Trade/Quote/BlockchainTransaction과 체인 receipt/Vault·Oracle 이벤트를 양방향으로 비교한다. 과거 signer/fee 근거 부족, 조회 실패, 스캔 불완전은 검증 한계이며 현재 값으로 추론하지 않는다. 일관된 DB/chain cut이 아니면 INCONCLUSIVE로 처리한다. 두 대사는 읽기 감사이며 실제 거래 재실행·자산 보정 기능이 아니다.

- [reserve/ReserveCalculator.java](../../backend/src/main/java/com/pricetrack/exchange/reserve/ReserveCalculator.java)
- [tradeaudit/AuditService.java](../../backend/src/main/java/com/pricetrack/exchange/tradeaudit/AuditService.java)
- [wallet/faucet/FaucetService.java](../../backend/src/main/java/com/pricetrack/exchange/wallet/faucet/FaucetService.java)

- [blockchain/reconciliation/BlockchainReconciliationService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java)
- [blockchain/contract/ContractEventParser.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/contract/ContractEventParser.java)
- [blockchain/settlement/OnchainSettlementService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
