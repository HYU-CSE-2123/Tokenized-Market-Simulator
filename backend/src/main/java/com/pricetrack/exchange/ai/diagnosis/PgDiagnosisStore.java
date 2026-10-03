package com.pricetrack.exchange.ai.diagnosis;

import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.diagnosis.DiagnosisSourceReader.Target;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Private AI JDBC pool. No trading TransactionManager, FK or long-running transaction. */
public final class PgDiagnosisStore implements AutoCloseable {
    private final HikariDataSource pool;
    private final DiagnosisProperties properties;
    public record Job(long id,Target target,String claimToken,int admissionAttempts) {}
    public record Row(long id,Long orderId,String jobStatus,Instant detectedAt,Instant startedAt,Instant completedAt,
            boolean targetStale,String errorCode,String result) {}
    public PgDiagnosisStore(AiProperties ai,DiagnosisProperties properties){
        this.properties=properties;HikariConfig c=new HikariConfig();
        c.setJdbcUrl(ai.jdbcUrl());c.setUsername(ai.dbUser());c.setPassword(ai.dbPassword());
        c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setConnectionTimeout(2000);c.setInitializationFailTimeout(-1);
        c.setPoolName("ai-diagnosis");c.addDataSourceProperty("connectTimeout","2");c.addDataSourceProperty("socketTimeout","5");
        pool=new HikariDataSource(c);
    }
    public void initialize(){
        try(Connection c=pool.getConnection();Statement s=c.createStatement()){
            s.setQueryTimeout(5);try(ResultSet r=s.executeQuery("select current_database()")){
                r.next();if(!r.getString(1).matches("exchange_ai(_[a-z0-9_]+)?"))throw new DiagnosisFailure("DIAGNOSIS_DATABASE_TARGET_INVALID");
            }
            s.execute(new ClassPathResource("ai/diagnosis-schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }catch(DiagnosisFailure e){throw e;}catch(Exception e){throw unavailable();}
    }
    public boolean enqueue(Target t){
        // Only the irreversible SHA-256 fingerprint is persisted, never the raw composite.
        String key=KnowledgeLoader.hash(properties.sourceNamespace()+":"+t.transactionId()+":"+(t.txHash()==null?"MISSING":t.txHash().toLowerCase(Locale.ROOT))+":REVIEW_REQUIRED");
        return transaction(c->{
            lock(c);
            if(scalar(c,"select count(*) from ai.diagnoses where job_status='QUEUED'")>=properties.queueLimit())return false;
            return update(c,"""
                insert into ai.diagnoses(source_namespace,event_key,transaction_id,order_id,tx_hash,job_status,error_code,completed_at,retention_expires_at)
                values(?,?,?,?,?,?,?,case when ? then now() else null end,case when ? then now()+(? * interval '1 day') else null end) on conflict(event_key) do nothing
                """,properties.sourceNamespace(),key,t.transactionId(),t.orderId(),t.txHash(),t.valid()?"QUEUED":"SKIPPED",
                    t.valid()?null:"SKIPPED_INVALID_LINK",!t.valid(),!t.valid(),properties.retentionDays())>0;
        });
    }
    public Job claim(String worker){return transaction(c->{
        lock(c);
        update(c,"update ai.diagnoses set job_status='INTERRUPTED',error_code='DIAGNOSIS_LEASE_EXPIRED',completed_at=now(),retention_expires_at=now()+(? * interval '1 day') where job_status='RUNNING' and lease_until<=now()",properties.retentionDays());
        if(scalar(c,"select count(*) from ai.diagnoses where job_status='RUNNING'")>0)return null;
        if(scalar(c,"select coalesce((select starts from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date),0)")>=properties.dailyLimit())return null;
        try(PreparedStatement s=statement(c,"select id,transaction_id,order_id,tx_hash,admission_attempts from ai.diagnoses where job_status='QUEUED' and source_namespace=? and available_at<=now() order by id limit 1 for update skip locked",properties.sourceNamespace());ResultSet r=s.executeQuery()){
            if(!r.next())return null;
            long id=r.getLong(1);Target t=new Target(r.getLong(2),(Long)r.getObject(3),r.getString(4),"REVIEW_REQUIRED","BUY");
            // Type is re-read and checked against the actual order before invocation, not trusted from the queue.
            String token=UUID.randomUUID().toString();int attempts=r.getInt(5)+1;
            update(c,"update ai.diagnoses set job_status='RUNNING',started_at=now(),claim_token=?,claimed_by=?,lease_until=now()+interval '90 seconds',admission_attempts=? where id=?",token,worker,attempts,id);
            update(c,"insert into ai.diagnosis_daily_usage(usage_date,starts) values((now() at time zone 'UTC')::date,1) on conflict(usage_date) do update set starts=ai.diagnosis_daily_usage.starts+1");
            return new Job(id,t,token,attempts);
        }
    });}
    public boolean busy(Job j){return transaction(c->{
        lock(c);
        int changed=update(c,"""
            update ai.diagnoses set job_status=?,error_code=?,completed_at=case when ? then now() else null end,
                available_at=now()+interval '10 seconds',claim_token=null,lease_until=null,
                retention_expires_at=case when ? then now()+(? * interval '1 day') else null end
            where id=? and claim_token=? and job_status='RUNNING' and lease_until>now()
            """,j.admissionAttempts()<3?"QUEUED":"FAILED",j.admissionAttempts()<3?null:"AGENT_BUSY",j.admissionAttempts()>=3,j.admissionAttempts()>=3,properties.retentionDays(),j.id(),j.claimToken());
        if(changed>0)update(c,"update ai.diagnosis_daily_usage set starts=greatest(0,starts-1) where usage_date=(select (started_at at time zone 'UTC')::date from ai.diagnoses where id=?)",j.id());
        return changed>0;
    });}
    public boolean finish(Job j,String status,String error,String result,Long actorId,boolean stale){
        if(!Set.of("COMPLETED","FAILED","SKIPPED","INTERRUPTED").contains(status))throw new IllegalArgumentException();
        if(result!=null && result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw new DiagnosisFailure("DIAGNOSIS_OUTPUT_LIMIT");
        return transaction(c->update(c,"""
            update ai.diagnoses set job_status=?,error_code=?,result=?::jsonb,actor_user_id=?,target_stale=?,completed_at=now(),
             retention_expires_at=now()+(? * interval '1 day')
            where id=? and claim_token=? and job_status='RUNNING' and lease_until>now()
            """,status,error,result,actorId,stale,properties.retentionDays(),j.id(),j.claimToken())>0);
    }
    public void purge(){transaction(c->{
        // Backfill old result-free terminal markers without touching queued/running jobs or identity.
        update(c,"update ai.diagnoses set retention_expires_at=coalesce(completed_at,detected_at)+(? * interval '1 day') where retention_expires_at is null and job_status in ('COMPLETED','FAILED','INTERRUPTED','SKIPPED')",properties.retentionDays());
        update(c,"update ai.diagnoses set result=null,actor_user_id=null,claim_token=null,claimed_by=null,tx_hash=null where retention_expires_at<=now() and (result is not null or actor_user_id is not null or tx_hash is not null or claim_token is not null or claimed_by is not null)");
        return null;
    });}
    /** Bounded AI-only operational snapshot; never substitutes zero for a database outage. */
    public Map<String,Long> observation(){return transaction(c->{
        try(var s=statement(c,"select count(*) filter (where job_status='QUEUED'),count(*) filter (where job_status='RUNNING'),coalesce((select starts from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date),0) from ai.diagnoses");var r=s.executeQuery()){
            r.next();return Map.of("queuedGlobal",r.getLong(1),"runningGlobal",r.getLong(2),"claimsTodayUtcGlobal",r.getLong(3));
        }
    });}
    public List<Row> list(Long order,String status,long before,int limit){
        if(limit<1 || limit>50 || before<=0 || order!=null && order<=0 || status!=null && !Set.of("QUEUED","RUNNING","COMPLETED","FAILED","INTERRUPTED","SKIPPED").contains(status))throw new DiagnosisFailure("INVALID_DIAGNOSIS_QUERY");
        return transaction(c->{List<Row> rows=new ArrayList<>();
            String sql="select id,order_id,job_status,detected_at,started_at,completed_at,target_stale,error_code,null::text as result from ai.diagnoses where source_namespace=? and id<?"
                +(order==null?"":" and order_id=?")+(status==null?"":" and job_status=?")+" order by id desc limit ?";
            List<Object> args=new ArrayList<>(List.of(properties.sourceNamespace(),before));if(order!=null)args.add(order);if(status!=null)args.add(status);args.add(limit);
            try(PreparedStatement s=statement(c,sql,args.toArray());ResultSet r=s.executeQuery()){while(r.next())rows.add(row(r));}return List.copyOf(rows);
        });
    }
    public Row detail(long id){return transaction(c->{
        try(PreparedStatement s=statement(c,"select id,order_id,job_status,detected_at,started_at,completed_at,target_stale,error_code,case when retention_expires_at>now() then result::text else null end as result from ai.diagnoses where source_namespace=? and id=?",properties.sourceNamespace(),id);ResultSet r=s.executeQuery()){
            if(!r.next())throw new DiagnosisFailure("DIAGNOSIS_NOT_FOUND");return row(r);
        }
    });}
    private Row row(ResultSet r)throws SQLException{return new Row(r.getLong("id"),(Long)r.getObject("order_id"),r.getString("job_status"),instant(r,"detected_at"),instant(r,"started_at"),instant(r,"completed_at"),r.getBoolean("target_stale"),r.getString("error_code"),r.getString("result"));}
    private Instant instant(ResultSet r,String col)throws SQLException{Timestamp t=r.getTimestamp(col);return t==null?null:t.toInstant();}
    private void lock(Connection c)throws SQLException{try(PreparedStatement s=statement(c,"select pg_advisory_xact_lock(502007)")){s.execute();}}
    private long scalar(Connection c,String sql)throws SQLException{try(PreparedStatement s=statement(c,sql);ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}
    private int update(Connection c,String sql,Object... args)throws SQLException{try(PreparedStatement s=statement(c,sql,args)){return s.executeUpdate();}}
    private PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException{
        PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(2);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;
    }
    @FunctionalInterface private interface Operation<T>{T run(Connection c)throws Exception;}
    private <T>T transaction(Operation<T> op){
        try(Connection c=pool.getConnection()){
            try(PreparedStatement s=statement(c,"select current_database()");ResultSet r=s.executeQuery()){
                r.next();if(!r.getString(1).matches("exchange_ai(_[a-z0-9_]+)?"))throw new DiagnosisFailure("DIAGNOSIS_DATABASE_TARGET_INVALID");
            }
            c.setAutoCommit(false);try{T result=op.run(c);c.commit();return result;}catch(Exception e){c.rollback();throw e;}
        }catch(DiagnosisFailure e){throw e;}catch(Exception e){throw unavailable();}
    }
    private DiagnosisFailure unavailable(){return new DiagnosisFailure("DIAGNOSIS_DATABASE_UNAVAILABLE");}
    @Override public void close(){pool.close();}
}
