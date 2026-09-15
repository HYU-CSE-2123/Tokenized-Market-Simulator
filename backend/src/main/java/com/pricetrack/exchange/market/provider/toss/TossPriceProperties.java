package com.pricetrack.exchange.market.provider.toss;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토스증권 시세 공급자 연결 설정이다. 비밀값은 환경 변수에서만 주입한다. */
@ConfigurationProperties("app.price.toss")
public record TossPriceProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String symbol,
        Duration connectTimeout,
        Duration readTimeout) {

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
