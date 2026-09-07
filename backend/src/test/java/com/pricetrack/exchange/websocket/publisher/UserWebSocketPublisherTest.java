package com.pricetrack.exchange.websocket.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderSide;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.portfolio.PortfolioService;
import com.pricetrack.exchange.portfolio.PortfolioService.Portfolio;
import com.pricetrack.exchange.websocket.event.OrderEventPayload;
import com.pricetrack.exchange.websocket.event.PortfolioEventPayload;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/** Verifies private order-state mapping and portfolio snapshot creation. */
class UserWebSocketPublisherTest {
    private final WebSocketEventPublisher eventPublisher = mock(WebSocketEventPublisher.class);
    private final PortfolioService portfolioService = mock(PortfolioService.class);
    private final UserWebSocketPublisher publisher =
            new UserWebSocketPublisher(eventPublisher, portfolioService);

    @Test
    void mapsPendingOrderToOwningUser() {
        Order order = order(OrderStatus.PENDING_ONCHAIN);
        ArgumentCaptor<OrderEventPayload> payload = ArgumentCaptor.forClass(OrderEventPayload.class);

        publisher.publishOrder(order);

        verify(eventPublisher).publishToUser(
                eq(15L),
                eq(WebSocketDestinations.ORDERS_QUEUE),
                eq(WebSocketEventType.ORDER_PENDING_ONCHAIN),
                payload.capture());
        assertThat(payload.getValue()).isEqualTo(new OrderEventPayload(
                31L, "mSEC", OrderSide.BUY, new BigDecimal("100000"),
                new BigDecimal("1.332"), OrderStatus.PENDING_ONCHAIN, "0x1234"));
    }

    @Test
    void mapsFilledAndFailedTypes() {
        publisher.publishOrder(order(OrderStatus.FILLED));
        publisher.publishOrder(order(OrderStatus.FAILED));

        verify(eventPublisher).publishToUser(
                eq(15L), eq(WebSocketDestinations.ORDERS_QUEUE),
                eq(WebSocketEventType.ORDER_FILLED), eq(payload(OrderStatus.FILLED)));
        verify(eventPublisher).publishToUser(
                eq(15L), eq(WebSocketDestinations.ORDERS_QUEUE),
                eq(WebSocketEventType.ORDER_FAILED), eq(payload(OrderStatus.FAILED)));
    }

    @Test
    void rejectsInternalRequestedState() {
        assertThatThrownBy(() -> publisher.publishOrder(order(OrderStatus.REQUESTED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishesCurrentPortfolioSnapshotToOwningUser() {
        Portfolio portfolio = new Portfolio(
                new BigDecimal("900000"), new BigDecimal("1.332"),
                new BigDecimal("75000"), new BigDecimal("75075.07507508"),
                new BigDecimal("99900"), new BigDecimal("-100"),
                new BigDecimal("999900"));
        when(portfolioService.portfolio(15L)).thenReturn(portfolio);

        publisher.publishPortfolio(15L);

        verify(eventPublisher).publishToUser(
                eq(15L),
                eq(WebSocketDestinations.PORTFOLIO_QUEUE),
                eq(WebSocketEventType.PORTFOLIO_UPDATED),
                eq(new PortfolioEventPayload(
                        portfolio.krwBalance(), portfolio.tokenBalance(), portfolio.currentPrice(),
                        portfolio.averageBuyPrice(), portfolio.tokenValue(),
                        portfolio.unrealizedProfit(), portfolio.totalValue())));
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(31L);
        order.setUserId(15L);
        order.setSymbol("mSEC");
        order.setSide(OrderSide.BUY);
        order.setInputAmount(new BigDecimal("100000"));
        order.setExpectedOutputAmount(new BigDecimal("1.332"));
        order.setStatus(status);
        order.setTxHash("0x1234");
        return order;
    }

    private OrderEventPayload payload(OrderStatus status) {
        return new OrderEventPayload(
                31L, "mSEC", OrderSide.BUY, new BigDecimal("100000"),
                new BigDecimal("1.332"), status, "0x1234");
    }
}
