package com.pricetrack.exchange.quote;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface PriceQuoteRepository extends JpaRepository<PriceQuote, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PriceQuote> findForUpdateByQuoteId(String quoteId);
}
