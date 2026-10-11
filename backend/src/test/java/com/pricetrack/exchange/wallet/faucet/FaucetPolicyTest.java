package com.pricetrack.exchange.wallet.faucet;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class FaucetPolicyTest {
    @Test void approvedValuesAndKstBoundary() {
        assertThat(FaucetPolicy.AMOUNT).isEqualByComparingTo("1000000");assertThat(FaucetPolicy.LIFETIME_COUNT).isEqualTo(5);assertThat(FaucetPolicy.BUDGET).isEqualByComparingTo("50000000");
        Instant before=Instant.parse("2026-10-10T14:59:00Z"),after=Instant.parse("2026-10-10T15:01:00Z");
        assertThat(FaucetPolicy.reason(1,FaucetPolicy.day(before),BigDecimal.ZERO,before)).isEqualTo("FAUCET_DAILY_LIMIT");
        assertThat(FaucetPolicy.reason(1,FaucetPolicy.day(before),BigDecimal.ZERO,after)).isNull();
        assertThat(FaucetPolicy.nextDay(before)).isEqualTo(Instant.parse("2026-10-10T15:00:00Z"));
    }
    @Test void lifetimeBudgetPartialAndReversedClock() {
        Instant now=Instant.parse("2026-10-11T00:00:00Z");
        assertThat(FaucetPolicy.reason(5,null,BigDecimal.ZERO,now)).isEqualTo("FAUCET_LIFETIME_LIMIT");
        assertThat(FaucetPolicy.reason(0,null,new BigDecimal("49500000"),now)).isEqualTo("FAUCET_BUDGET_EXHAUSTED");
        assertThat(FaucetPolicy.reason(0,null,new BigDecimal("49000000"),now)).isNull();
        assertThat(FaucetPolicy.reason(1,FaucetPolicy.day(now).plusDays(1),BigDecimal.ZERO,now)).isEqualTo("FAUCET_CLOCK_REVERSED");
    }
    @Test void keyIsBoundedFingerprintNotRaw() {
        assertThat(FaucetPolicy.key("test-key-12345678")).hasSize(64).doesNotContain("test-key");
        for(String key:new String[]{"","short","spaces are invalid","x".repeat(129)})assertThatThrownBy(()->FaucetPolicy.key(key)).isInstanceOf(FaucetFailure.class);
        assertThatThrownBy(()->FaucetPolicy.key(null)).isInstanceOf(FaucetFailure.class);
    }
}
