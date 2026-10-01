package com.pricetrack.exchange.ai.tool;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.trade.Trade;
import java.math.*;
import java.time.Instant;

/** Deterministic records with secret canaries; no quote issuance, chain writes or signing keys. */
public final class ToolFixtures {
    public static final String QUOTE = "0x" + "aa".repeat(32);
    public static final String HASH = "0x" + "bb".repeat(32);
    public static final String ADDRESS = "0x" + "11".repeat(20);
    public static Order order(long user) {
        Order o = new Order(); o.setUserId(user); o.setSymbol("mSEC"); o.setSide(OrderSide.BUY);
        o.setInputAmount(new BigDecimal("75000")); o.setExpectedOutputAmount(new BigDecimal("0.999"));
        o.setStatus(OrderStatus.PENDING_ONCHAIN); o.setTxHash(HASH); o.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return o;
    }
    public static PriceQuote quote(long user, long order) {
        PriceQuote q = new PriceQuote(); q.setQuoteId(QUOTE); q.setUserId(user); q.setOrderId(order);
        q.setSymbol("mSEC"); q.setSide(PriceReport.Side.BUY); q.setInputAmount(TokenUnits.toWei(new BigDecimal("75000")));
        q.setMinimumOutput(TokenUnits.toWei(new BigDecimal("0.999"))); q.setPriceE8(new BigInteger("7500000000000"));
        q.setFee(TokenUnits.toWei(new BigDecimal("75"))); q.setExecutor(ADDRESS); q.setSignature("0x" + "cc".repeat(65));
        q.setObservedAt(Instant.parse("2026-09-01T00:00:00Z")); q.setValidUntil(q.getObservedAt().plusSeconds(30));
        q.setCreatedAt(q.getObservedAt()); return q;
    }
    public static BlockchainTransaction tx(long order) {
        BlockchainTransaction t = new BlockchainTransaction(); t.setOrderId(order); t.setTxHash(HASH);
        t.setType(BlockchainTransactionType.BUY); t.setStatus(BlockchainTransactionStatus.REVIEW_REQUIRED);
        t.setSenderAddress(ADDRESS); t.setNonce(10L); t.setRawTransaction("SENSITIVE_RAW_TRANSACTION");
        t.setErrorMessage("SENSITIVE_RPC_ERROR jwt=SECRET password=SECRET"); t.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return t;
    }
    public static Trade trade(long user, long order) {
        Trade t = new Trade(); t.setUserId(user); t.setOrderId(order); t.setSymbol("mSEC"); t.setSide(OrderSide.BUY);
        t.setPrice(new BigDecimal("75000")); t.setBaseAmount(new BigDecimal("0.999"));
        t.setQuoteAmount(new BigDecimal("75000")); t.setFee(new BigDecimal("75")); t.setTxHash(HASH); return t;
    }
}
