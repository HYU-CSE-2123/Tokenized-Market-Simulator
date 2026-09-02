package com.pricetrack.exchange.market;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "price_ticks")
@Getter
@Setter
@NoArgsConstructor
public class PriceTick {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "blockchain_transaction_id", unique = true)
    private Long blockchainTransactionId;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal price;
    @Column(nullable = false, length = 50)
    private String source;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
