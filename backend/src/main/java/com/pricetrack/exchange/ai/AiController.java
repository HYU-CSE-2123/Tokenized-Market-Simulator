package com.pricetrack.exchange.ai;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.common.exception.ApiErrorResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** ADMIN 전용 최소 검증 API. 임의 파일·모델·역할·SQL은 요청에서 받지 않는다. */
@RestController
@RequestMapping("/api/ai")
@ConditionalOnProperty(name="app.ai.enabled",havingValue="true")
public class AiController {
    private final RagService service;
    public AiController(RagService service) {this.service=service;}
    public record Question(@NotBlank @Size(max=1000) String question) {}
    @PostMapping("/index") public RagService.IndexResult index(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.ingest(user);
    }
    @PostMapping("/search") public List<KnowledgeHit> search(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody Question request) {
        return service.search(user,request.question());
    }
    @PostMapping("/answers") public RagService.Answer answer(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody Question request) {
        return service.answer(user,request.question());
    }
    @ExceptionHandler(AiFailure.class)
    public ResponseEntity<ApiErrorResponse> unavailable(AiFailure error,HttpServletRequest request) {
        int status="AI_BUSY".equals(error.code())?429:503;
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status,error.code(),
                "AI 요청을 처리할 수 없습니다. 거래 기능과 별도로 설정·색인·공급자 상태를 확인하세요.",request.getRequestURI()));
    }
}

