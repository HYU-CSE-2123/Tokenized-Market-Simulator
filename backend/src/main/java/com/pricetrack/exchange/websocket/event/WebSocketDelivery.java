package com.pricetrack.exchange.websocket.event;

/**
 * DB 트랜잭션과 WebSocket 전송 시점을 분리하기 위한 Spring 내부 이벤트다.
 * {@code userName}이 없으면 공개 topic, 있으면 해당 Principal의 개인 queue로 전달한다.
 */
public record WebSocketDelivery(String userName, String destination, WebSocketEvent<?> event) {
    public boolean privateDelivery() {
        return userName != null;
    }
}
