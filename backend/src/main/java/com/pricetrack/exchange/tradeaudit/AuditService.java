package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A dedicated single worker observes records; it has no trading/settlement dependencies. */
@Service @ConditionalOnProperty(name="app.trade-audit.enabled",havingValue="true")
public class AuditService {
    private final AuditStore store;
    private final AuditChain chain;
    private final AuditValidator validator;
    private final String executionId;
    private final int confirmations,seconds;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"trade-audit-readonly"); t.setDaemon(true); return t;});
    public AuditService(AuditStore store,AuditChain chain,AuditValidator validator,
            @Value("${app.reserve.execution-id:}") String executionId,
            @Value("${app.blockchain.reconciliation.required-confirmations:1}") int confirmations,
            @Value("${app.trade-audit.timeout-seconds:30}") int seconds) {
        this.store=store;this.chain=chain;this.validator=validator;this.executionId=executionId;this.confirmations=confirmations;this.seconds=seconds;
        if(confirmations<1 || seconds<1 || seconds>120) throw new IllegalArgumentException("Invalid audit limits");
    }
    @PreDestroy void stop() { worker.shutdownNow(); }
    public Run start(long actor) {
        if(!busy.compareAndSet(false,true)) throw new ResponseStatusException(HttpStatus.CONFLICT,"AUDIT_BUSY");
        Run r=new Run(UUID.randomUUID().toString(),actor,"RUNNING","INCONCLUSIVE","OBSERVING",Instant.now(),null,null,null,executionId,null,null,new Coverage(false,false,false,false,0,0,0,0),0,0,0);
        try { store.start(r); worker.submit(()->observe(r)); return r; }
        catch(RuntimeException e) { busy.set(false); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AUDIT_START_FAILED"); }
    }
    private void observe(Run initial) {
        var items=new ArrayList<Item>(); Cut db=null; Block block=null;
        var reads=chain.session(); boolean dbComplete=false,chainComplete=false,stable=false,env=false;
        int eventCount=0; String reason="OBSERVATION_COMPLETE";
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        try {
            block=reads.head(); db=store.cut(); dbComplete=true;
            env=db.baseline()!=null && !executionId.isBlank() && executionId.equals(db.baseline().executionId()) && reads.environment(db.baseline());
            var ts=new HashMap<Long,List<TradeRow>>(); for(var t:db.trades()) ts.computeIfAbsent(t.orderId(),k->new ArrayList<>()).add(t);
            var xs=new HashMap<Long,List<TxRow>>(); for(var t:db.transactions()) xs.computeIfAbsent(t.orderId(),k->new ArrayList<>()).add(t);
            var qs=new HashMap<Long,List<QuoteRow>>(); for(var q:db.quotes()) qs.computeIfAbsent(q.orderId(),k->new ArrayList<>()).add(q);
            Set<Long> ids=new HashSet<>(); Map<String,OrderRow> hashOrders=new HashMap<>();
            for(var order:db.orders()) {
                budget(deadline); ids.add(order.id()); if(order.hash()!=null) hashOrders.put(AuditValidator.lower(order.hash()),order);
                try {
                    items.add(validator.inspect(order,ts.getOrDefault(order.id(),List.of()),xs.getOrDefault(order.id(),List.of()),qs.getOrDefault(order.id(),List.of()),reads,block,env?db.baseline():null,confirmations));
                } catch(Unavailable e) { items.add(AuditValidator.simple("order:"+order.id(),order,"INCONCLUSIVE",e.code)); }
                catch(RuntimeException e) { items.add(AuditValidator.simple("order:"+order.id(),order,"INCONCLUSIVE","EVIDENCE_UNREADABLE")); }
            }
            for(var trade:db.trades()) if(!ids.contains(trade.orderId())) items.add(orphan("trade:"+trade.id(),trade.orderId(),trade.hash(),"DB_TRADE_WITHOUT_ORDER"));
            for(var tx:db.transactions()) if(tx.orderId()==null || !ids.contains(tx.orderId())) items.add(orphan("tx:"+tx.id(),tx.orderId(),tx.hash(),"DB_TRANSACTION_WITHOUT_ORDER"));
            for(var q:db.quotes()) if(!ids.contains(q.orderId())) items.add(orphan("quote:"+q.id(),q.orderId(),null,"DB_QUOTE_WITHOUT_ORDER"));
            if(env) {
                var logs=reads.scan(db.baseline().chain().block().number().add(java.math.BigInteger.ONE),block.number());
                eventCount=logs.size();
                Set<String> known=db.transactions().stream().map(TxRow::hash).filter(Objects::nonNull).map(AuditValidator::lower).collect(java.util.stream.Collectors.toSet());
                Map<String,Block> blocks=new HashMap<>();
                for(var log:logs) {
                    budget(deadline);
                    Block observed=new Block(log.getBlockNumber(),log.getBlockHash(),java.math.BigInteger.ZERO);
                    if(!blocks.containsKey(log.getBlockHash())) {
                        if(!reads.canonical(observed)) throw new Unavailable("REORG"); blocks.put(log.getBlockHash(),observed);
                    }
                    OrderRow order=hashOrders.get(AuditValidator.lower(log.getTransactionHash()));
                    if(order==null) {
                        boolean pending=known.contains(AuditValidator.lower(log.getTransactionHash()));
                        items.add(new Item("event:"+log.getTransactionHash()+":"+log.getLogIndex(),null,"ONCHAIN",null,"CHAIN_EVENT",
                            pending?"INCONCLUSIVE":"MISMATCH",pending?"UNSETTLED_CHAIN_EVENT":"CHAIN_EVENT_WITHOUT_DB_SETTLEMENT",
                            log.getTransactionHash(),log.getBlockNumber().longValueExact(),log.getBlockHash(),List.of(),List.of()));
                    }
                }
                chainComplete=items.stream().noneMatch(i->i.limitations().stream().anyMatch(l->Set.of("CHAIN_EVIDENCE_INCOMPLETE","CHAIN_NOT_INSPECTED","QUOTE_NOT_INSPECTED").contains(l)));
            } else reason="ENVIRONMENT_NOT_VERIFIABLE";
            budget(deadline);
            Cut after=store.cut();
            stable=store.fingerprint(db).equals(store.fingerprint(after)) && reads.canonical(block) && reads.head().equals(block);
            if(!stable) {
                reason="CUT_CHANGED";
                items.replaceAll(i->new Item(i.key(),i.orderId(),i.mode(),i.side(),i.kind(),"INCONCLUSIVE","CUT_CHANGED",i.txHash(),i.receiptBlock(),i.receiptHash(),i.checks(),i.limitations()));
            }
        } catch(Unavailable e) { reason=e.code; }
        catch(Exception e) { reason="DEPENDENCY_READ_FAILED"; }
        try {
            int match=(int)items.stream().filter(i->i.verdict().equals("MATCH")).count();
            int mismatch=(int)items.stream().filter(i->i.verdict().equals("MISMATCH")).count();
            int uncertain=items.size()-match-mismatch;
            boolean complete=dbComplete && chainComplete && stable && env && uncertain==0;
            String verdict=complete?(mismatch==0?"MATCH":"MISMATCH"):"INCONCLUSIVE";
            String lifecycle=dbComplete && chainComplete && stable?"COMPLETED":"INCOMPLETE";
            if(reason.equals("OBSERVATION_COMPLETE") && (uncertain>0 || !chainComplete)) reason="ITEM_EVIDENCE_INCOMPLETE";
            if(!stable) items.replaceAll(i->new Item(i.key(),i.orderId(),i.mode(),i.side(),i.kind(),"INCONCLUSIVE",reasonCode(i),i.txHash(),i.receiptBlock(),i.receiptHash(),i.checks(),i.limitations()));
            // Count only final item verdicts, never provisional comparisons from an unstable cut.
            match=(int)items.stream().filter(i->i.verdict().equals("MATCH")).count(); mismatch=(int)items.stream().filter(i->i.verdict().equals("MISMATCH")).count(); uncertain=items.size()-match-mismatch;
            store.finish(new Run(initial.id(),initial.actorId(),lifecycle,verdict,reason,initial.startedAt(),Instant.now(),block,
                db==null||db.baseline()==null?null:db.baseline().id(),executionId,db==null?null:store.fingerprint(db),db,
                new Coverage(dbComplete,chainComplete,stable,env,db==null?0:db.orders().size(),eventCount,items.size(),reads.calls()),match,mismatch,uncertain),items);
        } finally { busy.set(false); }
    }
    private String reasonCode(Item i) { return i.reason().equals("CUT_CHANGED")?i.reason():"CUT_NOT_ESTABLISHED"; }
    private void budget(long deadline) { if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new Unavailable("RUN_DEADLINE"); }
    private Item orphan(String key,Long order,String hash,String reason) { return new Item(key,order,"UNKNOWN",null,"DB_ORPHAN","MISMATCH",reason,hash,null,null,List.of(),List.of()); }
    public Run detail(String id) { return publicView(store.detail(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"AUDIT_NOT_FOUND"))); }
    public Page<Run> history(String before) { var page=store.history(before); return new Page<>(page.items().stream().map(this::publicView).toList(),page.nextBefore()); }
    // API metadata must not dump tens of thousands of source rows; inspect through paginated items.
    private Run publicView(Run r) {
        Cut c=r.manifest();
        Cut summary=c==null?null:new Cut(c.at(),c.snapshot(),c.baseline(),List.of(),List.of(),List.of(),List.of());
        return new Run(r.id(),r.actorId(),r.lifecycle(),r.verdict(),r.reason(),r.startedAt(),r.completedAt(),r.block(),r.baselineId(),r.executionId(),r.manifestHash(),summary,r.coverage(),r.match(),r.mismatch(),r.inconclusive());
    }
    public Page<Item> items(String id,int before,String verdict,String mode,String side,Long order) { detail(id); return store.items(id,before,verdict,mode,side,order); }
}
