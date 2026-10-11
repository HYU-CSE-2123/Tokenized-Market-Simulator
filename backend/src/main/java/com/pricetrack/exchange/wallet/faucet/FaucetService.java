package com.pricetrack.exchange.wallet.faucet;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.common.exception.BalanceNotFoundException;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;
import static com.pricetrack.exchange.wallet.faucet.FaucetStore.*;

/** Account -> global budget -> balance. Replay precedes current policy/eligibility checks. */
@Service
public class FaucetService {
    public static final String REQUEST = FaucetPolicy.hash("POST /api/wallet/faucet:{}:v1");
    private final FaucetStore store;
    private final UserBalanceRepository balances;
    private final UserWebSocketPublisher events;
    private final Map<String,LongAdder> rejections=new ConcurrentHashMap<>();
    private final Instant startedAt=Instant.now();
    public FaucetService(FaucetStore store,UserBalanceRepository balances,UserWebSocketPublisher events) {
        this.store=store;this.balances=balances;this.events=events;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,timeout=8)
    public Result credit(long uid,String rawKey,String request) {
        String key=FaucetPolicy.key(rawKey);
        store.accountLock(uid);
        var replay=store.claim(uid,key,request,true);
        if(replay.isPresent()) return replay.get();
        if(!request.equals(REQUEST)) throw new FaucetFailure(400,"FAUCET_INVALID_REQUEST");
        store.budgetLock();
        if(!store.configured()) throw new FaucetFailure(503,"FAUCET_POLICY_UNAVAILABLE");
        if(!store.consistent()) throw new FaucetFailure(503,"FAUCET_ACCOUNTING_INCONSISTENT");
        var balance=balances.findForUpdate(uid,"mKRW").orElseThrow(BalanceNotFoundException::new);
        // DB clock AFTER every potentially waiting lock, not request/transaction arrival.
        Instant at=store.decisionTime();
        String reason=FaucetPolicy.reason(store.count(uid),store.last(uid),store.spent(),at);
        if(reason!=null) throw new FaucetFailure(reason.equals("FAUCET_DAILY_LIMIT")?429:reason.equals("FAUCET_CLOCK_REVERSED")?503:409,reason);
        balance.setAmount(balance.getAmount().add(FaucetPolicy.AMOUNT));
        var result=new Result("mKRW",FaucetPolicy.AMOUNT,balance.getAmount(),UUID.randomUUID().toString(),at,store.version,false);
        store.save(uid,key,request,result);
        events.publishPortfolio(uid);
        return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Result result(long uid,String rawKey) {
        return store.claim(uid,FaucetPolicy.key(rawKey),REQUEST,true).orElseThrow(()->new FaucetFailure(404,"FAUCET_CLAIM_NOT_FOUND"));
    }
    public record Status(boolean eligible,String reason,long remainingCount,LocalDate decisionDate,
                         Instant nextEligibleAt,String policyVersion,BigDecimal receivedAmount) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Status status(long uid) {
        Instant at=store.decisionTime();
        String reason=!store.configured()?"FAUCET_POLICY_UNAVAILABLE":!store.consistent()?"FAUCET_ACCOUNTING_INCONSISTENT":FaucetPolicy.reason(store.count(uid),store.last(uid),store.spent(),at);
        return new Status(reason==null,reason,Math.max(0,FaucetPolicy.LIFETIME_COUNT-store.count(uid)),FaucetPolicy.day(at),
            "FAUCET_DAILY_LIMIT".equals(reason)?FaucetPolicy.nextDay(at):null,store.version,FaucetPolicy.AMOUNT);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Page history(Long uid,String before) { return store.history(uid,before); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Status accountStatus(long uid) {
        if(store.jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=? AND symbol='mKRW'",Long.class,uid)==0)throw new FaucetFailure(404,"FAUCET_ACCOUNT_NOT_FOUND");
        return status(uid);
    }
    public record Admin(String configuredVersion,String activeVersion,String contentHash,String policy,
                        BigDecimal spent,BigDecimal remainingBudget,long grantCount,boolean configured,
                        boolean journalConsistent,Instant rejectionMetricsStartedAt,Map<String,Long> rejections) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Admin admin() {
        Map<String,Long> counts=new TreeMap<>();rejections.forEach((k,v)->counts.put(k,v.sum()));
        var row=store.jdbc.queryForMap("SELECT b.*,p.content_hash,p.content FROM faucet_budget_usage b LEFT JOIN faucet_policy_versions p ON b.active_version=p.version WHERE b.id=1");
        return new Admin(store.version,(String)row.get("active_version"),(String)row.get("content_hash"),(String)row.get("content"),store.spent(),FaucetPolicy.BUDGET.subtract(store.spent()).max(BigDecimal.ZERO),((Number)row.get("grant_count")).longValue(),store.configured(),store.consistent(),startedAt,Map.copyOf(counts));
    }
    public void rejection(String code) {
        if(Set.of("FAUCET_INVALID_KEY","FAUCET_KEY_CONFLICT","FAUCET_INVALID_REQUEST","FAUCET_POLICY_UNAVAILABLE","FAUCET_ACCOUNTING_INCONSISTENT","FAUCET_DAILY_LIMIT","FAUCET_LIFETIME_LIMIT","FAUCET_BUDGET_EXHAUSTED","FAUCET_CLOCK_REVERSED","FAUCET_DB_UNAVAILABLE").contains(code))
            rejections.computeIfAbsent(code,k->new LongAdder()).increment();
    }
}
