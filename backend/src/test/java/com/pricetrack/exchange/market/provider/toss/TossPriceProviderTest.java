package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

class TossPriceProviderTest {
    @Test
    void exposesInitialRestPriceAsLiveSnapshot() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        Instant observedAt = Instant.parse("2026-09-14T00:30:00Z");
        when(client.currentPrice("005930")).thenReturn(
                new TossMarketDataClient.TossPrice("005930", new BigDecimal("72000"), observedAt));
        TossPriceProvider provider = new TossPriceProvider(client, properties());

        provider.loadInitialPrice();

        assertThat(provider.current().price()).isEqualByComparingTo("72000");
        assertThat(provider.current().provider()).isEqualTo("TOSS");
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.LIVE);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        assertThat(provider.current().observedAt()).isEqualTo(observedAt);
    }

    @Test
    void refusesCurrentPriceBeforeInitialLoad() {
        TossPriceProvider provider = new TossPriceProvider(mock(TossMarketDataClient.class), properties());

        assertThatThrownBy(provider::current)
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("준비되지 않았습니다");
    }

    private TossPriceProperties properties() {
        return new TossPriceProperties("https://openapi.test", "client", "secret", "005930",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
