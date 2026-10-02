package com.pricetrack.exchange.ai.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.skill.SkillProperties;
import com.pricetrack.exchange.ai.tool.ToolProperties;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="app.ai.enabled",havingValue="true")
public class DiagnosisConfiguration {
    @Bean DiagnosisSourceReader diagnosisSourceReader(EntityManager em){return new DiagnosisSourceReader(em);}
    @Bean(destroyMethod="close") PgDiagnosisStore diagnosisStore(AiProperties ai,DiagnosisProperties p){return new PgDiagnosisStore(ai,p);}
    @Bean DiagnosisPayload diagnosisPayload(ObjectMapper json,ObjectProvider<KnowledgeLoader> loader,AiProperties ai){return new DiagnosisPayload(json,loader::getIfAvailable,ai.chatModel());}
    @Bean(initMethod="start",destroyMethod="close") DiagnosisCoordinator diagnosisCoordinator(DiagnosisProperties p,DiagnosisSourceReader reader,PgDiagnosisStore store,
            AgentService agent,DiagnosisPayload payload,AgentProperties ap,SkillProperties sp,ToolProperties tp){
        // Invalid flag combinations never start automatic trading reads or calls.
        var effective=new DiagnosisProperties(p.enabled() && ap.enabled() && sp.enabled() && tp.enabled(),p.actorLoginId(),p.sourceNamespace(),p.pollIntervalMs(),p.batchSize(),p.queueLimit(),p.dailyLimit(),p.retentionDays());
        return new DiagnosisCoordinator(effective,reader,store,agent,payload);
    }
}
