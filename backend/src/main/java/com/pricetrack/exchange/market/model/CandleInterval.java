package com.pricetrack.exchange.market.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CandleInterval {
    ONE_MINUTE("1m", 1, 200),
    FIVE_MINUTES("5m", 5, 100),
    FIFTEEN_MINUTES("15m", 15, 50),
    THIRTY_MINUTES("30m", 30, 30),
    ONE_HOUR("1h", 60, 20),
    ONE_DAY("1d", 1_440, 200);

    private final String value;
    private final int minutes;
    private final int maxCount;

    CandleInterval(String value, int minutes, int maxCount) {
        this.value = value;
        this.minutes = minutes;
        this.maxCount = maxCount;
    }
    @JsonValue public String value() { return value; }
    public int minutes() { return minutes; }
    public int maxCount() { return maxCount; }
    public boolean isAggregatedIntraday() {
        return this != ONE_MINUTE && this != ONE_DAY;
    }

    public static CandleInterval parse(String value) {
        for (CandleInterval interval : values()) {
            if (interval.value.equals(value)) return interval;
        }
        throw new IllegalArgumentException("지원하는 캔들 주기는 1m, 5m, 15m, 30m, 1h, 1d입니다.");
    }
}
