package com.pricetrack.exchange.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

@Component
public class TradeCalculator {
    public static final BigDecimal FEE_RATE = new BigDecimal("0.001");
    private static final int AMOUNT_SCALE = 18;

    public BuyCalculation buy(BigDecimal krwAmount, BigDecimal price) {
        BigDecimal fee = krwAmount.multiply(FEE_RATE).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal tokens = krwAmount.subtract(fee)
                .divide(price, AMOUNT_SCALE, RoundingMode.DOWN);
        return new BuyCalculation(fee, tokens);
    }

    public SellCalculation sell(BigDecimal tokenAmount, BigDecimal price) {
        BigDecimal gross = tokenAmount.multiply(price).setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        BigDecimal fee = gross.multiply(FEE_RATE).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new SellCalculation(gross, fee, gross.subtract(fee));
    }

    public record BuyCalculation(BigDecimal fee, BigDecimal tokenAmount) {}
    public record SellCalculation(BigDecimal grossKrw, BigDecimal fee, BigDecimal netKrw) {}
}
