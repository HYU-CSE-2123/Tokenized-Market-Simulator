package com.pricetrack.exchange.conditional;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import com.pricetrack.exchange.auth.AuthenticatedUser;

@RestController
public class ConditionalOrderController {
    private final ConditionalOrderService service;
    public ConditionalOrderController(ConditionalOrderService service){this.service=service;}
    @PostMapping("/api/conditional-orders")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ConditionalOrderService.View create(@AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody ConditionalOrderService.Request request){return service.create(user.userId(),key,request);}
    @GetMapping("/api/conditional-orders")
    public ConditionalOrderService.Page list(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(required=false) String before){return service.list(user.userId(),before);}
    @GetMapping("/api/conditional-orders/capabilities")
    public java.util.Map<String,Boolean> capabilities(){return java.util.Map.of("registrationEnabled",service.registrationEnabled());}
    @GetMapping("/api/conditional-orders/requests")
    public ConditionalOrderService.View request(@AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader(value="Idempotency-Key",required=false) String key){return service.request(user.userId(),key);}
    @GetMapping("/api/conditional-orders/{id}")
    public ConditionalOrderService.View find(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){return service.find(user.userId(),id);}
    @PostMapping("/api/conditional-orders/{id}/cancel")
    public ConditionalOrderService.View cancel(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){return service.cancel(user.userId(),id);}
    @GetMapping("/api/admin/conditional-orders")
    public ConditionalOrderService.Page admin(@RequestParam(required=false) String before){return service.list(null,before);}
    @ExceptionHandler(ConditionalOrderService.Failure.class)
    ResponseEntity<?> failure(ConditionalOrderService.Failure failure){return ResponseEntity.status(failure.status).body(java.util.Map.of("code",failure.code));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable(RuntimeException failure){return ResponseEntity.status(503).body(java.util.Map.of("code","CONDITIONAL_DB_UNAVAILABLE"));}
}
