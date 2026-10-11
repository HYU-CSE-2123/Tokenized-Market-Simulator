package com.pricetrack.exchange.blockchain.observation;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.conditional.ConditionalOrder;
import com.pricetrack.exchange.order.Order;

/** DB-only ADMIN projection: warning is not a business state or a receipt outcome. */
@Service
public class TransactionObservationService {
    private final EntityManager em;private final ObservationRecorder recorder;private final long threshold;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public TransactionObservationService(EntityManager em,ObservationRecorder recorder,
        @Value("${app.blockchain.observation.warning-seconds:300}") long threshold){
        this(em,recorder,threshold,Clock.systemUTC());
    }
    public TransactionObservationService(EntityManager em,ObservationRecorder recorder,long threshold,Clock clock){
        if(threshold<1 || threshold>86400)throw new IllegalArgumentException("Invalid observation threshold");
        this.em=em;this.recorder=recorder;this.threshold=threshold;this.clock=clock;
    }
    private void admin(AuthenticatedUser actor){if(actor==null || actor.role()!=UserRole.ADMIN)throw new AccessDeniedException("ADMIN required");}
    private ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid observation filter");}
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> list(AuthenticatedUser actor,String status,String attention,int limit,String cursor){
        admin(actor);if(!Set.of("ALL","SIGNED","SUBMITTED","CONFIRMED","FAILED","REVIEW_REQUIRED","CREATED").contains(status)
            || !Set.of("ALL","LONG_WAIT").contains(attention)||limit<1||limit>50)throw invalid();
        Instant at=clock.instant();Long after=null;
        if(cursor!=null){try{if(cursor.length()>32)throw invalid();String value=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);if(!value.matches("[1-9][0-9]{0,18}"))throw invalid();after=Long.valueOf(value);
            if(after<=0 || !Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)).equals(cursor))throw invalid();
        }catch(RuntimeException e){throw invalid();}}
        String predicate=" where 1=1";
        if(!status.equals("ALL"))predicate+=" and t.status=:status";
        if(attention.equals("LONG_WAIT"))predicate+=" and t.status in :pending and coalesce(t.submittedAt,t.createdAt)<=:cutoff";
        if(after!=null)predicate+=" and t.id>:after";
        var q=em.createQuery("select t from BlockchainTransaction t"+predicate+" order by t.id",BlockchainTransaction.class);
        if(!status.equals("ALL"))q.setParameter("status",BlockchainTransactionStatus.valueOf(status));
        if(attention.equals("LONG_WAIT"))q.setParameter("pending",List.of(BlockchainTransactionStatus.SIGNED,BlockchainTransactionStatus.SUBMITTED)).setParameter("cutoff",at.minusSeconds(threshold));
        if(after!=null)q.setParameter("after",after);
        var rows=q.setMaxResults(limit+1).setHint("jakarta.persistence.query.timeout",2000).getResultList();
        var response=new LinkedHashMap<String,Object>();response.put("observedAt",at);response.put("warningSeconds",threshold);
        response.put("items",rows.stream().limit(limit).map(t->project(t,at,false)).toList());
        response.put("nextCursor",rows.size()>limit?Base64.getUrlEncoder().withoutPadding().encodeToString(rows.get(limit-1).getId().toString().getBytes(StandardCharsets.UTF_8)):null);
        response.put("recorder",recorder.health());return response;
    }
    @Transactional(readOnly=true,timeout=3)
    public Map<String,Object> detail(AuthenticatedUser actor,long id){admin(actor);if(id<=0)throw invalid();
        var tx=em.find(BlockchainTransaction.class,id);if(tx==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Observation not found");
        return project(tx,clock.instant(),true);
    }
    public static long age(Instant at,Instant start){return Math.max(0,Duration.between(start,at).getSeconds());}
    private Map<String,Object> project(BlockchainTransaction t,Instant at,boolean detail){
        var out=new LinkedHashMap<String,Object>();var order=t.getOrderId()==null?null:em.find(Order.class,t.getOrderId());
        var observation=em.find(TransactionObservation.class,t.getId());
        Instant waiting=t.getSubmittedAt()==null?t.getCreatedAt():t.getSubmittedAt();
        boolean pending=t.getStatus()==BlockchainTransactionStatus.SIGNED||t.getStatus()==BlockchainTransactionStatus.SUBMITTED;
        out.put("id",t.getId());out.put("type",t.getType());out.put("status",t.getStatus());
        out.put("kind",t.getType()==BlockchainTransactionType.UPDATE_PRICE?"SYSTEM":"USER_TRANSACTION");
        out.put("orderId",t.getOrderId());out.put("linkStatus",order!=null?"LINKED":t.getOrderId()==null?"NO_ORDER":"ORDER_MISSING");
        out.put("orderStatus",order==null?null:order.getStatus());out.put("createdAt",t.getCreatedAt());out.put("submittedAt",t.getSubmittedAt());
        out.put("observedAt",at);out.put("warningSeconds",threshold);out.put("totalAgeSeconds",age(at,t.getCreatedAt()));
        out.put("submittedAgeSeconds",t.getSubmittedAt()==null?null:age(at,t.getSubmittedAt()));out.put("waitingAgeSeconds",age(at,waiting));
        out.put("attention",pending&&age(at,waiting)>=threshold?"LONG_WAIT":"NONE");
        out.put("recoveryPolicy",pending?"EXISTING_RECOVERY_CONTINUES":"EXISTING_STATE_POLICY");
        out.put("observationStatus",observation==null?"UNRECORDED":"RECORDED_COMPLETED_ATTEMPT");
        out.put("lastAttempt",observation==null?null:Map.of("startedAt",observation.getStartedAt(),"finishedAt",observation.getFinishedAt(),
            "phase",observation.getPhase(),"resultCode",observation.getResultCode(),"recordedCount",observation.getAttemptCount(),"recordedAt",observation.getRecordedAt(),"ageSeconds",age(at,observation.getFinishedAt())));
        var conditional=t.getOrderId()==null?List.<ConditionalOrder>of():em.createQuery("select c from ConditionalOrder c where c.executionOrderId=:order",ConditionalOrder.class)
            .setParameter("order",t.getOrderId()).setMaxResults(1).getResultList();
        out.put("conditionalOrderId",conditional.isEmpty()?null:conditional.get(0).getId());out.put("conditionalState",conditional.isEmpty()?null:conditional.get(0).getState());
        if(detail){out.put("txHash",t.getTxHash());out.put("nonce",t.getNonce());out.put("confirmedAt",t.getConfirmedAt());}
        return out;
    }
}
