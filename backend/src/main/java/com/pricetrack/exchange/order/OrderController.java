package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 API (기획서 §12.4).
 * TODO(Phase 2): DB 기반 주문 생성 → (Phase 3) web3j buy/sell 트랜잭션 연동.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record BuyRequest(String symbol, BigDecimal krwAmount) {}

    public record SellRequest(String symbol, BigDecimal tokenAmount) {}

    public record OrderResponse(Long orderId, String symbol, OrderSide side,
                                OrderStatus status, String txHash) {}

    @PostMapping("/buy")
    public OrderResponse buy(@RequestBody BuyRequest request) {
        throw new UnsupportedOperationException("not implemented (Phase 2/3)");
    }

    @PostMapping("/sell")
    public OrderResponse sell(@RequestBody SellRequest request) {
        throw new UnsupportedOperationException("not implemented (Phase 2/3)");
    }

    @GetMapping
    public List<OrderResponse> orders() {
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }

    @GetMapping("/{orderId}")
    public OrderResponse order(@PathVariable Long orderId) {
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }
}
