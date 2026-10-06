---
title: "시스템 책임과 지식의 경계"
domain: architecture
type: overview
version: 7
status: active
minimum_role: USER
updated_at: 2026-10-06
---

# 시스템 책임과 지식의 경계

> 거래 코드 기준: 30105a4. Phase 1 사용자 승인 완료. manifest에 등록된 내용 해시와 일치할 때만 ingest한다.

## 정의와 목적

mSEC는 삼성전자 기준 가격의 변동을 모사하는 교육용 합성자산 토큰이다. 실제 주식·배당·의결권·실제 원화 상환권을 제공하지 않는다. mKRW는 모의 원화이며 faucet은 실제 은행 입금이 아니다.

공개 데모는 SIMULATED 합성 시장이며 실제 삼성전자 시세를 추종하지 않는다. Toss 실연동은 별도 로컬 경로로 보존하지만 public profile에서 호출하지 않는다. 공개 설정의 블록체인은 인터넷에 RPC를 노출하지 않는 Anvil 테스트 체인이다. 이미지/운영 절차 준비와 실제 AWS 공개 배포 완료는 구분한다.

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

AI Phase 2 Basic RAG와 Phase 3 검색 품질 개선은 실제 공급자 평가와 사용자 승인을 완료했다. 문서 검색·출처 기반 답변 API는 ADMIN 전용이다. 기본 검색은 pgvector semantic 검색, threshold 0.25, 충분한 후보 중 문서당 최대 2개·최종 5개 선택이다. Hybrid Search는 비교 실험이며 기본 경로가 아니다.

Phase 4에는 독립적인 읽기 Tool 8개를 구현했다. getOrder/getQuote/getBlockchainTransaction/getReceiptSummary는 인가된 거래 상태를, getMarketStatus/getCurrentReferencePrice는 시장 스냅샷을, getPortfolio는 본인 자산을, listAbnormalOrders는 관리자용 이상 주문 목록을 조회한다. AI_TOOLS_ENABLED는 기본 false이며 AI DB·외부 AI API 없이도 Tool 조회가 동작한다.

RAG는 정적 규칙, Tool은 조회 시점의 사실을 제공한다. Phase 5의 제한된 RAG+Tool Agent는 KNOWLEDGE/STATE/MIXED로 질문을 분류하고 서버가 정한 읽기 경로만 실행한다. AI_AGENT_ENABLED 기본 false이며 USER/ADMIN의 POST /api/ai/agent/answers에 연결된다. 기존 Basic RAG의 현재 상태 질문은 여전히 LIVE_DATA_REQUIRED이며 Agent API와 구분한다.

Agent는 명시적 주문/견적 target의 소유권을 모델 호출 전에 확인한다. 문서 규칙과 성공 Tool 사실을 분리하고 인용 ID·사실 값을 검증한다. 조회 실패나 DB/체인 불일치는 불확실성으로 제공하며 원인을 확정하거나 거래를 실행하지 않는다. 주문 실행·서명·강제 정산을 AI에 허용하지 않는다.

Phase 6의 Skill은 버전과 승인 hash가 있는 배포 정의와 고정 조사 handler다. settlement-debugging은 ADMIN 전용 주문 조사, signed-quote-diagnosis는 인가된 견적 조사, market-availability-diagnosis는 단일 시장 가격 스냅샷 조사다. AI_SKILLS_ENABLED 기본 false이며 Agent와 Tool 경계를 그대로 사용한다. 명시 skillId는 분류를 생략하고 자동 선택은 기존 분류 한 번에서만 한다. 같은 run의 Tool 최대4, 검색1, 모델2 예산과 worker2를 공유한다. 반복 polling과 재계획은 없다.

Skill은 역할과 허용 domain을 SQL 후보 단계에서 동시에 제한한다. 정책이 없으면 검색 범위를 넓히지 않는다. 안전한 단계 trace와 서버 관측 분류를 반환하며 현재 만료와 소비를 별도로 표시한다. 실패 receipt만으로 서명, 만료 또는 최소 수령량 오류의 원인을 확정하지 않는다. 조회 연결이 불일치하면 후속 분기를 중단하며 자동 복구는 하지 않는다.

Phase 7은 커밋된 blockchain_transactions의 REVIEW_REQUIRED를 별도 polling으로 감지하고 settlement-debugging을 자동 실행한다. 주문 상태에 REVIEW_REQUIRED를 추가한 것이 아니다. AI_AUTO_DIAGNOSIS_ENABLED는 기본 false이며 기존 ADMIN 계정을 확인하고 Agent 예산을 공유한다. 중복 예약·claim·결과는 거래 DB가 아닌 AI DB에 기록한다. 자동 작업은 실제 Agent 실행 최대1개로 제한하고, 실행 여부가 불명확한 작업을 자동 재호출하지 않는다. ADMIN 전용 진단 이력과 최소 웹 패널에서 관측 시각·출처·trace·불확실성을 확인하며 자동 수정은 없다. 진단 이력은 RAG 지식이나 대화 기억이 아니다. 장시간 대기 자동 진단·범용 챗봇·장기 기억은 구현하지 않았다.

현재 가격, 특정 계정 잔고, 주문 상태, txHash·receipt는 이 문서에서 알 수 없다. 최신 인가 조회가 없으면 확인 불가로 설명한다. 승인된 Phase 2 선택은 기존 Boot 내부 어댑터, 별도 pgvector DB, text-embedding-3-small 1536차원과 gpt-5.6-terra다. 거래 DB와 AI DB 사이의 분산 트랜잭션은 없다.

## 근거

- [ai/tool/ToolRegistry.java](../../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolRegistry.java)
- [ai/RagService.java](../../backend/src/main/java/com/pricetrack/exchange/ai/RagService.java)
- [ai/agent/AgentService.java](../../backend/src/main/java/com/pricetrack/exchange/ai/agent/AgentService.java)

- [order/OrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)
- [wallet/WalletService.java](../../backend/src/main/java/com/pricetrack/exchange/wallet/WalletService.java)
- [blockchain/settlement/OnchainSettlementService.java](../../backend/src/main/java/com/pricetrack/exchange/blockchain/settlement/OnchainSettlementService.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
