package com.pricetrack.exchange.ai.skill;
import java.util.List;
/** Safe procedural metadata only. No target IDs, DTO payloads, transaction hashes or model thoughts. */
public record SkillResult(String id,int version,String definitionHash,Diagnosis diagnosis,List<Trace> trace) {
    public record Finding(String code,List<String> evidenceRefs) {}
    public record Diagnosis(String classification,List<Finding> observedFindings,List<String> hypotheses) {}
    public record Trace(String stepId,String status,String actionName,List<String> evidenceRefs,String resultCode,long latencyMs) {}
}
