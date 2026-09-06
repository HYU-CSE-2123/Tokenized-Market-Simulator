package com.pricetrack.exchange.websocket.publisher;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.pricetrack.exchange.websocket.event.WebSocketDelivery;

/** Spring 내부 전송 요청을 DB commit 이후 실제 STOMP broker 메시지로 변환한다. */
@Component
public class WebSocketDeliveryListener {
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketDeliveryListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 트랜잭션 안에서 발생하면 commit 후 전송하고 rollback이면 호출되지 않는다.
     * 가격처럼 트랜잭션 밖에서 발생한 이벤트는 {@code fallbackExecution}으로 즉시 전송한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliver(WebSocketDelivery delivery) {
        if (delivery.privateDelivery()) {
            messagingTemplate.convertAndSendToUser(
                    delivery.userName(), delivery.destination(), delivery.event());
        } else {
            messagingTemplate.convertAndSend(delivery.destination(), delivery.event());
        }
    }
}
