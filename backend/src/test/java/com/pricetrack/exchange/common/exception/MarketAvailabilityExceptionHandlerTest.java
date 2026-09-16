package com.pricetrack.exchange.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.pricetrack.exchange.market.MarketClosedException;
import com.pricetrack.exchange.market.StalePriceException;
import com.pricetrack.exchange.market.InvalidCandleQueryException;

import jakarta.servlet.http.HttpServletRequest;

class MarketAvailabilityExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = request();

    @Test
    void mapsClosedMarketToConflict() {
        var response = handler.marketClosed(new MarketClosedException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("MARKET_CLOSED");
    }

    @Test
    void mapsStalePriceToServiceUnavailable() {
        var response = handler.stalePrice(new StalePriceException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("PRICE_STALE");
    }

    @Test
    void mapsInvalidCandleQueryToBadRequest() {
        var response = handler.invalidCandleQuery(new InvalidCandleQueryException("invalid"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("INVALID_CANDLE_QUERY");
    }

    private HttpServletRequest request() {
        HttpServletRequest value = mock(HttpServletRequest.class);
        when(value.getRequestURI()).thenReturn("/api/orders/buy");
        return value;
    }
}
