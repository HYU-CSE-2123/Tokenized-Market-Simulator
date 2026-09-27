package com.pricetrack.exchange.ai;

/** 외부 오류 원문·비밀값을 API나 로그에 전달하지 않는 AI 전용 실패다. */
public class AiFailure extends RuntimeException {
    private final String code;
    public AiFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

