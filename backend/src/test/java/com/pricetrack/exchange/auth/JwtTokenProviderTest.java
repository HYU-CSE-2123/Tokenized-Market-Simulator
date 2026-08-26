package com.pricetrack.exchange.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32bytes";

    @Test
    void createsAndParsesToken() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3_600_000);

        String token = provider.createToken(42L, "kyobin21");

        assertThat(provider.getUserId(token)).isEqualTo(42L);
        assertThat(provider.getValiditySeconds()).isEqualTo(3600L);
    }

    @Test
    void rejectsTamperedToken() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3_600_000);
        JwtTokenProvider providerWithDifferentSecret = new JwtTokenProvider(
                "another-test-secret-another-test-secret-32bytes", 3_600_000);
        String token = provider.createToken(42L, "kyobin21");

        assertThatThrownBy(() -> providerWithDifferentSecret.getUserId(token))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, -1_000);
        String token = provider.createToken(42L, "kyobin21");

        assertThatThrownBy(() -> provider.getUserId(token))
                .isInstanceOf(RuntimeException.class);
    }
}
