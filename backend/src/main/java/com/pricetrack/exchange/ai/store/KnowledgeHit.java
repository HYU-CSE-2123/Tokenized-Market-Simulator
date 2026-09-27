package com.pricetrack.exchange.ai.store;
/** 서버가 검증한 근거만 인용 가능하다. 경로는 저장소 상대 경로다. */
public record KnowledgeHit(String id, String path, String title, String heading, String version,
                           String role, String content, double similarity) {}

