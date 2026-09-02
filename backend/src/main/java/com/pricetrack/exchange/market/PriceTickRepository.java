package com.pricetrack.exchange.market;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceTickRepository extends JpaRepository<PriceTick, Long> {
    List<PriceTick> findTop100BySymbolOrderByCreatedAtDesc(String symbol);
    boolean existsByBlockchainTransactionId(Long blockchainTransactionId);
}
