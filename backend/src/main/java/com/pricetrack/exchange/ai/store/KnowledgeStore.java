package com.pricetrack.exchange.ai.store;

import com.pricetrack.exchange.ai.knowledge.KnowledgeCorpus;
import java.util.*;
public interface KnowledgeStore {
    void initialize();
    boolean active(String fingerprint);
    Map<String, float[]> cached(String model);
    void publish(KnowledgeCorpus corpus, Map<String, float[]> embeddings, String model);
    List<KnowledgeHit> search(String fingerprint, float[] query, int topK, double minimumSimilarity);
}

