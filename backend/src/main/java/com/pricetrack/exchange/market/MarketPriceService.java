package com.pricetrack.exchange.market;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
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
}
