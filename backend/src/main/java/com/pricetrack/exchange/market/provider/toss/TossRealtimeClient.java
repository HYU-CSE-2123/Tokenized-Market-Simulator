package com.pricetrack.exchange.market.provider.toss;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the single Toss WebSocket connection, keepalive and reconnect lifecycle. */
public class TossRealtimeClient {
    private static final Logger log = LoggerFactory.getLogger(TossRealtimeClient.class);

    private final HttpClient httpClient;
    private final TossAuthClient authClient;
    private final TossRealtimeProtocol protocol;
    private final TossPriceProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Consumer<TossRealtimeProtocol.TossTrade> tradeConsumer;
    private volatile WebSocket webSocket;
    private volatile CompletableFuture<WebSocket> connecting;
    private volatile ScheduledFuture<?> pingTask;
    private volatile ScheduledFuture<?> reconnectTask;
    private int reconnectAttempts;

    public TossRealtimeClient(HttpClient httpClient, TossAuthClient authClient,
            TossRealtimeProtocol protocol, TossPriceProperties properties) {
        this(httpClient, authClient, protocol, properties,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "toss-realtime");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    TossRealtimeClient(HttpClient httpClient, TossAuthClient authClient,
            TossRealtimeProtocol protocol, TossPriceProperties properties,
            ScheduledExecutorService scheduler) {
        this.httpClient = httpClient;
        this.authClient = authClient;
        this.protocol = protocol;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    public void start(Consumer<TossRealtimeProtocol.TossTrade> tradeConsumer) {
        this.tradeConsumer = tradeConsumer;
        if (running.compareAndSet(false, true)) scheduler.execute(this::connect);
    }

    public void stop() {
        CompletableFuture<WebSocket> pending;
        WebSocket current;
        synchronized (this) {
            running.set(false);
            cancel(pingTask);
            cancel(reconnectTask);
            pending = connecting;
            connecting = null;
            current = webSocket;
            webSocket = null;
        }
        if (pending != null) pending.cancel(true);
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running.get()) return;
        String token;
        try {
            token = authClient.accessToken();
        } catch (RuntimeException exception) {
            connectionFailed(null, exception);
            return;
        }
        TossListener listener = new TossListener(token);
        CompletableFuture<WebSocket> attempt = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(properties.connectTimeout())
                .buildAsync(URI.create(properties.websocketUrl()), listener);
        synchronized (this) {
            if (!running.get()) {
                attempt.cancel(true);
            } else {
                connecting = attempt;
            }
        }
        attempt.whenComplete((socket, failure) -> {
                    boolean activate = false;
                    synchronized (this) {
                        if (connecting == attempt) connecting = null;
                        if (failure == null && running.get()) {
                            webSocket = socket;
                            activate = true;
                        }
                    }
                    if (failure != null) {
                        if (isUnauthorized(failure)) authClient.invalidate(token);
                        connectionFailed(null, failure);
                    } else if (!activate) {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "client stopped");
                    } else {
                        String requestId = UUID.randomUUID().toString();
                        socket.sendText(protocol.subscription(requestId), true)
                                .whenComplete((ignored, sendFailure) -> {
                                    if (sendFailure != null) connectionFailed(socket, sendFailure);
                                });
                    }
                });
    }

    private synchronized boolean subscribed(WebSocket socket) {
        if (socket != webSocket || !running.get()) return false;
        reconnectAttempts = 0;
        cancel(pingTask);
        long interval = properties.pingInterval().toMillis();
        pingTask = scheduler.scheduleAtFixedRate(() -> sendPing(socket), interval, interval,
                TimeUnit.MILLISECONDS);
        log.info("Toss realtime trade subscription established for {}", properties.symbol());
        return true;
    }

    private void sendPing(WebSocket socket) {
        if (!running.get() || socket != webSocket) return;
        socket.sendText("PING", true).whenComplete((ignored, failure) -> {
            if (failure != null) connectionFailed(socket, failure);
        });
    }

    private synchronized void connectionFailed(WebSocket source, Throwable failure) {
        if (!running.get()) return;
        if (source != null && source != webSocket) return;
        WebSocket previous = webSocket;
        webSocket = null;
        cancel(pingTask);
        if (previous != null) previous.abort();
        if (reconnectTask != null && !reconnectTask.isDone()) return;
        long base = exponentialDelay(properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(), reconnectAttempts++);
        long jitter = base <= 1 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(base / 2 + 1);
        long delay = Math.min(properties.reconnectMaxDelay().toMillis(), base + jitter);
        log.warn("Toss realtime connection lost; retrying in {} ms ({})", delay,
                failure.getClass().getSimpleName());
        reconnectTask = scheduler.schedule(() -> {
            reconnectTask = null;
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    static long exponentialDelay(Duration initial, Duration maximum, int attempts) {
        long delay = initial.toMillis();
        for (int i = 0; i < attempts && delay < maximum.toMillis(); i++) {
            delay = Math.min(maximum.toMillis(), delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2);
        }
        return Math.min(delay, maximum.toMillis());
    }

    private static boolean isUnauthorized(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WebSocketHandshakeException handshake
                    && handshake.getResponse().statusCode() == 401) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }

    private final class TossListener implements WebSocket.Listener {
        private final String token;
        private final StringBuilder text = new StringBuilder();
        private volatile boolean subscriptionConfirmed;

        private TossListener(String token) {
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String payload = text.toString();
                text.setLength(0);
                try {
                    TossRealtimeProtocol.Frame frame = protocol.parse(payload);
                    if (frame instanceof TossRealtimeProtocol.SubscribedFrame) {
                        subscriptionConfirmed = subscribed(socket);
                    }
                    if (frame instanceof TossRealtimeProtocol.TradeFrame tradeFrame) {
                        if (subscriptionConfirmed && running.get() && socket == webSocket) {
                            tradeConsumer.accept(tradeFrame.trade());
                        }
                    }
                } catch (RuntimeException exception) {
                    connectionFailed(socket, exception);
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return socket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            connectionFailed(socket,
                    new TossApiException("토스증권 실시간 연결이 종료됐습니다: " + statusCode));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            if (isUnauthorized(error)) authClient.invalidate(token);
            connectionFailed(socket, error);
        }
    }
}
