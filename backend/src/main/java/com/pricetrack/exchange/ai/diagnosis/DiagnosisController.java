package com.pricetrack.exchange.ai.diagnosis;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.common.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** ADMIN history only; there is deliberately no replay/recovery/update endpoint. */
@RestController
@RequestMapping("/api/ai/diagnoses")
public class DiagnosisController {
    private final ObjectProvider<PgDiagnosisStore> stores;private final ObjectProvider<DiagnosisPayload> payloads;
    public DiagnosisController(ObjectProvider<PgDiagnosisStore> stores,ObjectProvider<DiagnosisPayload> payloads){this.stores=stores;this.payloads=payloads;}
    private PgDiagnosisStore require(AuthenticatedUser user){
        if(user==null || user.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
        var store=stores.getIfAvailable();if(store==null)throw new DiagnosisFailure("DIAGNOSIS_DISABLED");return store;
    }
    @PostMapping("/index") public Map<String,String> initialize(@AuthenticationPrincipal AuthenticatedUser user){require(user).initialize();return Map.of("status","INITIALIZED");}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam(required=false) Long orderId,
            @RequestParam(required=false) String jobStatus,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="20") int limit){
        var rows=require(user).list(orderId,jobStatus,before,limit);Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);
        result.put("nextBefore",rows.size()==limit?rows.getLast().id():null);result.put("automaticallyModified",false);return result;
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id){
        if(id<=0)throw new DiagnosisFailure("INVALID_DIAGNOSIS_QUERY");var row=require(user).detail(id);
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",row.id());out.put("orderId",row.orderId());out.put("jobStatus",row.jobStatus());
        out.put("detectedAt",row.detectedAt());out.put("startedAt",row.startedAt());out.put("completedAt",row.completedAt());out.put("targetStale",row.targetStale());out.put("errorCode",row.errorCode());
        out.put("result",payloads.getObject().forRead(row.result()));out.put("automaticallyModified",false);return out;
    }
    @ExceptionHandler(DiagnosisFailure.class) ResponseEntity<ApiErrorResponse> error(DiagnosisFailure e,HttpServletRequest request){
        int status="DIAGNOSIS_NOT_FOUND".equals(e.getMessage())?404:"INVALID_DIAGNOSIS_QUERY".equals(e.getMessage())?400:503;
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status,e.getMessage(),"자동 진단 이력을 확인할 수 없습니다. 거래 기능과 별도로 설정과 AI DB를 확인하세요.",request.getRequestURI()));
    }
}
