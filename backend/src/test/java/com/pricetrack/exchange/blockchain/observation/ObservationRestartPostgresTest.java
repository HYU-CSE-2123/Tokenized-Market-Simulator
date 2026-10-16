package com.pricetrack.exchange.blockchain.observation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Run in a SECOND test JVM after the fresh conditional/observation E2E. Never initializes data. */
@EnabledIfEnvironmentVariable(named="OBSERVATION_RESTART_TESTS",matches="true")
@SpringBootTest @org.springframework.test.annotation.DirtiesContext
class ObservationRestartPostgresTest {
    @DynamicPropertySource static void fixture(DynamicPropertyRegistry registry)throws Exception{
        var phaseA=com.pricetrack.exchange.support.PhaseAFixture.optional(System.getenv("CONDITIONAL_E2E_RUN"));
        if(phaseA!=null){phaseA.forEach((k,v)->registry.add(k.toString(),()->v));registry.add("spring.sql.init.mode",()->"never");return;}
        Path root=Path.of(System.getenv("CONDITIONAL_E2E_RUN")).toRealPath();
        if(!root.getParent().equals(Path.of("../deployment/runtime").toRealPath())||!root.getFileName().toString().matches("audit-e2e-\\d{14}-[0-9a-f]{6}"))throw new IllegalStateException("New feature fixture required");
        var p=new Properties();try(var in=Files.newInputStream(root.resolve("secrets/test.properties"))){p.load(in);}
        if(!p.getProperty("spring.datasource.url").startsWith("jdbc:postgresql://127.0.0.1:26542/exchange_trade_audit_e2e?")||!p.getProperty("app.blockchain.rpc-url").equals("http://127.0.0.1:26545")||!p.getProperty("app.price.provider").equals("simulated")||!p.getProperty("app.ai.enabled").equals("false"))throw new IllegalStateException("Unsafe fixture");
        p.forEach((k,v)->registry.add(k.toString(),()->v));
        registry.add("spring.sql.init.mode",()->"never"); // second boot must only read the existing fresh fixture
    }
    @Autowired JdbcTemplate jdbc;@Autowired ObservationStore store;
    @Test void persistedSnapshotsSurviveNewApplicationJvmAndPostgresRejectsOldAndDuplicateResults(){
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blockchain_transaction_observations",Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blockchain_transaction_observations WHERE result_code='FAILURE_SETTLED'",Long.class)).isPositive();
        var txBefore=jdbc.queryForList("SELECT id,status,nonce,raw_transaction,tx_hash FROM blockchain_transactions ORDER BY id");
        var assetsBefore=jdbc.queryForList("SELECT id,amount,locked_amount FROM user_balances ORDER BY id");
        // Metadata fixture only: not an actual RPC attempt. Keep it separate from the real transaction rows.
        long id=Long.MAX_VALUE-1;Instant start=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var a=new ObservationRecorder.Attempt(id,UUID.randomUUID().toString(),start,start,ObservationRecorder.Phase.RECEIPT_CHECK,ObservationRecorder.Result.RECEIPT_NOT_FOUND);
        store.save(a);store.save(a);
        store.save(new ObservationRecorder.Attempt(id,UUID.randomUUID().toString(),start.minusSeconds(1),start.plusSeconds(1),ObservationRecorder.Phase.RECEIPT_CHECK,ObservationRecorder.Result.ATTEMPT_UNAVAILABLE));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM blockchain_transaction_observations WHERE transaction_id=?",Long.class,id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT result_code FROM blockchain_transaction_observations WHERE transaction_id=?",String.class,id)).isEqualTo("RECEIPT_NOT_FOUND");
        assertThat(jdbc.queryForList("SELECT id,status,nonce,raw_transaction,tx_hash FROM blockchain_transactions ORDER BY id")).isEqualTo(txBefore);
        assertThat(jdbc.queryForList("SELECT id,amount,locked_amount FROM user_balances ORDER BY id")).isEqualTo(assetsBefore);
    }
}
