package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.math.BigDecimal;
import java.time.*;

/** REST bootstrap is not a timestamped live tick. OPEN requires an explicit current-day 0s message. */
public class KiwoomPriceProvider implements MarketPriceProvider {
    private final KiwoomRestClient rest;
    private final KiwoomRealtimeClient realtime;
    private final KiwoomProtocol protocol;
    private final KiwoomPriceProperties properties;
    private final MarketWebSocketPublisher publisher;
    private final Clock clock;
    private MarketPriceSnapshot latest;
    private boolean realTick;
    private MarketStatus market=MarketStatus.UNKNOWN;
    private LocalDate marketDate,tradeDate;
    private long marketGeneration = -1;
    private BigDecimal cumulative=BigDecimal.ZERO;
    public KiwoomPriceProvider(KiwoomRestClient rest,KiwoomRealtimeClient realtime,KiwoomProtocol protocol,
            KiwoomPriceProperties properties,MarketWebSocketPublisher publisher,Clock clock) {
        this.rest=rest;this.realtime=realtime;this.protocol=protocol;this.properties=properties;this.publisher=publisher;this.clock=clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            JsonNode data=rest.currentPrice().body();
            BigDecimal price=KiwoomProtocol.positive(data.path("cur_prc").asText());
            BigDecimal change=KiwoomProtocol.decimal(data.path("pred_pre").asText());
            BigDecimal previous=price.subtract(change);
            if(previous.signum()<=0)throw new KiwoomApiException();
            synchronized(this){latest=new MarketPriceSnapshot("mSEC",price,previous,change,
                KiwoomProtocol.decimal(data.path("flu_rt").asText()),MarketStatus.UNKNOWN,PriceStatus.INITIALIZING,"KIWOOM",clock.instant());}
        } catch(RuntimeException ignored){ /* WS may recover independently; no fake fallback price. */ }
        realtime.start(this::accept);
    }
    synchronized void accept(KiwoomRealtimeClient.Frame observation) {
        if(observation.generation()!=realtime.connectionGeneration() || !realtime.connected())return;
        JsonNode frame=observation.payload();
        if(!frame.path("data").isArray())throw new KiwoomApiException();
        for(JsonNode entry:frame.path("data")) {
            MarketStatus state=protocol.state(entry);
            if(state!=null){market=state;marketDate=today();marketGeneration=observation.generation();continue;}
            KiwoomProtocol.Trade trade=protocol.trade(entry);
            if(trade==null)continue;
            LocalDate date=trade.observedAt().atZone(KiwoomRestClient.KST).toLocalDate();
            if(date.equals(tradeDate) && (trade.cumulative().compareTo(cumulative)<=0
                    || (realTick && trade.observedAt().isBefore(latest.observedAt()))))continue;
            if(!trade.regular()) { market=MarketStatus.CLOSED;marketDate=today();marketGeneration=observation.generation(); }
            cumulative=trade.cumulative();tradeDate=date;realTick=true;
            latest=new MarketPriceSnapshot("mSEC",trade.price(),trade.price().subtract(trade.change()),trade.change(),
                trade.rate(),marketStatus(),PriceStatus.LIVE,"KIWOOM",trade.observedAt());
            publisher.publishPrice(current(),trade.volume());
        }
    }
    private LocalDate today(){return clock.instant().atZone(KiwoomRestClient.KST).toLocalDate();}
    private MarketStatus marketStatus(){return realtime.connected() && marketGeneration==realtime.connectionGeneration()
        && today().equals(marketDate)?market:MarketStatus.UNKNOWN;}
    @Override public synchronized MarketPriceSnapshot current() {
        if(latest==null)throw new KiwoomApiException();
        Duration age=Duration.between(latest.observedAt(),clock.instant());
        PriceStatus status=!realTick?PriceStatus.INITIALIZING
            : age.compareTo(properties.staleAfter())>=0?PriceStatus.STALE
            : !realtime.connected() || age.compareTo(properties.degradedAfter())>=0?PriceStatus.DEGRADED:PriceStatus.LIVE;
        return new MarketPriceSnapshot(latest.symbol(),latest.price(),latest.previousClose(),latest.change(),latest.changeRate(),
                marketStatus(),status,"KIWOOM",latest.observedAt());
    }
    @PreDestroy public void stop(){realtime.stop();}
}
