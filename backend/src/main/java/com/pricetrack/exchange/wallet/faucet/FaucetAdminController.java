package com.pricetrack.exchange.wallet.faucet;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import java.util.Map;

/** ADMIN read only: no reset, override, credit or asset correction. */
@RestController @RequestMapping("/api/admin/faucet")
public class FaucetAdminController {
    private final FaucetService service;
    public FaucetAdminController(FaucetService service) {this.service=service;}
    @GetMapping public FaucetService.Admin policy() {return service.admin();}
    @GetMapping("/accounts/{userId}") public FaucetService.Status account(@PathVariable long userId) {
        if(userId<=0)throw new FaucetFailure(400,"FAUCET_INVALID_USER");return service.accountStatus(userId);
    }
    @GetMapping("/grants") public FaucetStore.Page grants(@RequestParam(required=false) Long userId,@RequestParam(required=false) String before) {
        if(userId!=null && userId<=0) throw new FaucetFailure(400,"FAUCET_INVALID_USER");return service.history(userId,before);
    }
    @ExceptionHandler(FaucetFailure.class) ResponseEntity<?> failure(FaucetFailure e) {return ResponseEntity.status(e.status()).body(Map.of("code",e.getMessage(),"message",e.getMessage()));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) ResponseEntity<?> unavailable(Exception e) {return ResponseEntity.status(503).body(Map.of("code","FAUCET_DB_UNAVAILABLE","message","지급 관측을 사용할 수 없습니다."));}
}
