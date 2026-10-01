package com.pricetrack.exchange.ai.retrieval;

import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.provider.EmbeddingProvider;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.List;

/** Server principal -> SQL candidate role filter. No USER-to-ADMIN principal substitution. */
public class AuthorizedKnowledgeRetrieval {
    private final AiProperties p;
    private final KnowledgeLoader loader;
    private final KnowledgeStore store;
    private final EmbeddingProvider embedding;
    public AuthorizedKnowledgeRetrieval(AiProperties p, KnowledgeLoader loader, KnowledgeStore store, EmbeddingProvider embedding) {
        this.p=p; this.loader=loader; this.store=store; this.embedding=embedding;
    }
    public record Evidence(String indexVersion, List<KnowledgeHit> hits) {}
    public Evidence search(AuthenticatedUser principal, String question, Duration budget) {
        return search(principal,question,budget,true);
    }
    /** Preserves the established Basic RAG provider/store contract for its ADMIN-only wrapper. */
    public Evidence searchLegacyAdmin(AuthenticatedUser principal,String question) {
        if(principal==null || principal.role()!=UserRole.ADMIN)throw new org.springframework.security.access.AccessDeniedException("ADMIN required");
        return search(principal,question,Duration.ofSeconds(p.timeoutSeconds()+5L),false);
    }
    private Evidence search(AuthenticatedUser principal,String question,Duration budget,boolean bounded) {
        if (principal == null || principal.userId() == null || principal.userId() <= 0 || principal.role() == null)
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        if (question == null || question.isBlank() || question.length() > 1000) throw new AiFailure("AI_INPUT_LIMIT");
        long end = System.nanoTime() + budget.toNanos();
        var corpus=loader.load();
        if(!store.active(corpus.fingerprint())) throw new AiFailure("AI_INDEX_NOT_READY");
        float[] vector=(bounded?embedding.embed(List.of(question),remaining(end)):embedding.embed(List.of(question))).getFirst();
        PgKnowledgeStore.vector(vector);
        var candidates=!bounded
                ? store.search(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity())
                : store.searchForRole(corpus.fingerprint(),vector,Math.max(40,p.topK()*8),p.minimumSimilarity(),principal.role(),remaining(end));
        var hits=EvidenceSelector.select(candidates,p.topK(),2);
        // Defense in depth; primary protection is SQL before top-K.
        if (principal.role() == UserRole.USER && hits.stream().anyMatch(h -> !"USER".equals(h.role())))
            throw new AiFailure("AI_ROLE_FILTER_INVALID");
        verify(corpus.fingerprint()); remaining(end);
        return new Evidence(corpus.fingerprint(), hits);
    }
    public void verify(String version) {
        if (!loader.load().fingerprint().equals(version) || !store.active(version)) throw new AiFailure("AI_APPROVAL_MISMATCH");
    }
    private static Duration remaining(long end) {
        long value=end-System.nanoTime();
        if(value<=0 || Thread.currentThread().isInterrupted()) throw new AiFailure("AI_QUERY_TIMEOUT");
        return Duration.ofNanos(value);
    }
}
