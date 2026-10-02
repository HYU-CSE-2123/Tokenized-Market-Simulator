package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.skill.SkillResult;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class DiagnosisPayloadTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final KnowledgeLoader loader=mock(KnowledgeLoader.class);
    final KnowledgeCorpus corpus=new KnowledgeCorpus("index",List.of(new KnowledgeCorpus.Document("policy.md","hash",Map.of("version","1","domain","settlement","minimum_role","ADMIN"))),List.of());
    final DiagnosisPayload payload=new DiagnosisPayload(json,()->loader,"gpt-5.6-terra");
    AgentResponse response(){return new AgentResponse("run","PARTIAL","MIXED","해석","index",List.of(new KnowledgeHit("k1","policy.md","정책","제목","1","ADMIN","SECRET_CHUNK_BODY",.9)),
        List.of(new AgentResponse.ToolEvidence("t1","getOrder","1","SUCCESS",Instant.now(),"DB",json.createObjectNode().put("status","PENDING_ONCHAIN").put("rawTransaction","SECRET_RAW").put("signature","SECRET_SIGN").put("txHash","SECRET_HASH"),null)),
        List.of("k1","t1"),List.of("MODEL_POSSIBILITY"),List.of("정책 확인"),new AgentResponse.Metrics(1,1,1,1,1,1),null,200,
        new SkillResult("settlement-debugging",1,"hash",new SkillResult.Diagnosis("REVIEW_REQUIRED_OBSERVED",List.of(),List.of()),List.of(new SkillResult.Trace("SUMMARIZE","EXECUTED","MODEL",List.of("k1"),"VALIDATED",1))));}
    @BeforeEach void setup(){when(loader.load()).thenReturn(corpus);}
    @Test void storedAllowlistOmitsSecretsAndChunkTextButRetainsSafeEvidence()throws Exception{
        String stored=payload.capture(response());assertThat(stored).doesNotContain("SECRET_","content","similarity");
        assertThat(payload.forRead(stored).path("toolEvidence").get(0).path("data").path("status").asText()).isEqualTo("PENDING_ONCHAIN");
        assertThat(payload.forRead(stored).path("answer").asText()).isEqualTo("해석");
    }
    @Test void approvalFailureRedactsInterpretationModelUncertaintyAndPolicyTrace()throws Exception{
        String stored=payload.capture(response());when(loader.load()).thenThrow(new RuntimeException("SECRET_APPROVAL"));
        var result=payload.forRead(stored);assertThat(result.path("answer").asText()).isEmpty();assertThat(result.path("citationIds")).isEmpty();
        assertThat(result.toString()).doesNotContain("MODEL_POSSIBILITY","정책 확인","SECRET_APPROVAL");
        assertThat(result.path("skill").path("trace").get(0).path("evidenceRefs")).isEmpty();
        assertThat(result.path("toolEvidence")).hasSize(1);
    }
    @Test void changedApprovedHashDoesNotExposeOldPolicyAndCaptureAlsoFailsClosed()throws Exception{
        String stored=payload.capture(response());when(loader.load()).thenReturn(new KnowledgeCorpus("new",List.of(),List.of()));
        assertThat(payload.forRead(stored).path("approvalStatus").asText()).isEqualTo("APPROVAL_UNVERIFIED");
        assertThat(json.readTree(payload.capture(response())).path("answer").asText()).isEmpty();
    }
}
