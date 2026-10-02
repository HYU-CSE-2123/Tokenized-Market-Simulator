package com.pricetrack.exchange.ai.diagnosis;

/** Closed, non-sensitive outward failures. Never expose database/provider exception messages. */
public final class DiagnosisFailure extends RuntimeException {
    public DiagnosisFailure(String code){super(code);}
}
