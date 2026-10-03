package com.pricetrack.exchange.ai.observability;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** Best-effort process-local counters. Fixed labels only; no prompts, payloads or identifiers. */
@Component
public class AiObservability {
    private static final Logger log=LoggerFactory.getLogger(AiObservability.class);
    private static final Set<String> STAGES=Set.of("AGENT","RAG","RETRIEVAL","LLM","EMBEDDING","TOOL");
    private static final Set<String> NAMES=Set.of("UNKNOWN","answer","search","index","call","KNOWLEDGE","STATE","MIXED","CLARIFY","UNSUPPORTED","ERROR",
        "getOrder","getQuote","getBlockchainTransaction","getReceiptSummary","getMarketStatus","getCurrentReferencePrice","getPortfolio","listAbnormalOrders");
    private static final Set<String> CODES=Set.of("OK","ANSWERED","PARTIAL","UNSUPPORTED","NEEDS_CLARIFICATION","INSUFFICIENT_EVIDENCE","LIVE_DATA_REQUIRED","AI_BUSY","AGENT_BUSY","AGENT_TIMEOUT",
        "SYNTHESIS_UNAVAILABLE","PLAN_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED","AGENT_CONTEXT_LIMIT","INVALID_AGENT_EVIDENCE","INVALID_AGENT_PLAN","RESOURCE_NOT_FOUND",
        "AUTHENTICATION_REQUIRED","AGENT_DISABLED","AGENT_CONFIGURATION_UNAVAILABLE","AGENT_INPUT_LIMIT","INVALID_AGENT_REQUEST","AGENT_UNAVAILABLE",
        "TOOL_DISABLED","TOOL_FORBIDDEN","TOOL_NOT_FOUND","TOOL_TIMEOUT","TOOL_BUSY","TOOL_NOT_ALLOWED","TOOL_UNAVAILABLE","INVALID_TOOL_ARGUMENTS",
        "AI_INPUT_LIMIT","AI_PROVIDER_UNAVAILABLE","AI_PROVIDER_RESPONSE_INVALID","AI_API_KEY_MISSING","AI_PROVIDER_CALL_LIMIT","AI_PROVIDER_CONFIG_INVALID",
        "AI_INDEX_NOT_READY","AI_APPROVAL_MISMATCH","AI_QUERY_TIMEOUT","AI_ROLE_FILTER_INVALID","AI_DOMAIN_FILTER_INVALID","AI_KNOWLEDGE_INVALID",
        "AI_CITATION_INVALID","AI_VECTOR_INVALID","AI_DATABASE_UNAVAILABLE","OTHER_FAILURE");
    private final ConcurrentHashMap<String,Counter> counters=new ConcurrentHashMap<>();
    private final ThreadLocal<String> correlation=new ThreadLocal<>();
    private final String startedAt=java.time.Instant.now().toString();
    public <T>T correlated(String id,Supplier<T> work){
        String previous=correlation.get();correlation.set(safeId(id));
        try{return work.get();}finally{if(previous==null)correlation.remove();else correlation.set(previous);}
    }
    public <T>T measure(String stage,String name,Supplier<T> work){
        long start=System.nanoTime();boolean success=false;String code="OTHER_FAILURE";
        try{T result=work.get();success=true;code="OK";return result;}
        catch(RuntimeException e){code=e.getMessage();throw e;}
        finally{record(stage,name,correlation.get(),start,success,code,0,null,null);}
    }
    public void record(String stage,String name,String id,long startedNanos,boolean success,String code,long documents,Long input,Long output){
        try{
            if(!STAGES.contains(stage))return;
            String label=name!=null && NAMES.contains(name)?name:"UNKNOWN";
            String failure=code!=null && CODES.contains(code)?code:success?"OK":"OTHER_FAILURE";
            long nanos=Math.max(0,System.nanoTime()-startedNanos);
            counters.computeIfAbsent(stage+":"+label,k->new Counter()).add(nanos,success,failure,documents,input,output,
                stage.equals("LLM")||stage.equals("EMBEDDING"));
            log.info("AiObservation runId={} stage={} name={} success={} code={} latencyMs={}",safeId(id==null?correlation.get():id),stage,label,success,failure,nanos/1_000_000);
        }catch(RuntimeException ignored){/* Observation must never fail a business operation. */}
    }
    public Map<String,Object> snapshot(){
        Map<String,Object> values=new TreeMap<>();counters.forEach((k,v)->values.put(k,v.snapshot()));
        return Map.of("scope","PROCESS_LOCAL_RESET_ON_RESTART","startedAt",startedAt,"series",values,"latencyMeaning","caller-observed; timeout does not prove physical call termination");
    }
    private static String safeId(String id){return id!=null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")?id:null;}
    private static final class Counter {
        long calls,failed,nanos,max,documents,input,output,reported,unknown;
        final Map<String,Long> codes=new TreeMap<>();
        synchronized void add(long n,boolean success,String code,long docs,Long in,Long out,boolean provider){
            calls++;if(!success)failed++;nanos+=n;max=Math.max(max,n);documents+=Math.max(0,docs);codes.merge(code,1L,Long::sum);
            if(provider){if(in!=null && out!=null && in>=0 && out>=0){reported++;input+=in;output+=out;}else unknown++;}
        }
        synchronized Map<String,Object> snapshot(){
            return Map.ofEntries(Map.entry("calls",calls),Map.entry("failures",failed),Map.entry("sumLatencyMs",nanos/1_000_000d),
                Map.entry("averageLatencyMs",calls==0?0:nanos/1_000_000d/calls),Map.entry("maxLatencyMs",max/1_000_000d),
                Map.entry("retrievedDocuments",documents),Map.entry("reportedInputTokens",input),Map.entry("reportedOutputTokens",output),
                Map.entry("usageReportedCalls",reported),Map.entry("usageUnknownCalls",unknown),Map.entry("codes",Map.copyOf(codes)));
        }
    }
}
