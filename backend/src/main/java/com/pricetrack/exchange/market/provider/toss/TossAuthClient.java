package com.pricetrack.exchange.market.provider.toss;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Client Credentials 토큰을 하나만 유지해 불필요한 재발급과 기존 토큰 무효화를 막는다. */
public class TossAuthClient {
    private static final long REFRESH_SAFETY_SECONDS = 60;

    private final RestClient restClient;
    private final TossPriceProperties properties;
    private final Clock clock;
    private CachedToken cachedToken;

    public TossAuthClient(RestClient restClient, TossPriceProperties properties) {
        this(restClient, properties, Clock.systemUTC());
    }

    TossAuthClient(RestClient restClient, TossPriceProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedToken.refreshAt())) {
            return cachedToken.value();
        }
        TokenResponse response = issueToken();
        long refreshAfter = response.expiresIn() > REFRESH_SAFETY_SECONDS
                ? response.expiresIn() - REFRESH_SAFETY_SECONDS
                : Math.max(1, response.expiresIn() / 2);
        cachedToken = new CachedToken(response.accessToken(), now.plusSeconds(refreshAfter));
        return cachedToken.value();
    }

    public synchronized void invalidate(String rejectedToken) {
        if (cachedToken != null && cachedToken.value().equals(rejectedToken)) {
            cachedToken = null;
        }
    }

    private TokenResponse issueToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        TokenResponse response;
        try {
            response = restClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RuntimeException exception) {
            throw new TossApiException("토스증권 액세스 토큰 발급에 실패했습니다.", exception);
        }
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() <= 0
                || !"Bearer".equalsIgnoreCase(response.tokenType())) {
            throw new TossApiException("토스증권 액세스 토큰 응답이 올바르지 않습니다.");
        }
        return response;
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {}

    private record CachedToken(String value, Instant refreshAt) {}
}
