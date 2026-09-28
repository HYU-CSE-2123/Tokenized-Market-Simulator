package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.EvidenceSelector;
import com.pricetrack.exchange.ai.store.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 유료 opt-in. 전용 평가 DB에만 publish하며 golden 원본과 Phase 2 색인을 수정하지 않는다. */
@EnabledIfEnvironmentVariable(named="AI_RETRIEVAL_EVALUATION", matches="true")
class RetrievalExperimentTest {
    static final String BASELINE="93730a121268017fb6a300578edfed3d9ea6ddd17a5236cfe320727174f2bc40";
    record Variant(String name, MarkdownChunker.Mode mode, double threshold, int perDocument, int bytes) {
        Variant(String name, MarkdownChunker.Mode mode, double threshold, int perDocument) {
            this(name,mode,threshold,perDocument,800);
        }
    }
    private final ObjectMapper json=new ObjectMapper();
    private AiProperties properties(String db) {
        return new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/"+db,"exchange_ai",
                System.getenv().getOrDefault("AI_DB_PASSWORD","ai-local-test-only"),
                "../docs/ai-knowledge","../docs/ai/ingest-manifest.json",System.getenv("OPENAI_API_KEY"),
                "https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",800,60,5,.3,20);
    }
    @Test void compareIndependentChangesUsingSharedQueryVectors() throws Exception {
        var p=properties("exchange_ai_retrieval_test");
        assertThat(p.apiKey()).as("API key configured (never printed)").isNotBlank();
        var provider=new OpenAiProvider(p,json);
        JsonNode golden=json.readTree(Path.of("src/test/resources/ai/retrieval-golden.json").toFile());
        JsonNode supplement=json.readTree(Path.of("src/test/resources/ai/retrieval-supplement.json").toFile());
        assertThat(golden.size()).isEqualTo(12);
        assertThat(KnowledgeLoader.hash(KnowledgeLoader.normalize(Files.readString(Path.of("src/test/resources/ai/retrieval-golden.json")))))
                .as("Phase 2 golden is frozen").isEqualTo("22006b55ed6256672195c873399df99fae5741fd47a9ba4319484fb97dd881ac");
        List<String> questions=new ArrayList<>();
        golden.forEach(q -> questions.add(q.path("question").asText()));
        supplement.path("negative").forEach(q -> questions.add(q.asText()));
        supplement.path("identifiers").forEach(q -> questions.add(q.path("question").asText()));
        Path cache=Path.of("build/reports/ai/phase3-query-vectors.json");
        Files.createDirectories(cache.getParent());
        Map<String,float[]> queryVectors=Files.exists(cache)
                ? json.readValue(cache.toFile(),new TypeReference<Map<String,float[]>>(){}) : new HashMap<>();
        for(String question:questions) {
            String key=KnowledgeLoader.hash(p.embeddingModel()+":1536:"+question);
            if(!queryVectors.containsKey(key)) queryVectors.put(key,provider.embed(List.of(question)).getFirst());
            PgKnowledgeStore.vector(queryVectors.get(key));
        }
        json.writeValue(cache.toFile(),queryVectors);
        List<Variant> variants=List.of(
            new Variant("A_baseline",MarkdownChunker.Mode.LEGACY,.3,5),
            new Variant("B_threshold_only",MarkdownChunker.Mode.LEGACY,.25,5),
            new Variant("C_diversity_only",MarkdownChunker.Mode.LEGACY,.3,2),
            new Variant("D_boundaries_only",MarkdownChunker.Mode.BOUNDARIES_ONLY,.3,5),
            new Variant("E_references_only",MarkdownChunker.Mode.REFERENCES_ONLY,.3,5),
            new Variant("F_clean_chunks_only",MarkdownChunker.Mode.CLEAN,.3,5),
            new Variant("G_threshold_diversity",MarkdownChunker.Mode.LEGACY,.25,2),
            new Variant("H_clean_diversity",MarkdownChunker.Mode.CLEAN,.3,2),
            new Variant("I_all_simple",MarkdownChunker.Mode.CLEAN,.25,2),
            new Variant("J_clean1200_only",MarkdownChunker.Mode.CLEAN,.3,5,1200),
            new Variant("K_clean1200_combined",MarkdownChunker.Mode.CLEAN,.25,2,1200),
            new Variant("L_hybrid_probe",MarkdownChunker.Mode.CLEAN,.25,2,1200));
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("createdAt",Instant.now().toString());
        report.put("baselineHitAt5",10.0/12);
        report.put("goldenHash",KnowledgeLoader.hash(KnowledgeLoader.normalize(Files.readString(Path.of("src/test/resources/ai/retrieval-golden.json")))));
        report.put("supplementHash",KnowledgeLoader.hash(Files.readString(Path.of("src/test/resources/ai/retrieval-supplement.json"))));
        List<Map<String,Object>> experiments=new ArrayList<>();
        report.put("experiments",experiments);
        try(var original=new PgKnowledgeStore(properties("exchange_ai_test")); var store=new PgKnowledgeStore(p)) {
            store.initialize();
            Map<String,float[]> vectors=new HashMap<>(original.cached(p.embeddingModel()));
            vectors.putAll(store.cached(p.embeddingModel()));
            for(var variant:variants) {
                var variantProperties=new AiProperties(p.enabled(),p.jdbcUrl(),p.dbUser(),p.dbPassword(),p.knowledgeRoot(),p.manifest(),
                    p.apiKey(),p.apiBase(),p.embeddingModel(),p.chatModel(),variant.bytes(),60,5,variant.threshold(),20);
                var loader=new KnowledgeLoader(variantProperties,json,new MarkdownChunker(variant.bytes(),60,variant.mode()));
                var corpus=loader.load();
                if(variant.mode()==MarkdownChunker.Mode.LEGACY) assertThat(corpus.fingerprint()).isEqualTo(BASELINE);
                var missing=corpus.chunks().stream().filter(c -> !vectors.containsKey(c.id())).toList();
                for(int start=0;start<missing.size();start+=16) {
                    var batch=missing.subList(start,Math.min(start+16,missing.size()));
                    var embedded=provider.embed(batch.stream().map(KnowledgeCorpus.Chunk::content).toList());
                    for(int i=0;i<batch.size();i++)vectors.put(batch.get(i).id(),embedded.get(i));
                }
                store.publish(corpus,vectors,p.embeddingModel());
                List<Map<String,Object>> rows=new ArrayList<>();
                int hits=0,regressions=0,negativeRetrieved=0,directCritical=0,identifierHits=0,identifierEvidence=0,directGolden=0;
                double reciprocal=0,identifierReciprocal=0;
                for(int index=0;index<questions.size();index++) {
                    String question=questions.get(index);
                    float[] vector=queryVectors.get(KnowledgeLoader.hash(p.embeddingModel()+":1536:"+question));
                    // 전체 corpus 조회는 진단 순위용. 실제 선택은 최대 40 후보에서 수행한다.
                    var ranked=store.search(corpus.fingerprint(),vector,500,-1);
                    var candidates=variant.name().equals("L_hybrid_probe")
                            ? KeywordExperiment.fuse(p,corpus.fingerprint(),question,ranked,variant.threshold())
                            : ranked.stream().filter(h -> h.similarity()>=variant.threshold()).limit(40).toList();
                    var selected=EvidenceSelector.select(candidates,5,variant.perDocument());
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("question",question); row.put("hits",selected); row.put("ranked",ranked);
                    if(index<12) {
                        List<String> expected=new ArrayList<>();golden.get(index).path("expected").forEach(n -> expected.add(n.asText()));
                        int rank=rank(selected,expected); row.put("expectedRank",rank);
                        List<String> evidence=new ArrayList<>();supplement.path("goldenEvidence").get(index).forEach(n -> evidence.add(n.asText()));
                        boolean hasEvidence=selected.stream().anyMatch(h -> expected.contains(h.path()) && evidence.stream().anyMatch(h.content()::contains));
                        row.put("directGoldenEvidence",hasEvidence);if(hasEvidence)directGolden++;
                        if(rank>0){hits++;reciprocal+=1.0/rank;} else if(index!=1 && index!=8)regressions++;
                        if(index==1 || index==8) {
                            String needle=index==1?"observedAt + 30초":"재연결 후 REST로 시장·주문·체결·포트폴리오를 재동기화한다";
                            boolean direct=selected.stream().anyMatch(h -> expected.contains(h.path()) && h.content().contains(needle));
                            row.put("directCriticalEvidence",direct);if(direct)directCritical++;
                        }
                    } else if(index<12+supplement.path("negative").size()) {
                        if(!selected.isEmpty())negativeRetrieved++;
                        row.put("negativeRetrieved",!selected.isEmpty());
                    } else {
                        var spec=supplement.path("identifiers").get(index-12-supplement.path("negative").size());
                        List<String> expected=new ArrayList<>();spec.path("expected").forEach(n -> expected.add(n.asText()));
                        int rank=rank(selected,expected);row.put("expectedRank",rank);
                        if(rank>0){identifierHits++;identifierReciprocal+=1.0/rank;}
                        boolean direct=selected.stream().anyMatch(h -> expected.contains(h.path()) && h.content().contains(question)
                                && h.content().contains(spec.path("needle").asText()));
                        row.put("identifierEvidence",direct);if(direct)identifierEvidence++;
                    }
                    rows.add(row);
                }
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("name",variant.name());result.put("indexVersion",corpus.fingerprint());result.put("chunks",corpus.chunks().size());
                result.put("threshold",variant.threshold());result.put("perDocument",variant.perDocument());
                result.put("hits",hits);result.put("hitAt5",hits/12.0);result.put("mrrAt5",reciprocal/12);
                result.put("regressions",regressions);result.put("directCriticalEvidence",directCritical);
                result.put("directGoldenEvidence",directGolden);
                result.put("negativeRetrieved",negativeRetrieved);result.put("negativeCount",supplement.path("negative").size());
                result.put("identifierHits",identifierHits);result.put("identifierMrrAt5",identifierReciprocal/supplement.path("identifiers").size());
                result.put("identifierEvidence",identifierEvidence);result.put("rows",rows);
                experiments.add(result);
                json.writerWithDefaultPrettyPrinter().writeValue(Path.of("build/reports/ai/phase3-experiments.json").toFile(),report);
            }
        }
        assertThat(experiments.getFirst().get("hits")).isEqualTo(10);
        var selected=experiments.stream().filter(e -> e.get("name").equals("K_clean1200_combined")).findFirst().orElseThrow();
        assertThat(selected.get("hits")).isEqualTo(12);
        assertThat(selected.get("regressions")).isEqualTo(0);
        assertThat(selected.get("directGoldenEvidence")).isEqualTo(12);
        assertThat((double)selected.get("mrrAt5")).isGreaterThan((double)experiments.getFirst().get("mrrAt5"));
        assertThat((int)selected.get("negativeRetrieved")).isLessThanOrEqualTo((int)experiments.getFirst().get("negativeRetrieved"));
    }
    static int rank(List<KnowledgeHit> hits,List<String> expected) {
        for(int i=0;i<hits.size();i++)if(expected.contains(hits.get(i).path()))return i+1;
        return 0;
    }
}
