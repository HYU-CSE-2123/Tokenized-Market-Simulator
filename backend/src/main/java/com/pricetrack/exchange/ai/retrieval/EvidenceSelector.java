package com.pricetrack.exchange.ai.retrieval;

import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.util.*;

/** 검색 후보를 충분히 확보한 뒤 최종 Top-K를 구성한다. 점수/권한을 변경하지 않는다. */
public final class EvidenceSelector {
    private EvidenceSelector() {}
    public static List<KnowledgeHit> select(List<KnowledgeHit> candidates, int topK, int perDocument) {
        if (topK < 1 || perDocument < 1) throw new IllegalArgumentException("Invalid evidence limits");
        Map<String, Integer> counts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<KnowledgeHit> selected = new ArrayList<>();
        for (var hit : candidates) {
            if (!seen.add(hit.id()) || counts.getOrDefault(hit.path(), 0) >= perDocument) continue;
            selected.add(hit);
            counts.merge(hit.path(), 1, Integer::sum);
            if (selected.size() == topK) break;
        }
        return List.copyOf(selected);
    }
}
