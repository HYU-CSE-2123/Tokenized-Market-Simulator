package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** Verifies that every simulated tick updates the price and emits the matching public event. */
class PriceSimulatorTest {
    @Test
    void tickPublishesUpdatedPriceAndChangeRate() {
        MarketWebSocketPublisher marketEvents = mock(MarketWebSocketPublisher.class);
        PriceSimulator simulator = new PriceSimulator(marketEvents);
        BigDecimal previous = simulator.getCurrentPrice();

        simulator.tick();

        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> changeRate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(marketEvents).publishPrice(price.capture(), changeRate.capture());
        assertThat(price.getValue()).isEqualByComparingTo(simulator.getCurrentPrice());
        assertThat(price.getValue()).isBetween(
                previous.multiply(new BigDecimal("0.997")),
                previous.multiply(new BigDecimal("1.003")));
        assertThat(changeRate.getValue()).isEqualByComparingTo(
                price.getValue().subtract(previous)
                        .divide(previous, 8, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)));
    }
}
