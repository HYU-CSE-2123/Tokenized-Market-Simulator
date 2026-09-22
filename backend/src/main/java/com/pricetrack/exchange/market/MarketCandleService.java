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

    public MarketCandlePage candles(String symbol, String interval, Integer count, Instant before) {
        if (!MarketPriceService.SYMBOL.equals(symbol)) throw new UnsupportedSymbolException();
        try {
            CandleInterval parsedInterval = CandleInterval.parse(interval);
            int requestedCount = count == null ? Math.min(100, parsedInterval.maxCount()) : count;
            if (requestedCount < 1 || requestedCount > parsedInterval.maxCount()) {
                throw new InvalidCandleQueryException("count는 " + interval + "에서 1 이상 "
                        + parsedInterval.maxCount() + " 이하여야 합니다.");
            }
            return provider.candles(parsedInterval, requestedCount,
                    before == null ? Instant.now() : before);
        } catch (InvalidCandleQueryException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InvalidCandleQueryException(exception.getMessage());
        }
    }
}
