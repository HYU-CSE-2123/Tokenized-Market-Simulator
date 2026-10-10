package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.reserve.ReserveModels.Baseline;
import jakarta.annotation.PostConstruct;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.web3j.crypto.Hash;

/** Source snapshot is read-only; audit writes use separate short transactions. */
@Service @DependsOn("entityManagerFactory")
@ConditionalOnProperty(name="app.trade-audit.enabled",havingValue="true")
public class AuditStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AuditStore(DataSource source,ObjectMapper json) {
        this.jdbc=new JdbcTemplate(source); this.jdbc.setQueryTimeout(3); this.json=json;
    }
    @PostConstruct void immutable() throws SQLException {
        try(var c=jdbc.getDataSource().getConnection()) {
            if(!c.getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        }
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_trade_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only trade audit evidence'; END $$");
        for(String table:List.of("trade_audit_runs","trade_audit_results","trade_audit_items")) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname=? AND tgrelid=?::regclass",Integer.class,table+"_immutable",table)==0)
                jdbc.execute("CREATE TRIGGER "+table+"_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON "+table+" FOR EACH STATEMENT EXECUTE FUNCTION reject_trade_audit_mutation()");
        }
        // Interrupted work is never automatically retried after startup.
        for(Run run:running()) finish(new Run(run.id(),run.actorId(),"INCOMPLETE","INCONCLUSIVE","PROCESS_INTERRUPTED",
            run.startedAt(),Instant.now(),run.block(),run.baselineId(),run.executionId(),run.manifestHash(),run.manifest(),
            new Coverage(false,false,false,false,0,0,0,0),0,0,0),List.of());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public Cut cut() {
        Instant at=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
        boolean postgres;
        try(var c=jdbc.getDataSource().getConnection()) { postgres=c.getMetaData().getDatabaseProductName().equals("PostgreSQL"); }
        catch(SQLException e) { throw new Unavailable("DB_READ_FAILED"); }
        String snapshot=postgres?jdbc.queryForObject("SELECT txid_current_snapshot()::text",String.class):"H2_FIXTURE";
        sourceBudget(postgres);
        Baseline baseline=jdbc.query("SELECT payload FROM reserve_baselines WHERE id=1",(r,n)->decode(r.getString(1),Baseline.class)).stream().findFirst().orElse(null);
        var orders=bounded(jdbc.query("SELECT id,user_id,symbol,side,execution_mode,status,input_amount,tx_hash,updated_at FROM orders ORDER BY id LIMIT 50001",
            (r,n)->new OrderRow(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),wei(r,7),r.getString(8),utc(r,9))));
        var trades=bounded(jdbc.query("SELECT id,order_id,user_id,symbol,side,execution_mode,base_amount,quote_amount,fee,price,tx_hash,created_at FROM trades ORDER BY id LIMIT 50001",
            (r,n)->new TradeRow(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),wei(r,7),wei(r,8),wei(r,9),r.getBigDecimal(10).movePointRight(8).toBigIntegerExact(),r.getString(11),utc(r,12))));
        var txs=bounded(jdbc.query("SELECT id,order_id,type,status,tx_hash,sender_address,nonce,block_number,raw_transaction FROM blockchain_transactions WHERE type IN ('BUY','SELL') ORDER BY id LIMIT 50001",
            (r,n)->new TxRow(r.getLong(1),r.getObject(2,Long.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,Long.class),r.getObject(8,Long.class),digest(r.getString(9)),r.getString(9))));
        var quotes=bounded(jdbc.query("SELECT quote_id,order_id,user_id,symbol,side,status,price_e8,input_amount,minimum_output,executor,observed_at,valid_until,signature FROM price_quotes WHERE order_id IS NOT NULL OR status='CONSUMED' ORDER BY quote_id LIMIT 50001",
            (r,n)->new QuoteRow(r.getString(1),r.getObject(2,Long.class),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getBigDecimal(7).toBigIntegerExact(),r.getBigDecimal(8).toBigIntegerExact(),r.getBigDecimal(9).toBigIntegerExact(),r.getString(10),utc(r,11),utc(r,12),digest(r.getString(13)),r.getString(13))));
        Cut result=new Cut(at,snapshot,baseline,orders,trades,txs,quotes);
        if(encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16_000_000) throw new Unavailable("DB_SNAPSHOT_LIMIT");
        return result;
    }
    // Same RR snapshot, before JDBC materializes or hashes any raw/signature TEXT. No source mutation.
    void sourceBudget(boolean postgres) {
        long bytes=0;
        for(String source:List.of("orders","trades","blockchain_transactions WHERE type IN ('BUY','SELL')","price_quotes WHERE order_id IS NOT NULL OR status='CONSUMED'")) {
            String length=postgres?"octet_length(row_to_json(s)::text)":source.startsWith("blockchain_transactions")?"octet_length(COALESCE(raw_transaction,''))":source.startsWith("price_quotes")?"octet_length(COALESCE(signature,''))":"0";
            var size=jdbc.queryForMap("SELECT count(*) AS row_count,COALESCE(SUM("+length+"),0) AS byte_count FROM (SELECT * FROM "+source+") s");
            if(((Number)size.get("row_count")).longValue()>50_000) throw new Unavailable("DB_SNAPSHOT_LIMIT");
            bytes=Math.addExact(bytes,((Number)size.get("byte_count")).longValue());
            if(bytes>16_000_000) throw new Unavailable("DB_SNAPSHOT_LIMIT");
        }
    }
    private <T> List<T> bounded(List<T> list) { if(list.size()>50000) throw new Unavailable("DB_SNAPSHOT_LIMIT"); return List.copyOf(list); }
    private static java.math.BigInteger wei(ResultSet r,int n) throws SQLException { return TokenUnits.toWei(r.getBigDecimal(n)); }
    // JPA Instant columns are UTC TIMESTAMP without timezone; JVM local Calendar must not shift them.
    static Instant utc(ResultSet r,int n) throws SQLException { return r.getTimestamp(n,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(); }
    public String fingerprint(Cut c) { return digest(encode(List.of(c.baseline()==null?"NONE":c.baseline(),c.orders(),c.trades(),c.transactions(),c.quotes()))); }
    public static String digest(String text) { return text==null?null:Hash.sha3String(text); }
    @Transactional public void start(Run run) { jdbc.update("INSERT INTO trade_audit_runs(id,payload,created_at) VALUES(?,?,?)",run.id(),encode(run),Timestamp.from(run.startedAt())); }
    @Transactional(timeout=5) public void finish(Run run,List<Item> items) {
        jdbc.update("INSERT INTO trade_audit_results(run_id,payload) VALUES(?,?)",run.id(),encode(run));
        var batch=new ArrayList<Object[]>(); int n=0;
        for(Item item:items) batch.add(new Object[]{run.id(),++n,item.orderId(),item.mode(),item.side(),item.verdict(),encode(item)});
        jdbc.batchUpdate("INSERT INTO trade_audit_items(run_id,seq,order_id,mode,side,verdict,payload) VALUES(?,?,?,?,?,?,?)",batch);
    }
    public Optional<Run> detail(String id) { return jdbc.query("SELECT COALESCE(f.payload,r.payload) FROM trade_audit_runs r LEFT JOIN trade_audit_results f ON f.run_id=r.id WHERE r.id=?",(r,n)->decode(r.getString(1),Run.class),id).stream().findFirst(); }
    public List<Run> running() { return jdbc.query("SELECT r.payload FROM trade_audit_runs r LEFT JOIN trade_audit_results f ON f.run_id=r.id WHERE f.run_id IS NULL",(r,n)->decode(r.getString(1),Run.class)); }
    public Page<Run> history(String before) {
        var rows=jdbc.query("SELECT COALESCE(f.payload,r.payload) FROM trade_audit_runs r LEFT JOIN trade_audit_results f ON f.run_id=r.id WHERE (CAST(? AS VARCHAR) IS NULL OR (r.created_at,r.id)<(SELECT created_at,id FROM trade_audit_runs WHERE id=?)) ORDER BY r.created_at DESC,r.id DESC LIMIT 21",
                (r,n)->decode(r.getString(1),Run.class),before,before);
        return new Page<>(rows.stream().limit(20).toList(),rows.size()>20?rows.get(19).id():null);
    }
    public Page<Item> items(String id,int before,String verdict,String mode,String side,Long orderId) {
        var rows=jdbc.query("SELECT seq,payload FROM trade_audit_items WHERE run_id=? AND seq>? AND (CAST(? AS VARCHAR) IS NULL OR verdict=?) AND (CAST(? AS VARCHAR) IS NULL OR mode=?) AND (CAST(? AS VARCHAR) IS NULL OR side=?) AND (CAST(? AS BIGINT) IS NULL OR order_id=?) ORDER BY seq LIMIT 51",
            (r,n)->Map.entry(r.getInt(1),decode(r.getString(2),Item.class)),id,before,verdict,verdict,mode,mode,side,side,orderId,orderId);
        return new Page<>(rows.stream().limit(50).map(Map.Entry::getValue).toList(),rows.size()>50?String.valueOf(rows.get(49).getKey()):null);
    }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new Unavailable("AUDIT_SERIALIZATION_FAILED"); } }
    private <T> T decode(String text,Class<T> type) { try { return json.readValue(text,type); } catch(Exception e) { throw new Unavailable("AUDIT_EVIDENCE_UNREADABLE"); } }
}
