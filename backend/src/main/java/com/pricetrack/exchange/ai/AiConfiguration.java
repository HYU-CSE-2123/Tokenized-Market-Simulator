package com.pricetrack.exchange.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** AI가 꺼지면 pool/provider도 생성하지 않는다. AI DB 초기화는 명시적 ingest에서만 수행한다. */
@Configuration
@ConditionalOnProperty(name="app.ai.enabled",havingValue="true")
public class AiConfiguration {
    @Bean com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval authorizedKnowledgeRetrieval(AiProperties p,
            KnowledgeLoader loader,PgKnowledgeStore store,OpenAiProvider provider){
        return new com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval(p,loader,store,provider);
    }
    @Bean com.pricetrack.exchange.ai.agent.AgentModelProvider agentModelProvider(OpenAiProvider provider,ObjectMapper json){
        return new com.pricetrack.exchange.ai.agent.OpenAiAgentProvider(provider,json);
    }
    @Bean(destroyMethod="close") PgKnowledgeStore aiKnowledgeStore(AiProperties p) {
        p.validate(); return new PgKnowledgeStore(p);
    }
    @Bean KnowledgeLoader aiKnowledgeLoader(AiProperties p,ObjectMapper json) { return new KnowledgeLoader(p,json); }
    @Bean OpenAiProvider aiProvider(AiProperties p,ObjectMapper json) { return new OpenAiProvider(p,json); }
    @Bean RagService ragService(AiProperties p,KnowledgeLoader loader,PgKnowledgeStore store,OpenAiProvider provider) {
        return new RagService(p,loader,store,provider,provider);
    }
}
