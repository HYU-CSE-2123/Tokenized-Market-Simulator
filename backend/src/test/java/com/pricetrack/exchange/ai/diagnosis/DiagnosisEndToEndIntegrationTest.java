package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReadOnlyReceiptClient;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.user.*;
import jakarta.persistence.EntityManager;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.core.DefaultBlockParameterName;

/** Dedicated trading test DB + AI test DB + actual read-only Anvil. Deletes only its own rows. */
@EnabledIfEnvironmentVariable(named="AI_DIAGNOSIS_INTEGRATION_TESTS",matches="true")
@SpringBootTest(properties={"app.ai.enabled=false","app.ai.tools.enabled=true","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/exchange_tool_test","spring.datasource.username=exchange","spring.datasource.password=exchange",
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"})
@AutoConfigureMockMvc
@Import(DiagnosisEndToEndIntegrationTest.Config.class)
class DiagnosisEndToEndIntegrationTest {
    static final String NS="e2e-"+UUID.randomUUID();
    static final AiProperties AI=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai","ai-local-test-only","../docs/ai-knowledge","../docs/ai/ingest-manifest.json","", "https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    @TestConfiguration static class Config {
        @Bean DiagnosisSourceReader reader(EntityManager em){return new DiagnosisSourceReader(em);}
        @Bean(destroyMethod="close") PgDiagnosisStore store(){return new PgDiagnosisStore(AI,new DiagnosisProperties(true,"admin",NS,5000,50,100,20,7));}
        @Bean DiagnosisPayload payload(ObjectMapper json){return new DiagnosisPayload(json,()->null,AI.chatModel());}
    }
    @Autowired DiagnosisSourceReader source;@Autowired PgDiagnosisStore store;@Autowired DiagnosisPayload payload;
    @Autowired ToolReadFacade reads;@Autowired ObjectMapper json;@Autowired UserRepository users;@Autowired OrderRepository orders;
    @Autowired BlockchainTransactionRepository txs;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired JwtTokenProvider jwt;
    @Autowired com.pricetrack.exchange.wallet.UserBalanceRepository balances;
    @Autowired com.pricetrack.exchange.quote.PriceQuoteRepository quotes;
    @Autowired OrderService trading;@Autowired com.pricetrack.exchange.wallet.WalletService wallets;
    @Test void committedReviewToStoredSkillHistoryPreservesTradingRowsAndChain()throws Exception{
        store.initialize();User admin=new User();admin.setLoginId("diag"+UUID.randomUUID().toString().replace("-","").substring(0,16));admin.setNickname("test");admin.setRole(UserRole.ADMIN);admin=users.saveAndFlush(admin);
        var order=orders.saveAndFlush(ToolFixtures.order(admin.getId()));var t=ToolFixtures.tx(order.getId());t.setTxHash("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));t.setNonce(System.nanoTime());var tx=txs.saveAndFlush(t);
        var balanceRow=new com.pricetrack.exchange.wallet.UserBalance(admin.getId(),"mKRW");balanceRow.setAmount(new java.math.BigDecimal("100000"));balanceRow.setLockedAmount(new java.math.BigDecimal("75000"));balanceRow=balances.saveAndFlush(balanceRow);
        var quote=ToolFixtures.quote(admin.getId(),order.getId());quote.setQuoteId("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));quote=quotes.saveAndFlush(quote);
        Web3j chain=Web3j.build(new HttpService("http://127.0.0.1:8545"));
        try{
            var height=chain.ethBlockNumber().send().getBlockNumber();var nonce=chain.ethGetTransactionCount(ToolFixtures.ADDRESS,DefaultBlockParameterName.PENDING).send().getTransactionCount();var balance=chain.ethGetBalance(ToolFixtures.ADDRESS,DefaultBlockParameterName.LATEST).send().getBalance();
            var beforeTx=jdbc.queryForMap("select * from blockchain_transactions where id=?",tx.getId());var beforeOrder=jdbc.queryForMap("select * from orders where id=?",order.getId());
            var beforeBalances=jdbc.queryForList("select * from user_balances where user_id=?",admin.getId());var beforeQuote=jdbc.queryForMap("select * from price_quotes where quote_id=?",quote.getQuoteId());
            var model=mock(AgentModelProvider.class);
            var properties=new BlockchainProperties(true,"http://127.0.0.1:8545","","","",ToolFixtures.ADDRESS,"");
            try(var rpc=new ReadOnlyReceiptClient(properties,new BlockchainReconciliationProperties(1000,1000,1),new ContractEventParser(),json);
                var tools=new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),reads,rpc,new ToolAudit(),json);
                var agent=new AgentService(new AgentProperties(true),()->model,()->null,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json));
                var coordinator=new DiagnosisCoordinator(new DiagnosisProperties(true,admin.getLoginId(),NS,5000,50,100,20,7),source,store,agent,payload)){
                coordinator.scan();coordinator.scan();coordinator.dispatch();coordinator.dispatch();
                var rows=store.list(order.getId(),null,Long.MAX_VALUE,20);assertThat(rows).hasSize(1);assertThat(rows.getFirst().jobStatus()).isEqualTo("COMPLETED");
                var result=payload.forRead(store.detail(rows.getFirst().id()).result());assertThat(result.path("skill").path("diagnosis").path("classification").asText()).isEqualTo("REVIEW_REQUIRED_OBSERVED");
                assertThat(result.toString()).contains("NOT_FOUND").doesNotContain("SENSITIVE_",t.getRawTransaction());
                mvc.perform(get("/api/ai/diagnoses/"+rows.getFirst().id()).header("Authorization","Bearer "+jwt.createToken(admin.getId(),admin.getLoginId())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.automaticallyModified").value(false));
                verifyNoInteractions(model);
            }
            assertThat(jdbc.queryForMap("select * from blockchain_transactions where id=?",tx.getId())).isEqualTo(beforeTx);
            assertThat(jdbc.queryForMap("select * from orders where id=?",order.getId())).isEqualTo(beforeOrder);
            assertThat(jdbc.queryForList("select * from user_balances where user_id=?",admin.getId())).isEqualTo(beforeBalances);
            assertThat(jdbc.queryForMap("select * from price_quotes where quote_id=?",quote.getQuoteId())).isEqualTo(beforeQuote);
            assertThat(jdbc.queryForObject("select count(*) from trades where order_id=?",Long.class,order.getId())).isZero();
            assertThat(chain.ethBlockNumber().send().getBlockNumber()).isEqualTo(height);
            assertThat(chain.ethGetTransactionCount(ToolFixtures.ADDRESS,DefaultBlockParameterName.PENDING).send().getTransactionCount()).isEqualTo(nonce);
            assertThat(chain.ethGetBalance(ToolFixtures.ADDRESS,DefaultBlockParameterName.LATEST).send().getBalance()).isEqualTo(balance);
        }finally{
            chain.shutdown();try(var c=DriverManager.getConnection(AI.jdbcUrl(),AI.dbUser(),AI.dbPassword());var s=c.prepareStatement("delete from ai.diagnoses where source_namespace=?")){s.setString(1,NS);s.executeUpdate();}
            txs.deleteById(tx.getId());quotes.deleteById(quote.getQuoteId());balances.deleteById(balanceRow.getId());orders.deleteById(order.getId());users.deleteById(admin.getId());
        }
    }
    @Test void mockTradingLatencyRemainsIndependentOfUnavailableAutomaticAiDatabase()throws Exception{
        User user=new User();user.setLoginId("bench"+UUID.randomUUID().toString().replace("-","").substring(0,15));user.setNickname("test");user.setRole(UserRole.ADMIN);user=users.saveAndFlush(user);
        long uid=user.getId();wallets.initializeBalances(uid);wallets.faucet(uid);
        var pending=orders.saveAndFlush(ToolFixtures.order(uid));var tx=ToolFixtures.tx(pending.getId());tx.setTxHash("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));tx.setNonce(System.nanoTime());tx=txs.saveAndFlush(tx);
        List<Double> baseline=new ArrayList<>(),unavailable=new ArrayList<>();
        try{
            trading.buy(uid,"mSEC",new java.math.BigDecimal("100"),null); // warmup, not measured
            for(int i=0;i<6;i++){long start=System.nanoTime();assertThat(trading.buy(uid,"mSEC",new java.math.BigDecimal("100"),null).getStatus()).isEqualTo(OrderStatus.FILLED);baseline.add((System.nanoTime()-start)/1_000_000.0);}
            var offAi=new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/exchange_ai_unavailable","exchange_ai","test","../docs/ai-knowledge","../docs/ai/ingest-manifest.json","", "https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
            var settings=new DiagnosisProperties(true,user.getLoginId(),"latency-"+UUID.randomUUID(),1000,50,100,20,7);
            try(var deadStore=new PgDiagnosisStore(offAi,settings);var coordinator=new DiagnosisCoordinator(settings,source,deadStore,mock(AgentService.class),payload)){
                coordinator.start();Thread.sleep(250);
                for(int i=0;i<6;i++){long start=System.nanoTime();assertThat(trading.buy(uid,"mSEC",new java.math.BigDecimal("100"),null).getStatus()).isEqualTo(OrderStatus.FILLED);unavailable.add((System.nanoTime()-start)/1_000_000.0);}
            }
            assertThat(txs.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(BlockchainTransactionStatus.REVIEW_REQUIRED);
            java.nio.file.Path path=java.nio.file.Path.of("build/reports/ai/phase7-trading-latency.json");java.nio.file.Files.createDirectories(path.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("sampleCountPerMode",6,"source","dedicated PostgreSQL, simulated DB trading, unavailable AI DB port 1",
                "baselineMs",baseline,"aiUnavailableMs",unavailable,"baselineMedianMs",median(baseline),"aiUnavailableMedianMs",median(unavailable)));
        }finally{
            jdbc.update("delete from trades where user_id=?",uid);txs.deleteById(tx.getId());jdbc.update("delete from orders where user_id=?",uid);
            jdbc.update("delete from user_balances where user_id=?",uid);users.deleteById(uid);
        }
    }
    double median(List<Double> values){var sorted=values.stream().sorted().toList();return (sorted.get(2)+sorted.get(3))/2;}
}
