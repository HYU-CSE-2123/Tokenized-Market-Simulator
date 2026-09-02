package com.pricetrack.exchange.blockchain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class PriceUnitsTest {
    @Test
    void convertsPriceToE8AndBack() {
        BigDecimal price = new BigDecimal("75000.25000000");
        BigInteger priceE8 = PriceUnits.toPriceE8(price);
        assertThat(priceE8).isEqualTo(new BigInteger("7500025000000"));
        assertThat(PriceUnits.fromPriceE8(priceE8)).isEqualByComparingTo(price);
    }

    @Test
    void rejectsInvalidPrecisionAndNonPositivePrice() {
        assertThatThrownBy(() -> PriceUnits.toPriceE8(new BigDecimal("1.000000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceUnits.toPriceE8(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
