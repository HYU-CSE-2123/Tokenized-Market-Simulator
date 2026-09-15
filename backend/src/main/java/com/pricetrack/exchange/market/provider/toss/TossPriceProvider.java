package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;

/** 실제 가격 모드 기동 시 REST로 첫 삼성전자 가격을 확보한다. */
public class TossPriceProvider implements MarketPriceProvider {
    private final TossMarketDataClient marketDataClient;
    private final TossPriceProperties properties;
    private final AtomicReference<MarketPriceSnapshot> current = new AtomicReference<>();

    public TossPriceProvider(TossMarketDataClient marketDataClient, TossPriceProperties properties) {
        this.marketDataClient = marketDataClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadInitialPrice() {
        TossMarketDataClient.TossPrice tossPrice = marketDataClient.currentPrice(properties.symbol());
        BigDecimal zero = BigDecimal.ZERO;
        current.set(new MarketPriceSnapshot(
                MarketPriceService.SYMBOL,
                tossPrice.price(),
                tossPrice.price(),
                zero,
                zero,
                MarketStatus.UNKNOWN,
                PriceStatus.LIVE,
                "TOSS",
                tossPrice.observedAt()));
    }

    @Override
    public MarketPriceSnapshot current() {
        MarketPriceSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new TossApiException("토스증권 초기 가격이 아직 준비되지 않았습니다.");
        }
        return snapshot;
    }
}
