package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Current K/corpus regression, not a rerun or overwrite of the historical Phase 3 experiments. */
@EnabledIfEnvironmentVariable(named="AI_KNOWLEDGE_REFRESH_EVALUATION", matches="true")
class KnowledgeRefreshEvaluationTest {
    final ObjectMapper json = new ObjectMapper();
    AiProperties properties(String database) {
        return new AiProperties(true, "jdbc:postgresql://127.0.0.1:5433/" + database, "exchange_ai",
                System.getenv().getOrDefault("AI_DB_PASSWORD", "ai-local-test-only"),
                "../docs/ai-knowledge", "../docs/ai/ingest-manifest.json", System.getenv("OPENAI_API_KEY"),
                "https://api.openai.com/v1/", "text-embedding-3-small", "gpt-5.6-terra", 1200, 60, 5, .25, 20);
    }
    @Test void currentCorpusPreservesRetrievalAndCanPublishApprovedLocalIndex() throws Exception {
        var p = properties("exchange_ai_test");
        assertThat(p.apiKey()).as("API key configured; value is never printed").isNotBlank();
        var observation=new com.pricetrack.exchange.ai.observability.AiObservability();
        var provider = new OpenAiProvider(p, json).observe(observation);
        var loader = new KnowledgeLoader(p, json);
        var corpus = loader.load();
        var baseline = json.readTree(Path.of("../docs/ai/phase-3-results.json").toFile());
        JsonNode previousK = null;
        for (var experiment : baseline.path("experiments"))
            if (experiment.path("name").asText().equals("K_clean1200_combined")) previousK = experiment;
        assertThat(previousK).isNotNull();
        Path goldenPath = Path.of("src/test/resources/ai/retrieval-golden.json");
        String goldenHash = KnowledgeLoader.hash(KnowledgeLoader.normalize(Files.readString(goldenPath)));
        assertThat(goldenHash).isEqualTo("22006b55ed6256672195c873399df99fae5741fd47a9ba4319484fb97dd881ac");
        var golden = json.readTree(goldenPath.toFile());
        var extra = json.readTree(Path.of("src/test/resources/ai/retrieval-supplement.json").toFile());
        assertThat(golden.size()).isEqualTo(12);
        assertThat(corpus.documents()).hasSize(9);
        // Reuse the exact Phase 3 query vectors when present. Cache identity includes model/dimensions/text.
        Path cachePath = Path.of("build/reports/ai/phase3-query-vectors.json");
        Map<String,float[]> cache = Files.exists(cachePath)
                ? json.readValue(cachePath.toFile(), new TypeReference<Map<String,float[]>>() {}) : new HashMap<>();
        EmbeddingProvider embedding = texts -> texts.stream().map(text -> {
            String key = KnowledgeLoader.hash(p.embeddingModel() + ":1536:" + text);
            float[] vector = cache.computeIfAbsent(key, ignored -> provider.embed(List.of(text)).getFirst());
            PgKnowledgeStore.vector(vector); return vector;
        }).toList();
        List<Map<String,Object>> rows = new ArrayList<>();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("createdAt", Instant.now().toString()); report.put("goldenHash", goldenHash);
        report.put("indexVersion", corpus.fingerprint()); report.put("documents", corpus.documents());
        report.put("chunks", corpus.chunks().size()); report.put("settings", "K: CLEAN/1200/60/.25/40/5/2");
        report.put("phase2BaselineHitAt5", 10.0/12); report.put("phase3BaselineMrrAt5", previousK.path("mrrAt5").asDouble());
        report.put("rows", rows);
        int hits = 0, direct = 0, negativeRetrieved = 0, falseAnswers = 0, criticalAnswered = 0;
        int regressions = 0, identifierHits = 0; double reciprocal = 0, identifierReciprocal = 0;
        try (var store = new PgKnowledgeStore(p)) {
            var service = new RagService(p, loader, store, embedding, provider).observe(observation);
            service.ingest(AiFixtures.ADMIN);
            assertThat(service.ingest(AiFixtures.ADMIN).unchanged()).isTrue();
            var user = new AuthenticatedUser(2L, "unused", UserRole.USER);
            assertThatThrownBy(() -> service.search(user, "주문 상태"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            List<String> questions = new ArrayList<>();
            golden.forEach(q -> questions.add(q.path("question").asText()));
            extra.path("negative").forEach(q -> questions.add(q.asText()));
            extra.path("identifiers").forEach(q -> questions.add(q.path("question").asText()));
            for (int i = 0; i < questions.size(); i++) {
                String question = questions.get(i);
                var found = service.search(AiFixtures.ADMIN, question);
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("question", question); row.put("hits", found);
                if (i < 12 || i >= 20) {
                    var spec = i < 12 ? golden.get(i) : extra.path("identifiers").get(i - 20);
                    List<String> expected = new ArrayList<>(); spec.path("expected").forEach(n -> expected.add(n.asText()));
                    int rank = RetrievalExperimentTest.rank(found, expected); row.put("expectedRank", rank);
                    if (i < 12) {
                        if (rank > 0) { hits++; reciprocal += 1.0 / rank; }
                        if (rank == 0 && baseline.path("selectedQuestions").get(i).path("expectedRank").asInt() > 0) regressions++;
                        List<String> anchors = new ArrayList<>(); extra.path("goldenEvidence").get(i).forEach(n -> anchors.add(n.asText()));
                        boolean evidence = found.stream().anyMatch(h -> expected.contains(h.path()) && anchors.stream().anyMatch(h.content()::contains));
                        if (evidence) direct++; row.put("directGoldenEvidence", evidence);
                    } else if (rank > 0) { identifierHits++; identifierReciprocal += 1.0 / rank; }
                } else { if (!found.isEmpty()) negativeRetrieved++; row.put("negativeRetrieved", !found.isEmpty()); }
                if (i == 1 || i == 8 || (i >= 12 && i < 20)) {
                    var answer = service.answer(AiFixtures.ADMIN, question);
                    row.put("answerStatus", answer.status()); row.put("answer", answer.answer()); row.put("sources", answer.sources());
                    if (i < 12 && answer.status().equals("ANSWERED")) criticalAnswered++;
                    if (i >= 12 && !answer.status().equals("INSUFFICIENT_EVIDENCE")) falseAnswers++;
                    if (answer.status().equals("ANSWERED")) assertThat(answer.sources()).isNotEmpty()
                            .allSatisfy(source -> assertThat(found).extracting(KnowledgeHit::id).contains(source.id()));
                }
                rows.add(row);
            }
            report.put("hits", hits); report.put("hitAt5", hits/12.0); report.put("mrrAt5", reciprocal/12);
            report.put("regressionsFromK", regressions); report.put("directGoldenEvidence", direct);
            report.put("negativeRetrieved", negativeRetrieved); report.put("negativeFalseAnswers", falseAnswers);
            report.put("criticalAnswered", criticalAnswered); report.put("identifierHits", identifierHits);
            report.put("identifierMrrAt5", identifierReciprocal/8);
            report.put("observability",observation.snapshot());write(report);
            assertThat(hits).isEqualTo(12); assertThat(regressions).isZero(); assertThat(direct).isEqualTo(12);
            assertThat(reciprocal/12).as("MRR must not regress before publication")
                    .isGreaterThanOrEqualTo(previousK.path("mrrAt5").asDouble() - 1e-8);
            assertThat(identifierHits).isGreaterThanOrEqualTo(previousK.path("identifierHits").asInt());
            assertThat(negativeRetrieved).isLessThanOrEqualTo(5); assertThat(falseAnswers).isZero();
            assertThat(criticalAnswered).isEqualTo(2);
            assertThat(service.answer(AiFixtures.ADMIN, "지금 내 잔고는?").status()).isEqualTo("LIVE_DATA_REQUIRED");
            // Explicit second opt-in. Publish only after regression succeeds; never touch the trading DB.
            if ("true".equals(System.getenv("AI_PUBLISH_CURRENT_INDEX"))) {
                var local = properties("exchange_ai");
                try (var target = new PgKnowledgeStore(local)) {
                    target.initialize();
                    assertThat(loader.load().fingerprint()).isEqualTo(corpus.fingerprint());
                    target.publish(corpus, store.cached(p.embeddingModel()), p.embeddingModel());
                    assertThat(target.active(corpus.fingerprint())).isTrue();
                    assertThat(new RagService(local, loader, target, embedding, provider)
                            .search(AiFixtures.ADMIN, questions.get(1))).isNotEmpty();
                    report.put("publishedLocalDatabase", "exchange_ai");
                    report.put("publishedIndex", corpus.fingerprint()); write(report);
                }
            }
        }
    }
    void write(Map<String,Object> report) throws Exception {
        Path path = Path.of("build/reports/ai/" + ("true".equals(System.getenv("AI_PHASE8_EVALUATION"))?"phase8-knowledge-refresh.json":"true".equals(System.getenv("AI_PHASE7_EVALUATION"))?"phase7-knowledge-refresh.json":"true".equals(System.getenv("AI_PHASE6_EVALUATION"))?"phase6-knowledge-refresh.json":"true".equals(System.getenv("AI_PHASE5_EVALUATION"))
                ? "phase5-knowledge-refresh.json" : "phase4-knowledge-refresh.json"));
        Files.createDirectories(path.getParent()); json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }
}
