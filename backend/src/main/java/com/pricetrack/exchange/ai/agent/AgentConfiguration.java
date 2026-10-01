package com.pricetrack.exchange.ai.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.tool.ToolDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
/** Optional AI dependencies: a bad Agent flag combination never prevents trading application startup. */
@Configuration
public class AgentConfiguration {
    @Bean(destroyMethod="close") AgentService agentService(AgentProperties properties,ObjectProvider<AgentModelProvider> model,
            ObjectProvider<AuthorizedKnowledgeRetrieval> retrieval,ToolDispatcher tools,ObjectMapper json){
        return new AgentService(properties,model::getIfAvailable,retrieval::getIfAvailable,tools,json);
    }
}
