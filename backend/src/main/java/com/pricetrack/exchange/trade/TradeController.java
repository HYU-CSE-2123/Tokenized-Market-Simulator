package com.pricetrack.exchange.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.order.OrderSide;

@RestController
@RequestMapping("/api/trades")
public class TradeController {
    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @GetMapping
    public List<TradeResponse> trades(@AuthenticationPrincipal AuthenticatedUser user) {
        return tradeService.findAll(user.userId()).stream().map(TradeResponse::from).toList();
    }

    public record TradeResponse(Long tradeId, Long orderId, String symbol, OrderSide side,
            BigDecimal price, BigDecimal tokenAmount, BigDecimal krwAmount, BigDecimal fee, Instant createdAt) {
        static TradeResponse from(Trade trade) {
            return new TradeResponse(trade.getId(), trade.getOrderId(), trade.getSymbol(), trade.getSide(),
                    trade.getPrice(), trade.getBaseAmount(), trade.getQuoteAmount(), trade.getFee(),
                    trade.getCreatedAt());
        }
    }
}
