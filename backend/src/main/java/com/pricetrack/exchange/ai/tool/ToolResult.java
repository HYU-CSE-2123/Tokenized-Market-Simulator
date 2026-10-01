package com.pricetrack.exchange.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Missing/error evidence never contains invented data. Resource states live inside successful data. */
public record ToolResult(String tool, String version, String status, Instant retrievedAt,
                         String source, JsonNode data, String error) {
    public static ToolResult success(String tool, String source, JsonNode data) {
        return new ToolResult(tool, "1", "SUCCESS", Instant.now(), source, data, null);
    }
    public static ToolResult failure(String tool, String code) {
        return new ToolResult(tool, "1", "ERROR", Instant.now(), "NONE", null, code);
    }
    public int httpStatus() {
        if (error == null) return 200;
        return switch (error) {
            case "AUTHENTICATION_REQUIRED" -> 401;
            case "TOOL_FORBIDDEN" -> 403;
            case "RESOURCE_NOT_FOUND", "TOOL_NOT_ALLOWED" -> 404;
            case "INVALID_TOOL_ARGUMENTS", "TOOL_INPUT_LIMIT" -> 400;
            case "TOOL_TIMEOUT" -> 504;
            default -> 503;
        };
    }
}
