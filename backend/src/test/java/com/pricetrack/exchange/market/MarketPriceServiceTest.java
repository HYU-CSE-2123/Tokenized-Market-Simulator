package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;

class MarketPriceServiceTest {
    private final MarketPriceProvider provider = mock(MarketPriceProvider.class);
    private final MarketPriceService service = new MarketPriceService(provider);

    @Test
    void permitsOpenLiveAndDegradedPrices() {
        MarketPriceSnapshot live = snapshot(MarketStatus.OPEN, PriceStatus.LIVE);
        when(provider.current()).thenReturn(live, snapshot(MarketStatus.OPEN, PriceStatus.DEGRADED));

        assertThat(service.requireTradableSnapshot()).isSameAs(live);
        assertThat(service.requireTradableSnapshot().priceStatus()).isEqualTo(PriceStatus.DEGRADED);
    }

    @Test
    void permitsSimulatedModeAroundTheClock() {
        MarketPriceSnapshot simulated = snapshot(MarketStatus.UNKNOWN, PriceStatus.SIMULATED);
        when(provider.current()).thenReturn(simulated);

        assertThat(service.requireTradableSnapshot()).isSameAs(simulated);
    }

    @Test
    void rejectsClosedMarketBeforeSettlement() {
        when(provider.current()).thenReturn(snapshot(MarketStatus.CLOSED, PriceStatus.LIVE));

        assertThatThrownBy(service::requireTradableSnapshot)
                .isInstanceOf(MarketClosedException.class);
    }

    @Test
    void rejectsStalePriceBeforeSettlement() {
        when(provider.current()).thenReturn(snapshot(MarketStatus.OPEN, PriceStatus.STALE));

        assertThatThrownBy(service::requireTradableSnapshot)
                .isInstanceOf(StalePriceException.class);
    }

    private MarketPriceSnapshot snapshot(MarketStatus marketStatus, PriceStatus priceStatus) {
        return new MarketPriceSnapshot("mSEC", new BigDecimal("75000"), new BigDecimal("74000"),
                new BigDecimal("1000"), new BigDecimal("1.35135135"), marketStatus, priceStatus,
                priceStatus == PriceStatus.SIMULATED ? "SIMULATED" : "TOSS", Instant.EPOCH);
    }
}
