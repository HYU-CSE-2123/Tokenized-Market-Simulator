package com.pricetrack.exchange.market.provider.toss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pricetrack.exchange.market.MarketCandleAggregator;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandle;
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
        if (interval.isAggregatedIntraday()) return aggregatedCandles(interval, count, before);
        TossMarketDataClient.TossCandlePage page = client.candles(
                properties.symbol(), interval, count, before);
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "TOSS",
                page.candles(), page.nextBefore());
    }

    private MarketCandlePage aggregatedCandles(CandleInterval interval, int count, Instant before) {
        int sourceLimit = (count + 1) * interval.minutes();
        Map<Instant, MarketCandle> unique = new LinkedHashMap<>();
        Instant cursor = before;
        while (unique.size() < sourceLimit) {
            int pageSize = Math.min(200, sourceLimit - unique.size());
            TossMarketDataClient.TossCandlePage page = client.candles(
                    properties.symbol(), CandleInterval.ONE_MINUTE, pageSize, cursor);
            page.candles().forEach(candle -> unique.put(candle.startedAt(), candle));
            if (page.nextBefore() == null || page.nextBefore().equals(cursor) || page.candles().isEmpty()) break;
            cursor = page.nextBefore();
        }
        List<MarketCandle> source = new ArrayList<>(unique.values());
        List<MarketCandle> aggregated = MarketCandleAggregator.aggregate(source, interval, Instant.now());
        int from = Math.max(0, aggregated.size() - count);
        // Toss 원본 before는 1분봉 종료 경계이므로 첫 반환 봉의 시작 시각이 바로 이전 봉의 상한이다.
        Instant nextBefore = from > 0 ? aggregated.get(from).startedAt() : null;
        return new MarketCandlePage(MarketPriceService.SYMBOL, interval, "TOSS",
                aggregated.subList(from, aggregated.size()), nextBefore);
    }
}
