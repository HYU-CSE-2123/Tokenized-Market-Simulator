package com.pricetrack.exchange.market;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

/** 공급자 이름을 열거하지 않고 서버 스냅샷의 실제/합성 및 시장 품질을 구분한다. */
public final class MarketDataEligibility {
    private MarketDataEligibility() {}

    public static boolean synthetic(MarketPriceSnapshot snapshot) {
        return "SIMULATED".equalsIgnoreCase(snapshot.provider());
    }

    public static boolean liveExternal(MarketPriceSnapshot snapshot) {
        return !synthetic(snapshot) && snapshot.marketStatus() == MarketStatus.OPEN
                && snapshot.priceStatus() == PriceStatus.LIVE;
    }
}
