package com.pricetrack.exchange.ai.tool;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.util.UUID;

/** Constructed by the server, explicitly propagated across worker threads, never decoded from arguments. */
public record ToolContext(Long userId, UserRole role, String requestId, String purpose, String agentRunId) {
    public static ToolContext from(AuthenticatedUser principal) {
        if (principal == null || principal.userId() == null || principal.userId() <= 0 || principal.role() == null)
            throw new ToolFailure("AUTHENTICATION_REQUIRED");
        return new ToolContext(principal.userId(), principal.role(), UUID.randomUUID().toString(),
                "READ_ONLY_TOOL_TEST", null);
    }
    public void requireAuthenticated() {
        if (userId == null || userId <= 0 || role == null) throw new ToolFailure("AUTHENTICATION_REQUIRED");
    }
    public static ToolContext forAgent(AuthenticatedUser principal, String runId) {
        ToolContext base=from(principal);
        if (runId == null || !runId.matches("[0-9a-f-]{36}")) throw new ToolFailure("INVALID_TOOL_ARGUMENTS");
        return new ToolContext(base.userId(),base.role(),base.requestId(),"AGENT_READ_ONLY",runId);
    }
    public void requireAdmin() {
        requireAuthenticated();
        if (role != UserRole.ADMIN) throw new ToolFailure("TOOL_FORBIDDEN");
    }
    public boolean admin() { return role == UserRole.ADMIN; }
}
