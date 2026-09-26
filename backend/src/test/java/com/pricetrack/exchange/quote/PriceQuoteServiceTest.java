package com.pricetrack.exchange.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.PriceReportIssuer;
import com.pricetrack.exchange.blockchain.oracle.SignedPriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceQuoteServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");
    private static final String QUOTE_ID = "0x" + "11".repeat(32);
    private static final BigDecimal INPUT = new BigDecimal("100000");
    private static final BigInteger INPUT_WEI = TokenUnits.toWei(INPUT);

    private final PriceReportIssuer issuer = mock(PriceReportIssuer.class);
    private final PriceQuoteRepository repository = mock(PriceQuoteRepository.class);
    private PriceQuoteService service;

    @BeforeEach
    void setUp() {
        service = new PriceQuoteService(issuer, repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issuesAndPersistsQuoteForAuthenticatedUser() {
        when(issuer.issue(PriceReport.Side.BUY, INPUT_WEI)).thenReturn(signedReport());

        PriceQuote quote = service.issue(7L, "mSEC", PriceReport.Side.BUY, INPUT);

        assertThat(quote.getQuoteId()).isEqualTo(QUOTE_ID);
        assertThat(quote.getUserId()).isEqualTo(7L);
        assertThat(quote.getSignature()).isEqualTo("0x" + "aa".repeat(65));
        assertThat(quote.getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
        assertThat(quote.getCreatedAt()).isEqualTo(NOW);
        verify(repository).save(quote);
    }

    @Test
    void consumesOwnedMatchingQuoteOnlyOnce() {
        PriceQuote quote = quote(7L, NOW.plusSeconds(20));
        when(repository.findForUpdateByQuoteId(QUOTE_ID)).thenReturn(Optional.of(quote));

        PriceQuote consumed = service.consume(7L, QUOTE_ID, PriceReport.Side.BUY, INPUT, 99L);

        assertThat(consumed.getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);
        assertThat(consumed.getConsumedAt()).isEqualTo(NOW);
        assertThat(consumed.getOrderId()).isEqualTo(99L);
        assertThatThrownBy(() -> service.consume(7L, QUOTE_ID, PriceReport.Side.BUY, INPUT, 100L))
                .isInstanceOf(PriceQuoteUnavailableException.class)
                .hasMessageContaining("이미 사용");
    }

    @Test
    void hidesForeignQuoteAsNotFound() {
        when(repository.findForUpdateByQuoteId(QUOTE_ID)).thenReturn(Optional.of(quote(8L, NOW.plusSeconds(20))));
        assertThatThrownBy(() -> service.consume(7L, QUOTE_ID, PriceReport.Side.BUY, INPUT, 99L))
                .isInstanceOf(PriceQuoteNotFoundException.class);
    }

    @Test
    void marksExpiredQuoteAndRejectsConsumption() {
        PriceQuote quote = quote(7L, NOW.minusSeconds(1));
        when(repository.findForUpdateByQuoteId(QUOTE_ID)).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> service.consume(7L, QUOTE_ID, PriceReport.Side.BUY, INPUT, 99L))
                .isInstanceOf(PriceQuoteUnavailableException.class)
                .hasMessageContaining("만료");
        assertThat(quote.getStatus()).isEqualTo(PriceQuoteStatus.EXPIRED);
    }

    @Test
    void acceptsQuoteExactlyAtValidityBoundary() {
        PriceQuote quote = quote(7L, NOW);
        when(repository.findForUpdateByQuoteId(QUOTE_ID)).thenReturn(Optional.of(quote));
        assertThat(service.consume(7L, QUOTE_ID, PriceReport.Side.BUY, INPUT, 99L).getStatus())
                .isEqualTo(PriceQuoteStatus.CONSUMED);
    }

    @Test
    void rejectsSideAndInputMismatchWithoutConsuming() {
        PriceQuote quote = quote(7L, NOW.plusSeconds(20));
        when(repository.findForUpdateByQuoteId(QUOTE_ID)).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> service.consume(7L, QUOTE_ID, PriceReport.Side.SELL, INPUT, 99L))
                .isInstanceOf(PriceQuoteUnavailableException.class).hasMessageContaining("방향");
        assertThatThrownBy(() -> service.consume(7L, QUOTE_ID, PriceReport.Side.BUY,
                new BigDecimal("99999"), 99L))
                .isInstanceOf(PriceQuoteUnavailableException.class).hasMessageContaining("수량");
        assertThat(quote.getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
    }

    private SignedPriceReport signedReport() {
        PriceReport report = new PriceReport(QUOTE_ID, "0x" + "22".repeat(32),
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8)),
                BigInteger.valueOf(NOW.minusSeconds(2).getEpochSecond()),
                BigInteger.valueOf(NOW.plusSeconds(28).getEpochSecond()),
                PriceReport.Side.BUY, INPUT_WEI, new BigInteger("1320000000000000000"),
                "0x2222222222222222222222222222222222222222");
        return new SignedPriceReport(report, "0x" + "aa".repeat(65), BigInteger.TEN);
    }

    private PriceQuote quote(Long userId, Instant validUntil) {
        PriceQuote quote = new PriceQuote();
        quote.setQuoteId(QUOTE_ID);
        quote.setUserId(userId);
        quote.setSide(PriceReport.Side.BUY);
        quote.setInputAmount(INPUT_WEI);
        quote.setStatus(PriceQuoteStatus.ISSUED);
        quote.setValidUntil(validUntil);
        return quote;
    }
}
