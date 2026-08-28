package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class TokenUnitsTest {
    @Test
    void convertsTokenAmountToWeiAndBack() {
        BigDecimal amount = new BigDecimal("9.990000000000000001");
        BigInteger wei = TokenUnits.toWei(amount);
        assertThat(wei).isEqualTo(new BigInteger("9990000000000000001"));
        assertThat(TokenUnits.fromWei(wei)).isEqualByComparingTo(amount);
    }

    @Test
    void rejectsMoreThanEighteenDecimals() {
        assertThatThrownBy(() -> TokenUnits.toWei(new BigDecimal("0.0000000000000000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
