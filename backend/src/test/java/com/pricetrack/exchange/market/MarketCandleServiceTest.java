package com.pricetrack.exchange.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.common.exception.UnsupportedSymbolException;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;

class MarketCandleServiceTest {
    private final MarketCandleService service = new MarketCandleService(mock(MarketCandleProvider.class));

    @Test
    void rejectsUnsupportedSymbolIntervalAndCount() {
        assertThatThrownBy(() -> service.candles("OTHER", "1m", 10, null))
                .isInstanceOf(UnsupportedSymbolException.class);
        assertThatThrownBy(() -> service.candles("mSEC", "5m", 10, null))
                .isInstanceOf(InvalidCandleQueryException.class);
        assertThatThrownBy(() -> service.candles("mSEC", "1m", 201, null))
                .isInstanceOf(InvalidCandleQueryException.class);
    }
}
