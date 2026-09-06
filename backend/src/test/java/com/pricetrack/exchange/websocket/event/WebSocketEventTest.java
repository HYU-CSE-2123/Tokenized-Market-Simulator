package com.pricetrack.exchange.websocket.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 공통 envelope가 클라이언트 계약에 필요한 ID·버전·종류·시각·data를 직렬화하는지 검증한다. */
class WebSocketEventTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesVersionedEnvelopeAndTypedPayload() throws Exception {
        WebSocketEvent<PriceEventPayload> event = WebSocketEvent.create(WebSocketEventType.PRICE_UPDATED,
                new PriceEventPayload("mSEC", new BigDecimal("75000.125"), new BigDecimal("0.25")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(event));

        assertThat(json.get("eventId").asText()).isNotBlank();
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("type").asText()).isEqualTo("PRICE_UPDATED");
        assertThat(json.get("occurredAt").asText()).isNotBlank();
        assertThat(json.at("/data/symbol").asText()).isEqualTo("mSEC");
        assertThat(json.at("/data/price").decimalValue()).isEqualByComparingTo("75000.125");
    }
}
