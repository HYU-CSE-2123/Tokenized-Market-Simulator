package com.pricetrack.exchange.market.provider.kiwoom;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KiwoomRestClientTest {
    static KiwoomPriceProperties properties(){return new KiwoomPriceProperties("https://api.kiwoom.com",
        "wss://api.kiwoom.com:10000/api/dostk/websocket","fixture-app","fixture-secret",
        Duration.ofSeconds(2),Duration.ofSeconds(5),Duration.ofSeconds(15),Duration.ofSeconds(60));}
    final RestClient.Builder builder=RestClient.builder().baseUrl("https://api.kiwoom.com");
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final KiwoomRestClient client=new KiwoomRestClient(builder.build(),properties(),Clock.fixed(KiwoomProtocolTest.NOW,ZoneOffset.UTC));
    void token(String value){server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
        .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"grant_type\":\"client_credentials\",\"appkey\":\"fixture-app\",\"secretkey\":\"fixture-secret\"}"))
        .andRespond(withSuccess("{\"return_code\":0,\"token\":\""+value+"\",\"token_type\":\"bearer\",\"expires_dt\":\"20261014100000\"}",MediaType.APPLICATION_JSON));}
    @Test void cachesTokenAndParsesExpiryAsKst() {
        token("t1");assertThat(client.accessToken()).isEqualTo("t1");assertThat(client.accessToken()).isEqualTo("t1");
        assertThat(client.tokenExpiresAt()).isEqualTo(Instant.parse("2026-10-14T01:00:00Z"));server.verify();
    }
    @Test void retriesReadOnlyExpiredTokenOnceAndCarriesNativeHeaders() {
        token("t1");server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
            .andExpect(header("api-id","ka10001")).andExpect(header("authorization","Bearer t1"))
            .andRespond(withSuccess("{\"return_code\":8005}",MediaType.APPLICATION_JSON));
        token("t2");server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
            .andExpect(header("authorization","Bearer t2")).andExpect(content().json("{\"stk_cd\":\"005930\"}"))
            .andRespond(withSuccess("{\"return_code\":0,\"cur_prc\":\"-75000\"}",MediaType.APPLICATION_JSON));
        assertThat(client.currentPrice().body().path("cur_prc").asText()).isEqualTo("-75000");server.verify();
    }
    @Test void chartContinuationAndUnadjustedPriceAreExplicit() {
        token("t1");server.expect(requestTo("https://api.kiwoom.com/api/dostk/chart"))
            .andExpect(header("api-id","ka10080")).andExpect(header("cont-yn","Y")).andExpect(header("next-key","cursor"))
            .andExpect(content().json("{\"stk_cd\":\"005930\",\"base_dt\":\"20261013\",\"tic_scope\":\"60\",\"upd_stkpc_tp\":\"0\"}"))
            .andRespond(withSuccess("{\"return_code\":0}",MediaType.APPLICATION_JSON).header("cont-yn","Y").header("next-key","next"));
        var r=client.chart(false,60,"20261013","cursor");assertThat(r.more()).isTrue();assertThat(r.nextKey()).isEqualTo("next");server.verify();
    }
    @Test void sanitizesVendorFailureAndDoesNotRetryInvalidCredentials() {
        token("t1");server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
            .andRespond(withSuccess("{\"return_code\":8001,\"return_msg\":\"fixture-secret\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::currentPrice).isInstanceOf(KiwoomApiException.class).hasMessageNotContaining("fixture-secret");server.verify();
    }
    @Test void configRejectsHostMismatchAndRedactsSecrets() {
        properties().validate();assertThat(properties().toString()).doesNotContain("fixture-secret","fixture-app");
        var bad=new KiwoomPriceProperties("https://example.com",properties().websocketUrl(),"x","y",
            Duration.ofSeconds(2),Duration.ofSeconds(5),Duration.ofSeconds(15),Duration.ofSeconds(60));
        assertThatThrownBy(bad::validate).isInstanceOf(IllegalStateException.class);
    }
}
