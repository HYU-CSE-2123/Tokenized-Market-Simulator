package com.pricetrack.exchange.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deliberately excludes arguments, user text, results, tx hashes and exception payloads. */
public class ToolAudit {
    private static final Logger log = LoggerFactory.getLogger(ToolAudit.class);
    public void record(ToolContext context, String tool, ToolResult result, long millis) {
        log.info("ReadTool requestId={} userId={} role={} tool={} purpose={} agentRunId={} status={} code={} latencyMs={}",
                context == null ? null : context.requestId(), context == null ? null : context.userId(),
                context == null ? null : context.role(), tool != null && ToolRegistry.NAMES.contains(tool) ? tool : "UNKNOWN",
                context == null ? null : context.purpose(), context == null ? null : context.agentRunId(),
                result.status(), result.error(), millis);
    }
}
