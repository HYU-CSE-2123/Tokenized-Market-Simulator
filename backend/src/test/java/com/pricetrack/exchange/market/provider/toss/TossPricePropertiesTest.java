package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TossPricePropertiesTest {
    @Test
    void rejectsMissingCredentials() {
        TossPriceProperties properties = new TossPriceProperties(
                "https://openapi.tossinvest.com", "", "", "005930",
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_CLIENT_ID");
    }

    @Test
    void rejectsAnotherValidKoreanStockSymbol() {
        TossPriceProperties properties = new TossPriceProperties(
                "https://openapi.tossinvest.com", "client", "secret", "000660",
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("005930");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        TossPriceProperties properties = new TossPriceProperties(
                "https://openapi.tossinvest.com", "client", "secret", "005930",
                Duration.ZERO, Duration.ofSeconds(5));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_CONNECT_TIMEOUT");
    }
}
