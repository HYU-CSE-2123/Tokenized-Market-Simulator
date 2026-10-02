package com.pricetrack.exchange.ai.diagnosis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.ai.agent.AgentResponse;
import com.pricetrack.exchange.ai.knowledge.*;
import java.util.*;
import java.util.function.Supplier;

/** Allow-listed history DTO: no chunk text, prompts, arbitrary provider data, or transaction hash. */
public final class DiagnosisPayload {
    private final ObjectMapper json;
    private final Supplier<KnowledgeLoader> loader;
    private final String model;
    private static final Set<String> FACT_KEYS=Set.of("orderId","symbol","side","status","inputAmount","expectedOutputAmount","inputSymbol","outputSymbol",
        "createdAt","updatedAt","dbReadAt","quoteLink","quoteId","trade","price","baseAmount","baseSymbol","quoteAmount","quoteSymbol","fee","feeSymbol",
        "storedStatus","minimumOutputAmount","expiredByTime","observedAt","validUntil","consumedAt","orderLink","linkStatus","type","databaseStatus",
        "hasRecordedError","errorCategory","blockNumber","submittedAt","confirmedAt","receiptStatus","executionStatus","confirmations","requiredConfirmations",
        "eventStatus","event","rpcReadAt","checkedAt","confirmationStatus","matchStatus","input","output","eventMatch","transactionStatus","receiptFound","outputAmount",
        "eventValidation","confirmationObservedAt","inputWei","outputWei","feeWei","priceE8");
    public DiagnosisPayload(ObjectMapper json,Supplier<KnowledgeLoader> loader,String model){this.json=json;this.loader=loader;this.model=model;}
    public String capture(AgentResponse response)throws Exception{
        ObjectNode out=json.createObjectNode().put("runId",response.runId()).put("responseStatus",response.status()).put("route",response.route())
            .put("executionOrigin","AUTO_DIAGNOSIS").put("model",model).put("indexVersion",response.indexVersion()).put("automaticallyModified",false);
        out.put("answer",response.answer());out.set("uncertainties",json.valueToTree(response.uncertainties()));
        out.set("recommendedNextCheck",json.valueToTree(response.recommendedNextCheck()));out.set("citationIds",json.valueToTree(response.citationIds()));
        out.set("metrics",json.valueToTree(response.metrics()));if(response.skill()!=null)out.set("skill",json.valueToTree(response.skill()));
        ArrayNode facts=out.putArray("toolEvidence");
        for(var t:response.toolEvidence()){
            if(!Set.of("getOrder","getQuote","getBlockchainTransaction","getReceiptSummary").contains(t.tool()))throw new DiagnosisFailure("DIAGNOSIS_EVIDENCE_INVALID");
            ObjectNode fact=facts.addObject().put("evidenceId",t.evidenceId()).put("tool",t.tool()).put("version",t.version()).put("status",t.status())
                .put("retrievedAt",t.retrievedAt()==null?null:t.retrievedAt().toString()).put("error",t.error());
            fact.set("data",safeFacts(t.data(),0));
        }
        ArrayNode sources=out.putArray("knowledgeSources");
        try{
            KnowledgeCorpus corpus=loader.get().load();
            if(!response.knowledgeSources().isEmpty() && !corpus.fingerprint().equals(response.indexVersion()))throw new DiagnosisFailure("APPROVAL_UNVERIFIED");
            for(var h:response.knowledgeSources()){
                var d=corpus.documents().stream().filter(x->x.path().equals(h.path()) && x.metadata().get("version").equals(h.version())).findFirst().orElseThrow();
                sources.addObject().put("id",h.id()).put("path",h.path()).put("version",h.version()).put("sourceHash",d.hash())
                    .put("domain",d.metadata().get("domain")).put("minimumRole",d.metadata().get("minimum_role"));
            }
        }catch(Exception e){redactPolicy(out);}
        String encoded=json.writeValueAsString(out);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)throw new DiagnosisFailure("DIAGNOSIS_OUTPUT_LIMIT");
        return encoded;
    }
    public JsonNode forRead(String stored){
        if(stored==null)return json.nullNode();
        try{
            ObjectNode out=(ObjectNode)json.readTree(stored);
            if(!out.path("knowledgeSources").isEmpty()){
                var corpus=loader.get().load();
                for(var s:out.path("knowledgeSources"))if(corpus.documents().stream().noneMatch(d->d.path().equals(s.path("path").asText())
                    && d.hash().equals(s.path("sourceHash").asText()) && d.metadata().get("version").equals(s.path("version").asText())
                    && d.metadata().get("domain").equals(s.path("domain").asText()) && d.metadata().get("minimum_role").equals(s.path("minimumRole").asText())))
                    throw new DiagnosisFailure("APPROVAL_UNVERIFIED");
            }
            return out;
        }catch(Exception e){try{ObjectNode out=(ObjectNode)json.readTree(stored);redactPolicy(out);return out;}catch(Exception invalid){return json.createObjectNode().put("error","DIAGNOSIS_RESULT_INVALID");}}
    }
    private JsonNode safeFacts(JsonNode node,int depth){
        if(node==null || node.isNull())return json.nullNode();if(depth>4)throw new DiagnosisFailure("DIAGNOSIS_EVIDENCE_INVALID");
        if(node.isObject()){ObjectNode copy=json.createObjectNode();node.fields().forEachRemaining(e->{if(FACT_KEYS.contains(e.getKey()))copy.set(e.getKey(),safeFacts(e.getValue(),depth+1));});return copy;}
        if(node.isArray()){ArrayNode a=json.createArrayNode();if(node.size()>20)throw new DiagnosisFailure("DIAGNOSIS_EVIDENCE_INVALID");node.forEach(x->a.add(safeFacts(x,depth+1)));return a;}
        if(node.isTextual() && node.asText().length()>200)throw new DiagnosisFailure("DIAGNOSIS_EVIDENCE_INVALID");return node.deepCopy();
    }
    private void redactPolicy(ObjectNode out){
        out.put("answer","");out.putNull("indexVersion");out.putArray("citationIds");out.putArray("knowledgeSources");out.putArray("recommendedNextCheck");
        out.putArray("uncertainties").add("APPROVAL_UNVERIFIED");out.put("approvalStatus","APPROVAL_UNVERIFIED");
        if(out.path("skill").isObject())for(var trace:out.path("skill").path("trace"))if(trace.isObject() && Set.of("RETRIEVE_POLICY","SUMMARIZE").contains(trace.path("stepId").asText())){
            ((ObjectNode)trace).putArray("evidenceRefs");((ObjectNode)trace).put("status","UNAVAILABLE").put("resultCode","APPROVAL_UNVERIFIED");
        }
    }
}
