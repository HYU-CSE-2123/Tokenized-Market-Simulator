---
title: "체인 전송 장애의 읽기 전용 진단"
domain: operations
type: runbook
version: 1
status: active
minimum_role: ADMIN
updated_at: 2026-09-27
---

# 체인 전송 장애의 읽기 전용 진단

> 거래 코드 기준: 30105a4. Phase 1 사용자 승인 완료. manifest에 등록된 내용 해시와 일치할 때만 ingest한다.

## 목적과 접근 범위

운영자가 전송 장애를 구분하기 위한 읽기 전용 점검 기준이다. 재서명·수동 raw 전송·DB 상태 수정·자산 잠금 해제를 지시하는 절차가 아니다. AI 자동 진단은 아직 구현되지 않았다.

## 정상 저장·전송 경계

nonce는 운영자 주소의 거래 순서를 구분한다. BlockchainTransactionSender는 단일 JVM에서 전송을 synchronized 처리하고 pending nonce를 조회한다. 분산 인스턴스 전체의 nonce 잠금을 보장하는 구현은 아니다.

gas 산정·거래 서명 후 raw transaction과 예상 txHash를 SIGNED 상태로 별도 DB 트랜잭션에 저장하고 RPC broadcast를 수행한다. 제출 확인 뒤 SUBMITTED 및 주문 PENDING_ONCHAIN을 기록한다.

## 상태별 확인 절차

| 관측 상태 | 확인할 정보 | 해석 |
|---|---|---|
| SIGNED | 저장된 예상 txHash의 체인 존재 여부, RPC 가용성 | 전송 전이거나 전송 응답만 유실됐을 수 있음 |
| SUBMITTED | receipt 유무, 성공 여부, 확인 수 | receipt 없음은 즉시 실패가 아님 |
| CONFIRMED | 연결 주문 FILLED 및 체결 반영 | 자동 정산 완료 |
| FAILED | 실패 단계와 주문 잠금 해제 결과 | 실패 확정 근거 확인 |
| REVIEW_REQUIRED | 정제된 오류 유형, 이벤트·주문·원장 불일치 | 자동 반복 처리 제외, 운영자 검토 필요 |

reconciliation은 SIGNED/SUBMITTED를 대상으로 한다. 서버 재시작 후에도 DB와 체인 상태가 유지되면 저장된 SIGNED를 다시 확인한다. 체인 자체를 초기화한 상황은 단순 서버 재시작과 다르다.

## RPC 응답 유실과 동일 거래 복구

SIGNED 복구는 예상 txHash로 체인을 먼저 조회하고, 없으면 저장된 동일 raw transaction을 재전송한다. 같은 raw는 같은 nonce·내용·txHash를 유지하므로 이미 실행된 거래를 새 거래로 복제하지 않는다.

새 nonce로 재서명하면 원래 거래가 성공한 상태에서 추가 거래가 실행될 수 있다. 따라서 RPC timeout만 보고 신규 거래를 만들거나 잔고를 풀지 않는다. 일시적인 RPC 예외는 다음 주기에 재시도한다.

SIGNED 저장 전 실패는 failIfNotSigned가 주문 실패·잠금 해제를 시도한다. 이미 SIGNED가 있으면 이 보상을 적용하지 않고 복구 대상으로 남긴다. 소비 견적은 보상 후에도 CONSUMED다.

## REVIEW_REQUIRED와 제한사항

EventValidationException 또는 SettlementConsistencyException이면 트랜잭션을 REVIEW_REQUIRED로 격리한다. 주문 상태와 잠금은 유지된다. receipt 미발견·일시적 RPC 오류만으로 격리하지 않는다.

운영자는 거래 유형, 연결 주문, Vault 이벤트 수·주소·입력, 잠금액, 중복 체결 여부를 확인한다. 비밀키·JWT·signature·raw transaction·원시 예외 전체를 AI 컨텍스트나 공유 문서에 붙이지 않는다. 인가된 요약 DTO는 후속 Phase에서 구현할 대상이다.

기존 UPDATE_PRICE 기록의 정산 복구 코드는 남아 있으나 현재 거래를 위한 주기적 updatePrice 제출은 제거됐다. 이 기록을 현재 주문으로 오해하지 않는다.

## 근거

- [blockchain/transaction/BlockchainTransactionSender.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionSender.java)
- [blockchain/transaction/BlockchainTransactionPersistence.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionPersistence.java)
- [blockchain/reconciliation/BlockchainReconciliationService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/reconciliation/BlockchainReconciliationService.java)
- [order/OnchainOrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
