package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;

/** 실제 가격 모드 기동 시 REST로 첫 삼성전자 가격을 확보한다. */
public class TossPriceProvider implements MarketPriceProvider {
    private static final Logger log = LoggerFactory.getLogger(TossPriceProvider.class);
    private final TossMarketDataClient marketDataClient;
    private final TossRealtimeClient realtimeClient;
    private final TossPriceProperties properties;
    private final MarketWebSocketPublisher marketEvents;
    private final Clock clock;
    private final AtomicReference<MarketPriceSnapshot> current = new AtomicReference<>();
    private volatile TossMarketDataClient.TossMarketReference marketReference;

    public TossPriceProvider(TossMarketDataClient marketDataClient, TossRealtimeClient realtimeClient,
            TossPriceProperties properties, MarketWebSocketPublisher marketEvents) {
        this(marketDataClient, realtimeClient, properties, marketEvents, Clock.systemUTC());
    }

    TossPriceProvider(TossMarketDataClient marketDataClient, TossRealtimeClient realtimeClient,
            TossPriceProperties properties, MarketWebSocketPublisher marketEvents, Clock clock) {
        this.marketDataClient = marketDataClient;
        this.realtimeClient = realtimeClient;
        this.properties = properties;
        this.marketEvents = marketEvents;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadInitialPrice() {
        TossMarketDataClient.TossPrice tossPrice = marketDataClient.currentPrice(properties.symbol());
        marketReference = loadMarketReference();
        BigDecimal previousClose = marketReference.previousClose();
        BigDecimal change = tossPrice.price().subtract(previousClose);
        BigDecimal changeRate = percentage(change, previousClose);
        current.set(new MarketPriceSnapshot(
                MarketPriceService.SYMBOL,
                tossPrice.price(),
                previousClose,
                change,
                changeRate,
                marketStatus(clock.instant()),
                priceStatus(tossPrice.observedAt(), clock.instant()),
                "TOSS",
                tossPrice.observedAt()));
        realtimeClient.start(this::acceptTrade);
    }

    /** Refreshes the business-day calendar and previous close shortly after KST midnight. */
    @Scheduled(cron = "${app.price.toss.reference-refresh-cron:0 5 0 * * *}", zone = "Asia/Seoul")
    public void refreshMarketReference() {
        if (current.get() == null) return;
        try {
            TossMarketDataClient.TossMarketReference nextReference = loadMarketReference();
            marketReference = nextReference;
            current.updateAndGet(snapshot -> withReference(snapshot, nextReference, clock.instant()));
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh Toss market reference data; retaining the last valid schedule", exception);
        }
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
                    marketStatus(clock.instant()),
                    priceStatus(trade.observedAt(), clock.instant()),
                    "TOSS",
                    trade.observedAt());
            if (current.compareAndSet(previous, next)) {
                // 같은 가격의 체결도 차트 거래량을 증가시키므로 모든 새 tick을 발행한다.
                marketEvents.publishPrice(next, trade.volume());
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
        Instant now = clock.instant();
        return new MarketPriceSnapshot(snapshot.symbol(), snapshot.price(), snapshot.previousClose(),
                snapshot.change(), snapshot.changeRate(), marketStatus(now),
                priceStatus(snapshot.observedAt(), now), snapshot.provider(), snapshot.observedAt());
    }

    private MarketStatus marketStatus(Instant instant) {
        TossMarketDataClient.TossMarketReference reference = marketReference;
        return reference != null && reference.isOpenAt(instant) ? MarketStatus.OPEN : MarketStatus.CLOSED;
    }

    private TossMarketDataClient.TossMarketReference loadMarketReference() {
        return marketDataClient.marketReference(properties.symbol(),
                clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
    }

    private MarketPriceSnapshot withReference(MarketPriceSnapshot snapshot,
            TossMarketDataClient.TossMarketReference reference, Instant now) {
        BigDecimal change = snapshot.price().subtract(reference.previousClose());
        return new MarketPriceSnapshot(snapshot.symbol(), snapshot.price(), reference.previousClose(),
                change, percentage(change, reference.previousClose()), marketStatus(now),
                priceStatus(snapshot.observedAt(), now), snapshot.provider(), snapshot.observedAt());
    }

    private PriceStatus priceStatus(Instant observedAt, Instant now) {
        if (marketStatus(now) == MarketStatus.CLOSED) return PriceStatus.LIVE;
        Duration age = Duration.between(observedAt, now);
        if (age.isNegative()) age = Duration.ZERO;
        if (age.compareTo(properties.staleAfter()) >= 0) return PriceStatus.STALE;
        if (realtimeClient.connectionState() != TossRealtimeClient.ConnectionState.CONNECTED
                || age.compareTo(properties.degradedAfter()) >= 0) return PriceStatus.DEGRADED;
        return PriceStatus.LIVE;
    }

    private static BigDecimal percentage(BigDecimal change, BigDecimal previousClose) {
        return change.divide(previousClose, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
