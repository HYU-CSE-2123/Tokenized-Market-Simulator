package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.store.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="AI_PGVECTOR_TESTS",matches="true")
class PgKnowledgeStoreIntegrationTest {
    @Test void realVectorSearchAtomicReplacementRollbackAndEmptyIndex() {
        var p=AiFixtures.properties(Path.of("."),Path.of("manifest"),"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test");
        try(var store=new PgKnowledgeStore(p)) {
            store.initialize(); store.initialize();
            String first=KnowledgeLoader.hash(UUID.randomUUID().toString());
            var meta=Map.of("title","Test","version","1","minimum_role","ADMIN","domain","operations","type","runbook","updated_at","2026-09-27");
            var doc=new KnowledgeCorpus.Document("test.md","hash",meta);
            var chunk=new KnowledgeCorpus.Chunk("c1","test.md","Test","Heading","1","ADMIN","operations","runbook","hash","근거");
            var corpus=new KnowledgeCorpus(first,List.of(doc),List.of(chunk));
            store.publish(corpus,Map.of("c1",AiFixtures.vector(0)),p.embeddingModel());
            store.publish(corpus,Map.of("c1",AiFixtures.vector(0)),p.embeddingModel());
            assertThat(store.active(first)).isTrue();
            assertThat(store.search(first,AiFixtures.vector(0),5,.9)).hasSize(1)
                    .first().satisfies(hit -> {assertThat(hit.heading()).isEqualTo("Heading");assertThat(hit.role()).isEqualTo("ADMIN");});
            assertThat(store.search(first,AiFixtures.vector(1),5,.9)).isEmpty();
            assertThat(store.cached(p.embeddingModel())).containsKey("c1");
            String second=KnowledgeLoader.hash(UUID.randomUUID().toString());
            assertThatThrownBy(() -> store.publish(new KnowledgeCorpus(second,List.of(doc),List.of(chunk)),Map.of(),p.embeddingModel()))
                    .isInstanceOf(AiFailure.class);
            assertThat(store.active(first)).isTrue();
            store.publish(new KnowledgeCorpus(second,List.of(),List.of()),Map.of(),p.embeddingModel());
            assertThat(store.active(first)).isFalse();
            assertThat(store.search(second,AiFixtures.vector(0),5,.1)).isEmpty();
            assertThat(store.search(first,AiFixtures.vector(0),5,.1)).isEmpty();
        }
    }
}

