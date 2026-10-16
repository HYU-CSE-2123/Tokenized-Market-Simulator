package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

class MarketControllerTest {
    @Test void exposesUnknownAndReadinessSeparatelyWithoutGeneration() throws Exception {
        var prices=mock(MarketPriceService.class);
        var snapshot=new MarketPriceSnapshot("mSEC",new BigDecimal("75000"),new BigDecimal("74000"),
            new BigDecimal("1000"),BigDecimal.ONE,MarketStatus.UNKNOWN,PriceStatus.LIVE,"KIWOOM",Instant.now());
        when(prices.currentForTrading()).thenReturn(new TradingPriceContext(snapshot,true,"REGULAR_TICK",7L));
        var controller=new MarketController(prices,mock(PriceTickRepository.class),mock(MarketCandleService.class));
        var response=controller.market("mSEC");
        assertThat(response.marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        assertThat(response.mockQuoteAllowed()).isTrue();
        assertThat(response.mockQuoteReason()).isEqualTo("REGULAR_TICK");
        var json=new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(response);
        assertThat(json).doesNotContain("generation");
    }
    @Test
    void exposesTheCompleteProviderSnapshot() throws Exception {
        MarketPriceService prices = mock(MarketPriceService.class);
        when(prices.currentForTrading()).thenAnswer(i -> TradingPriceContext.standard(prices.current()));
        Instant observedAt = Instant.parse("2026-09-15T00:30:00Z");
        when(prices.current()).thenReturn(new MarketPriceSnapshot(
                "mSEC", new BigDecimal("72000"), new BigDecimal("70000"),
                new BigDecimal("2000"), new BigDecimal("2.85714200"),
                MarketStatus.OPEN, PriceStatus.LIVE, "TOSS", observedAt));
        MarketController controller = new MarketController(prices, mock(PriceTickRepository.class),
                mock(MarketCandleService.class));

        MarketController.MarketResponse response = controller.market("mSEC");

        assertThat(response.price()).isEqualByComparingTo("72000");
        assertThat(response.previousClose()).isEqualByComparingTo("70000");
        assertThat(response.change()).isEqualByComparingTo("2000");
        assertThat(response.marketStatus()).isEqualTo(MarketStatus.OPEN);
        assertThat(response.priceStatus()).isEqualTo(PriceStatus.LIVE);
        assertThat(response.provider()).isEqualTo("TOSS");
        assertThat(response.observedAt()).isEqualTo(observedAt);
        assertThat(response.updatedAt()).isEqualTo(observedAt);

        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(response);
        assertThat(json).contains("\"observedAt\":\"2026-09-15T00:30:00Z\"")
                .contains("\"updatedAt\":\"2026-09-15T00:30:00Z\"");
    }
}
