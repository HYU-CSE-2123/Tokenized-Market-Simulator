package com.pricetrack.exchange.quote;

import java.math.BigDecimal;

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

    private final PriceSimulator priceSimulator;
    private final TradeCalculator tradeCalculator;

    public QuoteController(PriceSimulator priceSimulator, TradeCalculator tradeCalculator) {
        this.priceSimulator = priceSimulator;
        this.tradeCalculator = tradeCalculator;
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
        TradeCalculator.BuyCalculation calculation = tradeCalculator.buy(request.krwAmount(), price);
        return new BuyQuoteResponse(request.symbol(), "BUY", price,
                request.krwAmount(), calculation.fee(), calculation.tokenAmount());
    }

    @PostMapping("/sell")
    public SellQuoteResponse sell(@RequestBody SellQuoteRequest request) {
        BigDecimal price = priceSimulator.getCurrentPrice();
        TradeCalculator.SellCalculation calculation = tradeCalculator.sell(request.tokenAmount(), price);
        return new SellQuoteResponse(request.symbol(), "SELL", price,
                request.tokenAmount(), calculation.fee(), calculation.netKrw());
    }
}
