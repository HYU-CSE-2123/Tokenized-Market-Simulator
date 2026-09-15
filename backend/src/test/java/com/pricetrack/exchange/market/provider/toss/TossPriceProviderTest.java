package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

class TossPriceProviderTest {
    @Test
    void exposesInitialRestPriceAsLiveSnapshot() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        Instant observedAt = Instant.parse("2026-09-14T00:30:00Z");
        when(client.currentPrice("005930")).thenReturn(
                new TossMarketDataClient.TossPrice("005930", new BigDecimal("72000"), observedAt));
        TossRealtimeClient realtime = mock(TossRealtimeClient.class);
        TossPriceProvider provider = new TossPriceProvider(client, realtime, properties(),
                mock(MarketWebSocketPublisher.class));

        provider.loadInitialPrice();

        assertThat(provider.current().price()).isEqualByComparingTo("72000");
        assertThat(provider.current().provider()).isEqualTo("TOSS");
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.LIVE);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        assertThat(provider.current().observedAt()).isEqualTo(observedAt);
        verify(realtime).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesCurrentPriceBeforeInitialLoad() {
        TossPriceProvider provider = provider(mock(TossMarketDataClient.class),
                mock(MarketWebSocketPublisher.class));

        assertThatThrownBy(provider::current)
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("준비되지 않았습니다");
    }

    @Test
    void appliesNewerTradeAndPublishesChangedPrice() {
        TossMarketDataClient client = initialClient();
        MarketWebSocketPublisher events = mock(MarketWebSocketPublisher.class);
        TossPriceProvider provider = provider(client, events);
        provider.loadInitialPrice();

        provider.acceptTrade(new TossRealtimeProtocol.TossTrade(
                "005930", new BigDecimal("73000"), new BigDecimal("10"),
                Instant.parse("2026-09-14T00:30:01Z")));

        assertThat(provider.current().price()).isEqualByComparingTo("73000");
        assertThat(provider.current().previousClose()).isEqualByComparingTo("72000");
        assertThat(provider.current().change()).isEqualByComparingTo("1000");
        verify(events).publishPrice(new BigDecimal("73000"), provider.current().changeRate());
    }

    @Test
    void ignoresSameOrOlderTrade() {
        TossMarketDataClient client = initialClient();
        MarketWebSocketPublisher events = mock(MarketWebSocketPublisher.class);
        TossPriceProvider provider = provider(client, events);
        provider.loadInitialPrice();

        provider.acceptTrade(new TossRealtimeProtocol.TossTrade(
                "005930", new BigDecimal("71000"), BigDecimal.ONE,
                Instant.parse("2026-09-14T00:30:00Z")));

        assertThat(provider.current().price()).isEqualByComparingTo("72000");
        verify(events, never()).publishPrice(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private TossPriceProvider provider(TossMarketDataClient client, MarketWebSocketPublisher events) {
        return new TossPriceProvider(client, mock(TossRealtimeClient.class), properties(), events);
    }

    private TossMarketDataClient initialClient() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        when(client.currentPrice("005930")).thenReturn(new TossMarketDataClient.TossPrice(
                "005930", new BigDecimal("72000"), Instant.parse("2026-09-14T00:30:00Z")));
        return client;
    }

    private TossPriceProperties properties() {
        return new TossPriceProperties("https://openapi.test", "client", "secret", "005930",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
