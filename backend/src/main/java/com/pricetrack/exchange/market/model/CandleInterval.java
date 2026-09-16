package com.pricetrack.exchange.market.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CandleInterval {
    ONE_MINUTE("1m"),
    ONE_DAY("1d");

    private final String value;

    CandleInterval(String value) { this.value = value; }
    @JsonValue public String value() { return value; }

    public static CandleInterval parse(String value) {
        for (CandleInterval interval : values()) {
            if (interval.value.equals(value)) return interval;
        }
        throw new IllegalArgumentException("지원하는 캔들 주기는 1m과 1d입니다.");
    }
}
