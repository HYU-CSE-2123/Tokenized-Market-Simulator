---
title: "사용자 데이터와 관리자 권한 경계"
domain: security
type: policy
version: 1
status: draft
minimum_role: USER
updated_at: 2026-09-27
---

# 사용자 데이터와 관리자 권한 경계

> 코드 기준: 30105a4. Phase 1 사용자 검토 대기 문서이며 아직 검색·임베딩 대상이 아니다.

## 현재 인증

JWT 검증 후 DB 사용자를 조회하여 userId와 USER/ADMIN 역할을 복원한다. 요청 body나 모델 답변의 userId·role을 인증 근거로 쓰지 않는다.

주문 상세는 주문 ID와 인증 사용자 ID를 함께 조회한다. 주문 목록·체결·포트폴리오도 본인 기준이다. 타인의 견적은 없는 견적과 같은 PRICE_QUOTE_NOT_FOUND로 처리한다.

## 공개 정보와 개인 정보

시장 가격·캔들 및 공개 가격/체결 스트림과 개인 주문·잔고를 구분한다. 공개 체결 알림은 개인 식별 정보 공개를 뜻하지 않는다. WebSocket handshake가 공개여도 개인 queue는 STOMP CONNECT 인증이 필요하다.

현재 ADMIN 역할과 초기 관리자 생성 기반은 있지만 범용 관리자 조회 API가 이미 있는 것은 아니다. ADMIN이 기존 본인 주문 API를 호출해도 자동으로 다른 사용자의 주문을 볼 수 없다.

## AI 인가 — 후속 구현 원칙

AI 검색과 Tool은 아직 구현되지 않았다. 후속 구현에서는 서버가 만든 인증 컨텍스트로 문서 검색과 조회를 제한해야 한다. 모델에게 역할 판단을 맡기거나 프롬프트로만 차단하지 않는다.

문서의 minimum_role은 검색에 적용할 최소 역할 메타데이터다. ADMIN runbook은 USER 검색 후보에서 제외해야 한다. 지금 메타데이터를 작성했다고 런타임 접근 제어가 구현된 것은 아니다. 현재 저장소 파일은 런타임 인증으로 보호되는 지식 API가 아니다.

개인 데이터 Tool도 매 호출마다 소유권을 강제한다. 내부 메서드 호출은 HTTP 보안 필터를 다시 거치지 않으므로 조회 facade에서 검증해야 한다. 비동기 작업에 인증이 자동 전파된다고 가정하지 않는다.

## 허용하지 않는 데이터와 행동

비밀키·JWT·비밀번호·signature·raw transaction을 모델에 전달하지 않는다. 인가된 요약 DTO만 읽기 Tool 대상으로 삼는다. 로그인·견적 신규 발급·주문·faucet·서명·전송·강제 정산은 읽기 Tool이 아니다.

현재 사용자나 특정 주문 소유권을 문서만으로 판정할 수 없다. 인가 조회에 실패하면 접근 범위를 넓히거나 관리자 역할을 가정하지 않고 확인할 수 없다고 응답한다.

## 근거

- [auth/JwtAuthenticationFilter.java](../../backend/src/main/java/com/pricetrack/exchange/auth/JwtAuthenticationFilter.java)
- [common/config/SecurityConfig.java](../../backend/src/main/java/com/pricetrack/exchange/common/config/SecurityConfig.java)
- [order/OrderService.java](../../backend/src/main/java/com/pricetrack/exchange/order/OrderService.java)
- [quote/PriceQuoteService.java](../../backend/src/main/java/com/pricetrack/exchange/quote/PriceQuoteService.java)
- [websocket/config/WebSocketAuthInterceptor.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptor.java)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)

