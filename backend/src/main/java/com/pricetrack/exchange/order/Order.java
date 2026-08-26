package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private OrderSide side;
    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType = "MARKET";
    @Column(name = "input_amount", nullable = false, precision = 30, scale = 18)
    private BigDecimal inputAmount;
    @Column(name = "expected_output_amount", precision = 30, scale = 18)
    private BigDecimal expectedOutputAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.REQUESTED;
    @Column(name = "tx_hash")
    private String txHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
