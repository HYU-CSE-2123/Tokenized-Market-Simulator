package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.ToolResult;
import java.time.Instant;
import java.util.List;

/** answer is interpretation; toolEvidence contains server-copied facts, never model-generated values. */
public record AgentResponse(String runId,String status,String route,String answer,String indexVersion,
        List<KnowledgeHit> knowledgeSources,List<ToolEvidence> toolEvidence,List<String> citationIds,
        List<String> uncertainties,List<String> recommendedNextCheck,Metrics metrics,String error,int httpStatus) {
    public record ToolEvidence(String evidenceId,String tool,String version,String status,Instant retrievedAt,
                               String source,JsonNode data,String error) {
        public static ToolEvidence from(String id,ToolResult result){return new ToolEvidence(id,result.tool(),result.version(),
                result.status(),result.retrievedAt(),result.source(),result.data(),result.error());}
    }
    public record Metrics(int toolCalls,int retrievalCalls,int modelCalls,long inputTokens,long outputTokens,long latencyMs) {}
    public static AgentResponse failure(String runId,String code,int http){return new AgentResponse(runId,"ERROR","NONE",
            "요청을 처리할 수 없습니다.",null,List.of(),List.of(),List.of(),List.of(code),List.of(),
            new Metrics(0,0,0,0,0,0),code,http);}
}
