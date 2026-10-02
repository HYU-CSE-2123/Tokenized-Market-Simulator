package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.tool.ToolFixtures;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.user.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"app.ai.enabled=false","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:diagnosis_source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@Import(DiagnosisSourceIntegrationTest.ReadConfiguration.class)
class DiagnosisSourceIntegrationTest {
    @TestConfiguration static class ReadConfiguration {@Bean DiagnosisSourceReader reader(EntityManager em){return new DiagnosisSourceReader(em);}}
    @Autowired DiagnosisSourceReader source;@Autowired OrderRepository orders;@Autowired BlockchainTransactionRepository transactions;
    @Autowired UserRepository users;@Autowired PlatformTransactionManager tm;
    BlockchainTransaction create(){
        var order=orders.saveAndFlush(ToolFixtures.order(1));var t=ToolFixtures.tx(order.getId());
        t.setTxHash("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));t.setNonce(System.nanoTime());return transactions.saveAndFlush(t);
    }
    @Test void onlyCommittedReviewStatesAreVisibleAndOldIdTransitionsAreDiscoverable()throws Exception{
        long floor=source.scan(0,100).stream().mapToLong(DiagnosisSourceReader.Target::transactionId).max().orElse(0);
        var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()){
            var write=executor.submit(()->new TransactionTemplate(tm).execute(status->{var t=create();inserted.countDown();try{commit.await();}catch(InterruptedException e){throw new RuntimeException(e);}return t.getId();}));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();assertThat(source.scan(floor,50)).isEmpty();commit.countDown();
            long id=write.get(5,TimeUnit.SECONDS);assertThat(source.scan(floor,50)).extracting(DiagnosisSourceReader.Target::transactionId).contains(id);
            var t=transactions.findById(id).orElseThrow();t.setStatus(BlockchainTransactionStatus.SUBMITTED);transactions.saveAndFlush(t);
            assertThat(source.scan(floor,50)).isEmpty();t.setStatus(BlockchainTransactionStatus.REVIEW_REQUIRED);transactions.saveAndFlush(t);
            assertThat(source.scan(0,50)).extracting(DiagnosisSourceReader.Target::transactionId).contains(id);
        }finally{commit.countDown();}
        long id=new TransactionTemplate(tm).execute(status->{long value=create().getId();status.setRollbackOnly();return value;});
        assertThat(source.current(id)).isNull();
    }
    @Test void actorIsResolvedFromDbAndLinkedReadsDoNotTouchRecoveryMaterial(){
        var u=new User();u.setLoginId("d"+UUID.randomUUID().toString().replace("-",""));u.setNickname("test");u.setRole(UserRole.USER);users.saveAndFlush(u);
        assertThatThrownBy(()->source.actor(u.getLoginId())).hasMessage("DIAGNOSIS_ACTOR_UNAVAILABLE");u.setRole(UserRole.ADMIN);users.saveAndFlush(u);
        assertThat(source.actor(u.getLoginId()).userId()).isEqualTo(u.getId());
        var tx=create();var before=transactions.findById(tx.getId()).orElseThrow();var target=source.current(tx.getId());assertThat(source.linked(target)).isTrue();
        assertThat(target.toString()).doesNotContain("SENSITIVE");var after=transactions.findById(tx.getId()).orElseThrow();
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
    }
}
