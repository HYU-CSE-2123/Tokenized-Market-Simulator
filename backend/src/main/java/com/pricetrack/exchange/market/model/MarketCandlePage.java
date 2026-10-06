package com.pricetrack.exchange.market.model;

import java.time.Instant;
import java.util.List;

public record MarketCandlePage(String symbol, CandleInterval interval, String provider,
        List<MarketCandle> candles, Instant nextBefore, Instant asOf) {
    public MarketCandlePage(String symbol, CandleInterval interval, String provider,
            List<MarketCandle> candles, Instant nextBefore) {
        this(symbol, interval, provider, candles, nextBefore, null);
    }
    public MarketCandlePage { candles = List.copyOf(candles); }
}
