package com.pricetrack.exchange.ai.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.tool.ToolDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
/** Optional AI dependencies: a bad Agent flag combination never prevents trading application startup. */
@Configuration
public class AgentConfiguration {
    @Bean com.pricetrack.exchange.ai.skill.SkillRegistry skillRegistry(ObjectMapper json){return new com.pricetrack.exchange.ai.skill.SkillRegistry(json);}
    @Bean(destroyMethod="close") AgentService agentService(AgentProperties properties,ObjectProvider<AgentModelProvider> model,
            ObjectProvider<AuthorizedKnowledgeRetrieval> retrieval,ToolDispatcher tools,ObjectMapper json,
            com.pricetrack.exchange.ai.skill.SkillProperties skills,com.pricetrack.exchange.ai.skill.SkillRegistry registry,com.pricetrack.exchange.ai.observability.AiObservability observation){
        return new AgentService(properties,model::getIfAvailable,retrieval::getIfAvailable,tools,json,java.time.Duration.ofSeconds(40),skills,registry).observe(observation);
    }
}
