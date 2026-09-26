package com.pricetrack.exchange.quote;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.config.PriceReportProperties;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.support.PriceUnits;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.market.MarketPriceService;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 모의 견적과 사용자에게 귀속된 온체인 서명 견적을 발급한다. */
@RestController
@RequestMapping("/api/quotes")
@Validated
public class QuoteController {
    private final MarketPriceService marketPriceService;
    private final TradeCalculator tradeCalculator;
    private final BlockchainProperties blockchainProperties;
    private final PriceReportProperties priceReportProperties;
    private final BlockchainService blockchainService;
    private final PriceQuoteService priceQuoteService;

    public QuoteController(MarketPriceService marketPriceService, TradeCalculator tradeCalculator,
            BlockchainProperties blockchainProperties, PriceReportProperties priceReportProperties,
            BlockchainService blockchainService, PriceQuoteService priceQuoteService) {
        this.marketPriceService = marketPriceService;
        this.tradeCalculator = tradeCalculator;
        this.blockchainProperties = blockchainProperties;
        this.priceReportProperties = priceReportProperties;
        this.blockchainService = blockchainService;
        this.priceQuoteService = priceQuoteService;
    }

    public record BuyQuoteRequest(@NotBlank String symbol,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal krwAmount) {}
    public record SellQuoteRequest(@NotBlank String symbol,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal tokenAmount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuyQuoteResponse(String symbol, String side, BigDecimal price,
            BigDecimal inputAmount, BigDecimal fee, BigDecimal expectedTokenAmount,
            String quoteId, BigDecimal minimumOutputAmount, Instant observedAt,
            Instant validUntil, PriceQuoteStatus status) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SellQuoteResponse(String symbol, String side, BigDecimal price,
            BigDecimal inputAmount, BigDecimal fee, BigDecimal expectedKrwAmount,
            String quoteId, BigDecimal minimumOutputAmount, Instant observedAt,
            Instant validUntil, PriceQuoteStatus status) {}

    @PostMapping("/buy")
    public BuyQuoteResponse buy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BuyQuoteRequest request) {
        if (signedQuotesEnabled()) {
            PriceQuote quote = priceQuoteService.issue(
                    user.userId(), request.symbol(), PriceReport.Side.BUY, request.krwAmount());
            BigDecimal output = TokenUnits.fromWei(quote.getMinimumOutput());
            return new BuyQuoteResponse(quote.getSymbol(), "BUY", PriceUnits.fromPriceE8(quote.getPriceE8()),
                    request.krwAmount(), TokenUnits.fromWei(quote.getFee()), output,
                    quote.getQuoteId(), output, quote.getObservedAt(), quote.getValidUntil(), quote.getStatus());
        }
        if (blockchainProperties.enabled()) {
            var quote = blockchainService.quoteBuy(TokenUnits.toWei(request.krwAmount()));
            BigDecimal price = PriceUnits.fromPriceE8(blockchainService.oraclePrice().priceE8());
            return new BuyQuoteResponse(request.symbol(), "BUY", price, request.krwAmount(),
                    TokenUnits.fromWei(quote.fee()), TokenUnits.fromWei(quote.outputAmount()),
                    null, null, null, null, null);
        }
        BigDecimal price = marketPriceService.currentPrice();
        TradeCalculator.BuyCalculation calculation = tradeCalculator.buy(request.krwAmount(), price);
        return new BuyQuoteResponse(request.symbol(), "BUY", price, request.krwAmount(),
                calculation.fee(), calculation.tokenAmount(), null, null, null, null, null);
    }

    @PostMapping("/sell")
    public SellQuoteResponse sell(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SellQuoteRequest request) {
        if (signedQuotesEnabled()) {
            PriceQuote quote = priceQuoteService.issue(
                    user.userId(), request.symbol(), PriceReport.Side.SELL, request.tokenAmount());
            BigDecimal output = TokenUnits.fromWei(quote.getMinimumOutput());
            return new SellQuoteResponse(quote.getSymbol(), "SELL", PriceUnits.fromPriceE8(quote.getPriceE8()),
                    request.tokenAmount(), TokenUnits.fromWei(quote.getFee()), output,
                    quote.getQuoteId(), output, quote.getObservedAt(), quote.getValidUntil(), quote.getStatus());
        }
        if (blockchainProperties.enabled()) {
            var quote = blockchainService.quoteSell(TokenUnits.toWei(request.tokenAmount()));
            BigDecimal price = PriceUnits.fromPriceE8(blockchainService.oraclePrice().priceE8());
            return new SellQuoteResponse(request.symbol(), "SELL", price, request.tokenAmount(),
                    TokenUnits.fromWei(quote.fee()), TokenUnits.fromWei(quote.outputAmount()),
                    null, null, null, null, null);
        }
        BigDecimal price = marketPriceService.currentPrice();
        TradeCalculator.SellCalculation calculation = tradeCalculator.sell(request.tokenAmount(), price);
        return new SellQuoteResponse(request.symbol(), "SELL", price, request.tokenAmount(),
                calculation.fee(), calculation.netKrw(), null, null, null, null, null);
    }

    private boolean signedQuotesEnabled() {
        return blockchainProperties.enabled() && priceReportProperties.enabled();
    }
}
