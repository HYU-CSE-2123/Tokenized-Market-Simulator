package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.auth.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public record BuyRequest(@NotBlank String symbol,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal krwAmount) {}
    public record SellRequest(@NotBlank String symbol,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal tokenAmount) {}
    public record OrderResponse(Long orderId, String symbol, OrderSide side, BigDecimal inputAmount,
            BigDecimal outputAmount, OrderStatus status, String txHash, Instant createdAt) {
        static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getSymbol(), order.getSide(), order.getInputAmount(),
                    order.getExpectedOutputAmount(), order.getStatus(), order.getTxHash(), order.getCreatedAt());
        }
    }

    @PostMapping("/buy")
    public OrderResponse buy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BuyRequest request) {
        return OrderResponse.from(orderService.buy(user.userId(), request.symbol(), request.krwAmount()));
    }

    @PostMapping("/sell")
    public OrderResponse sell(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SellRequest request) {
        return OrderResponse.from(orderService.sell(user.userId(), request.symbol(), request.tokenAmount()));
    }

    @GetMapping
    public List<OrderResponse> orders(@AuthenticationPrincipal AuthenticatedUser user) {
        return orderService.findAll(user.userId()).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{orderId}")
    public OrderResponse order(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long orderId) {
        return OrderResponse.from(orderService.findOne(user.userId(), orderId));
    }
}
