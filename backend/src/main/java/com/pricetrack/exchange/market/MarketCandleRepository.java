package com.pricetrack.exchange.market;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pricetrack.exchange.market.model.CandleInterval;

public interface MarketCandleRepository extends JpaRepository<MarketCandleEntity, Long> {
    Optional<MarketCandleEntity> findBySymbolAndIntervalAndStartedAt(
            String symbol, CandleInterval interval, Instant startedAt);
    List<MarketCandleEntity> findBySymbolAndIntervalAndStartedAtLessThanEqualOrderByStartedAtDesc(
            String symbol, CandleInterval interval, Instant before, Pageable pageable);
}
