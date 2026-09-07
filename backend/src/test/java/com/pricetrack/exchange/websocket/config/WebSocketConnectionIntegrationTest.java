package com.pricetrack.exchange.websocket.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;

/** 실제 서버 endpoint에서 공개 구독, JWT 개인 queue와 SockJS 연결을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketConnectionIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired SimpMessagingTemplate messagingTemplate;
    @Autowired MarketWebSocketPublisher marketEvents;
    @Autowired UserWebSocketPublisher userEvents;
    @Autowired WalletService walletService;
    @Autowired ObjectMapper objectMapper;

    private StompSession session;
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        clients.forEach(WebSocketStompClient::stop);
    }

    @Test
    void anonymousClientReceivesVersionedPriceEventOverNativeWebSocket() throws Exception {
        session = connect(nativeClient(), "ws://localhost:" + port + "/ws", null);
        CompletableFuture<String> received = subscribe(session, WebSocketDestinations.PRICE_TOPIC);

        String payload = sendUntilReceived(received, () ->
                marketEvents.publishPrice(new BigDecimal("75100"), new BigDecimal("0.13333333")));
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("version").asInt()).isEqualTo(1);
        assertThat(event.path("type").asText()).isEqualTo("PRICE_UPDATED");
        assertThat(event.path("eventId").asText()).isNotBlank();
        assertThat(event.path("occurredAt").asText()).isNotBlank();
        assertThat(event.path("data").path("symbol").asText()).isEqualTo("mSEC");
        assertThat(event.path("data").path("price").decimalValue()).isEqualByComparingTo("75100");
    }

    @Test
    void anonymousClientReceivesPublicTradeWithoutPrivateFields() throws Exception {
        session = connect(nativeClient(), "ws://localhost:" + port + "/ws", null);
        CompletableFuture<String> received = subscribe(session, WebSocketDestinations.TRADES_TOPIC);
        Trade trade = publicTrade();

        String payload = sendUntilReceived(received, () -> marketEvents.publishTrade(trade));
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("type").asText()).isEqualTo("TRADE_EXECUTED");
        assertThat(event.path("data").path("tradeId").asLong()).isEqualTo(77L);
        assertThat(event.path("data").path("side").asText()).isEqualTo("SELL");
        assertThat(event.path("data").has("userId")).isFalse();
        assertThat(event.path("data").has("orderId")).isFalse();
        assertThat(event.path("data").has("txHash")).isFalse();
    }

    @Test
    void authenticatedClientReceivesItsPrivateQueue() throws Exception {
        User user = createUser("single");
        String token = jwtTokenProvider.createToken(user.getId(), user.getLoginId());
        session = connect(nativeClient(), "ws://localhost:" + port + "/ws", token);
        CompletableFuture<String> received = subscribe(session, "/user/queue/orders");

        Long userId = user.getId();
        String payload = sendUntilReceived(received, () ->
                messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/orders", "private-order"));
        assertThat(payload).isEqualTo("private-order");
    }

    @Test
    void privateOrderIsDeliveredOnlyToItsOwner() throws Exception {
        User owner = createUser("owner");
        User other = createUser("other");
        StompSession ownerSession = authenticatedSession(owner);
        StompSession otherSession = authenticatedSession(other);
        CompletableFuture<String> ownerReady = subscribe(
                ownerSession, WebSocketDestinations.PORTFOLIO_SUBSCRIPTION);
        CompletableFuture<String> otherReady = subscribe(
                otherSession, WebSocketDestinations.PORTFOLIO_SUBSCRIPTION);
        CompletableFuture<String> ownerOrders = subscribe(
                ownerSession, WebSocketDestinations.ORDERS_SUBSCRIPTION);
        CompletableFuture<String> otherOrders = subscribe(
                otherSession, WebSocketDestinations.ORDERS_SUBSCRIPTION);

        sendUntilReceived(ownerReady, () -> messagingTemplate.convertAndSendToUser(
                owner.getId().toString(), WebSocketDestinations.PORTFOLIO_QUEUE, "ready"));
        sendUntilReceived(otherReady, () -> messagingTemplate.convertAndSendToUser(
                other.getId().toString(), WebSocketDestinations.PORTFOLIO_QUEUE, "ready"));

        String payload = sendUntilReceived(ownerOrders, () -> userEvents.publishOrder(order(owner.getId())));
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("type").asText()).isEqualTo("ORDER_FILLED");
        assertThat(event.path("data").path("orderId").asLong()).isEqualTo(501L);
        assertThatThrownBy(() -> otherOrders.get(400, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void authenticatedClientReceivesPortfolioSnapshot() throws Exception {
        User user = createUser("portfolio");
        walletService.initializeBalances(user.getId());
        StompSession userSession = authenticatedSession(user);
        CompletableFuture<String> received = subscribe(
                userSession, WebSocketDestinations.PORTFOLIO_SUBSCRIPTION);

        String payload = sendUntilReceived(received, () -> userEvents.publishPortfolio(user.getId()));
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("type").asText()).isEqualTo("PORTFOLIO_UPDATED");
        assertThat(event.path("data").path("krwBalance").decimalValue()).isZero();
        assertThat(event.path("data").path("tokenBalance").decimalValue()).isZero();
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
        StompSession connected = client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        sessions.add(connected);
        return connected;
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

    private Trade publicTrade() {
        Trade trade = new Trade();
        trade.setId(77L);
        trade.setUserId(88L);
        trade.setOrderId(99L);
        trade.setTxHash("0xprivate");
        trade.setSymbol("mSEC");
        trade.setSide(OrderSide.SELL);
        trade.setPrice(new BigDecimal("75200"));
        trade.setBaseAmount(new BigDecimal("2"));
        trade.setQuoteAmount(new BigDecimal("150249.6"));
        trade.setFee(new BigDecimal("150.4"));
        trade.setCreatedAt(Instant.parse("2026-09-06T06:00:00Z"));
        return trade;
    }

    private User createUser(String label) {
        User user = new User();
        user.setLoginId("ws-" + label + "-" + System.nanoTime());
        user.setNickname("WebSocket " + label);
        return userRepository.saveAndFlush(user);
    }

    private StompSession authenticatedSession(User user) throws Exception {
        String token = jwtTokenProvider.createToken(user.getId(), user.getLoginId());
        return connect(nativeClient(), "ws://localhost:" + port + "/ws", token);
    }

    private Order order(Long userId) {
        Order order = new Order();
        order.setId(501L);
        order.setUserId(userId);
        order.setSymbol("mSEC");
        order.setSide(OrderSide.BUY);
        order.setInputAmount(new BigDecimal("100000"));
        order.setExpectedOutputAmount(new BigDecimal("1.332"));
        order.setStatus(OrderStatus.FILLED);
        order.setTxHash("0x1234");
        return order;
    }
}
