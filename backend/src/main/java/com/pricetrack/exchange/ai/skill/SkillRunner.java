package com.pricetrack.exchange.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.ai.agent.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.pricetrack.exchange.ai.skill.SkillResult.*;

/** Fixed handlers, executing on the caller's existing worker and run budget/cache, not an engine. */
public final class SkillRunner {
    public interface RunContext {
        AgentResponse.ToolEvidence call(String tool,Map<String,Object> arguments);
        void checkpoint();
        void uncertainty(String code);
    }
    private final SkillRegistry.Definition definition;
    private final RunContext run;
    private final List<Trace> trace=new ArrayList<>();
    private final List<Finding> findings=new ArrayList<>();
    private String classification="INSUFFICIENT_EVIDENCE";
    private boolean inconsistent;
    public SkillRunner(SkillRegistry.Definition definition,RunContext run){this.definition=definition;this.run=run;}
    public void execute(AgentRequest request){
        switch(definition.id()){
            case "settlement-debugging" -> settlement(request);
            case "signed-quote-diagnosis" -> quote(request);
            case "market-availability-diagnosis" -> market();
            default -> throw new AgentFailure("UNKNOWN_SKILL",400);
        }
        trace("CHECK_EVIDENCE","EXECUTED","OBSERVE",List.of(),inconsistent?"INVALID_LINK":"OBSERVED",0);
    }
    private void settlement(AgentRequest request){
        var order=load("LOAD_ORDER","getOrder",Map.of("orderId",request.orderId()));
        trace("CHECK_ORDER","EXECUTED","OBSERVE",refs(order),"OBSERVED",0);
        if(!success(order)){skip("LOAD_LINKED_QUOTE","getQuote");skipTransaction();return;}
        AgentResponse.ToolEvidence quote=null;
        if(order.data().hasNonNull("quoteId")){
            quote=load("LOAD_LINKED_QUOTE","getQuote",Map.of("quoteId",order.data().path("quoteId").asText()));
            if(!success(quote)){skipTransaction();observeOrder(order,null,null);return;}
            if(!linked(order,quote,request.orderId(),order.data().path("quoteId").asText())){mismatch(refs(order,quote));skipTransaction();return;}
        }else skip("LOAD_LINKED_QUOTE","getQuote");
        observeQuote(quote);
        var tx=load("LOAD_TRANSACTION","getBlockchainTransaction",Map.of("orderId",request.orderId()));
        var receipt=receipt(tx,request.orderId());observeOrder(order,tx,receipt);
    }
    private void quote(AgentRequest request){
        var quote=load("LOAD_QUOTE","getQuote",Map.of("quoteId",request.quoteId()));observeQuote(quote);
        if(!success(quote)){skip("LOAD_LINKED_ORDER","getOrder");skipTransaction();return;}
        if("INCONSISTENT_LINK".equals(quote.data().path("orderLink").asText())){
            mismatch(refs(quote));skip("LOAD_LINKED_ORDER","getOrder");skipTransaction();return;
        }
        if(!"LINKED".equals(quote.data().path("orderLink").asText()) || !quote.data().hasNonNull("orderId")){
            skip("LOAD_LINKED_ORDER","getOrder");skipTransaction();return;
        }
        long id=quote.data().path("orderId").asLong();
        if(id<=0){mismatch(refs(quote));skip("LOAD_LINKED_ORDER","getOrder");skipTransaction();return;}
        var order=load("LOAD_LINKED_ORDER","getOrder",Map.of("orderId",id));
        if(!success(order)){skipTransaction();return;}
        if(!linked(order,quote,id,request.quoteId())){mismatch(refs(order,quote));skipTransaction();return;}
        var tx=load("LOAD_TRANSACTION","getBlockchainTransaction",Map.of("orderId",id));
        var receipt=receipt(tx,id);observeOrder(order,tx,receipt);
    }
    private void market(){
        var snapshot=load("LOAD_REFERENCE","getCurrentReferencePrice",Map.of());
        if(!success(snapshot))return;
        var data=snapshot.data();
        if("CLOSED".equals(data.path("marketStatus").asText()))finding("MARKET_CLOSED_OBSERVED",refs(snapshot));
        if(Set.of("STALE","DEGRADED","INITIALIZING").contains(data.path("priceStatus").asText()))finding("PRICE_UNAVAILABLE_OBSERVED",refs(snapshot));
        if("SIMULATED".equals(data.path("provider").asText()) || "SIMULATED".equals(data.path("priceStatus").asText()))finding("SIMULATED_REFERENCE_OBSERVED",refs(snapshot));
        finding("REFERENCE_SNAPSHOT_OBSERVED",refs(snapshot));
    }
    private AgentResponse.ToolEvidence receipt(AgentResponse.ToolEvidence tx,long orderId){
        if(success(tx) && "LINKED".equals(tx.data().path("linkStatus").asText())){
            if(tx.data().path("orderId").asLong()!=orderId){mismatch(refs(tx));skip("LOAD_RECEIPT","getReceiptSummary");return null;}
            return load("LOAD_RECEIPT","getReceiptSummary",Map.of("orderId",orderId));
        }
        skip("LOAD_RECEIPT","getReceiptSummary");return null;
    }
    private boolean linked(AgentResponse.ToolEvidence order,AgentResponse.ToolEvidence quote,long id,String quoteId){
        var o=order.data();var q=quote.data();
        if(o.path("orderId").asLong()!=id || q.path("orderId").asLong()!=id
                || !"LINKED".equals(q.path("orderLink").asText()) || !quoteId.equalsIgnoreCase(q.path("quoteId").asText())
                || !quoteId.equalsIgnoreCase(o.path("quoteId").asText()))return false;
        for(String field:List.of("symbol","side","inputAmount","inputSymbol","outputSymbol")){
            if(!o.hasNonNull(field) || !q.hasNonNull(field))return false;
            if(field.equals("inputAmount")){
                try{if(new BigDecimal(o.path(field).asText()).compareTo(new BigDecimal(q.path(field).asText()))!=0)return false;}
                catch(NumberFormatException e){return false;}
            }else if(!o.path(field).asText().equals(q.path(field).asText()))return false;
        }
        return true;
    }
    private void observeQuote(AgentResponse.ToolEvidence quote){
        if(!success(quote))return;
        if(quote.data().path("expiredByTime").asBoolean())finding("EXPIRED_BY_TIME",refs(quote));
        if("CONSUMED".equals(quote.data().path("storedStatus").asText()))finding("CONSUMED_RECORDED",refs(quote));
    }
    private void observeOrder(AgentResponse.ToolEvidence order,AgentResponse.ToolEvidence tx,AgentResponse.ToolEvidence receipt){
        if(inconsistent)return;
        if(!success(order))return;String status=order.data().path("status").asText();
        if(success(receipt) && ("MISMATCH".equals(receipt.data().path("eventValidation").asText())
                || "FILLED".equals(status) && "FAILED".equals(receipt.data().path("executionStatus").asText())
                || "FAILED".equals(status) && "SUCCESS".equals(receipt.data().path("executionStatus").asText()))){
            mismatch(refs(order,receipt));
            if(success(tx) && "REVIEW_REQUIRED".equals(tx.data().path("databaseStatus").asText()))finding("REVIEW_REQUIRED_OBSERVED",refs(tx));
            return;
        }
        if(success(tx) && "REVIEW_REQUIRED".equals(tx.data().path("databaseStatus").asText())){
            classification="REVIEW_REQUIRED_OBSERVED";finding(classification,refs(tx));return;
        }
        if("FAILED".equals(status) || success(receipt) && "FAILED".equals(receipt.data().path("executionStatus").asText())){
            classification="FAILURE_OBSERVED";finding(classification,refs(order,receipt));
            run.uncertainty("FAILURE_CAUSE_UNVERIFIED");return;
        }
        if(success(receipt)){
            var data=receipt.data();
            if("MISMATCH".equals(data.path("eventValidation").asText())){mismatch(refs(receipt));return;}
            if("FILLED".equals(status) && order.data().hasNonNull("trade") && "SUCCESS".equals(data.path("executionStatus").asText())
                    && "MATCH".equals(data.path("eventValidation").asText()) && "CONFIRMED".equals(data.path("databaseStatus").asText())
                    && data.hasNonNull("confirmations") && data.hasNonNull("requiredConfirmations")
                    && data.path("confirmations").asLong()>=data.path("requiredConfirmations").asLong()){
                classification="SETTLED_OBSERVED";finding(classification,refs(order,receipt));return;
            }
        }
        if(Set.of("REQUESTED","PENDING_ONCHAIN").contains(status)){
            classification="WAITING_OBSERVED";finding(classification,refs(order));
        }else finding("ORDER_STATUS_OBSERVED",refs(order));
    }
    private AgentResponse.ToolEvidence load(String step,String tool,Map<String,Object> args){
        run.checkpoint();if(!definition.tools().contains(tool))throw new AgentFailure("SKILL_TOOL_FORBIDDEN",503);
        long start=System.nanoTime();var evidence=run.call(tool,args);
        trace(step,success(evidence)?"EXECUTED":"UNAVAILABLE",tool,refs(evidence),success(evidence)?"SUCCESS":"TOOL_UNAVAILABLE",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        return evidence;
    }
    private static boolean success(AgentResponse.ToolEvidence evidence){return evidence!=null && "SUCCESS".equals(evidence.status());}
    private static List<String> refs(AgentResponse.ToolEvidence... evidence){return Arrays.stream(evidence).filter(SkillRunner::success).map(AgentResponse.ToolEvidence::evidenceId).toList();}
    private void mismatch(List<String> refs){inconsistent=true;classification="INCONSISTENCY_OBSERVED";finding("INVALID_LINK",refs);run.uncertainty("SKILL_INVALID_LINK");}
    private void finding(String code,List<String> refs){findings.add(new Finding(code,refs));}
    private void skipTransaction(){skip("LOAD_TRANSACTION","getBlockchainTransaction");skip("LOAD_RECEIPT","getReceiptSummary");}
    private void skip(String step,String action){trace(step,"SKIPPED",action,List.of(),"DEPENDENCY_MISSING",0);}
    public void trace(String step,String status,String action,List<String> refs,String code,long latency){
        run.checkpoint();if(!definition.steps().contains(step) || trace.size()>=12 || trace.stream().anyMatch(t -> t.stepId().equals(step)))throw new AgentFailure("SKILL_TRACE_INVALID",503);
        trace.add(new Trace(step,status,action,List.copyOf(refs),code,latency));
    }
    public SkillResult result(){return new SkillResult(definition.id(),definition.version(),definition.hash(),
            new Diagnosis(classification,List.copyOf(findings),List.of()),List.copyOf(trace));}
    /** Approval loss invalidates policy citations and any synthesis trace, but preserves server observations. */
    public void discardPolicy(){
        trace.replaceAll(t -> Set.of("RETRIEVE_POLICY","SUMMARIZE").contains(t.stepId())
                ?new Trace(t.stepId(),"UNAVAILABLE",t.actionName(),List.of(),"APPROVAL_UNVERIFIED",t.latencyMs()):t);
    }
}
