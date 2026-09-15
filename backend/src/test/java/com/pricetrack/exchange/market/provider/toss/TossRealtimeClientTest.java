package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

class TossRealtimeClientTest {
    @Test
    void authenticatesSubscribesKeepsAliveAndReconnects() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket firstSocket = socket();
        WebSocket secondSocket = socket();
        TossAuthClient authClient = mock(TossAuthClient.class);
        TossPriceProperties properties = properties();
        when(authClient.accessToken()).thenReturn("access-token");
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        ArgumentCaptor<WebSocket.Listener> listeners = ArgumentCaptor.forClass(WebSocket.Listener.class);
        when(builder.buildAsync(any(URI.class), listeners.capture()))
                .thenReturn(CompletableFuture.completedFuture(firstSocket),
                        CompletableFuture.completedFuture(secondSocket));
        AtomicInteger received = new AtomicInteger();
        TossRealtimeClient client = new TossRealtimeClient(httpClient, authClient,
                new TossRealtimeProtocol(new ObjectMapper(), "005930"), properties);

        client.start(trade -> received.incrementAndGet());
        verify(builder, timeout(1_000)).header("Authorization", "Bearer access-token");
        verify(firstSocket, timeout(1_000)).sendText(
                org.mockito.ArgumentMatchers.contains("\"trade:kr\""), anyBoolean());
        WebSocket.Listener firstListener = listeners.getValue();
        firstListener.onText(firstSocket, tradeFrame("72000"), true);
        assertThat(received).hasValue(0);
        firstListener.onText(firstSocket, """
                {"type":"subscriptions","subscribed":["trade:kr:005930"],"rejected":[]}
                """, true);
        firstListener.onText(firstSocket, tradeFrame("72000"), true);
        assertThat(received).hasValue(1);
        verify(firstSocket, timeout(1_000).atLeastOnce()).sendText("PING", true);

        firstListener.onClose(firstSocket, 1006, "network lost");
        verify(builder, timeout(1_000).times(2)).buildAsync(any(URI.class), any(WebSocket.Listener.class));
        verify(secondSocket, timeout(1_000)).sendText(
                org.mockito.ArgumentMatchers.contains("\"trade:kr\""), anyBoolean());
        firstListener.onText(firstSocket, tradeFrame("73000"), true);
        assertThat(received).hasValue(1);
        client.stop();
    }

    @Test
    void closesSocketThatFinishesHandshakeAfterStop() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket socket = socket();
        TossAuthClient authClient = mock(TossAuthClient.class);
        when(authClient.accessToken()).thenReturn("access-token");
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        CompletableFuture<WebSocket> pending = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenReturn(pending);
        TossRealtimeClient client = new TossRealtimeClient(httpClient, authClient,
                new TossRealtimeProtocol(new ObjectMapper(), "005930"), properties());

        client.start(trade -> {});
        verify(builder, timeout(1_000)).buildAsync(any(URI.class), any(WebSocket.Listener.class));
        client.stop();
        pending.complete(socket);

        verify(socket, timeout(1_000)).sendClose(WebSocket.NORMAL_CLOSURE, "client stopped");
    }

    @Test
    void closesSocketWhenHandshakeCompletionRacesWithStop() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket socket = socket();
        TossAuthClient authClient = mock(TossAuthClient.class);
        when(authClient.accessToken()).thenReturn("access-token");
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        CompletableFuture<WebSocket> pending = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenReturn(pending);
        TossRealtimeClient client = new TossRealtimeClient(httpClient, authClient,
                new TossRealtimeProtocol(new ObjectMapper(), "005930"), properties());
        client.start(trade -> {});
        verify(builder, timeout(1_000)).buildAsync(any(URI.class), any(WebSocket.Listener.class));
        CountDownLatch start = new CountDownLatch(1);
        Thread stopping = Thread.startVirtualThread(() -> awaitAndRun(start, client::stop));
        Thread completing = Thread.startVirtualThread(() -> awaitAndRun(start, () -> pending.complete(socket)));

        start.countDown();
        stopping.join();
        completing.join();

        verify(socket, timeout(1_000)).sendClose(anyInt(), anyString());
    }

    @Test
    void capsExponentialReconnectDelay() {
        assertThat(TossRealtimeClient.exponentialDelay(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 0)).isEqualTo(1_000);
        assertThat(TossRealtimeClient.exponentialDelay(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 4)).isEqualTo(16_000);
        assertThat(TossRealtimeClient.exponentialDelay(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 20)).isEqualTo(30_000);
    }

    private TossPriceProperties properties() {
        return new TossPriceProperties(
                "https://openapi.test", "client", "secret", "005930",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                "wss://openapi-ws.test/ws/v1", Duration.ofMillis(20),
                Duration.ofMillis(10), Duration.ofMillis(20));
    }

    private WebSocket socket() {
        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        return socket;
    }

    private String tradeFrame(String price) {
        return """
                {"type":"message","topic":"trade:kr:005930","data":{
                  "price":"%s","volume":"1",
                  "timestamp":"2026-09-15T09:30:42+09:00","currency":"KRW"}}
                """.formatted(price);
    }

    private void awaitAndRun(CountDownLatch start, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
