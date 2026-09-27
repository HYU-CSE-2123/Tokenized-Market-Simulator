package com.pricetrack.exchange.ai.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeCorpus(String fingerprint, List<Document> documents, List<Chunk> chunks) {
    public record Document(String path, String hash, Map<String, String> metadata) {}
    public record Chunk(String id, String path, String title, String heading, String version,
                        String role, String domain, String type, String sourceHash, String content) {}
}

