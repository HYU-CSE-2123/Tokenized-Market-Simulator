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
    @Test void roleFilterRunsBeforeTopKAndNeverReturnsAdminToUser() {
        var p=AiFixtures.properties(Path.of("."),Path.of("manifest"),"jdbc:postgresql://127.0.0.1:5433/exchange_ai_test");
        try(var store=new PgKnowledgeStore(p)){
            store.initialize();String index=KnowledgeLoader.hash(UUID.randomUUID().toString());
            var docs=new ArrayList<KnowledgeCorpus.Document>();var chunks=new ArrayList<KnowledgeCorpus.Chunk>();
            for(String role:List.of("ADMIN","USER")){
                String path=role.toLowerCase()+".md";
                var meta=Map.of("title",role,"version","1","minimum_role",role,"domain","security","type","policy","updated_at","2026-09-30");
                docs.add(new KnowledgeCorpus.Document(path,"hash",meta));
                chunks.add(new KnowledgeCorpus.Chunk(role,path,role,"Heading","1",role,"security","policy","hash",role.equals("ADMIN")?"ADMIN_SECRET_CANARY":"PUBLIC_POLICY"));
            }
            var weaker=AiFixtures.vector(0);weaker[1]=.5f;
            store.publish(new KnowledgeCorpus(index,docs,chunks),Map.of("ADMIN",AiFixtures.vector(0),"USER",weaker),p.embeddingModel());
            assertThat(store.searchForRole(index,AiFixtures.vector(0),1,.2,com.pricetrack.exchange.user.UserRole.ADMIN,java.time.Duration.ofSeconds(5)))
                    .extracting(KnowledgeHit::role).containsExactly("ADMIN");
            assertThat(store.searchForRole(index,AiFixtures.vector(0),1,.2,com.pricetrack.exchange.user.UserRole.USER,java.time.Duration.ofSeconds(5)))
                    .extracting(KnowledgeHit::content).containsExactly("PUBLIC_POLICY");
            assertThatThrownBy(() -> store.searchForRole(index,AiFixtures.vector(0),1,.2,null,java.time.Duration.ofSeconds(5)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
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
