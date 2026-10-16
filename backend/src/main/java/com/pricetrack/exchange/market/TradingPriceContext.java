package com.pricetrack.exchange.market;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Internal quote evidence, not a market-OPEN assertion or a change to the user ledger. */
public record TradingPriceContext(MarketPriceSnapshot snapshot, boolean allowed, String reason, Long generation) {
    public TradingPriceContext { Objects.requireNonNull(snapshot); Objects.requireNonNull(reason); }
    public static TradingPriceContext standard(MarketPriceSnapshot snapshot) {
        boolean allowed = snapshot.marketStatus()!=MarketStatus.CLOSED && snapshot.priceStatus()!=PriceStatus.STALE
            && (MarketDataEligibility.synthetic(snapshot) || MarketDataEligibility.liveExternal(snapshot));
        return new TradingPriceContext(snapshot,allowed,allowed?"STANDARD_POLICY":"MARKET_UNAVAILABLE",null);
    }
    public boolean freshAt(Instant now) {
        Duration age=Duration.between(snapshot.observedAt(),now);
        return age.compareTo(Duration.ofSeconds(5))<=0 && age.compareTo(Duration.ofSeconds(-2))>=0;
    }
    public boolean sameConnection(TradingPriceContext other) {
        return snapshot.provider().equalsIgnoreCase(other.snapshot.provider()) && Objects.equals(generation,other.generation);
    }
}
