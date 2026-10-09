package com.pricetrack.exchange.reserve;
import static com.pricetrack.exchange.reserve.ReserveModels.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/reserve-reconciliations")
@ConditionalOnProperty(name="app.reserve.enabled",havingValue="true")
public class ReserveController {
    private final ReserveService service;
    public ReserveController(ReserveService service) { this.service=service; }
    @PostMapping("/baseline") public ResponseEntity<Baseline> create(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBaseline(actor.userId()));
    }
    @GetMapping("/baseline") public ResponseEntity<Baseline> baseline() {
        return service.baseline().map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build());
    }
    @PostMapping public Result run(@AuthenticationPrincipal AuthenticatedUser actor) { return service.run(actor.userId()); }
    @GetMapping public List<Result> history() { return service.history(); }
    @GetMapping("/{id}") public Result detail(@PathVariable String id) { return service.detail(id); }
}
