package com.pricetrack.exchange.wallet;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.wallet.faucet.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final FaucetService service;

    public WalletController(FaucetService service) {
        this.service = service;
    }

    @PostMapping("/faucet")
    public FaucetStore.Result faucet(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) JsonNode body) {
        String request=body==null || body.isObject() && body.isEmpty()?FaucetService.REQUEST:FaucetPolicy.hash("invalid-body:"+body.toString());
        return service.credit(user.userId(),key,request);
    }
    @GetMapping("/faucet/status") public FaucetService.Status status(@AuthenticationPrincipal AuthenticatedUser user) {return service.status(user.userId());}
    @GetMapping("/faucet/claims") public FaucetStore.Result claim(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {return service.result(user.userId(),key);}
    @GetMapping("/faucet/grants") public FaucetStore.Page grants(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(required=false) String before) {return service.history(user.userId(),before);}
    @ExceptionHandler(FaucetFailure.class) ResponseEntity<?> failure(FaucetFailure e) {
        service.rejection(e.getMessage());return ResponseEntity.status(e.status()).body(Map.of("code",e.getMessage(),"message",e.getMessage()));
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) ResponseEntity<?> unavailable(Exception e) {
        service.rejection("FAUCET_DB_UNAVAILABLE");return ResponseEntity.status(503).body(Map.of("code","FAUCET_DB_UNAVAILABLE","message","지급 결과를 같은 키로 확인하세요."));
    }
}
