package com.pricetrack.exchange.ai.tool;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Bounded raw request parsing avoids buffering arbitrary JSON before the Tool input limit. */
@RestController
@RequestMapping("/api/ai/tools")
public class ToolController {
    private final ToolDispatcher tools;
    public ToolController(ToolDispatcher tools) { this.tools = tools; }
    @PostMapping("/{toolName}")
    public ResponseEntity<ToolResult> invoke(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String toolName, HttpServletRequest request) throws IOException {
        ToolResult result = tools.invoke(user, toolName, request.getInputStream().readNBytes(2049));
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
