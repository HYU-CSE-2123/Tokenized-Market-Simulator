package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossAuthClientTest {
    @Test
    void cachesValidTokenInsteadOfIssuingAnotherOne() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openapi.test/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=client")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=secret")))
                .andRespond(withSuccess("""
                        {"access_token":"token-1","token_type":"Bearer","expires_in":86400}
                        """, MediaType.APPLICATION_JSON));
        TossAuthClient client = new TossAuthClient(builder.build(), properties(),
                Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC));

        assertThat(client.accessToken()).isEqualTo("token-1");
        assertThat(client.accessToken()).isEqualTo("token-1");
        server.verify();
    }

    @Test
    void invalidatedTokenIsIssuedAgain() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://openapi.test/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"token-1","token_type":"Bearer","expires_in":86400}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openapi.test/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"token-2","token_type":"Bearer","expires_in":86400}
                        """, MediaType.APPLICATION_JSON));
        TossAuthClient client = new TossAuthClient(builder.build(), properties());

        String first = client.accessToken();
        client.invalidate(first);

        assertThat(client.accessToken()).isEqualTo("token-2");
        server.verify();
    }

    private TossPriceProperties properties() {
        return new TossPriceProperties("https://openapi.test", "client", "secret", "005930",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
