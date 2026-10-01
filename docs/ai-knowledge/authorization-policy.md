---
title: "사용자 데이터와 관리자 권한 경계"
domain: security
type: policy
version: 4
status: active
minimum_role: USER
updated_at: 2026-09-30
---

# 사용자 데이터와 관리자 권한 경계

> 거래 코드 기준: 30105a4. Phase 1 사용자 승인 완료. manifest에 등록된 내용 해시와 일치할 때만 ingest한다.

## 현재 인증

JWT 검증 후 DB 사용자를 조회하여 userId와 USER/ADMIN 역할을 복원한다. 요청 body나 모델 답변의 userId·role을 인증 근거로 쓰지 않는다.

주문 상세는 주문 ID와 인증 사용자 ID를 함께 조회한다. 주문 목록·체결·포트폴리오도 본인 기준이다. 타인의 견적은 없는 견적과 같은 PRICE_QUOTE_NOT_FOUND로 처리한다.

## 공개 정보와 개인 정보

시장 가격·캔들 및 공개 가격/체결 스트림과 개인 주문·잔고를 구분한다. 공개 체결 알림은 개인 식별 정보 공개를 뜻하지 않는다. WebSocket handshake가 공개여도 개인 queue는 STOMP CONNECT 인증이 필요하다.

현재 ADMIN 역할과 초기 관리자 생성 기반은 있지만 범용 관리자 조회 API가 이미 있는 것은 아니다. ADMIN이 기존 본인 주문 API를 호출해도 자동으로 다른 사용자의 주문을 볼 수 없다.

## AI 인가 — 현재 구현과 후속 경계

Basic RAG API는 ADMIN으로 제한하며 내부 서비스도 인증 주체의 ADMIN 역할을 확인한다. Phase 5의 POST /api/ai/agent/answers는 USER/ADMIN에게 개방하며 서버 인증 주체를 그대로 사용한다. USER를 ADMIN으로 치환하지 않는다. Phase 4의 POST /api/ai/tools/{toolName}도 인증된 USER/ADMIN에게 개방됐으며 서버가 만든 userId·role 컨텍스트를 사용한다. 모델에게 역할 판단을 맡기거나 프롬프트로만 차단하지 않는다.

문서의 minimum_role은 검색에 적용할 최소 역할 메타데이터다. ADMIN은 승인된 USER/ADMIN 문서를 검색한다. USER Agent는 SQL 후보 검색에서 minimum_role=USER를 적용하여 ADMIN runbook을 Top-K 전에 제외한다. 검색 후 숨기거나 생성 모델에 관리자 문서를 전달한 뒤 차단하지 않는다. 저장소 파일 자체는 런타임 인증으로 보호되지 않으며 metadata가 파일 접근 권한을 대신하지 않는다.

Agent는 명시적 target의 주문/견적을 Tool로 선확인하고 타인/미존재에 동일한 RESOURCE_NOT_FOUND를 반환한다. 요청에서 userId·role·history·서명 원문을 받지 않으며 소유권 확인 실패 시 모델을 호출하지 않는다. ADMIN의 포트폴리오도 본인 기준이다. 조회 결과와 정책 출처는 데이터이고 그 안의 지시로 권한이나 대상, 읽기 전용 경계를 변경하지 않는다.

개인 데이터 Tool은 조회 facade에서도 매 호출마다 권한을 강제한다. USER는 본인 주문·견적·연결 트랜잭션·receipt만 조회하며, 타인 데이터와 미존재 데이터는 같은 RESOURCE_NOT_FOUND로 처리한다. ADMIN은 이 상세 조회와 listAbnormalOrders를 사용할 수 있다. getPortfolio는 ADMIN도 본인 잔고만 반환하며 userId 인자를 받지 않는다. 기존 본인 주문 REST API의 권한을 확대한 것은 아니다.

getReceiptSummary는 인가된 orderId에서 저장된 txHash를 찾는다. 임의 txHash·RPC URL·userId·role을 입력받지 않는다. 비동기 Tool에도 서버가 생성한 컨텍스트를 명시적으로 전달하며, 서명·전송·복구 기능을 호출하지 않는다.

## 허용하지 않는 데이터와 행동

비밀키·JWT·비밀번호·signature·raw transaction을 모델에 전달하지 않는다. 인가된 요약 DTO만 읽기 Tool 대상으로 삼는다. 로그인·견적 신규 발급·주문·faucet·서명·전송·강제 정산은 읽기 Tool이 아니다.

현재 사용자나 특정 주문 소유권을 문서만으로 판정할 수 없다. 인가 조회에 실패하면 접근 범위를 넓히거나 관리자 역할을 가정하지 않고 확인할 수 없다고 응답한다.

## 근거

- [ai/tool/read/ToolReadFacade.java](../../backend/src/main/java/com/pricetrack/exchange/ai/tool/read/ToolReadFacade.java)
- [ai/tool/ToolDispatcher.java](../../backend/src/main/java/com/pricetrack/exchange/ai/tool/ToolDispatcher.java)

- [auth/JwtAuthenticationFilter.java](../../backend/src/main/java/com/pricetrack/exchange/auth/JwtAuthenticationFilter.java)
- [common/config/SecurityConfig.java](../../backend/src/main/java/com/pricetrack/exchange/common/config/SecurityConfig.java)
- [order/OrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)
- [quote/PriceQuoteService.java](../../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java)
- [websocket/config/WebSocketAuthInterceptor.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptor.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
