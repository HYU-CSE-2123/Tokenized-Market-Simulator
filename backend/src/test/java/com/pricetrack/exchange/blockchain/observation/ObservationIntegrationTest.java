package com.pricetrack.exchange.blockchain.observation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.blockchain.observation.ObservationRecorder.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest class ObservationIntegrationTest {
    @Autowired JdbcTemplate jdbc;@Autowired BlockchainTransactionRepository txs;@Autowired ObservationStore store;
    @Autowired DataSourceProperties properties;@Autowired EntityManager em;@Autowired ObservationRecorder recorder;
    @Autowired PlatformTransactionManager tm;
    final AuthenticatedUser admin=new AuthenticatedUser(1L,"admin",UserRole.ADMIN);
    final Instant at=Instant.parse("2026-10-12T00:00:00Z");
    @BeforeEach void clear(){jdbc.update("DELETE FROM blockchain_transaction_observations");txs.deleteAll();}
    BlockchainTransaction tx(long age,BlockchainTransactionStatus status){var t=new BlockchainTransaction();t.setType(BlockchainTransactionType.BUY);t.setStatus(status);
        t.setCreatedAt(at.minusSeconds(age));t.setRawTransaction("PRIVATE_RAW");t.setErrorMessage("PRIVATE_RPC_URL");t.setTxHash("PRIVATE_HASH-"+UUID.randomUUID());t.setNonce(7L);return txs.saveAndFlush(t);}
    TransactionObservationService service(){return new TransactionObservationService(em,recorder,300,Clock.fixed(at,ZoneOffset.UTC));}
    Map<String,Object> detail(long id){return new TransactionTemplate(tm).execute(s->service().detail(admin,id));}
    @Test void exactBoundariesAndSubmittedClock(){
        assertThat(detail(tx(299,BlockchainTransactionStatus.SIGNED).getId()).get("attention")).isEqualTo("NONE");
        assertThat(detail(tx(300,BlockchainTransactionStatus.SIGNED).getId()).get("attention")).isEqualTo("LONG_WAIT");
        assertThat(detail(tx(301,BlockchainTransactionStatus.SUBMITTED).getId()).get("attention")).isEqualTo("LONG_WAIT");
        var t=tx(900,BlockchainTransactionStatus.SUBMITTED);t.setSubmittedAt(at.minusSeconds(299));txs.saveAndFlush(t);
        assertThat(detail(t.getId()).get("attention")).isEqualTo("NONE");
        assertThat(detail(tx(900,BlockchainTransactionStatus.REVIEW_REQUIRED).getId()).get("attention")).isEqualTo("NONE");
    }
    @Test void duplicatesAndOlderResultsDoNotOverwriteLatestAndRestartKeepsSnapshot(){
        var t=tx(301,BlockchainTransactionStatus.SIGNED);assertThat(detail(t.getId()).get("observationStatus")).isEqualTo("UNRECORDED");
        var recent=new Attempt(t.getId(),UUID.randomUUID().toString(),at.minusSeconds(10),at.minusSeconds(8),Phase.SIGNED_RECOVERY,Result.SUBMISSION_RECORDED);
        store.save(recent);store.save(recent);store.save(new Attempt(t.getId(),UUID.randomUUID().toString(),at.minusSeconds(20),at.minusSeconds(1),Phase.RECEIPT_CHECK,Result.ATTEMPT_UNAVAILABLE));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM blockchain_transaction_observations WHERE transaction_id=?",Long.class,t.getId())).isEqualTo(1);
        try(var restarted=new RestartedStore(properties)){restarted.value.save(new Attempt(t.getId(),UUID.randomUUID().toString(),at.minusSeconds(2),at,Phase.RECEIPT_CHECK,Result.RECEIPT_NOT_FOUND));}
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM blockchain_transaction_observations WHERE transaction_id=?",Long.class,t.getId())).isEqualTo(2);
        assertThat(detail(t.getId()).get("lastAttempt").toString()).contains("RECEIPT_NOT_FOUND");
    }
    static class RestartedStore implements AutoCloseable{final ObservationStore value;RestartedStore(DataSourceProperties p){value=new ObservationStore(p);}public void close(){value.close();}}
    @Test void listContainsUnlinkedAndSystemButNoHashNonceOrRaw(){
        var missing=tx(301,BlockchainTransactionStatus.SIGNED);missing.setOrderId(987654L);txs.saveAndFlush(missing);
        var system=tx(301,BlockchainTransactionStatus.SUBMITTED);system.setType(BlockchainTransactionType.UPDATE_PRICE);system.setTxHash("OTHER_PRIVATE_HASH");txs.saveAndFlush(system);
        var result=new TransactionTemplate(tm).execute(s->service().list(admin,"ALL","LONG_WAIT",1,null));
        assertThat(result.toString()).contains("ORDER_MISSING").doesNotContain("PRIVATE_HASH","PRIVATE_RAW","PRIVATE_RPC_URL","nonce=");
        var next=new TransactionTemplate(tm).execute(s->service().list(admin,"ALL","LONG_WAIT",1,(String)result.get("nextCursor")));
        assertThat(next.toString()).contains("SYSTEM");assertThat(next.get("nextCursor")).isNull();
    }
    @Test void serviceRejectsUserAndInvalidFilters(){
        assertThatThrownBy(()->service().list(new AuthenticatedUser(2L,"user",UserRole.USER),"ALL","ALL",20,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        for(String invalid:List.of("wrong","SIGNED' OR 1=1"))assertThatThrownBy(()->service().list(admin,invalid,"ALL",20,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service().list(admin,"ALL","ALL",51,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service().list(admin,"ALL","ALL",20,"bad")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
