package com.pricetrack.exchange.ai.store;

import com.pricetrack.exchange.ai.knowledge.KnowledgeCorpus;
import java.util.*;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
public interface KnowledgeStore {
    void initialize();
    boolean active(String fingerprint);
    Map<String, float[]> cached(String model);
    void publish(KnowledgeCorpus corpus, Map<String, float[]> embeddings, String model);
    List<KnowledgeHit> search(String fingerprint, float[] query, int topK, double minimumSimilarity);
    /** Existing implementations are ADMIN-only unless they explicitly implement SQL role filtering. */
    default List<KnowledgeHit> searchForRole(String fingerprint, float[] query, int topK,
            double minimumSimilarity, UserRole role, Duration timeout) {
        if (role != UserRole.ADMIN) throw new org.springframework.security.access.AccessDeniedException("Role-aware store required");
        return search(fingerprint, query, topK, minimumSimilarity);
    }
}
