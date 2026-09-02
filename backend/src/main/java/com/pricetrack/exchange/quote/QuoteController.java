package com.pricetrack.exchange.quote;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.market.PriceSimulator;
import com.pricetrack.exchange.blockchain.BlockchainProperties;
import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.PriceUnits;
import com.pricetrack.exchange.blockchain.TokenUnits;

/**
 * 견적 API (기획서 §12.3, §18.3 — 견적은 참고용, 실제 체결가는 온체인 실행 시점 오라클 가격).
 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final PriceSimulator priceSimulator;
    private final TradeCalculator tradeCalculator;
    private final BlockchainProperties blockchainProperties;
    private final BlockchainService blockchainService;

    public QuoteController(PriceSimulator priceSimulator, TradeCalculator tradeCalculator,
            BlockchainProperties blockchainProperties, BlockchainService blockchainService) {
        this.priceSimulator = priceSimulator;
        this.tradeCalculator = tradeCalculator;
        this.blockchainProperties = blockchainProperties;
        this.blockchainService = blockchainService;
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
        if (blockchainProperties.enabled()) {
            var quote = blockchainService.quoteBuy(TokenUnits.toWei(request.krwAmount()));
            BigDecimal price = PriceUnits.fromPriceE8(blockchainService.oraclePrice().priceE8());
            return new BuyQuoteResponse(request.symbol(), "BUY", price, request.krwAmount(),
                    TokenUnits.fromWei(quote.fee()), TokenUnits.fromWei(quote.outputAmount()));
        }
        BigDecimal price = priceSimulator.getCurrentPrice();
        TradeCalculator.BuyCalculation calculation = tradeCalculator.buy(request.krwAmount(), price);
        return new BuyQuoteResponse(request.symbol(), "BUY", price,
                request.krwAmount(), calculation.fee(), calculation.tokenAmount());
    }

    @PostMapping("/sell")
    public SellQuoteResponse sell(@RequestBody SellQuoteRequest request) {
        if (blockchainProperties.enabled()) {
            var quote = blockchainService.quoteSell(TokenUnits.toWei(request.tokenAmount()));
            BigDecimal price = PriceUnits.fromPriceE8(blockchainService.oraclePrice().priceE8());
            return new SellQuoteResponse(request.symbol(), "SELL", price, request.tokenAmount(),
                    TokenUnits.fromWei(quote.fee()), TokenUnits.fromWei(quote.outputAmount()));
        }
        BigDecimal price = priceSimulator.getCurrentPrice();
        TradeCalculator.SellCalculation calculation = tradeCalculator.sell(request.tokenAmount(), price);
        return new SellQuoteResponse(request.symbol(), "SELL", price,
                request.tokenAmount(), calculation.fee(), calculation.netKrw());
    }
}
