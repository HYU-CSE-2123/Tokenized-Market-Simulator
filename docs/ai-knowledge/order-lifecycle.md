---
title: "주문 상태와 트랜잭션 상태"
domain: trading
type: policy
version: 1
status: draft
minimum_role: USER
updated_at: 2026-09-27
---

# 주문 상태와 트랜잭션 상태

> 코드 기준: 30105a4. Phase 1 사용자 검토 대기 문서이며 아직 검색·임베딩 대상이 아니다.

## 정의

Order는 사용자의 거래 요청이며 BlockchainTransaction은 해당 요청을 실행하는 체인 전송 기록이다. 둘의 상태를 섞지 않는다.

## 현재 주문 상태

| OrderStatus | 의미와 전이 조건 |
|---|---|
| REQUESTED | 생성·준비된 주문. 아직 체인 제출 확인 전일 수 있음 |
| PENDING_ONCHAIN | 제출을 확인했으며 체인 결과와 DB 정산을 기다림 |
| FILLED | 체결과 자산 반영 완료 |
| FAILED | 준비·전송 실패 보상 또는 실패 receipt 정산 등으로 실패 확정 |
| CANCELED | enum은 존재하지만 현재 취소 API는 구현되지 않음 |

정상 온체인 흐름은 REQUESTED → PENDING_ONCHAIN → FILLED다. 준비/전송 실패는 REQUESTED → FAILED, 체인 실행 실패 정산은 PENDING_ONCHAIN → FAILED가 될 수 있다. 요청 검증에서 거부되면 주문 행 자체가 없을 수도 있다.

PENDING_ONCHAIN이 필요한 이유는 HTTP 요청 처리, 체인 채굴, 확인 수 충족, DB 정산 시점이 다르기 때문이다. 대기는 성공도 실패도 아니다. HTTP 응답만으로 FILLED로 표시하지 않는다.

## REVIEW_REQUIRED는 주문 상태가 아니다

REVIEW_REQUIRED는 BlockchainTransaction의 자동 정산 격리 상태다. 예상 이벤트나 원장 조건이 맞지 않을 때 운영자 확인이 필요하다는 의미다. 주문의 기존 상태와 자산 잠금은 유지된다. 사용자는 주문이 계속 대기로 보일 수 있다.

현재 주문 API는 트랜잭션 격리 상태를 직접 반환하지 않는다. PENDING_ONCHAIN만 보고 REVIEW_REQUIRED라고 단정할 수 없다. 인가된 추가 조회 없이는 원인을 확인할 수 없다.

## 실패·재시도 해석

RPC 응답을 못 받았다는 사실만으로 주문이 실패했다고 판단하지 않는다. 이미 전송됐을 수 있기 때문이다. 실패 receipt를 확인한 경우와 아직 receipt가 없는 경우를 구분한다. 실패 확정 전에 같은 의도로 새 주문을 반복하면 별도 거래가 될 수 있다.

체결 결과는 본인 주문 REST 조회로 확인한다. 잠금액은 이중 사용을 막기 위한 것이며 체결 완료된 차감과 구분한다. 모의 모드에서는 체인을 기다리지 않고 DB에서 즉시 FILLED로 정산한다.

## 근거

- [order/OrderStatus.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderStatus.java)
- [order/OrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)
- [order/OnchainOrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OnchainOrderService.java)
- [blockchain/transaction/BlockchainTransactionStatus.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/transaction/BlockchainTransactionStatus.java)
- [blockchain/settlement/OnchainSettlementService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)

