package com.pricetrack.exchange.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.market.PriceSimulator;

/**
 * 견적 API (기획서 §12.3, §18.3 — 견적은 참고용, 실제 체결가는 온체인 실행 시점 오라클 가격).
 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    // 수수료율 0.1% (= 컨트랙트 ExchangeVault.feeBps 10 과 일치)
    private static final BigDecimal FEE_RATE = new BigDecimal("0.001");

    private final PriceSimulator priceSimulator;

    public QuoteController(PriceSimulator priceSimulator) {
        this.priceSimulator = priceSimulator;
    }

    public record BuyQuoteRequest(String symbol, BigDecimal krwAmount) {}

    public record SellQuoteRequest(String symbol, BigDecimal tokenAmount) {}

    public record BuyQuoteResponse(String symbol, String side, BigDecimal price,
                                   BigDecimal inputAmount, BigDecimal fee,
                                   BigDecimal expectedTokenAmount) {}

    public record SellQuoteResponse(String symbol, String side, BigDecimal price,
                                    BigDecimal inputAmount, BigDecimal fee,
                                    BigDecimal expectedKrwAmount) {}

    @PostMapping("/buy")
    public BuyQuoteResponse buy(@RequestBody BuyQuoteRequest request) {
        BigDecimal price = priceSimulator.getCurrentPrice();
        BigDecimal fee = request.krwAmount().multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);
        BigDecimal net = request.krwAmount().subtract(fee);
        BigDecimal tokens = net.divide(price, 8, RoundingMode.HALF_UP);
        return new BuyQuoteResponse(request.symbol(), "BUY", price,
                request.krwAmount(), fee, tokens);
    }

    @PostMapping("/sell")
    public SellQuoteResponse sell(@RequestBody SellQuoteRequest request) {
        BigDecimal price = priceSimulator.getCurrentPrice();
        BigDecimal gross = request.tokenAmount().multiply(price).setScale(8, RoundingMode.HALF_UP);
        BigDecimal fee = gross.multiply(FEE_RATE).setScale(8, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(fee);
        return new SellQuoteResponse(request.symbol(), "SELL", price,
                request.tokenAmount(), fee, net);
    }
}
