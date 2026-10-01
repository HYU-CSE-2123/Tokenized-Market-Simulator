package com.pricetrack.exchange.ai.skill;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

class SkillServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final ToolDispatcher tools=mock(ToolDispatcher.class);
    final AuthorizedKnowledgeRetrieval retrieval=mock(AuthorizedKnowledgeRetrieval.class);
    final AgentModelProvider model=mock(AgentModelProvider.class);
    final AuthenticatedUser user=new AuthenticatedUser(1L,"secret",UserRole.USER),admin=new AuthenticatedUser(2L,"secret",UserRole.ADMIN);
    final String quoteId="0x"+"aa".repeat(32);
    final Map<String,ObjectNode> data=new HashMap<>();
    final List<String> calls=new ArrayList<>();
    AgentService agent;
    @BeforeEach void setup(){
        agent=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json));
        var order=json.createObjectNode().put("orderId",153).put("quoteId",quoteId).put("symbol","mSEC").put("side","BUY")
            .put("inputAmount","75000").put("inputSymbol","mKRW").put("outputSymbol","mSEC").put("status","PENDING_ONCHAIN").putNull("trade");
        data.put("getOrder",order);
        var quote=order.deepCopy().put("orderLink","LINKED").put("storedStatus","CONSUMED").put("expiredByTime",true);quote.remove("status");data.put("getQuote",quote);
        data.put("getBlockchainTransaction",json.createObjectNode().put("orderId",153).put("linkStatus","LINKED").put("databaseStatus","SUBMITTED"));
        data.put("getReceiptSummary",json.createObjectNode().put("receiptStatus","FOUND").put("executionStatus","SUCCESS")
            .put("eventValidation","MATCH").put("databaseStatus","CONFIRMED").put("confirmations",2).put("requiredConfirmations",1));
        data.put("getCurrentReferencePrice",json.createObjectNode().put("marketStatus","CLOSED").put("priceStatus","STALE").put("provider","SIMULATED").put("observedAt","2026-09-01T00:00:00Z"));
        when(tools.invokeForAgent(any(),anyString(),any(),anyString(),any())).thenAnswer(c -> {
            String tool=c.getArgument(1);calls.add(tool);return ToolResult.success(tool,"READ_ONLY",data.get(tool));
        });
        when(retrieval.searchScoped(any(),anyString(),any(),any())).thenReturn(new AuthorizedKnowledgeRetrieval.Evidence("index",
            List.of(new KnowledgeHit("k1","signed-quote-policy.md","정책","규칙","1","USER","소비는 체결 완료가 아니다",.8))));
        when(model.synthesize(any(),any(),any(),any())).thenAnswer(c -> {
            JsonNode input=c.getArgument(2);var first=input.path("tools").get(0);var payload=first.path("data");
            String pointer=payload.has("status")?"/status":payload.has("storedStatus")?"/storedStatus":"/marketStatus";
            return new Generated("ANSWERED","확인된 상태와 정책을 구분하며 과거 실패 원인은 확인되지 않습니다.",List.of("k1",first.path("evidenceId").asText()),
                List.of(new FactReference(first.path("evidenceId").asText(),pointer,payload.at(pointer).asText())),List.of(),List.of(),Usage.none());
        });
    }
    @AfterEach void close(){agent.close();}
    AgentResponse execute(AuthenticatedUser principal,String skill)throws Exception{
        var body=new LinkedHashMap<String,Object>();body.put("question","조회된 상태와 정책을 진단해 주세요");body.put("skillId",skill);
        if(skill.equals("settlement-debugging"))body.put("target",Map.of("orderId",153));
        if(skill.equals("signed-quote-diagnosis"))body.put("target",Map.of("quoteId",quoteId));
        return agent.answer(principal,json.writeValueAsBytes(body));
    }
    @Test void registryLoadsThreeApprovedDefinitions(){
        var registry=new SkillRegistry(json);
        for(var ceiling:SkillRegistry.ceilings())assertThat(registry.require(ceiling.id(),UserRole.ADMIN,ceiling.target()).hash()).hasSize(64);
        assertThat(registry.eligible(UserRole.USER,Subject.ORDER)).isEmpty();
    }
    @Test void explicitSettlementReusesPreflightAndFixedOrderWithinSharedBudget()throws Exception{
        var result=execute(admin,"settlement-debugging");
        assertThat(result.status()).isEqualTo("ANSWERED");assertThat(calls).containsExactly("getOrder","getQuote","getBlockchainTransaction","getReceiptSummary");
        assertThat(result.metrics().toolCalls()).isEqualTo(4);assertThat(result.metrics().modelCalls()).isEqualTo(1);assertThat(result.metrics().retrievalCalls()).isEqualTo(1);
        assertThat(result.skill().diagnosis().classification()).isEqualTo("WAITING_OBSERVED");
        assertThat(result.skill().trace()).extracting(SkillResult.Trace::stepId).containsExactly("LOAD_ORDER","CHECK_ORDER","LOAD_LINKED_QUOTE","LOAD_TRANSACTION","LOAD_RECEIPT","CHECK_EVIDENCE","RETRIEVE_POLICY","SUMMARIZE");
        verify(model,never()).plan(any(),any(),any());verify(model,never()).planWithSkills(any(),any(),any(),any());
        verify(retrieval).searchScoped(eq(admin),any(),any(),eq(Set.of("trading","settlement","operations","support")));
    }
    @Test void quoteKeepsConsumedAndExpiredIndependentWithoutClaimingSettlement()throws Exception{
        var result=execute(user,"signed-quote-diagnosis");
        assertThat(calls).containsExactly("getQuote","getOrder","getBlockchainTransaction","getReceiptSummary");
        assertThat(result.skill().diagnosis().observedFindings()).extracting(SkillResult.Finding::code).contains("EXPIRED_BY_TIME","CONSUMED_RECORDED");
        assertThat(result.skill().diagnosis().classification()).isNotEqualTo("SETTLED_OBSERVED");
        verify(retrieval).searchScoped(eq(user),any(),any(),eq(Set.of("trading","settlement","support")));
    }
    @Test void marketUsesSingleSnapshotAndLimitedDomainsWithSafeTrace()throws Exception{
        var result=execute(user,"market-availability-diagnosis");
        assertThat(calls).containsExactly("getCurrentReferencePrice");assertThat(result.metrics().toolCalls()).isEqualTo(1);
        assertThat(result.skill().diagnosis().observedFindings()).extracting(SkillResult.Finding::code).contains("MARKET_CLOSED_OBSERVED","PRICE_UNAVAILABLE_OBSERVED","SIMULATED_REFERENCE_OBSERVED");
        assertThat(result.skill().trace()).hasSize(4);assertThat(json.writeValueAsString(result.skill())).doesNotContain("153",quoteId,"secret","priceStatus","observedAt");
        verify(retrieval).searchScoped(eq(user),any(),any(),eq(Set.of("market","trading")));
    }
    @Test void forbiddenSkillAndForeignTargetNeverReachModelsOrRetrieval()throws Exception{
        assertThat(execute(user,"settlement-debugging").httpStatus()).isEqualTo(403);assertThat(calls).isEmpty();
        when(tools.invokeForAgent(any(),any(),any(),any(),any())).thenReturn(ToolResult.failure("getQuote","RESOURCE_NOT_FOUND"));
        var missing=execute(user,"signed-quote-diagnosis");assertThat(missing.httpStatus()).isEqualTo(404);assertThat(missing.skill()).isNull();
        verifyNoInteractions(model,retrieval);
    }
    @Test void invalidTargetUnknownSkillAndDisabledAreRejected()throws Exception{
        for(var body:List.of(Map.of("question","진단","skillId","signed-quote-diagnosis"),Map.of("question","진단","skillId","unknown")))
            assertThat(agent.answer(user,json.writeValueAsBytes(body)).httpStatus()).isEqualTo(400);
        try(var disabled=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json)){
            assertThat(disabled.answer(user,json.writeValueAsBytes(Map.of("question","진단","skillId","market-availability-diagnosis"))).error()).isEqualTo("SKILLS_DISABLED");
        }
        verifyNoInteractions(model,retrieval,tools);
    }
    @Test void autoSelectionUsesOneExistingPlanAndOneSynthesis()throws Exception{
        when(model.planWithSkills(any(),any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.QUOTE,Usage.none(),"signed-quote-diagnosis"));
        var result=agent.answer(user,json.writeValueAsBytes(Map.of("question","견적을 진단해주세요","target",Map.of("quoteId",quoteId))));
        assertThat(result.skill().id()).isEqualTo("signed-quote-diagnosis");assertThat(result.metrics().modelCalls()).isEqualTo(2);
        verify(model).planWithSkills(any(),eq(Subject.QUOTE),eq(List.of("signed-quote-diagnosis")),any());
    }
    @Test void forgedAutoSkillDoesNotExpandRights()throws Exception{
        when(model.planWithSkills(any(),any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.ORDER,Usage.none(),"settlement-debugging"));
        var result=agent.answer(user,json.writeValueAsBytes(Map.of("question","진단","target",Map.of("orderId",153))));
        assertThat(result.error()).isEqualTo("INVALID_AGENT_PLAN");assertThat(calls).containsExactly("getOrder");verifyNoInteractions(retrieval);
    }
    @Test void inconsistentReverseLinkStopsDependentReads()throws Exception{
        data.get("getOrder").put("quoteId","0x"+"bb".repeat(32));
        var result=execute(user,"signed-quote-diagnosis");
        assertThat(result.skill().diagnosis().classification()).isEqualTo("INCONSISTENCY_OBSERVED");assertThat(calls).containsExactly("getQuote","getOrder");
        assertThat(result.skill().trace()).filteredOn(t -> t.status().equals("SKIPPED")).hasSize(2);
    }
    @Test void inputAndDirectionMismatchStopsTransactionBranch()throws Exception{
        for(String field:List.of("side","inputAmount","symbol","inputSymbol","outputSymbol")){
            var original=data.get("getQuote").get(field);data.get("getQuote").put(field,"wrong");calls.clear();
            assertThat(execute(admin,"settlement-debugging").skill().diagnosis().classification()).isEqualTo("INCONSISTENCY_OBSERVED");
            assertThat(calls).containsExactly("getOrder","getQuote");data.get("getQuote").set(field,original);
        }
    }
    @Test void absentQuoteOrderAndTransactionConnectionsAreSkipped()throws Exception{
        data.get("getQuote").putNull("orderId").remove("orderLink");
        var result=execute(user,"signed-quote-diagnosis");assertThat(calls).containsExactly("getQuote");assertThat(result.metrics().toolCalls()).isEqualTo(1);
        assertThat(result.skill().trace()).filteredOn(t -> t.status().equals("SKIPPED")).hasSize(3);
    }
    @Test void buyAndSellSettlementRequiresAllIndependentObservations()throws Exception{
        for(String side:List.of("BUY","SELL")){
            for(String tool:List.of("getOrder","getQuote"))data.get(tool).put("side",side);
            data.get("getOrder").put("status","FILLED").putObject("trade").put("price","75000");
            assertThat(execute(admin,"settlement-debugging").skill().diagnosis().classification()).isEqualTo("SETTLED_OBSERVED");
            data.get("getReceiptSummary").put("confirmations",0);
            assertThat(execute(admin,"settlement-debugging").skill().diagnosis().classification()).isEqualTo("INSUFFICIENT_EVIDENCE");
            data.get("getReceiptSummary").put("confirmations",2).put("databaseStatus","SUBMITTED");
            assertThat(execute(admin,"settlement-debugging").uncertainties()).contains("CHAIN_SUCCESS_DB_NOT_CONFIRMED");
            data.get("getReceiptSummary").put("databaseStatus","CONFIRMED");
        }
    }
    @Test void failedReceiptDoesNotIdentifySignatureExpiryOrSlippageCause()throws Exception{
        data.get("getReceiptSummary").put("executionStatus","FAILED");
        var result=execute(user,"signed-quote-diagnosis");assertThat(result.skill().diagnosis().classification()).isEqualTo("FAILURE_OBSERVED");
        assertThat(result.uncertainties()).contains("FAILURE_CAUSE_UNVERIFIED");assertThat(result.skill().diagnosis().hypotheses()).isEmpty();
    }
    @Test void reviewMismatchAndMissingReceiptAreDistinct()throws Exception{
        data.get("getBlockchainTransaction").put("databaseStatus","REVIEW_REQUIRED");
        assertThat(execute(admin,"settlement-debugging").skill().diagnosis().classification()).isEqualTo("REVIEW_REQUIRED_OBSERVED");
        data.get("getBlockchainTransaction").put("databaseStatus","SUBMITTED");data.get("getReceiptSummary").put("eventValidation","MISMATCH");
        assertThat(execute(admin,"settlement-debugging").skill().diagnosis().classification()).isEqualTo("INCONSISTENCY_OBSERVED");
        data.get("getReceiptSummary").removeAll().put("receiptStatus","NOT_FOUND");
        var result=execute(admin,"settlement-debugging");assertThat(result.uncertainties()).contains("RECEIPT_NOT_FOUND");assertThat(result.skill().diagnosis().classification()).isEqualTo("WAITING_OBSERVED");
    }
    @Test void providerAndRagFailuresPreserveOnlyReadFactsNoScopeFallback()throws Exception{
        when(retrieval.searchScoped(any(),any(),any(),any())).thenThrow(new RuntimeException("SECRET_DB"));
        var result=execute(admin,"settlement-debugging");assertThat(result.status()).isEqualTo("PARTIAL");assertThat(result.skill().trace()).anyMatch(t -> t.stepId().equals("RETRIEVE_POLICY") && t.status().equals("UNAVAILABLE"));
        verify(retrieval,never()).search(any(),any(),any());verifyNoInteractions(model);assertThat(result.toString()).doesNotContain("SECRET_DB");
    }
    @Test void approvalLossPurgesAllKnowledgeReferencesAndSynthesis()throws Exception{
        doReturn(new Generated("ANSWERED","확인한 정책",List.of("k1","tool-1"),List.of(new FactReference("tool-1","/status","PENDING_ONCHAIN")),
                List.of("UNAPPROVED_MODEL_POLICY"),List.of(),Usage.none())).when(model).synthesize(any(),any(),any(),any());
        doThrow(new RuntimeException()).when(retrieval).verify("index");
        var result=execute(admin,"settlement-debugging");assertThat(result.knowledgeSources()).isEmpty();assertThat(result.citationIds()).isEmpty();
        assertThat(result.skill().trace()).filteredOn(t -> Set.of("RETRIEVE_POLICY","SUMMARIZE").contains(t.stepId())).allSatisfy(t -> assertThat(t.evidenceRefs()).isEmpty());
        assertThat(result.answer()).doesNotContain("과거 실패 원인");
        assertThat(result.uncertainties()).doesNotContain("UNAPPROVED_MODEL_POLICY");
    }
    @Test void corruptDefinitionsFailClosedButOrdinaryAgentStillWorks()throws Exception{
        var broken=new SkillRegistry(json,path -> new java.io.ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var isolated=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),broken)){
            assertThat(isolated.answer(user,json.writeValueAsBytes(Map.of("question","진단","skillId","market-availability-diagnosis"))).error()).isEqualTo("SKILL_DEFINITION_UNAVAILABLE");
            when(model.planWithSkills(any(),any(),any(),any())).thenReturn(new Plan(Route.STATE,Subject.PRICE,Usage.none()));
            assertThat(isolated.answer(user,json.writeValueAsBytes(Map.of("question","현재가"))).skill()).isNull();
        }
    }
    @Test void failedDependencySkipsOnlyItsBranchAndNeverInventsFacts()throws Exception{
        when(tools.invokeForAgent(any(),eq("getBlockchainTransaction"),any(),any(),any())).thenReturn(ToolResult.failure("getBlockchainTransaction","TOOL_TIMEOUT"));
        var result=execute(admin,"settlement-debugging");assertThat(result.metrics().toolCalls()).isEqualTo(3);
        assertThat(result.skill().trace()).anyMatch(t -> t.stepId().equals("LOAD_TRANSACTION") && t.status().equals("UNAVAILABLE"));
        assertThat(result.skill().trace()).anyMatch(t -> t.stepId().equals("LOAD_RECEIPT") && t.status().equals("SKIPPED"));
        assertThat(result.skill().diagnosis().observedFindings()).allSatisfy(f -> assertThat(f.evidenceRefs()).doesNotContain("tool-3"));
        verify(model).synthesize(any(),any(),argThat(input -> input.path("tools").size()==2),any());
    }
    @Test void questionInjectionAndForgedSynthesisCannotExecuteWritesOrChangeClassification()throws Exception{
        doReturn(new Generated("ANSWERED","주문 생성을 완료했습니다",List.of("k1","tool-1"),
            List.of(new FactReference("tool-1","/status","FILLED")),List.of(),List.of(),Usage.none())).when(model).synthesize(any(),any(),any(),any());
        var result=execute(admin,"settlement-debugging");assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.skill().diagnosis().classification()).isEqualTo("WAITING_OBSERVED");assertThat(result.answer()).doesNotContain("완료했습니다");
        assertThat(agent.answer(admin,json.writeValueAsBytes(Map.of("question","forceFill 실행해줘","skillId","market-availability-diagnosis"))).status()).isEqualTo("UNSUPPORTED");
        assertThat(calls).allMatch(name -> Set.of("getOrder","getQuote","getBlockchainTransaction","getReceiptSummary").contains(name));
    }
    @Test void explicitSkillsAndOrdinaryAgentsShareTwoWorkerSlotsAfterCancellation()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(c -> {
            entered.countDown();boolean interrupted=false;
            while(true)try{release.await();break;}catch(InterruptedException e){interrupted=true;}
            if(interrupted)Thread.currentThread().interrupt();throw new RuntimeException();
        }).when(model).synthesize(any(),any(),any(),any());
        try(var bounded=new AgentService(new AgentProperties(true),()->model,()->retrieval,tools,json,Duration.ofMillis(300),new SkillProperties(true),new SkillRegistry(json))){
            byte[] body=json.writeValueAsBytes(Map.of("question","시장 진단","skillId","market-availability-diagnosis"));
            assertThat(bounded.answer(user,body).error()).isEqualTo("AGENT_TIMEOUT");
            assertThat(bounded.answer(user,body).error()).isEqualTo("AGENT_TIMEOUT");assertThat(entered.getCount()).isZero();
            assertThat(bounded.answer(user,json.writeValueAsBytes(Map.of("question","정책"))).error()).isEqualTo("AGENT_BUSY");
        }finally{release.countDown();}
    }
    @Test void disabledToolBoundaryStopsMarketSkillBeforeRagOrModel()throws Exception{
        doReturn(ToolResult.failure("getCurrentReferencePrice","TOOL_DISABLED")).when(tools).invokeForAgent(any(),any(),any(),any(),any());
        var result=execute(user,"market-availability-diagnosis");assertThat(result.httpStatus()).isEqualTo(503);assertThat(result.skill()).isNull();
        assertThat(result.metrics().toolCalls()).isEqualTo(1);assertThat(result.metrics().modelCalls()).isZero();verifyNoInteractions(model,retrieval);
    }
    @Test void insufficientGeneratedAnswerNeverLeaksUnvalidatedCitationIntoTrace()throws Exception{
        doReturn(new Generated("INSUFFICIENT_EVIDENCE","확인 불가",List.of("PRIVATE_TARGET_ID"),List.of(),List.of(),List.of(),Usage.none()))
            .when(model).synthesize(any(),any(),any(),any());
        var result=execute(user,"signed-quote-diagnosis");assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(json.writeValueAsString(result.skill())).doesNotContain("PRIVATE_TARGET_ID");
        assertThat(result.skill().trace()).filteredOn(t -> t.stepId().equals("SUMMARIZE")).allSatisfy(t -> assertThat(t.evidenceRefs()).isEmpty());
    }
}
