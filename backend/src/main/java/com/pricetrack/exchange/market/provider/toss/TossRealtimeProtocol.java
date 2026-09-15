package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Toss AsyncAPI frames are validated here before they reach the market price state. */
public class TossRealtimeProtocol {
    private static final String TRADE_TOPIC_PREFIX = "trade:kr:";

    private final ObjectMapper objectMapper;
    private final String symbol;
    private final String expectedTopic;

    public TossRealtimeProtocol(ObjectMapper objectMapper, String symbol) {
        this.objectMapper = objectMapper;
        this.symbol = symbol;
        this.expectedTopic = TRADE_TOPIC_PREFIX + symbol;
    }

    public String subscription(String requestId) {
        try {
            ObjectNode declaration = objectMapper.createObjectNode().put("type", "trade:kr");
            declaration.putArray("codes").add(symbol);
            return objectMapper.writeValueAsString(List.of(
                    objectMapper.createObjectNode().put("id", requestId),
                    declaration));
        } catch (Exception exception) {
            throw new TossApiException("토스증권 실시간 구독 메시지를 만들 수 없습니다.", exception);
        }
    }

    public Frame parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = requiredText(root, "type");
            return switch (type) {
                case "subscriptions" -> parseSubscriptions(root);
                case "message" -> parseTrade(root);
                case "pong" -> new PongFrame();
                case "error" -> throw new TossApiException("토스증권 실시간 오류: "
                        + requiredText(root.path("error"), "code"));
                default -> throw new TossApiException("알 수 없는 토스증권 실시간 프레임입니다: " + type);
            };
        } catch (TossApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TossApiException("토스증권 실시간 응답 형식이 올바르지 않습니다.", exception);
        }
    }

    private Frame parseSubscriptions(JsonNode root) {
        boolean subscribed = false;
        for (JsonNode topic : root.path("subscribed")) {
            if (expectedTopic.equals(topic.asText())) subscribed = true;
        }
        if (!subscribed) {
            Optional<JsonNode> rejection = stream(root.path("rejected"))
                    .filter(item -> expectedTopic.equals(item.path("target").asText()))
                    .findFirst();
            String code = rejection.map(item -> item.path("code").asText("rejected"))
                    .orElse("not-subscribed");
            throw new TossApiException("토스증권 삼성전자 실시간 구독이 거부됐습니다: " + code);
        }
        return new SubscribedFrame();
    }

    private Frame parseTrade(JsonNode root) {
        if (!expectedTopic.equals(requiredText(root, "topic"))) {
            throw new TossApiException("구독하지 않은 토스증권 실시간 topic입니다.");
        }
        JsonNode data = root.path("data");
        if (!"KRW".equals(requiredText(data, "currency"))) {
            throw new TossApiException("삼성전자 실시간 체결 통화가 KRW가 아닙니다.");
        }
        BigDecimal price = new BigDecimal(requiredText(data, "price"));
        BigDecimal volume = new BigDecimal(requiredText(data, "volume"));
        Instant observedAt = OffsetDateTime.parse(requiredText(data, "timestamp")).toInstant();
        if (price.signum() <= 0 || volume.signum() <= 0) {
            throw new TossApiException("토스증권 실시간 체결가와 수량은 0보다 커야 합니다.");
        }
        return new TradeFrame(new TossTrade(symbol, price, volume, observedAt));
    }

    private static String requiredText(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) throw new TossApiException("토스증권 실시간 응답에 " + name + " 값이 없습니다.");
        return value;
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    public sealed interface Frame permits SubscribedFrame, TradeFrame, PongFrame {}
    public record SubscribedFrame() implements Frame {}
    public record TradeFrame(TossTrade trade) implements Frame {}
    public record PongFrame() implements Frame {}
    public record TossTrade(String symbol, BigDecimal price, BigDecimal volume, Instant observedAt) {}
}
