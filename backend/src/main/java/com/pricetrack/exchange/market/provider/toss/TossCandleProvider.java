package com.pricetrack.exchange.market.provider.toss;

import java.time.Instant;

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandlePage;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;

public class TossCandleProvider implements MarketCandleProvider {
    private final TossMarketDataClient client;
    private final TossPriceProperties properties;

    public TossCandleProvider(TossMarketDataClient client, TossPriceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public MarketCandlePage candles(CandleInterval interval, int count, Instant before) {
        TossMarketDataClient.TossCandlePage page = client.candles(
                properties.symbol(), interval, count, before);
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "TOSS",
                page.candles(), page.nextBefore());
    }
}
