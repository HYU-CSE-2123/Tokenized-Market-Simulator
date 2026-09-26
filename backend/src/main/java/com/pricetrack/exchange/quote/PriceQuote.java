package com.pricetrack.exchange.quote;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;

import java.math.BigInteger;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 서명 보고서를 사용자에게 귀속시키고 일회성 소비를 보장하는 영속 견적이다. */
@Entity
@Table(name = "price_quotes")
@Getter
@Setter
@NoArgsConstructor
public class PriceQuote {
    @Id
    @Column(name = "quote_id", length = 66)
    private String quoteId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PriceReport.Side side;
    @Column(name = "price_e8", nullable = false, precision = 78)
    private BigInteger priceE8;
    @Column(name = "input_amount", nullable = false, precision = 78)
    private BigInteger inputAmount;
    @Column(name = "minimum_output", nullable = false, precision = 78)
    private BigInteger minimumOutput;
    @Column(nullable = false, precision = 78)
    private BigInteger fee;
    @Column(nullable = false, length = 42)
    private String executor;
    @Column(nullable = false, length = 132)
    private String signature;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PriceQuoteStatus status = PriceQuoteStatus.ISSUED;
    @Column(name = "order_id", unique = true)
    private Long orderId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
}
