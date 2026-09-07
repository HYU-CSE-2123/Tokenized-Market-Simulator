package com.pricetrack.exchange.websocket.publisher;

import org.springframework.stereotype.Component;

import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.order.OrderStatus;
import com.pricetrack.exchange.portfolio.PortfolioService;
import com.pricetrack.exchange.portfolio.PortfolioService.Portfolio;
import com.pricetrack.exchange.websocket.event.OrderEventPayload;
import com.pricetrack.exchange.websocket.event.PortfolioEventPayload;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;

/** Converts user-owned domain state into private WebSocket events. */
@Component
public class UserWebSocketPublisher {
    private final WebSocketEventPublisher eventPublisher;
    private final PortfolioService portfolioService;

    public UserWebSocketPublisher(
            WebSocketEventPublisher eventPublisher, PortfolioService portfolioService) {
        this.eventPublisher = eventPublisher;
        this.portfolioService = portfolioService;
    }

    /** Publishes only externally meaningful, persisted order states. */
    public void publishOrder(Order order) {
        WebSocketEventType type = switch (order.getStatus()) {
            case PENDING_ONCHAIN -> WebSocketEventType.ORDER_PENDING_ONCHAIN;
            case FILLED -> WebSocketEventType.ORDER_FILLED;
            case FAILED -> WebSocketEventType.ORDER_FAILED;
            case REQUESTED, CANCELED -> throw new IllegalArgumentException(
                    order.getStatus() + " 주문은 아직 사용자 알림 대상이 아닙니다.");
        };
        eventPublisher.publishToUser(
                order.getUserId(),
                WebSocketDestinations.ORDERS_QUEUE,
                type,
                new OrderEventPayload(
                        order.getId(),
                        order.getSymbol(),
                        order.getSide(),
                        order.getInputAmount(),
                        order.getExpectedOutputAmount(),
                        order.getStatus(),
                        order.getTxHash()));
    }

    /** Captures the portfolio that will be visible after the surrounding transaction commits. */
    public void publishPortfolio(Long userId) {
        Portfolio portfolio = portfolioService.portfolio(userId);
        eventPublisher.publishToUser(
                userId,
                WebSocketDestinations.PORTFOLIO_QUEUE,
                WebSocketEventType.PORTFOLIO_UPDATED,
                new PortfolioEventPayload(
                        portfolio.krwBalance(),
                        portfolio.tokenBalance(),
                        portfolio.currentPrice(),
                        portfolio.averageBuyPrice(),
                        portfolio.tokenValue(),
                        portfolio.unrealizedProfit(),
                        portfolio.totalValue()));
    }
}
