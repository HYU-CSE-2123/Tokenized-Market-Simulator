package com.pricetrack.exchange.trade;

import java.math.BigDecimal;
import java.time.Instant;

import com.pricetrack.exchange.order.OrderSide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "trades")
@Getter
@Setter
@NoArgsConstructor
public class Trade {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private OrderSide side;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal price;
    @Column(name = "base_amount", nullable = false, precision = 30, scale = 18)
    private BigDecimal baseAmount;
    @Column(name = "quote_amount", nullable = false, precision = 30, scale = 18)
    private BigDecimal quoteAmount;
    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal fee;
    @Column(name = "tx_hash")
    private String txHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
