package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 유료 API를 사용하는 명시적 opt-in 평가. 출력에는 질문별 hit와 점수만 남긴다. */
@EnabledIfEnvironmentVariable(named="AI_LIVE_EVALUATION",matches="true")
class LiveRagEvaluationTest {
    @Test void realEmbeddingRetrievalAndCitedAnswer() throws Exception {
        String key=System.getenv("OPENAI_API_KEY");
        assertThat(key).as("OPENAI_API_KEY must be configured without printing its value").isNotBlank();
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test","exchange_ai",
                System.getenv().getOrDefault("AI_DB_PASSWORD","ai-local-test-only"),
                "../docs/ai-knowledge","../docs/ai/ingest-manifest.json",key,"https://api.openai.com/v1/",
                "text-embedding-3-small","gpt-5.6-terra",800,60,5,.3,20);
        var json=new ObjectMapper();
        try(var store=new PgKnowledgeStore(p)) {
            var loader=new KnowledgeLoader(p,json);var provider=new OpenAiProvider(p,json);
            var service=new RagService(p,loader,store,provider,provider);
            service.ingest(AiFixtures.ADMIN);
            assertThat(service.ingest(AiFixtures.ADMIN).unchanged()).isTrue();
            var cases=json.readTree(Path.of("src/test/resources/ai/retrieval-golden.json").toFile());
            List<Map<String,Object>> results=new ArrayList<>();int hits=0;
            for(var item:cases) {
                var found=service.search(AiFixtures.ADMIN,item.path("question").asText());
                List<String> expected=new ArrayList<>();item.path("expected").forEach(n -> expected.add(n.asText()));
                boolean hit=found.stream().anyMatch(h -> expected.contains(h.path()));
                if(hit)hits++;
                results.add(Map.of("question",item.path("question").asText(),"hit",hit,
                        "paths",found.stream().map(h -> h.path()).toList(),
                        "scores",found.stream().map(h -> h.similarity()).toList()));
            }
            Path report=Path.of("build/reports/ai/retrieval-evaluation.json");
            Files.createDirectories(report.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),Map.of(
                    "model",p.embeddingModel(),"indexVersion",loader.load().fingerprint(),"hitAt5",(double)hits/cases.size(),"results",results));
            assertThat(hits).as("golden hit@5 >= 10/12").isGreaterThanOrEqualTo(10);
            var answer=service.answer(AiFixtures.ADMIN,"quoteId는 왜 한 번만 사용할 수 있어?");
            assertThat(answer.status()).isEqualTo("ANSWERED");assertThat(answer.sources()).isNotEmpty();
            assertThat(service.answer(AiFixtures.ADMIN,"화성에서 감자를 재배하는 방법").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(service.answer(AiFixtures.ADMIN,"지금 내 잔고는?").status()).isEqualTo("LIVE_DATA_REQUIRED");
        }
    }
}

