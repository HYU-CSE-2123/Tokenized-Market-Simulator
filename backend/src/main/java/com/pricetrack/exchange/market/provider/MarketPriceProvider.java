package com.pricetrack.exchange.market.provider;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;

/** 시뮬레이션·실제 시세 공급자를 나머지 도메인으로부터 분리하는 경계다. */
public interface MarketPriceProvider {
    MarketPriceSnapshot current();
}
