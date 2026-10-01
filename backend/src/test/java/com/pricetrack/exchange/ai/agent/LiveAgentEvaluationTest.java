package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.order.OrderRepository;
import com.pricetrack.exchange.quote.PriceQuoteRepository;
import com.pricetrack.exchange.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Paid provider opt-in, real pgvector and Tool/JPA reads; fixtures exist only in an isolated H2 DB. */
@EnabledIfEnvironmentVariable(named="AI_AGENT_LIVE_EVALUATION",matches="true")
@SpringBootTest(properties={"app.ai.enabled=false","app.ai.tools.enabled=true","app.blockchain.enabled=false",
    "app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:agent_live_eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class LiveAgentEvaluationTest {
    @Autowired ToolDispatcher tools;@Autowired ObjectMapper json;
    @Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;
    @Test void realProviderRoutesRoleFilteredEvidenceAndReadOnlyFacts() throws Exception {
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai",
            System.getenv().getOrDefault("AI_DB_PASSWORD","ai-local-test-only"),"../docs/ai-knowledge","../docs/ai/ingest-manifest.json",
            System.getenv("OPENAI_API_KEY"),"https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        assertThat(p.apiKey()).as("Configured key; never print its value").isNotBlank();
        var provider=new OpenAiProvider(p,json);var loader=new KnowledgeLoader(p,json);
        var user=new AuthenticatedUser(810001L,"unused",UserRole.USER);
        var admin=new AuthenticatedUser(810002L,"unused",UserRole.ADMIN);
        var order=orders.saveAndFlush(ToolFixtures.order(user.userId()));
        quotes.saveAndFlush(ToolFixtures.quote(user.userId(),order.getId()));
        List<Map<String,Object>> rows=new ArrayList<>();
        try(var store=new PgKnowledgeStore(p)){
            var rag=new RagService(p,loader,store,provider,provider);rag.ingest(admin);assertThat(store.active(loader.load().fingerprint())).isTrue();
            var retrieval=new AuthorizedKnowledgeRetrieval(p,loader,store,provider);
            final AgentModelProvider.Generated[] captured={null};
            var adapter=new OpenAiAgentProvider(provider,json){
                @Override public Generated synthesize(String q,Route route,com.fasterxml.jackson.databind.JsonNode evidence,Duration timeout){
                    captured[0]=super.synthesize(q,route,evidence,timeout);return captured[0];
                }
            };
            try(var agent=new AgentService(new AgentProperties(true),()->adapter,()->retrieval,tools,json)){
                List<Map<String,Object>> scenarios=List.of(
                    Map.of("role","USER","route","KNOWLEDGE","body",Map.of("question","서명 견적 quoteId는 왜 한 번만 사용할 수 있나요?")),
                    Map.of("role","USER","route","STATE","body",Map.of("question","현재 시장 상태를 조회해 주세요.")),
                    Map.of("role","USER","route","STATE","body",Map.of("question","내 주문의 현재 상태만 조회해 주세요.","target",Map.of("orderId",order.getId()))),
                    Map.of("role","USER","route","MIXED","body",Map.of("question","이 주문의 대기 상태는 무엇을 의미하나요? 확정 조건과 함께 설명해 주세요.","target",Map.of("orderId",order.getId()))),
                    Map.of("role","USER","route","MIXED","body",Map.of("question","이 견적의 현재 상태와 만료 정책을 함께 설명해 주세요.","target",Map.of("quoteId",ToolFixtures.QUOTE))),
                    Map.of("role","ADMIN","route","KNOWLEDGE","body",Map.of("question","REVIEW_REQUIRED 상태에서 어떤 기록을 확인해야 하나요?")));
                for(var scenario:scenarios){
                    var principal=scenario.get("role").equals("USER")?user:admin;
                    captured[0]=null;
                    var result=agent.answer(principal,json.writeValueAsBytes(scenario.get("body")));
                    var row=new LinkedHashMap<String,Object>();row.put("scenario",scenario);row.put("result",result);row.put("generated",captured[0]);rows.add(row);write(loader.load().fingerprint(),rows);
                    assertThat(result.route()).as("Actual route for %s",scenario).isEqualTo(scenario.get("route"));
                    assertThat(result.status()).as("Real provider evidence validated").isIn("ANSWERED","PARTIAL");
                    assertThat(result.uncertainties()).doesNotContain("SYNTHESIS_UNAVAILABLE","PLAN_UNAVAILABLE","RAG_UNAVAILABLE");
                    if(principal.role()==UserRole.USER)assertThat(result.knowledgeSources()).allSatisfy(h -> assertThat(h.role()).isEqualTo("USER"));
                    assertThat(result.metrics().modelCalls()).isLessThanOrEqualTo(2);assertThat(result.metrics().toolCalls()).isLessThanOrEqualTo(4);
                    if(result.route().equals("MIXED")){
                        assertThat(result.citationIds()).anyMatch(id -> id.startsWith("tool-"));assertThat(result.knowledgeSources()).isNotEmpty();
                    }
                }
                var rejected=agent.answer(new AuthenticatedUser(810003L,"other",UserRole.USER),json.writeValueAsBytes(Map.of("question","주문 상태","target",Map.of("orderId",order.getId()))));
                assertThat(rejected.httpStatus()).isEqualTo(404);assertThat(rejected.metrics().modelCalls()).isZero();
                for(String q:List.of("서명 견적의 일회용 정책","현재 거래소의 사용자 데이터 권한","온체인 정산 확정 조건"))
                    assertThat(retrieval.search(user,q,Duration.ofSeconds(5)).hits()).isNotEmpty().allSatisfy(h -> assertThat(h.role()).isEqualTo("USER"));
                assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(com.pricetrack.exchange.order.OrderStatus.PENDING_ONCHAIN);
                assertThat(quotes.findById(ToolFixtures.QUOTE).orElseThrow().getStatus()).isEqualTo(com.pricetrack.exchange.quote.PriceQuoteStatus.ISSUED);
            }
        }
    }
    void write(String index,List<Map<String,Object>> rows)throws Exception{
        Path path=Path.of("build/reports/ai/phase5-agent-evaluation.json");Files.createDirectories(path.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),Map.of("createdAt",Instant.now(),"indexVersion",index,
            "provider","gpt-5.6-terra","stateSource","isolated H2 fixtures / simulation market; no real trading mutation","rows",rows));
    }
}
