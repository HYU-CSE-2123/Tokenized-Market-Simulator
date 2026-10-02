package com.pricetrack.exchange.ai.diagnosis;

import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.diagnosis.DiagnosisSourceReader.Target;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;

/** Own scanner/dispatcher clocks; never executes on the trading reconciliation scheduler. */
public final class DiagnosisCoordinator implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(DiagnosisCoordinator.class);
    private final DiagnosisProperties p;private final DiagnosisSourceReader source;private final PgDiagnosisStore store;
    private final AgentService agent;private final DiagnosisPayload payload;private final String worker=UUID.randomUUID().toString();
    private ScheduledExecutorService scanner,dispatcher;private long cursor;
    public DiagnosisCoordinator(DiagnosisProperties p,DiagnosisSourceReader source,PgDiagnosisStore store,AgentService agent,DiagnosisPayload payload){this.p=p;this.source=source;this.store=store;this.agent=agent;this.payload=payload;}
    public void start(){
        if(!p.enabled() || !p.valid())return;
        scanner=Executors.newSingleThreadScheduledExecutor(r->thread(r,"ai-diagnosis-scanner"));
        dispatcher=Executors.newSingleThreadScheduledExecutor(r->thread(r,"ai-diagnosis-dispatcher"));
        scanner.scheduleWithFixedDelay(this::scan,0,p.pollIntervalMs(),TimeUnit.MILLISECONDS);
        dispatcher.scheduleWithFixedDelay(this::dispatch,0,p.pollIntervalMs(),TimeUnit.MILLISECONDS);
    }
    private Thread thread(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    public void scan(){
        if(!p.enabled() || !p.valid())return;
        try{
            var rows=source.scan(cursor,p.batchSize());
            for(Target t:rows)store.enqueue(t);
            cursor=rows.size()<p.batchSize()?0:rows.getLast().transactionId();store.purge();
        }catch(Exception e){log.warn("AutomaticDiagnosis scanner code=DIAGNOSIS_SCAN_UNAVAILABLE");}
    }
    public void dispatch(){
        if(!p.enabled() || !p.valid())return;
        PgDiagnosisStore.Job job=null;Long actorId=null;
        try{
            job=store.claim(worker);if(job==null)return;
            var actor=source.actor(p.actorLoginId());actorId=actor.userId();
            Target current=source.current(job.target().transactionId());
            if(!same(job.target(),current)) {store.finish(job,"SKIPPED","SKIPPED_STALE_TARGET",null,actorId,true);return;}
            if(!source.linked(current)){store.finish(job,"SKIPPED","SKIPPED_INVALID_LINK",null,actorId,false);return;}
            var response=agent.answerAutomatic(actor,current.orderId());
            if("AGENT_BUSY".equals(response.error()) && response.metrics().toolCalls()==0 && response.metrics().modelCalls()==0 && response.metrics().retrievalCalls()==0){store.busy(job);return;}
            Target latest=source.current(current.transactionId());
            boolean stale=!same(current,latest) || !Objects.equals(current.type(),latest.type());
            // Recheck the actor before publishing a stored interpretation.
            if(!source.actor(p.actorLoginId()).userId().equals(actorId))throw new DiagnosisFailure("DIAGNOSIS_ACTOR_UNAVAILABLE");
            String result=payload.capture(response);
            boolean saved=store.finish(job,"ERROR".equals(response.status())?"FAILED":"COMPLETED",response.error(),result,actorId,stale);
            log.info("AutomaticDiagnosis jobId={} actorId={} stored={} status={}",job.id(),actorId,saved,response.status());
        }catch(Exception e){
            log.warn("AutomaticDiagnosis dispatcher code=DIAGNOSIS_RUN_UNAVAILABLE");
            if(job!=null)try{store.finish(job,"FAILED",e instanceof DiagnosisFailure?e.getMessage():"DIAGNOSIS_RUN_UNAVAILABLE",null,actorId,false);}catch(Exception ignored){/* lease expires: no replay of an uncertain call */}
        }
    }
    private boolean same(Target expected,Target actual){return actual!=null && actual.valid() && actual.transactionId()==expected.transactionId()
        && Objects.equals(expected.orderId(),actual.orderId()) && expected.txHash()!=null && expected.txHash().equalsIgnoreCase(actual.txHash());}
    @Override public void close(){if(scanner!=null)scanner.shutdownNow();if(dispatcher!=null)dispatcher.shutdownNow();}
}
