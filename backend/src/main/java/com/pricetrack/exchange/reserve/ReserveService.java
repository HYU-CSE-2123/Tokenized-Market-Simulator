package com.pricetrack.exchange.reserve;

import static com.pricetrack.exchange.reserve.ReserveModels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Observes only; never invokes trading/settlement, signs a transaction or holds balance write locks. */
@Service @ConditionalOnProperty(name="app.reserve.enabled",havingValue="true")
public class ReserveService {
    private final ReserveStore store;
    private final ReserveChainReader chain;
    private final String executionId;
    private final Semaphore job=new Semaphore(1);
    public ReserveService(ReserveStore store,ReserveChainReader chain,
            @Value("${app.reserve.execution-id:}") String executionId) {
        this.store=store; this.chain=chain; this.executionId=executionId;
    }
    public Baseline createBaseline(long actor) {
        acquire();
        try {
            if(executionId.isBlank()) throw conflict("EXECUTION_ID_REQUIRED");
            if(store.baseline().isPresent()) throw conflict("IMMUTABLE_BASELINE_EXISTS");
            Block block=chain.head(); DbCut db=store.cut();
            // Only a fresh zero-ledger environment can establish independent provenance.
            if(!db.orders().isEmpty() || !db.trades().isEmpty() || !db.transactions().isEmpty() || !db.grants().isEmpty()
                    || db.balances().stream().anyMatch(b->b.amount().signum()!=0 || b.locked().signum()!=0))
                throw conflict("FRESH_ZERO_LEDGER_REQUIRED");
            Chain snapshot=chain.read(block);
            if(!snapshot.rolesValid() || !stable(block,db)) throw conflict("BASELINE_CUT_NOT_STABLE");
            Baseline value=new Baseline(UUID.randomUUID().toString(),executionId,db.at(),actor,snapshot);
            try { store.createBaseline(value); } catch(DataIntegrityViolationException e) { throw conflict("IMMUTABLE_BASELINE_EXISTS"); }
            return value;
        } finally { job.release(); }
    }
    public Optional<Baseline> baseline() { return store.baseline(); }
    public List<Result> history() { return store.history(); }
    public Result detail(String id) {
        try { UUID.fromString(id); } catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESULT_ID"); }
        return store.result(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"RESULT_NOT_FOUND"));
    }
    public Result run(long actor) {
        acquire();
        Baseline baseline=null; DbCut db=null; Chain snapshot=null; String status="INCONCLUSIVE",reason="BASELINE_REQUIRED";
        List<Check> checks=List.of();
        try {
            baseline=store.baseline().orElse(null);
            if(baseline!=null) {
                Block block=chain.head(); db=store.cut(); snapshot=chain.read(block);
                String boundary=boundary(baseline,db,snapshot);
                if(boundary!=null) reason=boundary;
                else {
                    boolean receipts=true;
                    long deadline=System.nanoTime()+15_000_000_000L;
                    for(TradeRow t:db.trades()) {
                        if(System.nanoTime()>deadline) throw new ReserveStore.CutLimitException();
                        if(!t.mode().equals("ONCHAIN")) continue;
                        OrderRow order=db.orders().stream().filter(o->o.id()==t.orderId()).findFirst().orElseThrow();
                        TxRow tx=db.transactions().stream().filter(x->Objects.equals(x.orderId(),order.id())).findFirst().orElseThrow();
                        if(!chain.receiptMatches(t,order,tx,snapshot)) { receipts=false; break; }
                    }
                    Set<String> known=new HashSet<>();
                    db.trades().stream().filter(t->t.mode().equals("ONCHAIN")).forEach(t->known.add(t.hash().toLowerCase(Locale.ROOT)));
                    boolean unknown=receipts && chain.hasUnknownTransfers(baseline.chain().block(),block,known);
                    // Cut validation precedes any MISMATCH verdict, even if provisional checks failed.
                    if(!stable(block,db)) reason="CUT_CHANGED";
                    else if(!receipts) reason="RECEIPT_NOT_VERIFIABLE";
                    else {
                        checks=ReserveCalculator.calculate(db,baseline.chain(),snapshot);
                        if(unknown) {
                            var withFlow=new ArrayList<>(checks);
                            withFlow.add(new Check("UNEXPECTED_CHAIN_MOVEMENT","0","1","1",false)); checks=List.copyOf(withFlow);
                        }
                        status=checks.stream().allMatch(Check::matches)?"MATCH":"MISMATCH";
                        reason=status.equals("MATCH")?"EXACT_QUANTITY_MATCH":"UNEXPLAINED_QUANTITY_OR_CONTRACT_DIFFERENCE";
                    }
                }
            }
        } catch(ReserveStore.CutLimitException e) { reason="BOUNDED_READ_LIMIT"; }
        catch(Exception e) { status="UNAVAILABLE"; reason="DEPENDENCY_READ_FAILED"; }
        finally { job.release(); }
        // Do not combine allocation and chain resources when the cut was not established.
        Liquidity liquidity=Set.of("MATCH","MISMATCH").contains(status)?ReserveCalculator.liquidity(db,snapshot):null;
        return store.save(new Result(UUID.randomUUID().toString(),status,reason,Instant.now(),actor,
                baseline,db==null?null:db.at(),snapshot,checks,liquidity));
    }
    private String boundary(Baseline baseline,DbCut db,Chain snapshot) {
        if(!baseline.executionId().equals(executionId) || !baseline.chain().identity().equals(snapshot.identity())
                || !chain.canonical(baseline.chain().block())) return "BASELINE_ENVIRONMENT_NOT_VERIFIABLE";
        if(snapshot.block().number().compareTo(baseline.chain().block().number())<0) return "CHAIN_BEFORE_BASELINE";
        if(db.orders().stream().anyMatch(o->o.mode().equals("UNKNOWN"))) return "UNKNOWN_ORDER_EXECUTION";
        if(db.orders().stream().anyMatch(o->o.status().equals("REQUESTED") || o.status().equals("PENDING_ONCHAIN"))
                || db.transactions().stream().anyMatch(t->Set.of("CREATED","SIGNED","SUBMITTED","REVIEW_REQUIRED").contains(t.status())))
            return "UNSETTLED_ORDER_OR_REVIEW";
        for(TradeRow t:db.trades()) {
            OrderRow o=db.orders().stream().filter(x->x.id()==t.orderId()).findFirst().orElse(null);
            if(o==null || !o.mode().equals(t.mode()) || o.userId()!=t.userId() || !t.symbol().equals("mSEC")
                    || !o.side().equals(t.side()) || !o.status().equals("FILLED")
                    || !o.input().equals(o.side().equals("BUY")?t.quote():t.base())) return "ORDER_TRADE_LINK_NOT_VERIFIABLE";
            if(o.mode().equals("ONCHAIN")) {
                TxRow tx=db.transactions().stream().filter(x->Objects.equals(x.orderId(),o.id())).findFirst().orElse(null);
                if(tx==null || !o.side().equals(tx.type()) || !"CONFIRMED".equals(tx.status()) || tx.block()==null
                        || java.math.BigInteger.valueOf(tx.block()).compareTo(snapshot.block().number())>0
                        || !Objects.equals(t.hash(),tx.hash()) || !Objects.equals(t.hash(),o.hash())
                        || !snapshot.identity().operator().equalsIgnoreCase(tx.sender()==null?"":tx.sender())) return "ONCHAIN_LINK_OR_CUT_NOT_VERIFIABLE";
            } else if(!o.mode().equals("DB_ONLY") || t.hash()!=null || o.hash()!=null
                    || db.transactions().stream().anyMatch(tx->Objects.equals(tx.orderId(),o.id())))
                return "UNKNOWN_TRADE_EXECUTION";
        }
        if(db.orders().stream().anyMatch(o->o.status().equals("FILLED") && db.trades().stream().noneMatch(t->t.orderId()==o.id())))
            return "FILLED_WITHOUT_TRADE";
        if(db.transactions().stream().anyMatch(tx->"CONFIRMED".equals(tx.status()) && db.trades().stream().noneMatch(t->Objects.equals(tx.orderId(),t.orderId()))))
            return "CONFIRMED_WITHOUT_TRADE";
        return null;
    }
    private boolean stable(Block block,DbCut first) {
        DbCut second=store.cut();
        return fingerprint(first).equals(fingerprint(second)) && block.equals(chain.head()) && chain.canonical(block);
    }
    private String fingerprint(DbCut db) {
        return store.encode(List.of(db.balances(),db.orders(),db.trades(),db.transactions(),db.grants()));
    }
    private void acquire() { if(!job.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"RECONCILIATION_BUSY"); }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT,code); }
}
