package com.pricetrack.exchange.ai;

import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.ai.retrieval.EvidenceSelector;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Basic RAG만 수행한다. 거래 repository/service, Tool, Agent를 참조하지 않는다. */
public class RagService {
    private final AiProperties properties;
    private final KnowledgeLoader loader;
    private final KnowledgeStore store;
    private final EmbeddingProvider embedding;
    private final ChatModelProvider chat;
    private final AuthorizedKnowledgeRetrieval retrieval;
    private final Semaphore requests = new Semaphore(2);
    private final AtomicBoolean indexing = new AtomicBoolean();
    public RagService(AiProperties properties, KnowledgeLoader loader, KnowledgeStore store,
            EmbeddingProvider embedding, ChatModelProvider chat) {
        this.properties=properties; this.loader=loader; this.store=store; this.embedding=embedding; this.chat=chat;
        this.retrieval=new AuthorizedKnowledgeRetrieval(properties,loader,store,embedding);
    }
    public record IndexResult(String indexVersion, int documents, int chunks, boolean unchanged) {}
    public record Answer(String status, String answer, String indexVersion, List<KnowledgeHit> sources) {}
    public IndexResult ingest(AuthenticatedUser principal) {
        authorize(principal);
        if (!indexing.compareAndSet(false,true)) throw new AiFailure("AI_BUSY");
        try {
            KnowledgeCorpus corpus=loader.load();
            if(corpus.chunks().size()>500) throw new AiFailure("AI_INPUT_LIMIT");
            store.initialize();
            if(store.active(corpus.fingerprint())) return new IndexResult(corpus.fingerprint(),corpus.documents().size(),corpus.chunks().size(),true);
            Map<String,float[]> vectors=new HashMap<>(store.cached(properties.embeddingModel()));
            var missing=corpus.chunks().stream().filter(c -> !vectors.containsKey(c.id())).toList();
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(3).toNanos();
            for(int start=0;start<missing.size();start+=16) {
                if(System.nanoTime()>deadline) throw new AiFailure("AI_INDEX_TIMEOUT");
                var batch=missing.subList(start,Math.min(start+16,missing.size()));
                var result=embedding.embed(batch.stream().map(KnowledgeCorpus.Chunk::content).toList());
                if(result.size()!=batch.size()) throw new AiFailure("AI_VECTOR_INVALID");
                for(int i=0;i<batch.size();i++) { PgKnowledgeStore.vector(result.get(i)); vectors.put(batch.get(i).id(),result.get(i)); }
            }
            verifyUnchanged(corpus.fingerprint());
            store.publish(corpus,vectors,properties.embeddingModel());
            return new IndexResult(corpus.fingerprint(),corpus.documents().size(),corpus.chunks().size(),false);
        } finally { indexing.set(false); }
    }
    public List<KnowledgeHit> search(AuthenticatedUser principal,String question) {
        authorize(principal); validate(question);
        if(!requests.tryAcquire())throw new AiFailure("AI_BUSY");
        try { return retrieve(principal,question).hits(); } finally {requests.release();}
    }
    public Answer answer(AuthenticatedUser principal,String question) {
        authorize(principal); validate(question);
        if(!requests.tryAcquire())throw new AiFailure("AI_BUSY");
        try {
            // Only unambiguous value requests short-circuit. Static policy questions may contain 현재/내 주문.
            String compact=question.replaceAll("\\s+","");
            if(compact.matches("^(지금|현재)?(내|제)(잔고|포트폴리오)(는|가)?[?？]*$")
                    || compact.matches("^(지금|현재)?(내|제)주문(이)?(지금)?체결됐(어|나요)?[?？]*$")
                    || compact.matches("^(지금|현재)?(삼성전자|삼전|mSEC)(가격|현재가)(은|는|이|가)?얼마(야|예요|인가요)?[?？]*$"))
                return unavailable("LIVE_DATA_REQUIRED",null);
            Retrieved retrieved=retrieve(principal,question);
            if(retrieved.hits().isEmpty())return unavailable("INSUFFICIENT_EVIDENCE",retrieved.version());
            var result=chat.answer(question,retrieved.hits());
            verifyUnchanged(retrieved.version());
            if(!"ANSWERED".equals(result.status())) return unavailable(result.status(),retrieved.version());
            Set<String> citations=new LinkedHashSet<>(result.citationIds());
            if(citations.isEmpty() || !retrieved.hits().stream().map(KnowledgeHit::id).toList().containsAll(citations))
                throw new AiFailure("AI_CITATION_INVALID");
            return new Answer("ANSWERED",result.answer(),retrieved.version(),
                    retrieved.hits().stream().filter(h -> citations.contains(h.id())).toList());
        } finally {requests.release();}
    }
    private record Retrieved(String version,List<KnowledgeHit> hits) {}
    private Retrieved retrieve(AuthenticatedUser principal,String question) {
        var found=retrieval.searchLegacyAdmin(principal,question);
        return new Retrieved(found.indexVersion(),found.hits());
    }
    private void verifyUnchanged(String fingerprint) {
        if(!loader.load().fingerprint().equals(fingerprint))throw new AiFailure("AI_APPROVAL_MISMATCH");
    }
    private Answer unavailable(String status,String version) {
        if("LIVE_DATA_REQUIRED".equals(status))
            return new Answer(status,"현재 상태는 Tool 연결 후 조회 가능합니다. 이 단계에서는 개인 거래·실시간 값을 확인하지 않습니다.",version,List.of());
        return new Answer("INSUFFICIENT_EVIDENCE","승인된 문서에서 충분한 근거를 확인하지 못했습니다.",version,List.of());
    }
    private void validate(String question) {
        if(question==null || question.isBlank() || question.length()>1000)throw new AiFailure("AI_INPUT_LIMIT");
    }
    private void authorize(AuthenticatedUser user) {
        if(user==null || user.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
    }
}
