package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.ai.skill.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

/** Bounded stateless orchestration. Never opens a trading transaction or accesses domain repositories. */
public final class AgentService implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(AgentService.class);
    private final AgentProperties properties;
    private final Supplier<AgentModelProvider> model;
    private final Supplier<AuthorizedKnowledgeRetrieval> retrieval;
    private final ToolDispatcher tools;
    private final ObjectMapper json;
    private final Duration totalBudget;
    private final ThreadPoolExecutor workers;
    private final SkillProperties skills;
    private final SkillRegistry registry;
    private com.pricetrack.exchange.ai.observability.AiObservability observation;
    public AgentService observe(com.pricetrack.exchange.ai.observability.AiObservability value){observation=value;return this;}
    // Retained until the actual worker exits, including a timed-out non-cooperative call.
    private final java.util.concurrent.atomic.AtomicBoolean automaticRunning=new java.util.concurrent.atomic.AtomicBoolean();
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json){this(p,model,retrieval,tools,json,Duration.ofSeconds(40));}
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json,Duration budget){
        this(p,model,retrieval,tools,json,budget,new SkillProperties(false),new SkillRegistry(json));
    }
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json,Duration budget,SkillProperties skills,SkillRegistry registry){
        this.skills=skills;this.registry=registry;
        this.properties=p;this.model=model;this.retrieval=retrieval;this.tools=tools;this.totalBudget=budget;
        this.json=json.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        workers=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
            Thread t=new Thread(r,"read-agent");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public AgentResponse answer(AuthenticatedUser principal,byte[] input){
        return answer(principal,input,false);
    }
    /** Server-only fixed procedure. HTTP cannot supply an origin or an automatic execution plan. */
    public AgentResponse answerAutomatic(AuthenticatedUser principal,long orderId){
        if(principal==null || principal.role()!=com.pricetrack.exchange.user.UserRole.ADMIN || orderId<=0)
            return AgentResponse.failure(UUID.randomUUID().toString(),"TOOL_FORBIDDEN",403);
        byte[] input=("{\"question\":\"이 주문의 검토 필요 상태와 정산 근거를 조사해줘\",\"target\":{\"orderId\":"+orderId+"},\"skillId\":\"settlement-debugging\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return answer(principal,input,true);
    }
    private AgentResponse answer(AuthenticatedUser principal,byte[] input,boolean automatic){
        String runId=UUID.randomUUID().toString();long start=System.nanoTime();Future<AgentResponse> work=null;AgentResponse result;Run run=null;
        try{
            if(principal==null || principal.userId()==null || principal.userId()<=0 || principal.role()==null)throw new AgentFailure("AUTHENTICATION_REQUIRED",401);
            if(!properties.enabled())throw new AgentFailure("AGENT_DISABLED",503);
            AgentRequest request=AgentRequest.parse(json,input);
            var provider=model.get();
            if(provider==null)throw new AgentFailure("AGENT_CONFIGURATION_UNAVAILABLE",503);
            run=new Run(runId,principal,request,provider,start,start+totalBudget.toNanos());
            if(automatic){
                if(!automaticRunning.compareAndSet(false,true))throw new RejectedExecutionException();
                final Run captured=run;
                FutureTask<AgentResponse> task=new FutureTask<>(()->observedRun(runId,captured)){
                    @Override public void run(){try{super.run();}finally{automaticRunning.set(false);}}
                };
                try{workers.execute(task);work=task;}catch(RejectedExecutionException e){automaticRunning.set(false);throw e;}
            }else {final Run captured=run;work=workers.submit(()->observedRun(runId,captured));}
            result=work.get(Math.max(1,run.deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(json.writeValueAsBytes(result).length>65_536)throw new AgentFailure("AGENT_OUTPUT_LIMIT",503);
        }catch(AgentFailure e){result=failure(runId,e.code(),e.http(),run);}
        catch(RejectedExecutionException e){result=failure(runId,"AGENT_BUSY",503,run);}
        catch(TimeoutException e){result=failure(runId,"AGENT_TIMEOUT",504,run);}
        catch(InterruptedException e){Thread.currentThread().interrupt();result=failure(runId,"AGENT_TIMEOUT",504,run);}
        catch(Exception e){result=failure(runId,"AGENT_UNAVAILABLE",503,run);}
        finally{if(work!=null && !work.isDone())work.cancel(true);}
        var m=result.metrics();
        if(observation!=null){
            String dependency=result.uncertainties().stream().filter(c->Set.of("SYNTHESIS_UNAVAILABLE","PLAN_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED","AGENT_CONTEXT_LIMIT").contains(c)).findFirst().orElse(null);
            observation.record("AGENT",result.route(),runId,start,result.httpStatus()<400 && dependency==null,
                result.error()!=null?result.error():dependency!=null?dependency:result.status(),0,null,null);
        }
        if(automatic)log.info("AutomaticDiagnosis runId={} actorId={} origin=AUTO_DIAGNOSIS status={} code={}",runId,principal.userId(),result.status(),result.error());
        log.info("ReadAgent runId={} role={} route={} status={} code={} toolCalls={} retrievalCalls={} modelCalls={} inputTokens={} outputTokens={} latencyMs={}",
                runId,principal==null?null:principal.role(),result.route(),result.status(),result.error(),m.toolCalls(),m.retrievalCalls(),m.modelCalls(),
                m.inputTokens(),m.outputTokens(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        if(result.skill()!=null)log.info("ReadSkill runId={} skillId={} version={} hash={} classification={} trace={}",
                runId,result.skill().id(),result.skill().version(),result.skill().definitionHash(),result.skill().diagnosis().classification(),
                result.skill().trace().stream().map(t -> t.stepId()+":"+t.status()+":"+t.resultCode()).toList());
        return result;
    }
    private AgentResponse observedRun(String id,Run run){return observation==null?run.execute():observation.correlated(id,run::execute);}
    private AgentResponse failure(String id,String code,int http,Run run){
        if(run==null)return AgentResponse.failure(id,code,http);
        return new AgentResponse(id,"ERROR",run.route,"요청을 처리할 수 없습니다.",null,List.of(),List.of(),List.of(),
            List.of(code),List.of(),run.metrics(),code,http);
    }
    private final class Run {
        final String id;final AuthenticatedUser user;final AgentRequest request;final AgentModelProvider provider;final long start,deadline;
        final LinkedHashMap<String,AgentResponse.ToolEvidence> evidence=new LinkedHashMap<>();
        final List<String> uncertainties=new ArrayList<>();List<KnowledgeHit> knowledge=List.of();String index;
        List<String> modelUncertainties=List.of();
        SkillRegistry.Definition skill;SkillRunner skillRunner;
        // One worker writes; timeout/audit thread reads observed counts without waiting for cancellation.
        volatile int toolCalls,retrievalCalls,modelCalls;volatile long inputTokens,outputTokens;volatile String route="NONE";
        Run(String id,AuthenticatedUser user,AgentRequest request,AgentModelProvider provider,long start,long deadline){
            this.id=id;this.user=user;this.request=request;this.provider=provider;this.start=start;this.deadline=deadline;
        }
        Duration remaining(int maxSeconds){long n=deadline-System.nanoTime();if(n<=0 || Thread.currentThread().isInterrupted())throw new AgentFailure("AGENT_TIMEOUT",504);return Duration.ofNanos(Math.min(n,Duration.ofSeconds(maxSeconds).toNanos()));}
        AgentResponse execute(){
            try{
                if(request.conflictingTarget())return response("NEEDS_CLARIFICATION","질문과 target을 일치시켜 다시 요청해 주세요.",List.of(),List.of());
                if(request.mutationRequest())return response("UNSUPPORTED","조회와 근거 설명만 지원합니다.",List.of(),List.of());
                if(request.skillId()!=null){
                    if(!skills.enabled())throw new AgentFailure("SKILLS_DISABLED",503);
                    skill=registry.require(request.skillId(),user.role(),request.targetKind());
                }
                if(request.orderId()!=null)preflight(call("getOrder",Map.of("orderId",request.orderId())));
                if(request.quoteId()!=null)preflight(call("getQuote",Map.of("quoteId",request.quoteId())));
                if(skill!=null && skill.target()==Subject.NONE)preflight(call("getCurrentReferencePrice",Map.of()));
                Plan plan;
                try{
                    if(skill!=null)plan=new Plan(Route.MIXED,skill.target()==Subject.NONE?Subject.PRICE:skill.target(),Usage.none(),skill.id());
                    else {modelCalls++;plan=skills.enabled()?provider.planWithSkills(request.modelQuestion(),request.targetKind(),registry.eligible(user.role(),request.targetKind()),remaining(5))
                            :provider.plan(request.modelQuestion(),request.targetKind(),remaining(5));addUsage(plan==null?null:plan.usage());}
                }
                catch(Exception e){return degraded("PLAN_UNAVAILABLE");}
                if(plan==null || plan.route()==null || plan.subject()==null)throw new AgentFailure("INVALID_AGENT_PLAN",503);
                route=plan.route().name();
                if(skill==null && plan.skillId()!=null && !plan.skillId().equals("NONE")){
                    if(!skills.enabled() || plan.route()!=Route.MIXED || !registry.eligible(user.role(),request.targetKind()).contains(plan.skillId()))
                        throw new AgentFailure("INVALID_AGENT_PLAN",503);
                    skill=registry.require(plan.skillId(),user.role(),request.targetKind());
                    if(plan.subject()!=(skill.target()==Subject.NONE?Subject.PRICE:skill.target()))throw new AgentFailure("INVALID_AGENT_PLAN",503);
                    if(skill.target()==Subject.NONE)preflight(call("getCurrentReferencePrice",Map.of()));
                }
                if(plan.route()==Route.CLARIFY)return response("NEEDS_CLARIFICATION","조회 대상을 명시해 주세요.",List.of(),List.of());
                if(plan.route()==Route.UNSUPPORTED)return response("UNSUPPORTED","조회와 프로젝트 규칙 설명만 지원합니다.",List.of(),List.of());
                if(plan.route()==Route.KNOWLEDGE && (plan.subject()!=Subject.NONE || request.targetKind()!=Subject.NONE))
                    return response("NEEDS_CLARIFICATION","정책 질문과 조회 대상을 구분해 주세요.",List.of(),List.of());
                if(plan.route()!=Route.KNOWLEDGE && (plan.subject()==Subject.NONE
                        || (plan.subject()==Subject.ORDER || plan.subject()==Subject.QUOTE) && plan.subject()!=request.targetKind()
                        || request.targetKind()!=Subject.NONE && plan.subject()!=request.targetKind()))
                    return response("NEEDS_CLARIFICATION","주문/견적 target과 질문 대상을 일치시켜 주세요.",List.of(),List.of());
                if(plan.subject()==Subject.ABNORMAL && user.role()!=UserRole.ADMIN)throw new AgentFailure("TOOL_FORBIDDEN",403);
                if(skill!=null){
                    skillRunner=new SkillRunner(skill,new SkillRunner.RunContext(){
                        public AgentResponse.ToolEvidence call(String name,Map<String,Object> args){return Run.this.call(name,args);}
                        public void checkpoint(){remaining(40);}
                        public void uncertainty(String code){uncertainties.add(code);}
                    });
                    skillRunner.execute(request);
                }else if(plan.route()!=Route.KNOWLEDGE)executeTools(plan);
                checkConsistency();
                if(plan.route()!=Route.STATE){
                    retrievalCalls++;
                    long retrievalStart=System.nanoTime();
                    try{
                        var retriever=retrieval.get();if(retriever==null)throw new AgentFailure("RAG_UNAVAILABLE",503);
                        var found=skill==null?retriever.search(user,retrievalQuestion(plan),remaining(5))
                                :retriever.searchScoped(user,retrievalQuestion(plan),remaining(5),skill.domains());knowledge=found.hits();index=found.indexVersion();
                        if(skillRunner!=null)skillRunner.trace("RETRIEVE_POLICY","EXECUTED","RAG",knowledge.stream().map(KnowledgeHit::id).toList(),knowledge.isEmpty()?"NO_EVIDENCE":"SUCCESS",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-retrievalStart));
                    }catch(Exception e){uncertainties.add("RAG_UNAVAILABLE");traceOnce("RETRIEVE_POLICY","UNAVAILABLE","RAG",List.of(),"RAG_UNAVAILABLE",retrievalStart);}
                }
                if(plan.route()==Route.STATE){
                    if(!hasFacts())return degraded("TOOL_UNAVAILABLE");
                    return response(uncertainties.isEmpty()?"ANSWERED":"PARTIAL","조회 시점의 사실은 toolEvidence에 표시했습니다.",List.of(),List.of());
                }
                if(knowledge.isEmpty()){
                    uncertainties.add("KNOWLEDGE_EVIDENCE_MISSING");
                    return hasFacts()?response("PARTIAL","현재 사실은 조회했지만 관련 규칙의 근거를 확보하지 못했습니다.",List.of(),List.of())
                            :response("INSUFFICIENT_EVIDENCE","승인된 문서에서 충분한 근거를 확인하지 못했습니다.",List.of(),List.of());
                }
                if(plan.route()==Route.MIXED && !hasFacts())return response("PARTIAL","현재 상태는 확인하지 못했습니다. 조회된 정책 출처만 제공합니다.",List.of(),List.of());
                var modelInput=AgentEvidence.modelInput(json,knowledge,List.copyOf(evidence.values()));
                if(skillRunner!=null)modelInput.set("diagnosticObservations",json.valueToTree(skillRunner.result().diagnosis()));
                if(json.writeValueAsBytes(modelInput).length>24_576)return degraded("AGENT_CONTEXT_LIMIT");
                long synthesisStart=System.nanoTime();
                try{
                    modelCalls++;var generated=provider.synthesize(request.modelQuestion(),plan.route(),modelInput,remaining(10));
                    addUsage(generated==null?null:generated.usage());AgentEvidence.validate(generated,plan.route(),modelInput);
                    traceOnce("SUMMARIZE","EXECUTED","MODEL","ANSWERED".equals(generated.status())?generated.citationIds():List.of(),"ANSWERED".equals(generated.status())?"VALIDATED":"NO_EVIDENCE",synthesisStart);
                    if(!"ANSWERED".equals(generated.status()))return response("INSUFFICIENT_EVIDENCE","확보된 근거로 답변을 확정하지 못했습니다.",List.of(),List.of());
                    modelUncertainties=List.copyOf(generated.uncertainties());
                    return response(uncertainties.isEmpty() && modelUncertainties.isEmpty()?"ANSWERED":"PARTIAL",generated.interpretation(),generated.citationIds(),generated.recommendedNextCheck());
                }catch(Exception e){traceOnce("SUMMARIZE","UNAVAILABLE","MODEL",List.of(),"SYNTHESIS_UNAVAILABLE",synthesisStart);return degraded("SYNTHESIS_UNAVAILABLE");}
            }catch(AgentFailure e){return failure(id,e.code(),e.http(),this);}
            catch(Exception e){return degraded("AGENT_UNAVAILABLE");}
        }
        String retrievalQuestion(Plan plan){
            if(plan.route()==Route.KNOWLEDGE)return request.modelQuestion();
            return switch(plan.subject()){
                case ORDER -> "주문 대기 PENDING_ONCHAIN REVIEW_REQUIRED 상태와 온체인 receipt DB 정산 확정 조건";
                case QUOTE -> "서명 견적 만료 validUntil observedAt 30초 일회용 소비 CONSUMED 정책";
                case MARKET,PRICE -> "시장 CLOSED LIVE STALE 가격 신선도 신규 거래 차단 정책";
                case PORTFOLIO -> "포트폴리오 잔고 잠금 available 내부 원장 기준";
                case ABNORMAL -> "REVIEW_REQUIRED SIGNED SUBMITTED 장애 읽기 진단 정책";
                default -> request.modelQuestion();
            };
        }
        void traceOnce(String step,String status,String action,List<String> refs,String code,long start){
            if(skillRunner!=null && skillRunner.result().trace().stream().noneMatch(t -> t.stepId().equals(step)))
                skillRunner.trace(step,status,action,refs,code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
        void executeTools(Plan plan){
            switch(plan.subject()){
                case ORDER -> {
                    var order=call("getOrder",Map.of("orderId",request.orderId()));
                    if(plan.route()==Route.MIXED){
                        var tx=call("getBlockchainTransaction",Map.of("orderId",request.orderId()));
                        if("SUCCESS".equals(tx.status()) && "LINKED".equals(tx.data().path("linkStatus").asText()))
                            call("getReceiptSummary",Map.of("orderId",request.orderId()));
                        if("SUCCESS".equals(order.status()) && order.data().hasNonNull("quoteId"))
                            call("getQuote",Map.of("quoteId",order.data().path("quoteId").asText()));
                    }
                }
                case QUOTE -> call("getQuote",Map.of("quoteId",request.quoteId()));
                case MARKET -> call("getMarketStatus",Map.of());
                case PRICE -> call("getCurrentReferencePrice",Map.of());
                case PORTFOLIO -> call("getPortfolio",Map.of());
                case ABNORMAL -> call("listAbnormalOrders",Map.of("limit",10));
                default -> throw new AgentFailure("INVALID_AGENT_PLAN",503);
            }
        }
        AgentResponse.ToolEvidence call(String name,Map<String,Object> args){
            String key=name+args;
            if(evidence.containsKey(key))return evidence.get(key);
            if(++toolCalls>4)throw new AgentFailure("AGENT_TOOL_BUDGET",503);
            try{
                var result=tools.invokeForAgent(user,name,json.writeValueAsBytes(Map.of("arguments",args)),id,remaining(5));
                var item=AgentResponse.ToolEvidence.from("tool-"+toolCalls,result);evidence.put(key,item);
                if(!"SUCCESS".equals(result.status()))uncertainties.add(name+":"+result.error());
                return item;
            }catch(AgentFailure e){throw e;}catch(Exception e){throw new AgentFailure("TOOL_UNAVAILABLE",503);}
        }
        void preflight(AgentResponse.ToolEvidence result){if(!"SUCCESS".equals(result.status())){
            int http=switch(result.error()){case "RESOURCE_NOT_FOUND" -> 404;case "TOOL_FORBIDDEN" -> 403;case "TOOL_TIMEOUT" -> 504;default -> 503;};
            throw new AgentFailure(result.error(),http);
        }}
        boolean hasFacts(){return evidence.values().stream().anyMatch(e -> "SUCCESS".equals(e.status()));}
        void checkConsistency(){
            JsonNode order=null,quote=null;
            for(var item:evidence.values())if("SUCCESS".equals(item.status())){
                JsonNode data=item.data();
                checkFreshness(data);
                if(data.hasNonNull("reference"))checkFreshness(data.path("reference"));
                if(item.tool().equals("getOrder"))order=data;
                if(item.tool().equals("getQuote"))quote=data;
                if(data.path("orderLink").asText().equals("INCONSISTENT_LINK"))uncertainties.add("INCONSISTENT_LINK");
                if(item.tool().equals("getReceiptSummary")){
                    if(data.path("receiptStatus").asText().equals("NOT_FOUND"))uncertainties.add("RECEIPT_NOT_FOUND");
                    if(data.path("eventValidation").asText().equals("MISMATCH"))uncertainties.add("RECEIPT_EVENT_MISMATCH");
                    if(data.has("confirmations") && data.path("confirmations").asLong()<data.path("requiredConfirmations").asLong())
                        uncertainties.add("CONFIRMATIONS_INSUFFICIENT");
                    if(data.path("executionStatus").asText().equals("SUCCESS") && !data.path("databaseStatus").asText().equals("CONFIRMED"))
                        uncertainties.add("CHAIN_SUCCESS_DB_NOT_CONFIRMED");
                }
            }
            if(order!=null && quote!=null)for(String field:List.of("side","inputAmount","inputSymbol","outputSymbol"))
                if(order.hasNonNull(field) && quote.hasNonNull(field) && !sameFact(field,order.get(field).asText(),quote.get(field).asText()))
                    uncertainties.add("ORDER_QUOTE_MISMATCH:"+field);
        }
        boolean sameFact(String field,String first,String second){
            if(field.equals("inputAmount"))try{return new java.math.BigDecimal(first).compareTo(new java.math.BigDecimal(second))==0;}
                catch(NumberFormatException e){return false;}
            return first.equals(second);
        }
        void checkFreshness(JsonNode data){
            String freshness=data.path("priceStatus").asText();
            if(Set.of("STALE","DEGRADED","INITIALIZING").contains(freshness))uncertainties.add("PRICE_"+freshness);
            if(data.has("priceStatus") && (!data.hasNonNull("observedAt") || invalidObservation(data.path("observedAt").asText())))
                uncertainties.add("PRICE_OBSERVATION_UNVERIFIED");
        }
        boolean invalidObservation(String timestamp){
            try{java.time.Instant.parse(timestamp);return false;}
            catch(java.time.format.DateTimeParseException e){return true;}
        }
        void addUsage(Usage usage){if(usage!=null){inputTokens+=Math.max(0,usage.inputTokens());outputTokens+=Math.max(0,usage.outputTokens());}}
        AgentResponse degraded(String code){
            uncertainties.add(code);
            if(hasFacts() || !knowledge.isEmpty())return response("PARTIAL","확보된 근거만 제공합니다. 추가 해석은 확인하지 못했습니다.",List.of(),List.of());
            return failure(id,code,code.equals("AGENT_TIMEOUT")?504:503,this);
        }
        AgentResponse.Metrics metrics(){return new AgentResponse.Metrics(toolCalls,retrievalCalls,modelCalls,inputTokens,outputTokens,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
        AgentResponse response(String status,String answer,List<String> citations,List<String> next){
            remaining(40);
            if(skill!=null)registry.verify(skill);
            if(skillRunner!=null && skillRunner.result().trace().stream().noneMatch(t -> t.stepId().equals("SUMMARIZE")))
                skillRunner.trace("SUMMARIZE","SKIPPED","MODEL",List.of(),"EVIDENCE_UNAVAILABLE",0);
            // Every output path, including provider-failure PARTIAL, revalidates approval before exposing documents.
            if(!knowledge.isEmpty())try{
                var retriever=retrieval.get();if(retriever==null)throw new AgentFailure("RAG_UNAVAILABLE",503);
                retriever.verify(index);
            }catch(Exception e){
                knowledge=List.of();index=null;modelUncertainties=List.of();uncertainties.add("KNOWLEDGE_APPROVAL_UNVERIFIED");
                if(skillRunner!=null)skillRunner.discardPolicy();
                if(!hasFacts())return failure(id,"KNOWLEDGE_APPROVAL_UNVERIFIED",503,this);
                status="PARTIAL";answer="현재 조회 사실만 제공합니다. 문서 승인 상태를 확인하지 못해 정책 근거는 제외했습니다.";
                citations=List.of();next=List.of();
            }
            remaining(40);
            var safeUncertainties=new LinkedHashSet<>(uncertainties);safeUncertainties.addAll(modelUncertainties);
            return new AgentResponse(id,status,route,answer,index,knowledge,List.copyOf(evidence.values()),List.copyOf(citations),
                    List.copyOf(safeUncertainties),List.copyOf(next),
                    metrics(),null,200,skillRunner==null?null:skillRunner.result());
        }
    }
    @Override public void close(){workers.shutdownNow();}
}
