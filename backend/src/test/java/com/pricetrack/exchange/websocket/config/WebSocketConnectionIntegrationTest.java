package com.pricetrack.exchange.websocket.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;

/** 실제 서버 endpoint에서 공개 구독, JWT 개인 queue와 SockJS 연결을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketConnectionIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired SimpMessagingTemplate messagingTemplate;

    private StompSession session;
    private final List<WebSocketStompClient> clients = new ArrayList<>();

    @AfterEach
    void disconnect() {
        if (session != null && session.isConnected()) session.disconnect();
        clients.forEach(WebSocketStompClient::stop);
    }

    @Test
    void anonymousClientReceivesPublicMarketEventOverNativeWebSocket() throws Exception {
        session = connect(nativeClient(), "ws://localhost:" + port + "/ws", null);
        CompletableFuture<String> received = subscribe(session, "/topic/markets/mSEC/price");

        String payload = sendUntilReceived(received, () ->
                messagingTemplate.convertAndSend("/topic/markets/mSEC/price", "public-price"));
        assertThat(payload).isEqualTo("public-price");
    }

    @Test
    void authenticatedClientReceivesItsPrivateQueue() throws Exception {
        User user = new User();
        user.setLoginId("ws-user");
        user.setNickname("WebSocket User");
        user = userRepository.saveAndFlush(user);
        String token = jwtTokenProvider.createToken(user.getId(), user.getLoginId());
        session = connect(nativeClient(), "ws://localhost:" + port + "/ws", token);
        CompletableFuture<String> received = subscribe(session, "/user/queue/orders");

        Long userId = user.getId();
        String payload = sendUntilReceived(received, () ->
                messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/orders", "private-order"));
        assertThat(payload).isEqualTo("private-order");
    }

    @Test
    void browserCompatibleSockJsEndpointConnects() throws Exception {
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient client = configured(new WebSocketStompClient(sockJsClient));
        session = connect(client, "http://localhost:" + port + "/ws-sockjs", null);
        assertThat(session.isConnected()).isTrue();
    }

    private WebSocketStompClient nativeClient() {
        return configured(new WebSocketStompClient(new StandardWebSocketClient()));
    }

    private WebSocketStompClient configured(WebSocketStompClient client) {
        clients.add(client);
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String url, String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) connectHeaders.add("Authorization", "Bearer " + token);
        return client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<String> subscribe(StompSession stompSession, String destination) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        stompSession.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete(new String((byte[]) payload));
            }
        });
        return received;
    }

    private String sendUntilReceived(CompletableFuture<String> received, Runnable send) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            send.run();
            try {
                return received.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // SUBSCRIBE 처리는 비동기이므로 등록될 때까지 동일 테스트 이벤트를 다시 보낸다.
            }
        }
        return received.get(1, TimeUnit.SECONDS);
    }
}
