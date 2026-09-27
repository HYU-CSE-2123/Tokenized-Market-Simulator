package com.pricetrack.exchange.ai.provider;
import java.util.List;
public interface EmbeddingProvider { List<float[]> embed(List<String> texts); }

