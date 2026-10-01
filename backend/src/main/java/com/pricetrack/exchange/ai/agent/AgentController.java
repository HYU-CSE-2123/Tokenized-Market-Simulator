package com.pricetrack.exchange.ai.agent;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class AgentController {
    private final AgentService service;
    public AgentController(AgentService service){this.service=service;}
    @PostMapping("/api/ai/agent/answers")
    public ResponseEntity<AgentResponse> answer(@AuthenticationPrincipal AuthenticatedUser principal,HttpServletRequest request)throws IOException{
        var result=service.answer(principal,request.getInputStream().readNBytes(4097));
        return ResponseEntity.status(result.httpStatus()).body(result);
    }
}
