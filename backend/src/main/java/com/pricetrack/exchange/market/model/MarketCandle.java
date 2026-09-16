package com.pricetrack.exchange.market.model;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketCandle(Instant startedAt, BigDecimal open, BigDecimal high,
        BigDecimal low, BigDecimal close, BigDecimal volume, boolean closed) {}
