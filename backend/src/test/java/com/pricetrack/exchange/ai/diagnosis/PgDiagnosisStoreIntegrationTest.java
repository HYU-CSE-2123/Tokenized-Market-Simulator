package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.diagnosis.DiagnosisSourceReader.Target;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="AI_PGVECTOR_TESTS",matches="true")
class PgDiagnosisStoreIntegrationTest {
    final String namespace="diag-"+UUID.randomUUID();
    final AiProperties ai=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai","ai-local-test-only","../docs/ai-knowledge","../docs/ai/ingest-manifest.json","", "https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    PgDiagnosisStore store;
    Integer previousStarts;
    Target target(long id){return new Target(id,id,"0x"+String.format("%064x",id),"REVIEW_REQUIRED","BUY");}
    @BeforeEach void setup()throws Exception{
        store=new PgDiagnosisStore(ai,new DiagnosisProperties(true,"admin",namespace,5000,50,2,1,7));store.initialize();
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("select starts from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date")){previousStarts=r.next()?r.getInt(1):null;}
    }
    @AfterEach void cleanup()throws Exception{
        try(var c=connection();var s=c.prepareStatement("delete from ai.diagnoses where source_namespace=?")){s.setString(1,namespace);s.executeUpdate();}
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");
        if(previousStarts!=null)sql("insert into ai.diagnosis_daily_usage(usage_date,starts) values((now() at time zone 'UTC')::date,"+previousStarts+")");
        store.close();
    }
    Connection connection()throws Exception{return DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword());}
    void sql(String sql)throws Exception{try(var c=connection();var s=c.createStatement()){s.execute(sql);}}
    @Test void concurrentDuplicateEnqueueAndClaimHaveSingleWinner()throws Exception{
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");
        try(var pool=Executors.newFixedThreadPool(4)){
            List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->store.enqueue(target(1))));
            int wins=0;for(var f:jobs)if(f.get())wins++;assertThat(wins).isEqualTo(1);
            var first=pool.submit(()->store.claim("a"));var second=pool.submit(()->store.claim("b"));
            var results=Arrays.asList(first.get(),second.get());assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var job=results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(store.finish(job,"COMPLETED",null,"{}",1L,false)).isTrue();assertThat(store.finish(job,"COMPLETED",null,"{}",1L,false)).isFalse();
        }
    }
    @Test void queueQuotaBusyRetryAndDailyQuotaAreAtomic()throws Exception{
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");assertThat(store.enqueue(target(1))).isTrue();assertThat(store.enqueue(target(2))).isTrue();assertThat(store.enqueue(target(3))).isFalse();
        var j=store.claim("a");assertThat(store.busy(j)).isTrue();sql("update ai.diagnoses set available_at=now() where source_namespace='"+namespace+"'");
        var retry=store.claim("a");assertThat(retry.id()).isEqualTo(j.id());assertThat(retry.admissionAttempts()).isEqualTo(2);
        store.finish(retry,"COMPLETED",null,"{}",1L,false);assertThat(store.claim("a")).isNull();
    }
    @Test void expiredLeaseNeverReplaysAndExpiredPayloadKeepsDedupMarker()throws Exception{
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");store.enqueue(target(1));var j=store.claim("a");
        sql("update ai.diagnoses set lease_until=now()-interval '1 second' where source_namespace='"+namespace+"'");
        assertThat(store.claim("b")).isNull();assertThat(store.detail(j.id()).jobStatus()).isEqualTo("INTERRUPTED");
        assertThat(store.finish(j,"COMPLETED",null,"{}",1L,false)).isFalse();assertThat(store.enqueue(target(1))).isFalse();
        sql("update ai.diagnoses set result='{}',retention_expires_at=now()-interval '1 second' where source_namespace='"+namespace+"'");
        store.purge();assertThat(store.detail(j.id()).result()).isNull();assertThat(store.enqueue(target(1))).isFalse();
    }
    @Test void malformedTargetIsRecordedWithoutRunningAndQueryBoundariesFailClosed(){
        assertThat(store.enqueue(new Target(1,null,null,"REVIEW_REQUIRED","BUY"))).isTrue();assertThat(store.list(null,null,Long.MAX_VALUE,20)).singleElement().satisfies(r->assertThat(r.jobStatus()).isEqualTo("SKIPPED"));
        assertThatThrownBy(()->store.list(null,"INJECT",Long.MAX_VALUE,20)).hasMessage("INVALID_DIAGNOSIS_QUERY");
        assertThatThrownBy(()->store.list(null,null,Long.MAX_VALUE,51)).hasMessage("INVALID_DIAGNOSIS_QUERY");
    }
    @Test void busyStopsAfterThirdAdmissionAndNamespaceIsolatesIdentity()throws Exception{
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");store.enqueue(target(1));
        for(int attempt=1;attempt<=3;attempt++){
            var j=store.claim("a");assertThat(j.admissionAttempts()).isEqualTo(attempt);store.busy(j);
            sql("update ai.diagnoses set available_at=now() where source_namespace='"+namespace+"'");
        }
        assertThat(store.list(null,null,Long.MAX_VALUE,20)).singleElement().satisfies(r->assertThat(r.jobStatus()).isEqualTo("FAILED"));assertThat(store.claim("a")).isNull();
        String other=namespace+"-b";
        try(var second=new PgDiagnosisStore(ai,new DiagnosisProperties(true,"admin",other,5000,50,100,20,7))){assertThat(second.enqueue(target(1))).isTrue();}
        try(var c=connection();var s=c.prepareStatement("delete from ai.diagnoses where source_namespace=?")){s.setString(1,other);s.executeUpdate();}
    }
    @Test void fingerprintAndAllTerminalMetadataExpireWithoutLosingDedupOrLiveJobs()throws Exception{
        sql("delete from ai.diagnosis_daily_usage where usage_date=(now() at time zone 'UTC')::date");
        var malformed=new Target(91,null,"0x"+"b".repeat(64),"REVIEW_REQUIRED","BUY");
        store.enqueue(malformed);
        var skipped=store.list(null,null,Long.MAX_VALUE,20).getFirst();
        try(var c=connection();var s=c.prepareStatement("select event_key,tx_hash,retention_expires_at from ai.diagnoses where id=?")){
            s.setLong(1,skipped.id());try(var r=s.executeQuery()){r.next();assertThat(r.getString(1)).matches("[0-9a-f]{64}").doesNotContain(malformed.txHash(),namespace,":");assertThat(r.getTimestamp(3)).isNotNull();}
        }
        store.enqueue(target(92));
        for(int i=0;i<3;i++){var j=store.claim("worker-secret-marker");store.busy(j);sql("update ai.diagnoses set available_at=now() where source_namespace='"+namespace+"'");}
        var failed=store.list(null,"FAILED",Long.MAX_VALUE,20).getFirst();
        // Simulates the Phase 7 legacy terminal rows without expiry, not a migration of live jobs.
        sql("update ai.diagnoses set retention_expires_at=null,completed_at=now()-interval '8 days',claim_token='old',claimed_by='old' where source_namespace='"+namespace+"'");
        store.enqueue(target(93));
        store.purge();
        try(var c=connection();var s=c.prepareStatement("select event_key,tx_hash,claimed_by,claim_token,job_status from ai.diagnoses where id in (?,?)")){
            s.setLong(1,skipped.id());s.setLong(2,failed.id());try(var r=s.executeQuery()){int count=0;while(r.next()){count++;assertThat(r.getString(1)).matches("[0-9a-f]{64}");assertThat(r.getString(2)).isNull();assertThat(r.getString(3)).isNull();assertThat(r.getString(4)).isNull();assertThat(r.getString(5)).isIn("SKIPPED","FAILED");}assertThat(count).isEqualTo(2);}
        }
        assertThat(store.enqueue(malformed)).isFalse();assertThat(store.enqueue(target(92))).isFalse();
        assertThat(store.list(93L,null,Long.MAX_VALUE,20)).singleElement().satisfies(r->assertThat(r.jobStatus()).isEqualTo("QUEUED"));
        assertThat(store.observation().get("queuedGlobal")).isGreaterThanOrEqualTo(1L);
        // A non-expired terminal remains intact until the boundary, including when purge runs repeatedly.
        var j=store.claim("a");store.finish(j,"COMPLETED",null,"{}",1L,false);store.purge();assertThat(store.detail(j.id()).result()).isEqualTo("{}");
    }
}
