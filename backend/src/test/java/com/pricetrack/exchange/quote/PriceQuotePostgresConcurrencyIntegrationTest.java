package com.pricetrack.exchange.quote;

import static org.assertj.core.api.Assertions.assertThat;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.PriceReportIssuer;
import com.pricetrack.exchange.blockchain.support.TokenUnits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/** 실제 PostgreSQL의 비관적 행 잠금이 동일 견적의 이중 소비를 막는지 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/exchange}",
        "spring.datasource.username=${DB_USERNAME:exchange}",
        "spring.datasource.password=${DB_PASSWORD:exchange}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "app.blockchain.enabled=false",
        "app.blockchain.price-report.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "POSTGRES_INTEGRATION_TESTS", matches = "true")
class PriceQuotePostgresConcurrencyIntegrationTest {
    private static final Long USER_ID = 9_530_001L;
    private static final BigDecimal INPUT_AMOUNT = new BigDecimal("100000");
    private String quoteId;

    @Autowired PriceQuoteRepository repository;
    @Autowired PriceQuoteService service;
    @MockBean PriceReportIssuer issuer;

    @AfterEach
    void cleanUp() {
        if (quoteId != null) repository.deleteById(quoteId);
    }

    @Test
    void exactlyOneConcurrentConsumerCanUseTheSameQuote() throws Exception {
        quoteId = "0x" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        long firstOrderId = Math.abs(System.nanoTime());
        long secondOrderId = firstOrderId + 1;
        repository.saveAndFlush(quote());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Result>> results = List.of(
                    executor.submit(consumeAfterSignal(ready, start, firstOrderId)),
                    executor.submit(consumeAfterSignal(ready, start, secondOrderId)));

            ready.await();
            start.countDown();

            assertThat(results).extracting(this::resultOf)
                    .containsExactlyInAnyOrder(Result.CONSUMED, Result.REJECTED);
        }

        PriceQuote stored = repository.findById(quoteId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PriceQuoteStatus.CONSUMED);
        assertThat(stored.getOrderId()).isIn(firstOrderId, secondOrderId);
        assertThat(stored.getConsumedAt()).isNotNull();
    }

    private Callable<Result> consumeAfterSignal(CountDownLatch ready, CountDownLatch start, Long orderId) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                service.consume(USER_ID, quoteId, PriceReport.Side.BUY, INPUT_AMOUNT, orderId);
                return Result.CONSUMED;
            } catch (PriceQuoteUnavailableException exception) {
                return Result.REJECTED;
            }
        };
    }

    private Result resultOf(Future<Result> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("동시 견적 소비 결과를 확인하지 못했습니다.", exception);
        }
    }

    private PriceQuote quote() {
        Instant now = Instant.now();
        PriceQuote quote = new PriceQuote();
        quote.setQuoteId(quoteId);
        quote.setUserId(USER_ID);
        quote.setSymbol("mSEC");
        quote.setSide(PriceReport.Side.BUY);
        quote.setPriceE8(BigInteger.valueOf(75_300).multiply(BigInteger.TEN.pow(8)));
        quote.setInputAmount(TokenUnits.toWei(INPUT_AMOUNT));
        quote.setMinimumOutput(new BigInteger("1320000000000000000"));
        quote.setFee(BigInteger.TEN);
        quote.setExecutor("0x2222222222222222222222222222222222222222");
        quote.setSignature("0x" + "aa".repeat(65));
        quote.setObservedAt(now.minusSeconds(1));
        quote.setValidUntil(now.plusSeconds(60));
        quote.setStatus(PriceQuoteStatus.ISSUED);
        quote.setCreatedAt(now);
        return quote;
    }

    private enum Result {
        CONSUMED,
        REJECTED
    }
}
