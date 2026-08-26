package com.pricetrack.exchange.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class TradeCalculatorTest {
    private final TradeCalculator calculator = new TradeCalculator();

    @Test
    void calculatesBuyWithPointOnePercentFee() {
        var result = calculator.buy(new BigDecimal("750000"), new BigDecimal("75000"));

        assertThat(result.fee()).isEqualByComparingTo("750");
        assertThat(result.tokenAmount()).isEqualByComparingTo("9.99");
    }

    @Test
    void calculatesSellWithPointOnePercentFee() {
        var result = calculator.sell(new BigDecimal("10"), new BigDecimal("80000"));

        assertThat(result.grossKrw()).isEqualByComparingTo("800000");
        assertThat(result.fee()).isEqualByComparingTo("800");
        assertThat(result.netKrw()).isEqualByComparingTo("799200");
    }
}
