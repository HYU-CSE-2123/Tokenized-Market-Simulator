package com.pricetrack.exchange.websocket.event;

import java.time.Instant;
import java.util.UUID;

/** 모든 WebSocket 메시지가 공유하는 버전 있는 envelope다. */
public record WebSocketEvent<T>(UUID eventId, int version, WebSocketEventType type,
        Instant occurredAt, T data) {
    public static final int CURRENT_VERSION = 1;

    /** 호출 시점의 고유 ID와 발생 시각으로 현재 버전 이벤트를 생성한다. */
    public static <T> WebSocketEvent<T> create(WebSocketEventType type, T data) {
        if (type == null) throw new IllegalArgumentException("WebSocket event type이 필요합니다.");
        if (data == null) throw new IllegalArgumentException("WebSocket event data가 필요합니다.");
        return new WebSocketEvent<>(UUID.randomUUID(), CURRENT_VERSION, type, Instant.now(), data);
    }
}
