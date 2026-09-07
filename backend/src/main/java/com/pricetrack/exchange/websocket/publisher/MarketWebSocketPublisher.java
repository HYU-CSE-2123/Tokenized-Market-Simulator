package com.pricetrack.exchange.websocket.publisher;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.pricetrack.exchange.market.PriceSimulator;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.websocket.event.PriceEventPayload;
import com.pricetrack.exchange.websocket.event.TradeEventPayload;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/** Converts market domain values into the public WebSocket contract. */
@Component
public class MarketWebSocketPublisher {
    private final WebSocketEventPublisher eventPublisher;

    public MarketWebSocketPublisher(WebSocketEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Publishes the latest simulated price without requiring a DB transaction. */
    public void publishPrice(BigDecimal price, BigDecimal changeRate) {
        eventPublisher.publishPublic(
                WebSocketDestinations.PRICE_TOPIC,
                WebSocketEventType.PRICE_UPDATED,
                new PriceEventPayload(PriceSimulator.SYMBOL, price, changeRate));
    }

    /**
     * Publishes only market-visible execution fields. User, order and transaction identifiers
     * intentionally stay out of the public stream.
     */
    public void publishTrade(Trade trade) {
        eventPublisher.publishPublic(
                WebSocketDestinations.TRADES_TOPIC,
                WebSocketEventType.TRADE_EXECUTED,
                new TradeEventPayload(
                        trade.getId(),
                        trade.getSymbol(),
                        trade.getSide(),
                        trade.getPrice(),
                        trade.getBaseAmount(),
                        trade.getQuoteAmount(),
                        trade.getFee(),
                        trade.getCreatedAt()));
    }
}
