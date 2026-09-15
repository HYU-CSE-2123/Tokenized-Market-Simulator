package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TossRealtimeProtocolTest {
    private final TossRealtimeProtocol protocol = new TossRealtimeProtocol(new ObjectMapper(), "005930");

    @Test
    void createsDeclarativeKoreanTradeSubscription() throws Exception {
        var json = new ObjectMapper().readTree(protocol.subscription("request-1"));

        assertThat(json.isArray()).isTrue();
        assertThat(json.get(0).path("id").asText()).isEqualTo("request-1");
        assertThat(json.get(1).path("type").asText()).isEqualTo("trade:kr");
        assertThat(json.get(1).path("codes").get(0).asText()).isEqualTo("005930");
    }

    @Test
    void acceptsSubscriptionAckForExpectedTopic() {
        TossRealtimeProtocol.Frame frame = protocol.parse("""
                {"type":"subscriptions","id":"request-1",
                 "subscribed":["trade:kr:005930"],"rejected":[]}
                """);

        assertThat(frame).isInstanceOf(TossRealtimeProtocol.SubscribedFrame.class);
    }

    @Test
    void rejectsAckThatDoesNotSubscribeSamsungTrade() {
        assertThatThrownBy(() -> protocol.parse("""
                {"type":"subscriptions","subscribed":[],
                 "rejected":[{"target":"trade:kr:005930","code":"stock-not-found","message":"missing"}]}
                """))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("stock-not-found");
    }

    @Test
    void convertsRealtimeTradeFrame() {
        TossRealtimeProtocol.TradeFrame frame = (TossRealtimeProtocol.TradeFrame) protocol.parse("""
                {"type":"message","topic":"trade:kr:005930","data":{
                  "price":"72000","volume":"120",
                  "timestamp":"2026-09-15T09:30:42.123+09:00","currency":"KRW"}}
                """);

        assertThat(frame.trade().symbol()).isEqualTo("005930");
        assertThat(frame.trade().price()).isEqualByComparingTo("72000");
        assertThat(frame.trade().volume()).isEqualByComparingTo("120");
        assertThat(frame.trade().observedAt()).isEqualTo(Instant.parse("2026-09-15T00:30:42.123Z"));
    }

    @Test
    void rejectsUnexpectedTopicAndCurrency() {
        assertThatThrownBy(() -> protocol.parse("""
                {"type":"message","topic":"trade:kr:000660","data":{
                  "price":"72000","volume":"1",
                  "timestamp":"2026-09-15T09:30:42+09:00","currency":"KRW"}}
                """))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("구독하지 않은");

        assertThatThrownBy(() -> protocol.parse("""
                {"type":"message","topic":"trade:kr:005930","data":{
                  "price":"72000","volume":"1",
                  "timestamp":"2026-09-15T09:30:42+09:00","currency":"USD"}}
                """))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("KRW");
    }

    @Test
    void parsesPongAndRejectsErrorFrame() {
        assertThat(protocol.parse("{\"type\":\"pong\"}"))
                .isInstanceOf(TossRealtimeProtocol.PongFrame.class);
        assertThatThrownBy(() -> protocol.parse("""
                {"type":"error","error":{"code":"server-shutdown","message":"deploy"}}
                """))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("server-shutdown");
    }
}
