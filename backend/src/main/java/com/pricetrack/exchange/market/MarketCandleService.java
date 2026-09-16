package com.pricetrack.exchange.market;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.common.exception.UnsupportedSymbolException;
import com.pricetrack.exchange.market.model.CandleInterval;
import com.pricetrack.exchange.market.model.MarketCandlePage;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;

@Service
public class MarketCandleService {
    private final MarketCandleProvider provider;

    public MarketCandleService(MarketCandleProvider provider) { this.provider = provider; }

    public MarketCandlePage candles(String symbol, String interval, int count, Instant before) {
        if (!MarketPriceService.SYMBOL.equals(symbol)) throw new UnsupportedSymbolException();
        if (count < 1 || count > 200) {
            throw new InvalidCandleQueryException("count는 1 이상 200 이하여야 합니다.");
        }
        try {
            return provider.candles(CandleInterval.parse(interval), count,
                    before == null ? Instant.now() : before);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCandleQueryException(exception.getMessage());
        }
    }
}
