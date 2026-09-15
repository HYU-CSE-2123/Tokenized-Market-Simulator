package com.pricetrack.exchange.market.provider.toss;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 토스증권 시세 공급자 연결 설정이다. 비밀값은 환경 변수에서만 주입한다. */
@ConfigurationProperties("app.price.toss")
public record TossPriceProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String symbol,
        Duration connectTimeout,
        Duration readTimeout,
        String websocketUrl,
        Duration pingInterval,
        Duration reconnectInitialDelay,
        Duration reconnectMaxDelay,
        Duration degradedAfter,
        Duration staleAfter) {

    @ConstructorBinding
    public TossPriceProperties {}

    TossPriceProperties(String baseUrl, String clientId, String clientSecret, String symbol,
            Duration connectTimeout, Duration readTimeout) {
        this(baseUrl, clientId, clientSecret, symbol, connectTimeout, readTimeout,
                "wss://openapi-ws.tossinvest.com/ws/v1", Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(15),
                Duration.ofSeconds(60));
    }

    TossPriceProperties(String baseUrl, String clientId, String clientSecret, String symbol,
            Duration connectTimeout, Duration readTimeout, String websocketUrl,
            Duration pingInterval, Duration reconnectInitialDelay, Duration reconnectMaxDelay) {
        this(baseUrl, clientId, clientSecret, symbol, connectTimeout, readTimeout, websocketUrl,
                pingInterval, reconnectInitialDelay, reconnectMaxDelay, Duration.ofSeconds(15),
                Duration.ofSeconds(60));
    }

    public void validate() {
        requireText(baseUrl, "TOSS_API_BASE_URL");
        requireText(clientId, "TOSS_CLIENT_ID");
        requireText(clientSecret, "TOSS_CLIENT_SECRET");
        requireText(symbol, "TOSS_SYMBOL");
        if (!"005930".equals(symbol)) {
            throw new IllegalStateException("TOSS_SYMBOL은 삼성전자 종목코드 005930이어야 합니다.");
        }
        requirePositive(connectTimeout, "TOSS_CONNECT_TIMEOUT");
        requirePositive(readTimeout, "TOSS_READ_TIMEOUT");
        requireText(websocketUrl, "TOSS_WEBSOCKET_URL");
        if (!websocketUrl.startsWith("wss://") && !websocketUrl.startsWith("ws://")) {
            throw new IllegalStateException("TOSS_WEBSOCKET_URL은 WebSocket URL이어야 합니다.");
        }
        requirePositive(pingInterval, "TOSS_WEBSOCKET_PING_INTERVAL");
        if (pingInterval.compareTo(Duration.ofSeconds(180)) >= 0) {
            throw new IllegalStateException("TOSS_WEBSOCKET_PING_INTERVAL은 180초보다 짧아야 합니다.");
        }
        requirePositive(reconnectInitialDelay, "TOSS_WEBSOCKET_RECONNECT_INITIAL_DELAY");
        requirePositive(reconnectMaxDelay, "TOSS_WEBSOCKET_RECONNECT_MAX_DELAY");
        if (reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalStateException("TOSS_WEBSOCKET_RECONNECT_MAX_DELAY는 초기 지연보다 짧을 수 없습니다.");
        }
        requirePositive(degradedAfter, "TOSS_PRICE_DEGRADED_AFTER");
        requirePositive(staleAfter, "TOSS_PRICE_STALE_AFTER");
        if (staleAfter.compareTo(degradedAfter) <= 0) {
            throw new IllegalStateException("TOSS_PRICE_STALE_AFTER must be greater than TOSS_PRICE_DEGRADED_AFTER.");
        }
    }

    private static void requireText(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " 환경 변수가 필요합니다.");
        }
    }

    private static void requirePositive(Duration value, String environmentName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(environmentName + "은 0보다 커야 합니다.");
        }
    }
}
