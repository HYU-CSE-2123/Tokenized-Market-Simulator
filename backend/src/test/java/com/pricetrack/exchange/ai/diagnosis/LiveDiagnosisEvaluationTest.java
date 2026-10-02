package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.user.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Paid actual provider, embedding, pgvector and durable queue. Trading/receipt observations are fixtures. */
@EnabledIfEnvironmentVariable(named="AI_DIAGNOSIS_LIVE_EVALUATION",matches="true")
@SpringBootTest(properties={"app.ai.enabled=false","app.ai.tools.enabled=true","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:diagnosis_live;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@Import(DiagnosisSourceIntegrationTest.ReadConfiguration.class)
class LiveDiagnosisEvaluationTest {
    @Autowired DiagnosisSourceReader source;@Autowired ToolReadFacade reads;@Autowired ObjectMapper json;
    @Autowired UserRepository users;@Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;@Autowired BlockchainTransactionRepository txs;
    @Test void threeReviewScenariosRunThroughDurableAutomaticSkillAndRetainGroundedHistory()throws Exception{
        var ai=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai",System.getenv().getOrDefault("AI_DB_PASSWORD","ai-local-test-only"),
            "../docs/ai-knowledge","../docs/ai/ingest-manifest.json",System.getenv("OPENAI_API_KEY"),"https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        assertThat(ai.apiKey()).as("key configured, never printed").isNotBlank();String ns="live-"+UUID.randomUUID();
        User actor=new User();actor.setLoginId("diag"+UUID.randomUUID().toString().replace("-","").substring(0,16));actor.setNickname("test");actor.setRole(UserRole.ADMIN);actor=users.saveAndFlush(actor);
        var p=new DiagnosisProperties(true,actor.getLoginId(),ns,5000,50,100,20,7);var provider=new OpenAiProvider(ai,json);var loader=new KnowledgeLoader(ai,json);var corpus=loader.load();
        List<Map<String,Object>> rows=new ArrayList<>();ReceiptReader receipts=mock(ReceiptReader.class);
        try(var knowledge=new PgKnowledgeStore(ai);var history=new PgDiagnosisStore(ai,p)){
            history.initialize();new RagService(ai,loader,knowledge,provider,provider).ingest(new AuthenticatedUser(actor.getId(),actor.getLoginId(),UserRole.ADMIN));
            var retrieval=new AuthorizedKnowledgeRetrieval(ai,loader,knowledge,provider);var payload=new DiagnosisPayload(json,()->loader,ai.chatModel());
            try(var tools=new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),reads,receipts,new ToolAudit(),json);
                var agent=new AgentService(new AgentProperties(true),()->new OpenAiAgentProvider(provider,json),()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json));
                var coordinator=new DiagnosisCoordinator(p,source,history,agent,payload)){
                for(String scenario:List.of("RECEIPT_NOT_FOUND","EVENT_MISMATCH","FAILED_RECEIPT")){
                    var order=orders.saveAndFlush(ToolFixtures.order(actor.getId()));var quote=ToolFixtures.quote(actor.getId(),order.getId());quote.setQuoteId("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));quotes.saveAndFlush(quote);
                    var tx=ToolFixtures.tx(order.getId());tx.setTxHash("0x"+UUID.randomUUID().toString().replace("-","").repeat(2));tx.setNonce(System.nanoTime());tx=txs.saveAndFlush(tx);
                    var observed=json.createObjectNode().put("orderId",order.getId()).put("databaseStatus","REVIEW_REQUIRED").put("receiptStatus",scenario.equals("RECEIPT_NOT_FOUND")?"NOT_FOUND":"FOUND")
                        .put("executionStatus",scenario.equals("RECEIPT_NOT_FOUND")?"UNKNOWN":scenario.equals("FAILED_RECEIPT")?"FAILED":"SUCCESS").put("eventValidation",scenario.equals("EVENT_MISMATCH")?"MISMATCH":"UNKNOWN")
                        .put("confirmations","2").put("requiredConfirmations",1);
                    when(receipts.read(any())).thenReturn(observed);
                    coordinator.scan();coordinator.scan();coordinator.dispatch();
                    var stored=history.list(order.getId(),null,Long.MAX_VALUE,20);assertThat(stored).hasSize(1);var row=stored.getFirst();var result=payload.forRead(history.detail(row.id()).result());
                    rows.add(Map.of("scenario",scenario,"jobStatus",row.jobStatus(),"result",result));write(corpus.fingerprint(),rows);
                    assertThat(row.jobStatus()).isEqualTo("COMPLETED");assertThat(result.path("responseStatus").asText()).isIn("PARTIAL","ANSWERED");
                    assertThat(result.path("uncertainties").toString()).doesNotContain("SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE","APPROVAL_UNVERIFIED");
                    assertThat(result.path("skill").path("id").asText()).isEqualTo("settlement-debugging");
                    assertThat(result.path("metrics").path("modelCalls").asInt()).isEqualTo(1);assertThat(result.path("metrics").path("toolCalls").asInt()).isEqualTo(4);
                    assertThat(result.path("knowledgeSources")).isNotEmpty();assertThat(result.path("citationIds").toString()).contains("tool-");
                    assertThat(result.path("skill").path("trace")).hasSize(8);assertThat(result.toString()).doesNotContain("SENSITIVE_","rawTransaction","signature","SECRET_");
                    assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_ONCHAIN);
                    assertThat(quotes.findById(quote.getQuoteId()).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
                    assertThat(txs.findById(tx.getId()).orElseThrow()).usingRecursiveComparison().isEqualTo(tx);
                    coordinator.dispatch();assertThat(history.list(order.getId(),null,Long.MAX_VALUE,20)).hasSize(1);
                }
            }
        }finally{try(var c=DriverManager.getConnection(ai.jdbcUrl(),ai.dbUser(),ai.dbPassword());var s=c.prepareStatement("delete from ai.diagnoses where source_namespace=?")){s.setString(1,ns);s.executeUpdate();}}
    }
    void write(String index,List<Map<String,Object>> rows)throws Exception{
        Path path=Path.of("build/reports/ai/phase7-diagnosis-evaluation.json");Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("indexVersion",index,"model","gpt-5.6-terra","stateSource","isolated H2 and receipt fixtures","rows",rows));
    }
}
