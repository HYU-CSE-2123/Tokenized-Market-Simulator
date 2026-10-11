package com.pricetrack.exchange.blockchain.observation;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Best-effort bounded telemetry. Failure cannot escape into recovery/settlement. No RPC here. */
@Component
public class ObservationRecorder {
    public enum Phase { SIGNED_RECOVERY, RECEIPT_CHECK }
    public enum Result { SUBMISSION_RECORDED, RECEIPT_NOT_FOUND, CONFIRMATIONS_PENDING, SUCCESS_SETTLED,
        FAILURE_SETTLED, REVIEW_REQUIRED, ATTEMPT_UNAVAILABLE }
    public record Attempt(long transactionId,String attemptId,Instant startedAt,Instant finishedAt,Phase phase,Result result){}
    private final ObservationStore store;
    private final ThreadPoolExecutor worker;
    private final AtomicLong dropped=new AtomicLong(),failed=new AtomicLong();
    private final AtomicReference<Instant> lastStart=new AtomicReference<>(Instant.MIN);
    public ObservationRecorder(ObservationStore store){
        this.store=store;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),r->{
            var t=new Thread(r,"transaction-observation-writer");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public Instant start(){return lastStart.updateAndGet(old->{var at=Instant.now().truncatedTo(ChronoUnit.MICROS);return at.isAfter(old)?at:old.plus(1,ChronoUnit.MICROS);});}
    public void record(Long id,Instant started,Phase phase,Result result){
        if(id==null)return;
        record(new Attempt(id,UUID.randomUUID().toString(),started,Instant.now().isBefore(started)?started:Instant.now(),phase,result));
    }
    public void record(Attempt a){
        try{worker.execute(()->{try{store.save(a);}catch(RuntimeException failure){failed.incrementAndGet();}});}
        catch(RuntimeException saturated){dropped.incrementAndGet();}
    }
    public java.util.Map<String,Object> health(){return java.util.Map.of("scope","PROCESS_LOCAL_RESET_ON_RESTART","queued",worker.getQueue().size(),"dropped",dropped.get(),"storageFailures",failed.get());}
    @PreDestroy public void close(){worker.shutdown();}
}
