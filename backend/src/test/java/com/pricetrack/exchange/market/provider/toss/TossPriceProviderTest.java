package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

class TossPriceProviderTest {
    private static final Instant NOW = Instant.parse("2026-09-14T00:30:05Z");
    @Test
    void exposesInitialRestPriceAsLiveSnapshot() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        Instant observedAt = Instant.parse("2026-09-14T00:30:00Z");
        when(client.currentPrice("005930")).thenReturn(
                new TossMarketDataClient.TossPrice("005930", new BigDecimal("72000"), observedAt));
        TossRealtimeClient realtime = mock(TossRealtimeClient.class);
        when(realtime.connectionState()).thenReturn(TossRealtimeClient.ConnectionState.CONNECTED);
        stubReference(client, true);
        TossPriceProvider provider = new TossPriceProvider(client, realtime, properties(),
                mock(MarketWebSocketPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));

        provider.loadInitialPrice();

        assertThat(provider.current().price()).isEqualByComparingTo("72000");
        assertThat(provider.current().previousClose()).isEqualByComparingTo("70000");
        assertThat(provider.current().change()).isEqualByComparingTo("2000");
        assertThat(provider.current().provider()).isEqualTo("TOSS");
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.LIVE);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.OPEN);
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
        assertThat(provider.current().previousClose()).isEqualByComparingTo("70000");
        assertThat(provider.current().change()).isEqualByComparingTo("3000");
        verify(events).publishPrice(new BigDecimal("73000"), provider.current().changeRate());
    }

    @Test
    void reportsDegradedAndStaleDuringOpenMarket() {
        TossMarketDataClient client = initialClient();
        TossRealtimeClient realtime = mock(TossRealtimeClient.class);
        when(realtime.connectionState()).thenReturn(TossRealtimeClient.ConnectionState.DISCONNECTED);
        TossPriceProvider degraded = provider(client, realtime, mock(MarketWebSocketPublisher.class));
        degraded.loadInitialPrice();
        assertThat(degraded.current().priceStatus()).isEqualTo(PriceStatus.DEGRADED);

        Clock staleClock = Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC);
        TossPriceProvider stale = new TossPriceProvider(client, realtime, properties(),
                mock(MarketWebSocketPublisher.class), staleClock);
        stale.loadInitialPrice();
        assertThat(stale.current().priceStatus()).isEqualTo(PriceStatus.STALE);
    }

    @Test
    void closedMarketKeepsOfficialLastPriceUsable() {
        TossMarketDataClient client = initialClient();
        stubReference(client, false);
        TossRealtimeClient realtime = mock(TossRealtimeClient.class);
        when(realtime.connectionState()).thenReturn(TossRealtimeClient.ConnectionState.DISCONNECTED);
        TossPriceProvider provider = new TossPriceProvider(client, realtime, properties(),
                mock(MarketWebSocketPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
        provider.loadInitialPrice();

        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.CLOSED);
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.LIVE);
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
        TossRealtimeClient realtime = mock(TossRealtimeClient.class);
        when(realtime.connectionState()).thenReturn(TossRealtimeClient.ConnectionState.CONNECTED);
        return provider(client, realtime, events);
    }

    private TossPriceProvider provider(TossMarketDataClient client, TossRealtimeClient realtime,
            MarketWebSocketPublisher events) {
        stubReference(client, true);
        return new TossPriceProvider(client, realtime, properties(), events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TossMarketDataClient initialClient() {
        TossMarketDataClient client = mock(TossMarketDataClient.class);
        when(client.currentPrice("005930")).thenReturn(new TossMarketDataClient.TossPrice(
                "005930", new BigDecimal("72000"), Instant.parse("2026-09-14T00:30:00Z")));
        return client;
    }

    private void stubReference(TossMarketDataClient client, boolean open) {
        List<TossMarketDataClient.MarketSession> sessions = open
                ? List.of(new TossMarketDataClient.MarketSession(NOW.minusSeconds(60), NOW.plusSeconds(3600)))
                : List.of();
        when(client.marketReference(org.mockito.ArgumentMatchers.eq("005930"),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                        new TossMarketDataClient.TossMarketReference(new BigDecimal("70000"), sessions));
    }

    private TossPriceProperties properties() {
        return new TossPriceProperties("https://openapi.test", "client", "secret", "005930",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
