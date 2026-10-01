package com.pricetrack.exchange.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Explicit read-only allowlist and matching JSON schemas; no reflection or arbitrary bean dispatch. */
public final class ToolRegistry {
    public static final Set<String> NAMES = Set.of("getOrder", "getQuote", "getBlockchainTransaction",
            "getReceiptSummary", "getMarketStatus", "getCurrentReferencePrice", "getPortfolio", "listAbnormalOrders");
    private static final Set<String> ORDER_TOOLS = Set.of("getOrder", "getBlockchainTransaction", "getReceiptSummary");
    private final ObjectMapper json;
    public ToolRegistry(ObjectMapper json) { this.json = json; }

    public record Arguments(Long orderId, String quoteId, int limit, String filter, int waitingSeconds, Cursor cursor) {}
    public record Cursor(Instant createdAt, long id) {
        public String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
        public static Cursor decode(String text) {
            try {
                if (text.isEmpty() || text.length() > 128 || !text.matches("[A-Za-z0-9_-]+")) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2 || !parts[1].matches("[1-9][0-9]{0,18}")) throw invalid();
                Cursor cursor = new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
                if (cursor.id <= 0 || !cursor.encode().equals(text)) throw invalid();
                return cursor;
            } catch (RuntimeException e) { throw invalid(); }
        }
    }

    public Arguments validate(String name, JsonNode args) {
        if (!NAMES.contains(name)) throw new ToolFailure("TOOL_NOT_ALLOWED");
        if (args == null || !args.isObject()) throw invalid();
        Set<String> allowed = ORDER_TOOLS.contains(name) ? Set.of("orderId") : name.equals("getQuote")
                ? Set.of("quoteId") : name.equals("listAbnormalOrders")
                ? Set.of("limit", "filter", "waitingSeconds", "cursor") : Set.of();
        args.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
        if (ORDER_TOOLS.contains(name)) return new Arguments(integer(args, "orderId", 1, Long.MAX_VALUE, null), null, 0, null, 0, null);
        if (name.equals("getQuote")) {
            JsonNode value = args.get("quoteId");
            if (value == null || !value.isTextual() || !value.textValue().matches("0x[0-9a-fA-F]{64}")) throw invalid();
            return new Arguments(null, value.textValue(), 0, null, 0, null);
        }
        if (name.equals("listAbnormalOrders")) {
            int limit = (int) integer(args, "limit", 1, 20, 10L);
            int wait = (int) integer(args, "waitingSeconds", 60, 3600, 300L);
            String filter = args.has("filter") ? text(args.get("filter")) : "REVIEW_REQUIRED";
            if (!Set.of("REVIEW_REQUIRED", "WAITING_LONG").contains(filter)) throw invalid();
            Cursor cursor = args.has("cursor") ? Cursor.decode(text(args.get("cursor"))) : null;
            return new Arguments(null, null, limit, filter, wait, cursor);
        }
        return new Arguments(null, null, 0, null, 0, null);
    }
    private static long integer(JsonNode args, String field, long min, long max, Long fallback) {
        JsonNode value = args.get(field);
        if (value == null && fallback != null) return fallback;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < min || value.longValue() > max) throw invalid();
        return value.longValue();
    }
    private static String text(JsonNode value) {
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static ToolFailure invalid() { return new ToolFailure("INVALID_TOOL_ARGUMENTS"); }

    public JsonNode inputSchema(String name) {
        if (!NAMES.contains(name)) throw new ToolFailure("TOOL_NOT_ALLOWED");
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        var required = schema.putArray("required");
        if (ORDER_TOOLS.contains(name)) {
            props.putObject("orderId").put("type", "integer").put("minimum", 1).put("maximum", Long.MAX_VALUE);
            required.add("orderId");
        } else if (name.equals("getQuote")) {
            props.putObject("quoteId").put("type", "string").put("pattern", "^0x[0-9a-fA-F]{64}$");
            required.add("quoteId");
        } else if (name.equals("listAbnormalOrders")) {
            props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20).put("default", 10);
            props.putObject("waitingSeconds").put("type", "integer").put("minimum", 60).put("maximum", 3600).put("default", 300);
            props.putObject("filter").put("type", "string").put("default", "REVIEW_REQUIRED")
                    .putArray("enum").add("REVIEW_REQUIRED").add("WAITING_LONG");
            props.putObject("cursor").put("type", "string").put("minLength", 1).put("maxLength", 128)
                    .put("pattern", "^[A-Za-z0-9_-]+$").put("description", "Canonical base64url createdAt|positive-Long id; server validates both components");
        }
        return schema;
    }
}
