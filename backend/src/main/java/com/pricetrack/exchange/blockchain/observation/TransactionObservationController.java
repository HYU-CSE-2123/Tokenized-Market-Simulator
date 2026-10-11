package com.pricetrack.exchange.blockchain.observation;
import java.util.Map;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/transaction-observations")
public class TransactionObservationController {
    private final TransactionObservationService service;
    public TransactionObservationController(TransactionObservationService service){this.service=service;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal AuthenticatedUser actor,
        @RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="ALL") String attention,
        @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor){return service.list(actor,status,attention,limit,cursor);}
    @GetMapping("/{id}") public Map<String,Object> detail(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable long id){return service.detail(actor,id);}
}
