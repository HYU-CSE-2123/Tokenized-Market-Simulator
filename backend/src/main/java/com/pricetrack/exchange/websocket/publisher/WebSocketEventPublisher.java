package com.pricetrack.exchange.websocket.publisher;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.pricetrack.exchange.websocket.event.WebSocketDelivery;
import com.pricetrack.exchange.websocket.event.WebSocketEvent;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/**
 * 도메인 서비스가 STOMP 구현을 모르고 공개·개인 이벤트 전송을 요청하는 경계다.
 * 실제 broker 전송은 transaction 상태를 인식하는 listener가 수행한다.
 */
@Component
public class WebSocketEventPublisher {
    private final ApplicationEventPublisher applicationEvents;

    public WebSocketEventPublisher(ApplicationEventPublisher applicationEvents) {
        this.applicationEvents = applicationEvents;
    }

    public <T> void publishPublic(String topic, WebSocketEventType type, T data) {
        if (topic == null || !topic.startsWith("/topic/")) {
            throw new IllegalArgumentException("공개 WebSocket destination은 /topic/으로 시작해야 합니다.");
        }
        applicationEvents.publishEvent(new WebSocketDelivery(null, topic, WebSocketEvent.create(type, data)));
    }

    public <T> void publishToUser(Long userId, String queue, WebSocketEventType type, T data) {
        if (userId == null) throw new IllegalArgumentException("WebSocket 수신 사용자 ID가 필요합니다.");
        if (queue == null || !queue.startsWith("/queue/")) {
            throw new IllegalArgumentException("개인 WebSocket destination은 /queue/로 시작해야 합니다.");
        }
        applicationEvents.publishEvent(
                new WebSocketDelivery(userId.toString(), queue, WebSocketEvent.create(type, data)));
    }
}
