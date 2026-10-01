package com.pricetrack.exchange.ai.agent;
public final class AgentFailure extends RuntimeException {
    private final String code;
    private final int http;
    public AgentFailure(String code,int http){super(code);this.code=code;this.http=http;}
    public String code(){return code;}
    public int http(){return http;}
}
