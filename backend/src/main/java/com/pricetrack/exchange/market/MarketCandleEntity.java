package com.pricetrack.exchange.market;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.pricetrack.exchange.market.model.CandleInterval;

@Entity
@Table(name = "market_candles", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_candles_bucket", columnNames = {"symbol", "candle_interval", "started_at"}))
@Getter @Setter @NoArgsConstructor
public class MarketCandleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Enumerated(EnumType.STRING)
    @Column(name = "candle_interval", nullable = false, length = 20)
    private CandleInterval interval;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal open;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal high;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal low;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal close;
    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal volume;
}
