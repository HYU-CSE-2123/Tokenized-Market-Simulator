package com.pricetrack.exchange.reserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import static com.pricetrack.exchange.reserve.ReserveModels.*;
import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import jakarta.annotation.PostConstruct;

/** Short repeatable-read snapshots; no FOR UPDATE and no RPC while this transaction is open. */
@Service @DependsOn("entityManagerFactory")
@ConditionalOnProperty(name="app.reserve.enabled",havingValue="true")
public class ReserveStore {
    private static final int MAX=10000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ReserveStore(DataSource ds,ObjectMapper json) {
        this.jdbc=new JdbcTemplate(ds); this.jdbc.setQueryTimeout(3); this.json=json;
    }
    @PostConstruct void immutableEvidence() throws SQLException {
        try(var connection=jdbc.getDataSource().getConnection()) {
            if(!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        }
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_reserve_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only reserve evidence'; END $$");
        for(String table:List.of("reserve_baselines","faucet_grants","reserve_runs")) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname=? AND tgrelid=?::regclass",
                    Integer.class,table+"_immutable",table)==0)
                jdbc.execute("CREATE TRIGGER "+table+"_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON "+table+
                        " FOR EACH STATEMENT EXECUTE FUNCTION reject_reserve_mutation()");
        }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW,timeout=5)
    public DbCut cut() {
        Instant at=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant();
        var balances=bounded(jdbc.query("SELECT user_id,symbol,amount,locked_amount FROM user_balances ORDER BY user_id,symbol LIMIT 10001",
                (r,n)->new Balance(r.getLong(1),r.getString(2),wei(r,3),wei(r,4))));
        var orders=bounded(jdbc.query("SELECT id,user_id,side,execution_mode,status,input_amount,tx_hash,updated_at FROM orders ORDER BY id LIMIT 10001",
                (r,n)->new OrderRow(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),wei(r,6),r.getString(7),r.getTimestamp(8).toInstant())));
        var trades=bounded(jdbc.query("SELECT id,order_id,user_id,symbol,execution_mode,side,base_amount,quote_amount,fee,price,tx_hash,created_at FROM trades ORDER BY id LIMIT 10001",
                (r,n)->new TradeRow(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),wei(r,7),wei(r,8),wei(r,9),
                        r.getBigDecimal(10).setScale(8).unscaledValue(),r.getString(11),r.getTimestamp(12).toInstant())));
        var txs=bounded(jdbc.query("SELECT id,order_id,type,status,tx_hash,sender_address,nonce,block_number FROM blockchain_transactions WHERE type IN ('BUY','SELL') ORDER BY id LIMIT 10001",
                (r,n)->new TxRow(r.getLong(1),r.getObject(2,Long.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,Long.class),r.getObject(8,Long.class))));
        var grants=bounded(jdbc.query("SELECT id,user_id,amount,created_at FROM faucet_grants ORDER BY id LIMIT 10001",
                (r,n)->new Grant(r.getString(1),r.getLong(2),wei(r,3),r.getTimestamp(4).toInstant())));
        return new DbCut(at,balances,orders,trades,txs,grants);
    }
    private static <T> List<T> bounded(List<T> rows) {
        if(rows.size()>MAX) throw new CutLimitException(); return List.copyOf(rows);
    }
    private static BigInteger wei(ResultSet r,int index) throws SQLException { return TokenUnits.toWei(r.getBigDecimal(index)); }
    public Optional<Baseline> baseline() {
        return jdbc.query("SELECT payload FROM reserve_baselines WHERE id=1",
                (r,n)->decode(r.getString(1),Baseline.class)).stream().findFirst();
    }
    @Transactional public void createBaseline(Baseline value) {
        // PK=1 serializes simultaneous creates; no reset/upsert/update path.
        jdbc.update("INSERT INTO reserve_baselines(id,payload,actor_id,created_at) VALUES(1,?,?,?)",
                encode(value),value.actorId(),Timestamp.from(value.dbAt()));
    }
    @Transactional public Result save(Result value) {
        jdbc.update("INSERT INTO reserve_runs(id,payload,actor_id,created_at) VALUES(?,?,?,?)",
                value.id(),encode(value),value.actorId(),Timestamp.from(value.createdAt())); return value;
    }
    public List<Result> history() {
        return jdbc.query("SELECT payload FROM reserve_runs ORDER BY created_at DESC,id DESC LIMIT 20",
                (r,n)->decode(r.getString(1),Result.class));
    }
    public Optional<Result> result(String id) {
        return jdbc.query("SELECT payload FROM reserve_runs WHERE id=?", (r,n)->decode(r.getString(1),Result.class),id).stream().findFirst();
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Reserve serialization failed"); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch(Exception e) { throw new IllegalStateException("Reserve evidence unreadable"); }
    }
    public static class CutLimitException extends RuntimeException {}
}
