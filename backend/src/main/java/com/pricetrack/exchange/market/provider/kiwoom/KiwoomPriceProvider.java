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
import com.pricetrack.exchange.market.TradingPriceContext;

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
    private long tickGeneration = -1;
    private boolean regularTick;
    private LocalDate vetoDate;
    private String vetoReason;
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
            MarketStatus state;
            try { state=protocol.state(entry); }
            catch(RuntimeException invalid) {
                if("0s".equals(entry.path("type").asText())) { vetoDate=today();vetoReason="INVALID_MARKET_STATE"; }
                throw invalid;
            }
            if(state!=null){
                market=state;marketDate=today();marketGeneration=observation.generation();
                vetoDate=today();vetoReason=state==MarketStatus.OPEN?null:state==MarketStatus.CLOSED?"CLOSED":"UNSUPPORTED_MARKET_STATE";
                continue;
            }
            KiwoomProtocol.Trade trade=protocol.trade(entry);
            if(trade==null)continue;
            LocalDate date=trade.observedAt().atZone(KiwoomRestClient.KST).toLocalDate();
            if(date.equals(tradeDate) && (trade.cumulative().compareTo(cumulative)<=0
                    || (realTick && trade.observedAt().isBefore(latest.observedAt()))))continue;
            if(!trade.regular()) { market=MarketStatus.CLOSED;marketDate=today();marketGeneration=observation.generation();vetoDate=today();vetoReason="CLOSED"; }
            tickGeneration=observation.generation();regularTick=trade.regular();
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

    @Override public synchronized TradingPriceContext currentForTrading() {
        long generation=realtime.connectionGeneration();
        MarketPriceSnapshot snapshot=current();
        String reason=null;
        if(!realtime.connected()) reason="DISCONNECTED";
        else if(today().equals(vetoDate) && vetoReason!=null) reason=vetoReason;
        else if(!realTick || tickGeneration!=generation || !today().equals(tradeDate)) reason="NO_CURRENT_GENERATION_TICK";
        else if(!regularTick) reason="NON_REGULAR_SESSION";
        else if(snapshot.priceStatus()!=PriceStatus.LIVE) reason="PRICE_NOT_LIVE";
        else if(!new TradingPriceContext(snapshot,true,"REGULAR_TICK",generation).freshAt(clock.instant())) reason="STALE_OR_FUTURE_PRICE";
        if(generation!=realtime.connectionGeneration() || !realtime.connected()) reason="CONNECTION_CHANGED";
        return new TradingPriceContext(snapshot,reason==null,reason==null?"REGULAR_TICK":reason,generation);
    }
}
