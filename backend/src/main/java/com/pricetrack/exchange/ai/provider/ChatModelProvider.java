package com.pricetrack.exchange.ai.provider;
import java.util.List;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
public interface ChatModelProvider {
    Generated answer(String question, List<KnowledgeHit> evidence);
    record Generated(String status, String answer, List<String> citationIds) {}
}

