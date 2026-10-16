package com.pricetrack.exchange.market.provider.kiwoom;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.market.model.MarketStatus;
import java.time.*;
import org.junit.jupiter.api.Test;

class KiwoomProtocolTest {
    static final Instant NOW=Instant.parse("2026-10-13T01:00:00Z");
    final KiwoomProtocol p=new KiwoomProtocol(new ObjectMapper(),Clock.fixed(NOW,ZoneOffset.UTC));
    static String tick(String time,String cumulative) {return """
        {"type":"0B","item":"005930","values":{"10":"-75000","11":"-100","12":"-0.13",
        "15":"-2","13":"%s","20":"%s","290":"2"}}
        """.formatted(cumulative,time);}
    @Test void signedPriceAndVolumeAreMagnitudesButChangeIsSigned() {
        var t=p.trade(p.parse(tick("100000","1234")));
        assertThat(t.price()).isEqualByComparingTo("75000");assertThat(t.volume()).isEqualByComparingTo("2");
        assertThat(t.change()).isEqualByComparingTo("-100");assertThat(t.observedAt()).isEqualTo(NOW);
        assertThat(t.regular()).isTrue();
    }
    @Test void rejectsStaleFutureMalformedAndOtherMarkets() {
        for(String time:new String[]{"095954","100003","bad"})
            assertThatThrownBy(()->p.trade(p.parse(tick(time,"1234")))).isInstanceOf(KiwoomApiException.class);
        assertThat(p.trade(p.parse(tick("100000","1234").replace("005930","005930_NX")))).isNull();
        assertThatThrownBy(()->p.trade(p.parse(tick("100000","0")))).isInstanceOf(KiwoomApiException.class);
    }
    @Test void mapsOnlyExplicitKrxStateAndUsesNoTradingHours() {
        assertThat(p.state(p.parse(state("3")))).isEqualTo(MarketStatus.OPEN);
        for(String code:new String[]{"0","2","4","8","9","a","d"})assertThat(p.state(p.parse(state(code)))).isEqualTo(MarketStatus.CLOSED);
        assertThat(p.state(p.parse(state("P")))).isEqualTo(MarketStatus.UNKNOWN);
    }
    static String state(String code){return "{\"type\":\"0s\",\"item\":\"\",\"values\":{\"215\":\""+code+"\",\"20\":\"100000\"}}";}
    @Test void subscriptionHasNoOrderAccountRequest() {
        var r=p.parse(p.subscribe());assertThat(r.path("trnm").asText()).isEqualTo("REG");
        assertThat(r.path("data").get(0).path("type").get(0).asText()).isEqualTo("0B");
        assertThat(r.path("data").get(1).path("type").get(0).asText()).isEqualTo("0s");
        assertThat(r.path("data").get(1).path("item").isArray()).isTrue();
        assertThat(r.path("data").get(1).path("item").size()).isZero();
        assertThat(r.path("data").get(0).path("item").get(0).asText()).isEqualTo("005930");
        assertThat(p.parse(p.login("fixture-token")).path("token").asText()).isEqualTo("fixture-token");
    }
    @Test void staleStateIsNotSilentlyAcceptedAndFreshTickCannotReplaceIt() {
        assertThatThrownBy(()->p.state(p.parse(state("3").replace("100000","090000"))))
            .isInstanceOf(KiwoomApiException.class);
    }
}
