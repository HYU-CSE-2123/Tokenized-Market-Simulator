package com.pricetrack.exchange.websocket.publisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pricetrack.exchange.websocket.event.WebSocketEvent;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/** WebSocket 전송이 commit 이후 실행되고 rollback에서는 폐기되는 transaction 경계를 검증한다. */
@SpringBootTest
class WebSocketEventPublisherTest {
    @Autowired WebSocketEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean SimpMessagingTemplate messagingTemplate;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        reset(messagingTemplate);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void publishesPrivateEventOnlyAfterCommit() {
        transactions.executeWithoutResult(status -> {
            publisher.publishToUser(15L, "/queue/orders", WebSocketEventType.ORDER_FILLED, "filled");
            verifyNoInteractions(messagingTemplate);
        });

        verify(messagingTemplate).convertAndSendToUser(
                eq("15"), eq("/queue/orders"), any(WebSocketEvent.class));
    }

    @Test
    void discardsEventWhenTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            publisher.publishToUser(15L, "/queue/orders", WebSocketEventType.ORDER_FAILED, "failed");
            status.setRollbackOnly();
        });

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void publishesPublicEventImmediatelyOutsideTransaction() {
        publisher.publishPublic("/topic/markets/mSEC/price", WebSocketEventType.PRICE_UPDATED, "price");

        verify(messagingTemplate).convertAndSend(
                eq("/topic/markets/mSEC/price"), any(WebSocketEvent.class));
    }
}
