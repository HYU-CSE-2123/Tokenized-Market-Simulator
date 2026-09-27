---
title: "시스템 책임과 지식의 경계"
domain: architecture
type: overview
version: 2
status: active
minimum_role: USER
updated_at: 2026-09-27
---

# 시스템 책임과 지식의 경계

> 거래 코드 기준: 30105a4. Phase 1 사용자 승인 완료. manifest에 등록된 내용 해시와 일치할 때만 ingest한다.

## 정의와 목적

mSEC는 삼성전자 기준 가격의 변동을 모사하는 교육용 합성자산 토큰이다. 실제 주식·배당·의결권·실제 원화 상환권을 제공하지 않는다. mKRW는 모의 원화이며 faucet은 실제 은행 입금이 아니다.

## 현재 구현과 책임

| 구성 | 책임과 기준 |
|---|---|
| Spring Boot | 인증, 견적 발급·소유권, 주문, 자산 잠금, 체인 전송·복구, 조회 |
| PostgreSQL | 사용자별 잔고·잠금·주문·체결·견적과 전송 상태의 영속 기록 |
| PriceOracle | 주문별 서명 가격 보고서의 신뢰·시간·일회성 검증 |
| ExchangeVault | 보고서 조건에 따른 mKRW 이동과 mSEC 발행·소각 |
| Toss 공급자 | 삼성전자 현재가·시장 일정·과거 봉과 실시간 tick 제공 |
| 웹 | REST로 상태 조회, WebSocket으로 알림·실시간 가격 반영 |

사용자끼리 주문을 매칭하는 호가장이 아니다. 거래 상대는 Vault이며 사용자의 수요·공급으로 mSEC 가격을 만들지 않는다. 화면의 현재 가격과 이미 발급된 견적 가격은 구분한다.

## 운영자 통합 지갑과 원장 경계

현재 온체인 executor는 백엔드 운영자 지갑이다. 개인별 온체인 지갑이 아니므로 체인 이벤트의 user 주소는 서비스 사용자의 DB ID가 아니다. 사용자별 자산 귀속의 기준은 PostgreSQL 내부 원장이고, 체인 receipt와 검증된 이벤트는 운영자 거래의 실행·정산 근거다.

DB와 체인은 하나의 ACID 트랜잭션이 아니다. 먼저 입력 자산을 잠그고, 체인 실행을 확인한 뒤 DB 정산을 확정하며, 중간 장애는 저장한 상태로 복구한다. 온체인 유동성과 사용자 원장의 준비금 대사는 별도 미완료 과제다.

블록체인 비활성 모드에서는 DB 모의 거래가 즉시 정산된다. 이를 온체인 거래 성공으로 설명하지 않는다. DB faucet 잔고 지급이 Vault의 온체인 유동성까지 자동 충당한다는 뜻도 아니다.

## AI 범위 — 계획과 현재의 구분

AI Phase 2에는 ADMIN 전용 Basic RAG 저장·색인·검색·출처 기반 답변 코드를 추가했다. 실제 공급자 품질 평가는 별도 완료 확인이 필요하다. Tool·Agent는 아직 구현하지 않았다. RAG는 정책을 설명하고 향후 인가된 읽기 Tool은 현재 상태를 조회한다. 주문 실행·서명·강제 정산을 AI에 허용하지 않는다.

현재 가격, 특정 계정 잔고, 주문 상태, txHash·receipt는 이 문서에서 알 수 없다. 최신 인가 조회가 없으면 확인 불가로 설명한다. 승인된 Phase 2 선택은 기존 Boot 내부 어댑터, 별도 pgvector DB, text-embedding-3-small 1536차원과 gpt-5.6-terra다. 거래 DB와 AI DB 사이의 분산 트랜잭션은 없다.

## 근거

- [order/OrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)
- [wallet/WalletService.java](../../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java)
- [blockchain/settlement/OnchainSettlementService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
