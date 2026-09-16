package com.pricetrack.exchange.websocket.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.websocket.event.PriceEventPayload;
import com.pricetrack.exchange.websocket.event.TradeEventPayload;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/** Verifies the mapping from domain values to the stable public WebSocket contract. */
class MarketWebSocketPublisherTest {
    private final WebSocketEventPublisher eventPublisher = mock(WebSocketEventPublisher.class);
    private final MarketWebSocketPublisher publisher = new MarketWebSocketPublisher(eventPublisher);

    @Test
    void mapsPriceToPublicPayload() {
        Instant observedAt = Instant.parse("2026-09-16T01:23:45Z");
        MarketPriceSnapshot snapshot = new MarketPriceSnapshot(
                "mSEC", new BigDecimal("75100"), new BigDecimal("75000"),
                new BigDecimal("100"), new BigDecimal("0.13333333"),
                MarketStatus.OPEN, PriceStatus.LIVE, "TOSS", observedAt);
        publisher.publishPrice(snapshot, new BigDecimal("12.5"));

        verify(eventPublisher).publishPublic(
                eq(WebSocketDestinations.PRICE_TOPIC),
                eq(WebSocketEventType.PRICE_UPDATED),
                eq(new PriceEventPayload(
                        "mSEC", new BigDecimal("75100"), new BigDecimal("75000"),
                        new BigDecimal("100"), new BigDecimal("0.13333333"), new BigDecimal("12.5"),
                        MarketStatus.OPEN, PriceStatus.LIVE, "TOSS", observedAt, observedAt)));
    }

    @Test
    void mapsTradeWithoutPrivateIdentifiers() {
        Instant createdAt = Instant.parse("2026-09-06T06:00:00Z");
        Trade trade = trade(createdAt);
        ArgumentCaptor<TradeEventPayload> payload = ArgumentCaptor.forClass(TradeEventPayload.class);

        publisher.publishTrade(trade);

        verify(eventPublisher).publishPublic(
                eq(WebSocketDestinations.TRADES_TOPIC),
                eq(WebSocketEventType.TRADE_EXECUTED),
                payload.capture());
        assertThat(payload.getValue()).isEqualTo(new TradeEventPayload(
                31L, "mSEC", OrderSide.BUY, new BigDecimal("75000"),
                new BigDecimal("1.5"), new BigDecimal("112500"),
                new BigDecimal("112.5"), createdAt));
    }

    private Trade trade(Instant createdAt) {
        Trade trade = new Trade();
        trade.setId(31L);
        trade.setUserId(99L);
        trade.setOrderId(41L);
        trade.setTxHash("0xprivate");
        trade.setSymbol("mSEC");
        trade.setSide(OrderSide.BUY);
        trade.setPrice(new BigDecimal("75000"));
        trade.setBaseAmount(new BigDecimal("1.5"));
        trade.setQuoteAmount(new BigDecimal("112500"));
        trade.setFee(new BigDecimal("112.5"));
        trade.setCreatedAt(createdAt);
        return trade;
    }
}
