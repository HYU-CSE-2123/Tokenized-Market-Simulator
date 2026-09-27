package com.pricetrack.exchange.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** AI 전용 설정. 거래 datasource/JPA 설정을 재사용하지 않는다. */
@ConfigurationProperties("app.ai")
public record AiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("jdbc:postgresql://127.0.0.1:5433/exchange_ai?connectTimeout=2&socketTimeout=5") String jdbcUrl,
        @DefaultValue("exchange_ai") String dbUser,
        @DefaultValue("") String dbPassword,
        @DefaultValue("../docs/ai-knowledge") String knowledgeRoot,
        @DefaultValue("../docs/ai/ingest-manifest.json") String manifest,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.openai.com/v1/") String apiBase,
        @DefaultValue("text-embedding-3-small") String embeddingModel,
        @DefaultValue("gpt-5.6-terra") String chatModel,
        @DefaultValue("800") int chunkTokens,
        @DefaultValue("60") int overlapTokens,
        @DefaultValue("5") int topK,
        @DefaultValue("0.3") double minimumSimilarity,
        @DefaultValue("20") int timeoutSeconds) {
    public static final int DIMENSIONS = 1536;
    public void validate() {
        if (chunkTokens < 256 || chunkTokens > 2000 || overlapTokens < 0 || overlapTokens >= chunkTokens / 2
                || topK < 1 || topK > 10 || minimumSimilarity < 0 || minimumSimilarity > 1
                || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Invalid AI limits");
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("AI requires PostgreSQL");
    }
}

