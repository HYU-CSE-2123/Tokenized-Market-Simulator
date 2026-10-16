package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.pricetrack.exchange.common.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

@RestController @RequestMapping("/api/admin/trade-audits")
public class AuditController {
    private final ObjectProvider<AuditService> services;
    public AuditController(ObjectProvider<AuditService> services) { this.services=services; }
    private AuditService service() { var s=services.getIfAvailable(); if(s==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TRADE_AUDIT_DISABLED"); return s; }
    @PostMapping public ResponseEntity<Run> start(@AuthenticationPrincipal AuthenticatedUser user) { return ResponseEntity.accepted().body(service().start(user.userId())); }
    @GetMapping public Page<Run> history(@RequestParam(required=false) String before) { if(before!=null) uuid(before); return service().history(before); }
    @GetMapping("/{id}") public Run detail(@PathVariable String id) { uuid(id); return service().detail(id); }
    @GetMapping("/{id}/items") public Page<Item> items(@PathVariable String id,@RequestParam(defaultValue="0") int before,
            @RequestParam(required=false) String verdict,@RequestParam(required=false) String mode,@RequestParam(required=false) String side,@RequestParam(required=false) Long orderId) {
        uuid(id); allowed(verdict,"MATCH","MISMATCH","INCONCLUSIVE"); allowed(mode,"ONCHAIN","DB_ONLY","UNKNOWN"); allowed(side,"BUY","SELL");
        if(before<0 || (orderId!=null && orderId<1)) throw bad();
        return service().items(id,before,verdict,mode,side,orderId);
    }
    private void uuid(String value) { try { UUID.fromString(value); } catch(IllegalArgumentException e) { throw bad(); } }
    private void allowed(String value,String... values) { if(value!=null && !List.of(values).contains(value)) throw bad(); }
    private ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_AUDIT_QUERY"); }
    // Return JSON directly: sendError would trigger a second secured /error dispatch and mask 409 as 401.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> status(ResponseStatusException error,HttpServletRequest request) {
        var status=HttpStatus.valueOf(error.getStatusCode().value());
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status.value(),error.getReason(),"감사 요청 상태를 확인하세요.",request.getRequestURI()));
    }
    @ExceptionHandler({Unavailable.class,org.springframework.dao.DataAccessException.class})
    ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiErrorResponse.of(503,"AUDIT_STORE_UNAVAILABLE","감사 기록을 조회할 수 없습니다.",request.getRequestURI()));
    }
}
