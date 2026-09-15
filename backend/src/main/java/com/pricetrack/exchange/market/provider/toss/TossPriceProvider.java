package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** 실제 가격 모드 기동 시 REST로 첫 삼성전자 가격을 확보한다. */
public class TossPriceProvider implements MarketPriceProvider {
    private final TossMarketDataClient marketDataClient;
    private final TossRealtimeClient realtimeClient;
    private final TossPriceProperties properties;
    private final MarketWebSocketPublisher marketEvents;
    private final AtomicReference<MarketPriceSnapshot> current = new AtomicReference<>();

    public TossPriceProvider(TossMarketDataClient marketDataClient, TossRealtimeClient realtimeClient,
            TossPriceProperties properties, MarketWebSocketPublisher marketEvents) {
        this.marketDataClient = marketDataClient;
        this.realtimeClient = realtimeClient;
        this.properties = properties;
        this.marketEvents = marketEvents;
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
        realtimeClient.start(this::acceptTrade);
    }

    void acceptTrade(TossRealtimeProtocol.TossTrade trade) {
        while (true) {
            MarketPriceSnapshot previous = current.get();
            if (previous == null || !trade.observedAt().isAfter(previous.observedAt())) return;
            BigDecimal change = trade.price().subtract(previous.previousClose());
            BigDecimal changeRate = change.divide(previous.previousClose(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            MarketPriceSnapshot next = new MarketPriceSnapshot(
                    MarketPriceService.SYMBOL,
                    trade.price(),
                    previous.previousClose(),
                    change,
                    changeRate,
                    MarketStatus.UNKNOWN,
                    PriceStatus.LIVE,
                    "TOSS",
                    trade.observedAt());
            if (current.compareAndSet(previous, next)) {
                if (trade.price().compareTo(previous.price()) != 0) {
                    marketEvents.publishPrice(next.price(), next.changeRate());
                }
                return;
            }
        }
    }

    @PreDestroy
    public void stopRealtime() {
        realtimeClient.stop();
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
