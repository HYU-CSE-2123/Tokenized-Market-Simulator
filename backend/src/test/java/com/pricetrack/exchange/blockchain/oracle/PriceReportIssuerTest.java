package com.pricetrack.exchange.blockchain.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.config.PriceReportProperties;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.utils.Numeric;

class PriceReportIssuerTest {
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:05Z");
    private static final String ORACLE = "0x1111111111111111111111111111111111111111";
    private static final String OPERATOR = "0x2222222222222222222222222222222222222222";
    private static final BigInteger INPUT = BigInteger.valueOf(100_000).multiply(BigInteger.TEN.pow(18));
    private static final BigInteger OUTPUT = new BigInteger("1320000000000000000");

    private final MarketPriceService prices = mock(MarketPriceService.class);
    private final BlockchainService blockchain = mock(BlockchainService.class);
    private final PriceReportSigner signer = new PriceReportSigner(new PriceReportProperties(true, "a11ce"));
    private PriceReportIssuer issuer;

    @BeforeEach
    void setUp() {
        issuer = new PriceReportIssuer(prices, blockchain, signer, Clock.fixed(NOW, ZoneOffset.UTC));
        when(blockchain.chainId()).thenReturn(BigInteger.valueOf(31_337));
        when(blockchain.oracleAddress()).thenReturn(ORACLE);
        when(blockchain.operatorAddress()).thenReturn(OPERATOR);
        when(blockchain.quoteAtPrice(PriceReport.Side.BUY, INPUT,
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8))))
                .thenReturn(new ContractGateway.Quote(OUTPUT, BigInteger.ONE));
    }

    @Test
    void issuesSignedReportFromFreshSnapshotAndOnChainQuote() {
        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW.minusSeconds(5)));

        SignedPriceReport result = issuer.issue(PriceReport.Side.BUY, INPUT);

        PriceReport report = result.report();
        assertThat(report.symbolHash()).isEqualTo(PriceReportEip712.hashText("mSEC"));
        assertThat(report.priceE8()).isEqualTo("7530000000000");
        assertThat(report.observedAt()).isEqualTo(BigInteger.valueOf(NOW.minusSeconds(5).getEpochSecond()));
        assertThat(report.validUntil().subtract(report.observedAt())).isEqualTo("30");
        assertThat(report.minimumOutput()).isEqualTo(OUTPUT);
        assertThat(report.executor()).isEqualTo(OPERATOR);
        assertThat(Numeric.hexStringToByteArray(result.signature())).hasSize(65);
    }

    @Test
    void issuesSellReportUsingSellQuote() {
        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW));
        when(blockchain.quoteAtPrice(PriceReport.Side.SELL, INPUT,
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8))))
                .thenReturn(new ContractGateway.Quote(OUTPUT, BigInteger.ONE));

        PriceReport report = issuer.issue(PriceReport.Side.SELL, INPUT).report();

        assertThat(report.side()).isEqualTo(PriceReport.Side.SELL);
        assertThat(report.minimumOutput()).isEqualTo(OUTPUT);
    }

    @Test
    void rejectsSnapshotOlderThanFiveSeconds() {
        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW.minusSeconds(6)));
        assertThatThrownBy(() -> issuer.issue(PriceReport.Side.BUY, INPUT))
                .isInstanceOf(PriceReportIssuanceException.class)
                .hasMessageContaining("5초");
    }

    @Test
    void acceptsTwoSecondFutureBoundaryButRejectsBeyondIt() {
        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW.plusSeconds(2)));
        assertThat(issuer.issue(PriceReport.Side.BUY, INPUT).report().observedAt())
                .isEqualTo(BigInteger.valueOf(NOW.plusSeconds(2).getEpochSecond()));

        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW.plusSeconds(3)));
        assertThatThrownBy(() -> issuer.issue(PriceReport.Side.BUY, INPUT))
                .isInstanceOf(PriceReportIssuanceException.class)
                .hasMessageContaining("미래");
    }

    @Test
    void rejectsUnsupportedSymbolAndZeroOutput() {
        when(prices.requireTradableSnapshot()).thenReturn(snapshot("OTHER", NOW));
        assertThatThrownBy(() -> issuer.issue(PriceReport.Side.BUY, INPUT))
                .isInstanceOf(PriceReportIssuanceException.class)
                .hasMessageContaining("지원하지 않는");

        when(prices.requireTradableSnapshot()).thenReturn(snapshot(NOW));
        when(blockchain.quoteAtPrice(PriceReport.Side.BUY, INPUT,
                BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8))))
                .thenReturn(new ContractGateway.Quote(BigInteger.ZERO, BigInteger.ONE));
        assertThatThrownBy(() -> issuer.issue(PriceReport.Side.BUY, INPUT))
                .isInstanceOf(PriceReportIssuanceException.class)
                .hasMessageContaining("최소 수령량");
    }

    @Test
    void validatesDedicatedSignerAgainstOracle() {
        when(blockchain.oraclePriceSigner()).thenReturn(signer.signerAddress());
        issuer.validateSignerConfiguration();

        when(blockchain.oraclePriceSigner()).thenReturn(OPERATOR);
        assertThatThrownBy(issuer::validateSignerConfiguration)
                .isInstanceOf(PriceReportIssuanceException.class)
                .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    void rejectsInvalidRequestBeforeReadingMarket() {
        assertThatThrownBy(() -> issuer.issue(null, INPUT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issue(PriceReport.Side.BUY, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MarketPriceSnapshot snapshot(Instant observedAt) {
        return snapshot("mSEC", observedAt);
    }

    private MarketPriceSnapshot snapshot(String symbol, Instant observedAt) {
        return new MarketPriceSnapshot(symbol, new BigDecimal("75300"), new BigDecimal("75000"),
                new BigDecimal("300"), new BigDecimal("0.4"), MarketStatus.OPEN, PriceStatus.LIVE,
                "TOSS", observedAt);
    }
}
