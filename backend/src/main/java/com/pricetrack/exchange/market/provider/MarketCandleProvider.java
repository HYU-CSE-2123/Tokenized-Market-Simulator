package com.pricetrack.exchange.market.provider;

import java.time.Instant;

import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandlePage;

public interface MarketCandleProvider {
    MarketCandlePage candles(CandleInterval interval, int count, Instant before);
}
