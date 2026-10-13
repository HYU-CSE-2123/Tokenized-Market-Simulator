---
title: "WebSocket 알림과 REST 재동기화"
domain: realtime
type: policy
version: 2
status: active
minimum_role: USER
updated_at: 2026-10-13
---

# WebSocket 알림과 REST 재동기화

> 2026-10-13 통합 추가 기능 및 키움 기능 소스 기준 갱신. 기존9문서 역할·active 승인 경계를 유지하며 manifest의 버전/내용 해시와 일치할 때만 ingest한다. 기존 AI DB 재색인이나 최신 실환경 인수 완료를 의미하지 않는다.

## 역할

WebSocket은 실시간 가격과 상태 변경 알림을 전달한다. 개인 주문·잔고의 영속 기준은 DB를 읽는 REST이며, WebSocket은 이벤트 영속 저장소나 누락 없는 전달 보장이 아니다.

## 전송 시점

WebSocketDeliveryListener는 AFTER_COMMIT을 사용한다. DB 트랜잭션 안에서 발행된 이벤트는 commit 이후 전달하므로 롤백된 주문·자산 변경을 정상 완료처럼 알리지 않는다.

다만 fallbackExecution=true이므로 가격 알림처럼 DB 트랜잭션 밖에서 발행된 이벤트는 즉시 전달할 수 있다. 모든 WebSocket 이벤트가 반드시 DB 저장을 거쳤다고 해석하면 안 된다. 전송 실패가 DB commit을 취소하지도 않는다.

## 공개·개인 구독

| 구독 경로 | 의미 |
|---|---|
| /topic/markets/mSEC/price | 공개 가격 |
| /topic/markets/mSEC/trades | 공개 체결 |
| /user/queue/orders | 인증된 본인의 주문 |
| /user/queue/portfolio | 인증된 본인의 포트폴리오 |

Native WebSocket과 SockJS 모두 STOMP 구독 정책을 따른다. CONNECT의 Bearer JWT로 신원을 설정한다. 익명은 공개 destination만 구독할 수 있다. 임의 destination과 클라이언트 STOMP SEND는 거부한다.

## 연결 복구와 화면 해석

웹 클라이언트는 재연결 후 REST로 시장·주문·체결·포트폴리오를 재동기화한다. 재시도 지연은 1~30초 지수 백오프다. 복구 요청 일부만 실패할 수 있으므로 성공 항목은 반영하고 실패를 표시한다.

계정 변경·로그아웃·인증 실패 후 이전 개인 이벤트가 새 화면을 덮지 않도록 연결 세대와 계정 상태를 구분한다. 인증 실패나 명시적 연결 해제는 무조건 자동 재접속하는 상황이 아니다. 주문 이벤트가 역순 도착하더라도 완료 주문을 과거 대기로 되돌리지 않도록 처리한다.

이벤트를 못 받았다는 이유만으로 주문을 다시 제출하지 않는다. REST 조회도 실패하면 최신 상태 확인 불가로 표시하며 마지막 화면을 현재 확정 상태라고 단정하지 않는다.

## 근거

## Faucet·조건부 주문·새 시세 공급자

Faucet 최초 지급의 포트폴리오 알림은 commit 후 전달하며 성공 키 replay는 새 지급·추가 알림을 만들지 않는다. 응답 유실 시 동일 키로 결과를 조회하고 현재 잔고는 별도 REST로 재확인한다. 조건부 상태 알림과 실제 실행 Order 결과는 별도이며 취소·대기를 온체인 체결 완료로 표시하지 않는다.

Toss/KIWOOM 실제 tick은 공급자 스트림이고 사용자 거래 체결이 아니다. KIWOOM의 체결량은 기초 주식 수량이며 Synthetic는 합성 수량이다. 실제 키움 stream/차트 시간 경계는 아직 실환경 미검증이다. 같은 초의 서로 다른 체결량을 무조건 timestamp 중복으로 버리지 않는다. 로그아웃·계정 전환 후 늦은 REST/개인 WS가 다른 계정을 덮지 않도록 기존 화면 세대 경계를 유지한다.

- [websocket/publisher/WebSocketDeliveryListener.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/WebSocketDeliveryListener.java)
- [websocket/config/WebSocketAuthInterceptor.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptor.java)
- [websocket/event/WebSocketDestinations.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/event/WebSocketDestinations.java)
- [웹 복구 처리](../../tools/websocket-test-client/src/recovery.js)
- [웹 연결 처리](../../tools/websocket-test-client/src/websocket.js)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)
