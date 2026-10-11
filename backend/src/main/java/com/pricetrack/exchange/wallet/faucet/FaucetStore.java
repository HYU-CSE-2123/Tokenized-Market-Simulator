package com.pricetrack.exchange.wallet.faucet;

import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.*;

/** All mutation helpers run inside the caller's single JPA/PostgreSQL transaction. No RPC. */
@Component @DependsOn("entityManagerFactory")
public class FaucetStore {
    final JdbcTemplate jdbc;
    final String version;
    boolean postgres;
    public FaucetStore(DataSource ds,@Value("${app.faucet.policy-version:kst-v1}") String version) {
        this.jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3);this.version=version;
        if(!version.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid faucet policy version");
    }
    @PostConstruct void initialize() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()){postgres=c.getMetaData().getDatabaseProductName().equals("PostgreSQL");}
        new ResourceDatabasePopulator(new ClassPathResource("faucet-schema.sql")).execute(jdbc.getDataSource());
        if(postgres) {
            jdbc.execute("CREATE OR REPLACE FUNCTION reject_faucet_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'append-only faucet evidence'; END $$");
            for(String table:List.of("faucet_grants","faucet_policy_versions","faucet_claims"))
                if(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname=? AND tgrelid=?::regclass",Integer.class,table+"_policy_immutable",table)==0)
                    jdbc.execute("CREATE TRIGGER "+table+"_policy_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON "+table+" FOR EACH STATEMENT EXECUTE FUNCTION reject_faucet_mutation()");
            jdbc.update("INSERT INTO faucet_policy_versions(version,content_hash,content,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(version) DO NOTHING",version,FaucetPolicy.hash(FaucetPolicy.CONTENT),FaucetPolicy.CONTENT);
            // Activation only in an empty ledger. Never infer prior credits from current balances.
            jdbc.update("INSERT INTO faucet_budget_usage(id,active_version,grant_count,spent,initialized) SELECT 1,?,0,0,NOT EXISTS(SELECT 1 FROM users) AND NOT EXISTS(SELECT 1 FROM faucet_grants) AND NOT EXISTS(SELECT 1 FROM user_balances WHERE amount<>0 OR locked_amount<>0) AND NOT EXISTS(SELECT 1 FROM orders) ON CONFLICT(id) DO NOTHING",version);
        } else {
            if(jdbc.queryForObject("SELECT count(*) FROM faucet_policy_versions WHERE version=?",Integer.class,version)==0)
                jdbc.update("INSERT INTO faucet_policy_versions(version,content_hash,content,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",version,FaucetPolicy.hash(FaucetPolicy.CONTENT),FaucetPolicy.CONTENT);
            if(jdbc.queryForObject("SELECT count(*) FROM faucet_budget_usage",Integer.class)==0)
                jdbc.update("INSERT INTO faucet_budget_usage(id,active_version,grant_count,spent,initialized) VALUES(1,?,0,0,TRUE)",version);
        }
    }
    void accountLock(long uid) {
        if(postgres){jdbc.execute("SET LOCAL lock_timeout='2000ms'");jdbc.execute("SET LOCAL statement_timeout='3000ms'");
            jdbc.update("INSERT INTO faucet_account_usage(user_id,grant_count) VALUES(?,0) ON CONFLICT(user_id) DO NOTHING",uid);
        } else if(jdbc.queryForObject("SELECT count(*) FROM faucet_account_usage WHERE user_id=?",Integer.class,uid)==0)
            jdbc.update("INSERT INTO faucet_account_usage(user_id,grant_count) VALUES(?,0)",uid);
        jdbc.queryForObject("SELECT user_id FROM faucet_account_usage WHERE user_id=? FOR UPDATE",Long.class,uid);
    }
    void budgetLock() { jdbc.queryForObject("SELECT id FROM faucet_budget_usage WHERE id=1 FOR UPDATE",Integer.class); }
    public Instant decisionTime() { return jdbc.queryForObject(postgres?"SELECT clock_timestamp()":"SELECT CURRENT_TIMESTAMP",Timestamp.class).toInstant(); }
    long count(long uid) { return jdbc.queryForObject("SELECT COALESCE(MAX(grant_count),0) FROM faucet_account_usage WHERE user_id=?",Long.class,uid); }
    LocalDate last(long uid) { return jdbc.query("SELECT last_day FROM faucet_account_usage WHERE user_id=?",(r,n)->r.getObject(1,LocalDate.class),uid).stream().filter(Objects::nonNull).findFirst().orElse(null); }
    BigDecimal spent() { return jdbc.queryForObject("SELECT spent FROM faucet_budget_usage WHERE id=1",BigDecimal.class); }
    boolean configured() {
        return jdbc.queryForObject("SELECT count(*) FROM faucet_budget_usage b JOIN faucet_policy_versions p ON p.version=b.active_version WHERE b.id=1 AND b.initialized=TRUE AND b.active_version=? AND p.content_hash=? AND p.content=?",Integer.class,version,FaucetPolicy.hash(FaucetPolicy.CONTENT),FaucetPolicy.CONTENT)==1;
    }
    /** Compare complete journal, claims and all account counters, not merely the caller. */
    public boolean consistent() {
        long bad=jdbc.queryForObject("SELECT count(*) FROM faucet_account_usage a WHERE a.grant_count<>(SELECT count(*) FROM faucet_grants g WHERE g.user_id=a.user_id)",Long.class);
        bad+=jdbc.queryForObject("SELECT count(*) FROM faucet_grants g LEFT JOIN faucet_account_usage a ON a.user_id=g.user_id LEFT JOIN faucet_claims c ON c.grant_id=g.id WHERE a.user_id IS NULL OR c.grant_id IS NULL OR c.user_id<>g.user_id OR c.received_amount<>g.amount OR c.granted_at<>g.created_at",Long.class);
        bad+=jdbc.queryForObject("SELECT count(*) FROM faucet_claims c LEFT JOIN faucet_grants g ON g.id=c.grant_id WHERE g.id IS NULL",Long.class);
        bad+=jdbc.queryForObject("SELECT count(*) FROM faucet_grants WHERE amount<>?",Long.class,FaucetPolicy.AMOUNT);
        bad+=jdbc.query("SELECT a.last_day,MAX(g.created_at) AS latest FROM faucet_account_usage a LEFT JOIN faucet_grants g ON a.user_id=g.user_id GROUP BY a.user_id,a.last_day",(r,n)-> {
            Instant latest=r.getTimestamp("latest",Calendar.getInstance(TimeZone.getTimeZone("UTC")))==null?null:utc(r,"latest");
            return Objects.equals(r.getObject("last_day",LocalDate.class),latest==null?null:FaucetPolicy.day(latest))?0:1;
        }).stream().mapToInt(Integer::intValue).sum();
        if(bad!=0) return false;
        return jdbc.queryForObject("SELECT count(*) FROM faucet_budget_usage b WHERE b.id=1 AND b.grant_count=(SELECT count(*) FROM faucet_grants) AND b.spent=(SELECT COALESCE(sum(amount),0) FROM faucet_grants)",Integer.class)==1;
    }
    public record Result(String symbol,BigDecimal receivedAmount,BigDecimal balance,String grantId,Instant grantedAt,String policyVersion,boolean replayed) {}
    Optional<Result> claim(long uid,String key,String request,boolean replay) {
        return jdbc.query("SELECT * FROM faucet_claims WHERE user_id=? AND key_fingerprint=?",(r,n)-> {
            if(!r.getString("request_fingerprint").equals(request)) throw new FaucetFailure(409,"FAUCET_KEY_CONFLICT");
            return new Result("mKRW",r.getBigDecimal("received_amount"),r.getBigDecimal("balance"),r.getString("grant_id"),utc(r,"granted_at"),r.getString("policy_version"),replay);
        },uid,key).stream().findFirst();
    }
    void save(long uid,String key,String request,Result r) {
        jdbc.update("INSERT INTO faucet_grants(id,user_id,amount,created_at) VALUES(?,?,?,?)",p->{
            p.setString(1,r.grantId());p.setLong(2,uid);p.setBigDecimal(3,r.receivedAmount());p.setTimestamp(4,Timestamp.from(r.grantedAt()),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        });
        jdbc.update("INSERT INTO faucet_claims(grant_id,user_id,key_fingerprint,request_fingerprint,policy_version,received_amount,balance,granted_at) VALUES(?,?,?,?,?,?,?,?)",p->{
            p.setString(1,r.grantId());p.setLong(2,uid);p.setString(3,key);p.setString(4,request);p.setString(5,r.policyVersion());p.setBigDecimal(6,r.receivedAmount());p.setBigDecimal(7,r.balance());p.setTimestamp(8,Timestamp.from(r.grantedAt()),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        });
        jdbc.update("UPDATE faucet_account_usage SET grant_count=grant_count+1,last_day=? WHERE user_id=?",FaucetPolicy.day(r.grantedAt()),uid);
        jdbc.update("UPDATE faucet_budget_usage SET grant_count=grant_count+1,spent=spent+? WHERE id=1",r.receivedAmount());
    }
    static Instant utc(ResultSet r,String column) throws SQLException {return r.getTimestamp(column,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant();}
    public record Grant(String grantId,long userId,BigDecimal amount,Instant grantedAt,String policyVersion) {}
    public record Page(List<Grant> items,String nextBefore) {}
    public Page history(Long uid,String before) {
        if(before!=null && !before.matches("[0-9a-f-]{36}")) throw new FaucetFailure(400,"FAUCET_INVALID_CURSOR");
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
        if(uid!=null){where+=" AND g.user_id=?";args.add(uid);}
        if(before!=null){where+=" AND (g.created_at,g.id)<(SELECT created_at,id FROM faucet_grants WHERE id=?"+(uid!=null?" AND user_id=?":"")+")";args.add(before);if(uid!=null)args.add(uid);}
        var rows=jdbc.query("SELECT g.*,c.policy_version FROM faucet_grants g LEFT JOIN faucet_claims c ON c.grant_id=g.id"+where+" ORDER BY g.created_at DESC,g.id DESC LIMIT 21",(r,n)->new Grant(r.getString("id"),r.getLong("user_id"),r.getBigDecimal("amount"),utc(r,"created_at"),r.getString("policy_version")),args.toArray());
        return new Page(List.copyOf(rows.subList(0,Math.min(20,rows.size()))),rows.size()>20?rows.get(19).grantId():null);
    }
}
