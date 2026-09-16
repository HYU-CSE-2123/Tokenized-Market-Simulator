package com.pricetrack.exchange.market.provider.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** 기존 랜덤 tick의 가격 범위와 공개 이벤트 계약이 공급자 분리 뒤에도 유지되는지 검증한다. */
class SimulatedPriceProviderTest {
    @Test
    void tickPublishesUpdatedPriceAndChangeRate() {
        MarketWebSocketPublisher marketEvents = mock(MarketWebSocketPublisher.class);
        SimulatedCandleProvider candles = mock(SimulatedCandleProvider.class);
        SimulatedPriceProvider provider = new SimulatedPriceProvider(marketEvents, candles);
        BigDecimal previous = provider.current().price();

        provider.tick();

        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> changeRate = ArgumentCaptor.forClass(BigDecimal.class);
        verify(marketEvents).publishPrice(price.capture(), changeRate.capture());
        verify(candles).record(org.mockito.ArgumentMatchers.eq(provider.current().price()),
                org.mockito.ArgumentMatchers.eq(provider.current().observedAt()));
        assertThat(price.getValue()).isEqualByComparingTo(provider.current().price());
        assertThat(price.getValue()).isBetween(
                previous.multiply(new BigDecimal("0.997")),
                previous.multiply(new BigDecimal("1.003")));
        assertThat(changeRate.getValue()).isEqualByComparingTo(
                price.getValue().subtract(previous)
                        .divide(previous, 8, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)));
        assertThat(provider.current().previousClose()).isEqualByComparingTo(previous);
        assertThat(provider.current().change()).isEqualByComparingTo(price.getValue().subtract(previous));
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.SIMULATED);
        assertThat(provider.current().provider()).isEqualTo("SIMULATED");
    }
}
