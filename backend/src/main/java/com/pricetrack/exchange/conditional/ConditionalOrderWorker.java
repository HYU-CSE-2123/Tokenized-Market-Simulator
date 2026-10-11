package com.pricetrack.exchange.conditional;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.pricetrack.exchange.order.OnchainOrderService;
import com.pricetrack.exchange.common.exception.InsufficientBalanceException;
import com.pricetrack.exchange.quote.PriceQuoteUnavailableException;

/** Dedicated one-worker executor, no unbounded queue. This is NOT a distributed nonce/worker lock. */
@Component
public class ConditionalOrderWorker {
    private final ConditionalOrderService service;
    private final OnchainOrderService execution;
    private final String owner=UUID.randomUUID().toString();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"conditional-order-worker");t.setDaemon(true);return t;});
    private final AtomicBoolean queued=new AtomicBoolean();
    private final ReentrantLock gate=new ReentrantLock();
    public ConditionalOrderWorker(ConditionalOrderService service,OnchainOrderService execution){this.service=service;this.execution=execution;}
    @Scheduled(fixedDelayString="${app.conditional.poll-ms:1000}",initialDelayString="${app.conditional.initial-delay-ms:5000}")
    public void poll(){
        if(!service.enabled() || !queued.compareAndSet(false,true))return;
        try{executor.execute(()->{try{for(String id:service.due())evaluate(id);}finally{queued.set(false);}});}
        catch(RejectedExecutionException shutdown){queued.set(false);}
    }
    public void evaluate(String id){
        if(!service.enabled() || !gate.tryLock())return;
        try{
            var c=service.claim(id,owner);
            if(c==null){service.refresh(id);return;}
            var quote=service.issue(c);
            var prepared=service.prepare(id,owner,quote.getQuoteId());
            try{execution.submitPrepared(prepared);}catch(RuntimeException transport){/* Existing compensation/raw recovery owns execution. */}
            service.refresh(id);
        }catch(InsufficientBalanceException insufficient){service.defer(id,owner,"INSUFFICIENT_AVAILABLE_BALANCE",true);}
        catch(PriceQuoteUnavailableException expired){service.defer(id,owner,"QUOTE_UNAVAILABLE",false);}
        catch(ConditionalOrderService.Failure guarded){service.defer(id,owner,guarded.code,false);}
        catch(RuntimeException unavailable){service.defer(id,owner,"PREPARATION_UNAVAILABLE",false);}
        finally{gate.unlock();}
    }
    @PreDestroy void close(){executor.shutdown();}
}
