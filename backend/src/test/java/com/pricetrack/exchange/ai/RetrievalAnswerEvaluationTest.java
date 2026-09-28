package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 검색 후보 오탐과 최종 답변 오탐을 분리한다. 임의 모델 판정으로 retrieval 점수를 바꾸지 않는다. */
@EnabledIfEnvironmentVariable(named="AI_RETRIEVAL_ANSWER_EVALUATION",matches="true")
class RetrievalAnswerEvaluationTest {
    @Test void compareBaselineAndSelectedEvidenceWithRealProvider() throws Exception {
        var json=new ObjectMapper();
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:5433/exchange_ai_retrieval_test","exchange_ai","unused",
                "../docs/ai-knowledge","../docs/ai/ingest-manifest.json",System.getenv("OPENAI_API_KEY"),
                "https://api.openai.com/v1/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var provider=new OpenAiProvider(p,json);
        var report=json.readTree(Path.of("build/reports/ai/phase3-experiments.json").toFile());
        assertThat(report.path("goldenHash").asText()).isEqualTo("22006b55ed6256672195c873399df99fae5741fd47a9ba4319484fb97dd881ac");
        Set<String> required=Set.of("A_baseline","K_clean1200_combined","L_hybrid_probe");
        Set<String> evaluated=new HashSet<>();
        List<Map<String,Object>> results=new ArrayList<>();
        for(var experiment:report.path("experiments")) {
            if(!required.contains(experiment.path("name").asText()))continue;
            assertThat(experiment.path("rows").size()).isEqualTo(28);
            evaluated.add(experiment.path("name").asText());
            int falseAnswers=0;
            for(int i=0;i<20;i++) {
                if(i<12 && i!=1 && i!=8)continue;
                var row=experiment.path("rows").get(i);
                List<KnowledgeHit> evidence=List.of(json.treeToValue(row.path("hits"),KnowledgeHit[].class));
                String status="INSUFFICIENT_EVIDENCE";String answer="";
                if(!evidence.isEmpty()) {
                    var generated=provider.answer(row.path("question").asText(),evidence);
                    status=generated.status();answer=generated.answer();
                    if(status.equals("ANSWERED")) {
                        assertThat(generated.citationIds()).isNotEmpty().isSubsetOf(evidence.stream().map(KnowledgeHit::id).toList());
                    }
                }
                if(i>=12 && !status.equals("INSUFFICIENT_EVIDENCE"))falseAnswers++;
                results.add(Map.of("variant",experiment.path("name").asText(),"question",row.path("question").asText(),
                        "negative",i>=12,"status",status,"answer",answer));
                json.writerWithDefaultPrettyPrinter().writeValue(Path.of("build/reports/ai/phase3-answers.json").toFile(),results);
            }
            assertThat(falseAnswers).as("Negative answer errors: "+experiment.path("name").asText()).isZero();
        }
        assertThat(evaluated).containsExactlyInAnyOrderElementsOf(required);
    }
}
