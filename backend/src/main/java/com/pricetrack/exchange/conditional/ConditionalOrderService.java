package com.pricetrack.exchange.conditional;

import java.math.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.support.*;
import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.market.*;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;
import static com.pricetrack.exchange.conditional.ConditionalOrder.State.*;

/** Intent lifecycle only; execution balances, fees and settlement remain in the existing Order path. */
@Service @DependsOn("conditionalSchema")
public class ConditionalOrderService {
    public static class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    public record Request(String symbol,OrderSide side,BigDecimal inputAmount,BigDecimal targetPrice,Instant expiresAt){}
    public record Page(List<View> items,String nextBefore){}
    public record View(String id,String symbol,OrderSide side,BigDecimal inputAmount,BigDecimal targetPrice,
        ConditionalOrder.State state,Instant expiresAt,Instant createdAt,Instant updatedAt,
        Long executionOrderId,String reasonCode,boolean assetsReserved) {
        static View of(ConditionalOrder c){return new View(c.getId(),"mSEC",c.getSide(),c.getInputAmount(),
            PriceUnits.fromPriceE8(c.getTargetPriceE8()),c.getState(),c.getExpiresAt(),c.getCreatedAt(),c.getUpdatedAt(),
            c.getExecutionOrderId(),c.getReasonCode(),c.getExecutionOrderId()!=null && (c.getState()==EXECUTING||c.getState()==REVIEW_REQUIRED));}
    }
    private final ConditionalOrderRepository intents;
    private final PriceQuoteService quotes;
    private final PriceQuoteRepository quoteRows;
    private final OnchainOrderPreparationService preparation;
    private final OrderRepository orders;
    private final BlockchainTransactionRepository transactions;
    private final MarketPriceService market;
    private final JdbcTemplate jdbc;
    private final WebSocketEventPublisher events;
    private final BlockchainProperties blockchain;
    private final Environment environment;
    private final boolean enabled,fixture;
    public ConditionalOrderService(ConditionalOrderRepository intents,PriceQuoteService quotes,PriceQuoteRepository quoteRows,
            OnchainOrderPreparationService preparation,OrderRepository orders,BlockchainTransactionRepository transactions,
            MarketPriceService market,JdbcTemplate jdbc,WebSocketEventPublisher events,BlockchainProperties blockchain,
            Environment environment,@Value("${app.conditional.enabled:false}") boolean enabled,
            @Value("${app.conditional.fixture:false}") boolean fixture){
        this.intents=intents;this.quotes=quotes;this.quoteRows=quoteRows;this.preparation=preparation;this.orders=orders;
        this.transactions=transactions;this.market=market;this.jdbc=jdbc;this.events=events;this.blockchain=blockchain;
        this.environment=environment;this.enabled=enabled;this.fixture=fixture;
    }
    public boolean enabled(){return enabled && blockchain.enabled()
        && environment.getProperty("app.blockchain.price-report.enabled",Boolean.class,false)
        && !Arrays.asList(environment.getActiveProfiles()).contains("public");}
    public Instant now(){
        boolean pg=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
            connection->connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        return jdbc.queryForObject(pg?"SELECT clock_timestamp()":"SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class).toInstant();
    }
    public boolean fixtureAllowed(){
        if(!fixture || !Arrays.asList(environment.getActiveProfiles()).contains("conditional-fixture")
            || !blockchain.rpcUrl().matches("http://(127\\.0\\.0\\.1|localhost):[0-9]+/?"))return false;
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            String url=connection.getMetaData().getURL();
            return url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/(exchange_trade_audit_e2e|exchange_conditional[^?]*)(\\?.*)?")
                || url.startsWith("jdbc:h2:mem:conditional-test");
        }));
    }
    public boolean registrationEnabled(){
        if(!enabled())return false;
        try{return !com.pricetrack.exchange.market.MarketDataEligibility.synthetic(market.current())||fixtureAllowed();}
        catch(RuntimeException unavailable){return false;}
    }
    public MarketPriceSnapshot checkedMarket(){
        if(!enabled())throw new Failure(409,"CONDITIONAL_DISABLED");
        var snapshot=market.current();
        boolean valid=com.pricetrack.exchange.market.MarketDataEligibility.liveExternal(snapshot)
            || (fixtureAllowed() && snapshot.provider().equalsIgnoreCase("SIMULATED")
                && snapshot.marketStatus()==MarketStatus.OPEN && snapshot.priceStatus()==PriceStatus.SIMULATED);
        Duration age=Duration.between(snapshot.observedAt(),now());
        if(!valid || age.compareTo(Duration.ofSeconds(5))>0 || age.compareTo(Duration.ofSeconds(-2))<0)
            throw new Failure(409,"MARKET_UNAVAILABLE");
        return snapshot;
    }
    public static boolean matches(OrderSide side,BigInteger price,BigInteger target){
        int comparison=price.compareTo(target);
        return side==OrderSide.BUY?comparison<=0:comparison>=0;
    }
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String key(String value){
        if(value==null || !value.matches("[A-Za-z0-9_-]{16,128}"))throw new Failure(400,"INVALID_IDEMPOTENCY_KEY");
        return hash("conditional-order:key:v1:"+value);
    }
    @Transactional(timeout=5)
    public View create(long uid,String rawKey,Request r){
        String fingerprint=key(rawKey);
        if(r==null || !"mSEC".equals(r.symbol()) || r.side()==null || r.inputAmount()==null || r.inputAmount().signum()<=0
            || r.expiresAt()==null)throw new Failure(400,"INVALID_CONDITIONAL_ORDER");
        BigInteger amount,target;
        if(r.inputAmount().scale()>18 || r.inputAmount().precision()-r.inputAmount().scale()>12
            || r.targetPrice()==null || r.targetPrice().scale()>8 || r.targetPrice().precision()-r.targetPrice().scale()>70)
            throw new Failure(400,"INVALID_CONDITIONAL_ORDER");
        try{amount=TokenUnits.toWei(r.inputAmount());target=PriceUnits.toPriceE8(r.targetPrice());}
        catch(IllegalArgumentException invalid){throw new Failure(400,"INVALID_CONDITIONAL_ORDER");}
        if(amount.toString().length()>30 || target.toString().length()>78 || r.expiresAt().getNano()!=0)
            throw new Failure(400,"INVALID_CONDITIONAL_ORDER");
        String requestHash=hash(r.symbol()+":"+r.side()+":"+amount+":"+target+":"+r.expiresAt());
        // Short owner-row lock serializes same-key registrations; never held over external I/O.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,uid);
        var prior=intents.findByUserIdAndRequestKeyHash(uid,fingerprint);
        if(prior.isPresent()){
            if(!prior.get().getRequestHash().equals(requestHash))throw new Failure(409,"CONDITIONAL_KEY_CONFLICT");
            return View.of(prior.get());
        }
        if(!enabled())throw new Failure(409,"CONDITIONAL_DISABLED");
        var snapshot=market.current();
        if(com.pricetrack.exchange.market.MarketDataEligibility.synthetic(snapshot) && !fixtureAllowed())throw new Failure(409,"CONDITIONAL_PROVIDER_DISABLED");
        Instant at=now();
        if(!r.expiresAt().isAfter(at))throw new Failure(400,"INVALID_EXPIRY");
        ConditionalOrder c=new ConditionalOrder(); c.setId(UUID.randomUUID().toString());c.setUserId(uid);c.setSide(r.side());
        c.setInputAmount(new BigDecimal(amount,18));c.setTargetPriceE8(target);c.setExpiresAt(r.expiresAt());
        c.setCreatedAt(at);c.setUpdatedAt(at);c.setNextEvaluationAt(at);c.setState(WAITING);
        c.setRequestKeyHash(fingerprint);c.setRequestHash(requestHash);
        intents.saveAndFlush(c);publish(c);return View.of(c);
    }
    @Transactional(readOnly=true)
    public View request(long uid,String rawKey){return View.of(intents.findByUserIdAndRequestKeyHash(uid,key(rawKey))
        .orElseThrow(()->new Failure(404,"CONDITIONAL_NOT_FOUND")));}
    @Transactional(readOnly=true)
    public View find(long uid,String id){return View.of(intents.findById(id).filter(c->c.getUserId()==uid)
        .orElseThrow(()->new Failure(404,"CONDITIONAL_NOT_FOUND")));}
    @Transactional(readOnly=true)
    public Page list(Long uid,String before){
        Instant at=Instant.parse("9999-01-01T00:00:00Z");String id="~";
        if(before!=null){var cursor=intents.findById(before).filter(c->uid==null||c.getUserId().equals(uid))
            .orElseThrow(()->new Failure(404,"CONDITIONAL_NOT_FOUND"));at=cursor.getCreatedAt();id=cursor.getId();}
        var rows=intents.history(uid,at,id,PageRequest.of(0,41));
        var result=rows.stream().limit(40).map(View::of).toList();
        return new Page(result,rows.size()>40?result.get(result.size()-1).id():null);
    }
    @Transactional(timeout=5)
    public View cancel(long uid,String id){
        var c=intents.lock(id).filter(found->found.getUserId()==uid).orElseThrow(()->new Failure(404,"CONDITIONAL_NOT_FOUND"));
        if(c.getState()==CANCELLED)return View.of(c);
        if(c.getExecutionOrderId()!=null || (c.getState()!=WAITING && c.getState()!=PREPARING))throw new Failure(409,"CONDITIONAL_NOT_CANCELLABLE");
        change(c,now().isBefore(c.getExpiresAt())?CANCELLED:EXPIRED,null);return View.of(c);
    }
    public List<String> due(){return intents.due(List.of(WAITING,PREPARING,EXECUTING,REVIEW_REQUIRED),now(),PageRequest.of(0,20));}
    @Transactional(timeout=5)
    public ConditionalOrder claim(String id,String owner){
        var c=intents.lock(id).orElse(null);
        if(c==null || c.getExecutionOrderId()!=null || (c.getState()!=WAITING && c.getState()!=PREPARING))return null;
        if(!now().isBefore(c.getExpiresAt())){change(c,EXPIRED,null);return null;}
        // Called only under the single-process worker gate. Foreign PREPARING is a pre-order restart recovery.
        MarketPriceSnapshot snapshot;
        try{snapshot=checkedMarket();}
        catch(RuntimeException unavailable){change(c,WAITING,"MARKET_UNAVAILABLE");return null;}
        if(!matches(c.getSide(),PriceUnits.toPriceE8(snapshot.price()),c.getTargetPriceE8())){
            change(c,WAITING,"PRICE_NOT_TRIGGERED");return null;
        }
        c.setWorkerOwner(owner);change(c,PREPARING,null);return c;
    }
    public PriceQuote issue(ConditionalOrder c){return quotes.issue(c.getUserId(),"mSEC",
        c.getSide()==OrderSide.BUY?PriceReport.Side.BUY:PriceReport.Side.SELL,c.getInputAmount());}
    private void checkedCondition(ConditionalOrder c){
        var snapshot=checkedMarket();
        if(!matches(c.getSide(),PriceUnits.toPriceE8(snapshot.price()),c.getTargetPriceE8()))
            throw new Failure(409,"PRICE_CONDITION_CHANGED");
    }
    @Transactional(timeout=5)
    public OnchainOrderPreparationService.PreparedOrder prepare(String id,String owner,String quoteId){
        var c=intents.lock(id).orElseThrow(()->new Failure(404,"CONDITIONAL_NOT_FOUND"));
        if(c.getState()!=PREPARING || !owner.equals(c.getWorkerOwner()) || c.getExecutionOrderId()!=null)
            throw new Failure(409,"CONDITIONAL_CLAIM_LOST");
        if(!now().isBefore(c.getExpiresAt()))throw new Failure(409,"CONDITIONAL_EXPIRED");
        checkedCondition(c);
        var quote=quoteRows.findForUpdateByQuoteId(quoteId).orElseThrow(()->new Failure(409,"QUOTE_UNAVAILABLE"));
        if(!matches(c.getSide(),quote.getPriceE8(),c.getTargetPriceE8()))throw new Failure(409,"QUOTE_CONDITION_CHANGED");
        var prepared=preparation.prepareWithinTransaction(c.getUserId(),c.getSide(),c.getInputAmount(),quoteId);
        // Balance/quote locks may wait: revalidate after those waits and rollback the whole new preparation.
        Instant finalTime=now();
        if(!finalTime.isBefore(c.getExpiresAt()))throw new Failure(409,"CONDITIONAL_EXPIRED");
        if(finalTime.isAfter(quote.getValidUntil()))throw new Failure(409,"QUOTE_UNAVAILABLE");
        checkedCondition(c);
        c.setExecutionOrderId(prepared.order().getId());change(c,EXECUTING,null);
        return prepared;
    }
    @Transactional(timeout=5)
    public void defer(String id,String owner,String reason,boolean terminal){
        var c=intents.lock(id).orElse(null);
        if(c==null || c.getExecutionOrderId()!=null || (c.getState()!=WAITING && c.getState()!=PREPARING))return;
        if(c.getState()==PREPARING && !Objects.equals(owner,c.getWorkerOwner()))return;
        change(c,!now().isBefore(c.getExpiresAt())?EXPIRED:terminal?FAILED:WAITING,reason);
    }
    @Transactional(timeout=5,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void refresh(String id){
        var c=intents.lock(id).orElse(null);if(c==null || c.getExecutionOrderId()==null)return;
        var order=orders.findById(c.getExecutionOrderId()).orElse(null);
        var tx=transactions.findByOrderId(c.getExecutionOrderId()).orElse(null);
        if(order==null){change(c,REVIEW_REQUIRED,"EXECUTION_EVIDENCE_MISSING");return;}
        if(!c.getUserId().equals(order.getUserId()) || c.getSide()!=order.getSide()
            || c.getInputAmount().compareTo(order.getInputAmount())!=0 || order.getExecutionMode()!=ExecutionMode.ONCHAIN){
            change(c,REVIEW_REQUIRED,"EXECUTION_EVIDENCE_MISMATCH");return;
        }
        if(tx!=null && tx.getStatus()==BlockchainTransactionStatus.REVIEW_REQUIRED){change(c,REVIEW_REQUIRED,"RECEIPT_REVIEW_REQUIRED");return;}
        if(order.getStatus()==OrderStatus.FILLED && tx!=null && tx.getStatus()==BlockchainTransactionStatus.CONFIRMED){
            var prices=jdbc.queryForList("SELECT price FROM trades WHERE order_id=?",BigDecimal.class,order.getId());
            if(prices.size()!=1 || !matches(c.getSide(),PriceUnits.toPriceE8(prices.get(0)),c.getTargetPriceE8())){
                change(c,REVIEW_REQUIRED,"SETTLED_PRICE_CONDITION_MISMATCH");return;
            }
            change(c,SUCCEEDED,null);return;
        }
        if(order.getStatus()==OrderStatus.FAILED && (tx==null || tx.getStatus()==BlockchainTransactionStatus.FAILED)){change(c,FAILED,"EXECUTION_FAILED");return;}
        if(tx==null){change(c,REVIEW_REQUIRED,"PRE_SUBMIT_INTERRUPTED");return;}
        if(tx.getStatus()==BlockchainTransactionStatus.CONFIRMED || tx.getStatus()==BlockchainTransactionStatus.FAILED){
            change(c,REVIEW_REQUIRED,"EXECUTION_EVIDENCE_MISMATCH");return;
        }
        change(c,EXECUTING,"AWAITING_RECEIPT");
    }
    private void change(ConditionalOrder c,ConditionalOrder.State state,String reason){
        boolean changed=c.getState()!=state || !Objects.equals(c.getReasonCode(),reason);
        c.setState(state);c.setReasonCode(reason);c.setUpdatedAt(now());c.setNextEvaluationAt(c.getUpdatedAt().plusSeconds(5));
        if(changed)publish(c);
    }
    private void publish(ConditionalOrder c){events.publishToUser(c.getUserId(),"/queue/conditional-orders",
        WebSocketEventType.CONDITIONAL_ORDER_UPDATED,View.of(c));}
}
