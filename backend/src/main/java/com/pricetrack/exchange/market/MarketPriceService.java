package com.pricetrack.exchange.market;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.provider.MarketPriceProvider;

/** 도메인 서비스가 선택된 가격 공급자의 구현을 알지 않도록 하는 단일 진입점이다. */
@Service
public class MarketPriceService {
    public static final String SYMBOL = "mSEC";

    private final MarketPriceProvider provider;

    public MarketPriceService(MarketPriceProvider provider) {
        this.provider = provider;
    }

    public MarketPriceSnapshot current() {
        return provider.current();
    }

    public BigDecimal currentPrice() {
        return current().price();
    }

    /** Returns one checked snapshot so validation and calculation use the same observed price. */
    public MarketPriceSnapshot requireTradableSnapshot() {
        MarketPriceSnapshot snapshot = current();
        if (snapshot.marketStatus() == MarketStatus.CLOSED) throw new MarketClosedException();
        if (snapshot.priceStatus() == PriceStatus.STALE) throw new StalePriceException();
        return snapshot;
    }

    /** Oracle and orders share the same closed/stale settlement boundary. */
    public boolean isSettlementAllowed(MarketPriceSnapshot snapshot) {
        return snapshot.marketStatus() != MarketStatus.CLOSED
                && snapshot.priceStatus() != PriceStatus.STALE;
    }
}
