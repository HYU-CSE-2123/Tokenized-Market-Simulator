package com.pricetrack.exchange.ai.observability;

import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.diagnosis.PgDiagnosisStore;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** No database dependency for local counters; optional AI queue reads are independently bounded. */
@RestController
@RequestMapping("/api/ai/observability")
public class AiObservabilityController {
    private final AiObservability metrics;private final AiProperties properties;private final ObjectProvider<PgDiagnosisStore> stores;
    public AiObservabilityController(AiObservability metrics,AiProperties properties,ObjectProvider<PgDiagnosisStore> stores){this.metrics=metrics;this.properties=properties;this.stores=stores;}
    @GetMapping public Map<String,Object> get(@AuthenticationPrincipal AuthenticatedUser user){
        if(user==null || user.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
        Map<String,Object> out=new LinkedHashMap<>(metrics.snapshot());
        out.put("chatModel",approved(properties.chatModel()));out.put("embeddingModel",approved(properties.embeddingModel()));
        try{var store=stores.getIfAvailable();out.put("diagnosis",store==null?Map.of("status","DISABLED"):Map.of("status","AVAILABLE","values",store.observation()));}
        catch(RuntimeException e){out.put("diagnosis",Map.of("status","UNAVAILABLE"));}
        return out;
    }
    private String approved(String model){return Set.of("gpt-5.6-terra","text-embedding-3-small").contains(model)?model:"CUSTOM_UNREPORTED";}
}
