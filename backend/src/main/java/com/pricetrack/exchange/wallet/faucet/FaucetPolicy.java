package com.pricetrack.exchange.wallet.faucet;

import java.math.BigDecimal;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Approved synthetic DB-credit policy; not a source of on-chain funds. */
public final class FaucetPolicy {
    private FaucetPolicy() {}
    public static final BigDecimal AMOUNT = new BigDecimal("1000000.000000000000000000");
    public static final int LIFETIME_COUNT = 5;
    public static final BigDecimal BUDGET = new BigDecimal("50000000");
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final String CONTENT = "amount=1000000;daily=1;lifetime=5;budget=50000000;zone=Asia/Seoul;cooldown=none;periodBudget=none";
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Fingerprint unavailable"); }
    }
    public static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) throw new FaucetFailure(400,"FAUCET_INVALID_KEY");
        return hash("wallet/faucet:"+value);
    }
    public static LocalDate day(Instant value) { return value.atZone(ZONE).toLocalDate(); }
    public static Instant nextDay(Instant value) { return day(value).plusDays(1).atStartOfDay(ZONE).toInstant(); }
    public static String reason(long count, LocalDate lastDay, BigDecimal spent, Instant at) {
        if (lastDay != null && day(at).isBefore(lastDay)) return "FAUCET_CLOCK_REVERSED";
        if (count >= LIFETIME_COUNT) return "FAUCET_LIFETIME_LIMIT";
        if (spent.add(AMOUNT).compareTo(BUDGET)>0) return "FAUCET_BUDGET_EXHAUSTED";
        if (day(at).equals(lastDay)) return "FAUCET_DAILY_LIMIT";
        return null;
    }
}
