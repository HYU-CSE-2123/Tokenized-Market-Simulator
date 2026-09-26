package com.pricetrack.exchange.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.PriceReportIssuer;
import com.pricetrack.exchange.blockchain.oracle.SignedPriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:signed-quotes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.blockchain.enabled=true",
        "app.blockchain.price-report.enabled=true"
})
@AutoConfigureMockMvc
class SignedQuoteIntegrationTest {
    private static final String QUOTE_ID = "0x" + "11".repeat(32);
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-26T03:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PriceQuoteRepository repository;
    @Autowired PriceQuoteService quoteService;
    @MockBean PriceReportIssuer issuer;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        BigInteger input = TokenUnits.toWei(new BigDecimal("100000"));
        PriceReport report = new PriceReport(QUOTE_ID, "0x" + "22".repeat(32),
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8)),
                BigInteger.valueOf(OBSERVED_AT.getEpochSecond()),
                BigInteger.valueOf(OBSERVED_AT.plusSeconds(30).getEpochSecond()),
                PriceReport.Side.BUY, input, new BigInteger("1320000000000000000"),
                "0x2222222222222222222222222222222222222222");
        when(issuer.issue(eq(PriceReport.Side.BUY), any()))
                .thenReturn(new SignedPriceReport(report, "0x" + "aa".repeat(65), BigInteger.TEN));
    }

    @Test
    void authenticatedUserReceivesOwnedQuoteWithoutSignature() throws Exception {
        String token = signup();

        String body = mockMvc.perform(post("/api/quotes/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").value(QUOTE_ID))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.minimumOutputAmount").value(1.32))
                .andExpect(jsonPath("$.signature").doesNotExist())
                .andExpect(jsonPath("$.executor").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("aa".repeat(65), "2222222222222222222222222222222222222222");
        PriceQuote stored = repository.findById(QUOTE_ID).orElseThrow();
        assertThat(stored.getSignature()).startsWith("0x").hasSize(132);
        assertThat(stored.getUserId()).isPositive();
    }

    @Test
    void quoteEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/quotes/buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredStatusCommitsWhenConsumptionIsRejected() throws Exception {
        String token = signup();
        mockMvc.perform(post("/api/quotes/buy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"mSEC\",\"krwAmount\":\"100000\"}"))
                .andExpect(status().isOk());
        PriceQuote stored = repository.findById(QUOTE_ID).orElseThrow();

        assertThatThrownBy(() -> quoteService.consume(stored.getUserId(), QUOTE_ID,
                PriceReport.Side.BUY, new BigDecimal("100000"), 99L))
                .isInstanceOf(PriceQuoteUnavailableException.class).hasMessageContaining("만료");
        assertThat(repository.findById(QUOTE_ID).orElseThrow().getStatus())
                .isEqualTo(PriceQuoteStatus.EXPIRED);
    }

    private String signup() throws Exception {
        String loginId = "quote" + System.nanoTime();
        String response = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId
                                + "\",\"password\":\"password123\",\"nickname\":\"Quote\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
