package com.pricetrack.exchange.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.config.PriceReportProperties;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.user.UserRole;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class QuoteControllerTest {
    @Test
    void signedQuoteResponseExposesClientFieldsButNotSignatureOrExecutor() throws Exception {
        MarketPriceService market = mock(MarketPriceService.class);
        TradeCalculator calculator = mock(TradeCalculator.class);
        PriceQuoteService quotes = mock(PriceQuoteService.class);
        QuoteController controller = new QuoteController(market, calculator,
                new BlockchainProperties(true, "rpc", "", "", "", "", ""),
                new PriceReportProperties(true, "secret-key-must-not-appear"), quotes);
        BigDecimal input = new BigDecimal("100000");
        when(quotes.issue(7L, "mSEC", PriceReport.Side.BUY, input)).thenReturn(quote());

        QuoteController.BuyQuoteResponse response = controller.buy(
                new AuthenticatedUser(7L, "user", UserRole.USER),
                new QuoteController.BuyQuoteRequest("mSEC", input));
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(response);

        assertThat(response.quoteId()).isEqualTo("0x" + "11".repeat(32));
        assertThat(response.status()).isEqualTo(PriceQuoteStatus.ISSUED);
        assertThat(json).contains("minimumOutputAmount", "validUntil")
                .doesNotContain("signature", "executor", "secret-key-must-not-appear");
    }

    private PriceQuote quote() {
        PriceQuote quote = new PriceQuote();
        quote.setQuoteId("0x" + "11".repeat(32));
        quote.setUserId(7L);
        quote.setSymbol("mSEC");
        quote.setSide(PriceReport.Side.BUY);
        quote.setPriceE8(BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8)));
        quote.setInputAmount(TokenUnits.toWei(new BigDecimal("100000")));
        quote.setMinimumOutput(new BigInteger("1320000000000000000"));
        quote.setFee(BigInteger.TEN);
        quote.setExecutor("0x2222222222222222222222222222222222222222");
        quote.setSignature("0x" + "aa".repeat(65));
        quote.setObservedAt(Instant.parse("2026-09-26T03:00:00Z"));
        quote.setValidUntil(Instant.parse("2026-09-26T03:00:30Z"));
        return quote;
    }
}
