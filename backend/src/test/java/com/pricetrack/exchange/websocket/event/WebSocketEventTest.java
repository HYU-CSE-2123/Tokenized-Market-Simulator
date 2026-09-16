package com.pricetrack.exchange.websocket.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;

/** 공통 envelope가 클라이언트 계약에 필요한 ID·버전·종류·시각·data를 직렬화하는지 검증한다. */
class WebSocketEventTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesVersionedEnvelopeAndTypedPayload() throws Exception {
        WebSocketEvent<PriceEventPayload> event = WebSocketEvent.create(WebSocketEventType.PRICE_UPDATED,
                new PriceEventPayload(
                        "mSEC", new BigDecimal("75000.125"), new BigDecimal("74800"),
                        new BigDecimal("200.125"), new BigDecimal("0.25"), new BigDecimal("3"),
                        MarketStatus.OPEN, PriceStatus.LIVE, "TOSS",
                        Instant.parse("2026-09-16T01:23:45Z"),
                        Instant.parse("2026-09-16T01:23:45Z")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(event));

        assertThat(json.get("eventId").asText()).isNotBlank();
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("type").asText()).isEqualTo("PRICE_UPDATED");
        assertThat(json.get("occurredAt").asText()).isNotBlank();
        assertThat(json.at("/data/symbol").asText()).isEqualTo("mSEC");
        assertThat(json.at("/data/price").decimalValue()).isEqualByComparingTo("75000.125");
        assertThat(json.at("/data/changeRate").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(json.at("/data/volume").decimalValue()).isEqualByComparingTo("3");
        assertThat(json.at("/data/marketStatus").asText()).isEqualTo("OPEN");
        assertThat(json.at("/data/priceStatus").asText()).isEqualTo("LIVE");
        assertThat(json.at("/data/provider").asText()).isEqualTo("TOSS");
        assertThat(json.at("/data/observedAt").asText()).isEqualTo("2026-09-16T01:23:45Z");
        assertThat(json.at("/data/updatedAt").asText()).isEqualTo("2026-09-16T01:23:45Z");
    }
}
