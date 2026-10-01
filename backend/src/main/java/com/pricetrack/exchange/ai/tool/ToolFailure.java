package com.pricetrack.exchange.ai.tool;

/** Only an allowlisted code crosses the boundary; internal exception messages never do. */
public final class ToolFailure extends RuntimeException {
    private final String code;
    public ToolFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
