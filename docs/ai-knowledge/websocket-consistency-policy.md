---
title: "WebSocket 알림과 REST 재동기화"
domain: realtime
type: policy
version: 1
status: draft
minimum_role: USER
updated_at: 2026-09-27
---

# WebSocket 알림과 REST 재동기화

> 코드 기준: 30105a4. Phase 1 사용자 검토 대기 문서이며 아직 검색·임베딩 대상이 아니다.

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

- [websocket/publisher/WebSocketDeliveryListener.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/publisher/WebSocketDeliveryListener.java)
- [websocket/config/WebSocketAuthInterceptor.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/config/WebSocketAuthInterceptor.java)
- [websocket/event/WebSocketDestinations.java](../../backend/src/main/java/com/pricetrack/exchange/websocket/event/WebSocketDestinations.java)
- [웹 복구 처리](../../tools/websocket-test-client/src/recovery.js)
- [웹 연결 처리](../../tools/websocket-test-client/src/websocket.js)
- [현재 구현 Brief](../../claude-docs/career-project-brief.md)

