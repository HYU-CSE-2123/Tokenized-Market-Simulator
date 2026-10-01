package com.pricetrack.exchange.ai.skill;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.quote.PriceQuoteRepository;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Paid opt-in: actual model/embedding/pgvector; state fixtures are isolated H2, not production trades. */
@EnabledIfEnvironmentVariable(named="AI_SKILL_LIVE_EVALUATION",matches="true")
@SpringBootTest(properties={"app.ai.enabled=false","app.ai.tools.enabled=true","app.blockchain.enabled=false",
    "app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:skill_live_eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class LiveSkillEvaluationTest {
    @Autowired ToolDispatcher tools;@Autowired ObjectMapper json;
    @Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;
    @Test void actualProviderExplicitAndAutomaticSkillsAndScopedEvidence()throws Exception{
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai",
            System.getenv().getOrDefault("AI_DB_PASSWORD","ai-local-test-only"),"../docs/ai-knowledge","../docs/ai/ingest-manifest.json",
            System.getenv("OPENAI_API_KEY"),"https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        assertThat(p.apiKey()).as("Configured key, never expose").isNotBlank();
        var provider=new OpenAiProvider(p,json);var loader=new KnowledgeLoader(p,json);var corpus=loader.load();
        var user=new AuthenticatedUser(830001L,"unused",UserRole.USER);var admin=new AuthenticatedUser(830002L,"unused",UserRole.ADMIN);
        var order=orders.saveAndFlush(ToolFixtures.order(user.userId()));quotes.saveAndFlush(ToolFixtures.quote(user.userId(),order.getId()));
        var registry=new SkillRegistry(json);var rows=new ArrayList<Map<String,Object>>();
        try(var store=new PgKnowledgeStore(p)){
            new RagService(p,loader,store,provider,provider).ingest(admin);
            var retrieval=new AuthorizedKnowledgeRetrieval(p,loader,store,provider);
            final AgentModelProvider.Generated[] generated={null};
            var adapter=new OpenAiAgentProvider(provider,json){
                @Override public Generated synthesize(String q,Route route,com.fasterxml.jackson.databind.JsonNode evidence,Duration budget){
                    generated[0]=super.synthesize(q,route,evidence,budget);return generated[0];
                }
            };
            try(var agent=new AgentService(new AgentProperties(true),()->adapter,()->retrieval,tools,json,
                    Duration.ofSeconds(40),new SkillProperties(true),registry)){
                for(boolean explicit:List.of(true,false))for(String id:List.of("settlement-debugging","signed-quote-diagnosis","market-availability-diagnosis")){
                    var principal=id.equals("settlement-debugging")?admin:user;
                    var definition=registry.require(id,principal.role(),id.equals("settlement-debugging")?AgentModelProvider.Subject.ORDER:id.equals("signed-quote-diagnosis")?AgentModelProvider.Subject.QUOTE:AgentModelProvider.Subject.NONE);
                    var body=new LinkedHashMap<String,Object>();
                    String question=switch(id){
                        case "settlement-debugging" -> "이 주문의 온체인 정산을 settlement-debugging 절차로 조사하고 대기와 DB 확정 조건을 설명해주세요.";
                        case "signed-quote-diagnosis" -> "이 견적을 signed-quote-diagnosis 절차로 진단하고 현재 만료와 소비 상태를 정책과 함께 설명해주세요.";
                        default -> "지금 시장에서 거래가 가능한지 market-availability-diagnosis 절차로 진단하고 가격 신선도와 시장 가용성을 설명해주세요.";
                    };
                    body.put("question",question);if(explicit)body.put("skillId",id);
                    if(id.equals("settlement-debugging"))body.put("target",Map.of("orderId",order.getId()));
                    if(id.equals("signed-quote-diagnosis"))body.put("target",Map.of("quoteId",ToolFixtures.QUOTE));
                    generated[0]=null;var result=agent.answer(principal,json.writeValueAsBytes(body));
                    var row=new LinkedHashMap<String,Object>();row.put("skillId",id);row.put("selection",explicit?"EXPLICIT":"AUTOMATIC");row.put("role",principal.role());row.put("result",result);row.put("generated",generated[0]);
                    rows.add(row);write(corpus.fingerprint(),rows);
                    assertThat(result.skill()).as("Skill selected: %s",id).isNotNull();assertThat(result.skill().id()).isEqualTo(id);
                    assertThat(result.status()).isIn("ANSWERED","PARTIAL");assertThat(result.route()).isEqualTo("MIXED");
                    assertThat(result.uncertainties()).doesNotContain("PLAN_UNAVAILABLE","SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED");
                    assertThat(result.citationIds()).anyMatch(ref -> ref.startsWith("tool-"));assertThat(result.knowledgeSources()).isNotEmpty();
                    for(var hit:result.knowledgeSources()){
                        var document=corpus.documents().stream().filter(d -> d.path().equals(hit.path())).findFirst().orElseThrow();
                        assertThat(definition.domains()).contains(document.metadata().get("domain"));
                        if(principal.role()==UserRole.USER)assertThat(hit.role()).isEqualTo("USER");
                    }
                    assertThat(result.metrics().modelCalls()).isEqualTo(explicit?1:2);assertThat(result.metrics().toolCalls()).isLessThanOrEqualTo(4);
                    assertThat(result.metrics().retrievalCalls()).isEqualTo(1);assertThat(result.skill().trace()).hasSizeLessThanOrEqualTo(12);
                }
                var denied=agent.answer(user,json.writeValueAsBytes(Map.of("question","정산 진단","skillId","settlement-debugging","target",Map.of("orderId",order.getId()))));
                assertThat(denied.httpStatus()).isEqualTo(403);assertThat(denied.metrics().modelCalls()).isZero();assertThat(denied.metrics().toolCalls()).isZero();
                var foreign=agent.answer(new AuthenticatedUser(830003L,"other",UserRole.USER),json.writeValueAsBytes(Map.of("question","견적 진단","skillId","signed-quote-diagnosis","target",Map.of("quoteId",ToolFixtures.QUOTE))));
                assertThat(foreign.httpStatus()).isEqualTo(404);assertThat(foreign.metrics().modelCalls()).isZero();
                assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(com.pricetrack.exchange.order.OrderStatus.PENDING_ONCHAIN);
                assertThat(quotes.findById(ToolFixtures.QUOTE).orElseThrow().getStatus()).isEqualTo(com.pricetrack.exchange.quote.PriceQuoteStatus.ISSUED);
                // Scoped retrieval is a separate 3-question evaluation, not a modification of the golden 12.
                var scoped=new ArrayList<Map<String,Object>>();
                for(var spec:List.of(Map.of("id","settlement-debugging","q","온체인 주문 FILLED 확정 receipt 이벤트 DB 정산 조건","expected","onchain-settlement-policy.md"),
                        Map.of("id","signed-quote-diagnosis","q","서명 견적 30초 만료 일회용 소비 정책","expected","signed-quote-policy.md"),
                        Map.of("id","market-availability-diagnosis","q","CLOSED STALE 가격 신선도 신규 거래 차단 정책","expected","market-data-policy.md"))){
                    var d=registry.require(spec.get("id"),UserRole.ADMIN,SkillRegistry.ceilings().stream().filter(c -> c.id().equals(spec.get("id"))).findFirst().orElseThrow().target());
                    var evidence=retrieval.searchScoped(admin,spec.get("q"),Duration.ofSeconds(5),d.domains());
                    boolean hit=evidence.hits().stream().anyMatch(h -> h.path().equals(spec.get("expected")));
                    scoped.add(Map.of("skillId",d.id(),"hit",hit,"evidence",evidence));assertThat(hit).isTrue();
                }
                Path report=Path.of("build/reports/ai/phase6-scoped-retrieval.json");Files.createDirectories(report.getParent());json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),scoped);
            }
        }
    }
    void write(String index,List<Map<String,Object>> rows)throws Exception{
        Path path=Path.of("build/reports/ai/phase6-skill-evaluation.json");Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("createdAt",Instant.now(),"indexVersion",index,"model","gpt-5.6-terra",
            "stateSource","isolated H2 fixtures and simulated market, no real trading mutation","rows",rows));
    }
}
