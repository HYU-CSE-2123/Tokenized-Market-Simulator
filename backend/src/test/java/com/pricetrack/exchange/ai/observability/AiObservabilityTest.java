package com.pricetrack.exchange.ai.observability;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class AiObservabilityTest {
    @Test void concurrentCountersAndFixedLabelsKeepSecretsOut()throws Exception{
        var m=new AiObservability();
        try(var pool=Executors.newFixedThreadPool(4)){var jobs=new ArrayList<Future<?>>();for(int i=0;i<100;i++)jobs.add(pool.submit(()->m.record("TOOL","SECRET_TOOL","SECRET_JWT",System.nanoTime(),false,"SECRET_DB_PASSWORD",0,null,null)));for(var f:jobs)f.get();}
        String result=m.snapshot().toString();assertThat(result).doesNotContain("SECRET");
        @SuppressWarnings("unchecked") var series=(Map<String,Object>)m.snapshot().get("series");
        @SuppressWarnings("unchecked") var count=(Map<String,Object>)series.get("TOOL:UNKNOWN");assertThat(count.get("calls")).isEqualTo(100L);assertThat(count.get("failures")).isEqualTo(100L);
    }
    @Test void failureAndUnknownUsageDoNotBecomeZeroOrChangeReturnedFailure(){
        var m=new AiObservability();var error=new IllegalStateException("SECRET_EXCEPTION");
        assertThatThrownBy(()->m.measure("RETRIEVAL","search",()->{throw error;})).isSameAs(error);
        m.record("LLM","call",null,System.nanoTime(),false,"AI_PROVIDER_UNAVAILABLE",0,null,null);
        m.record("LLM","call",null,System.nanoTime(),true,"OK",0,10L,2L);
        assertThat(m.snapshot().toString()).contains("usageReportedCalls=1","usageUnknownCalls=1","reportedInputTokens=10").doesNotContain("SECRET_EXCEPTION");
    }
    @Test void correlationIsClearedAndNestedObservationNeverReexecutes(){
        var m=new AiObservability();var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(m.correlated(UUID.randomUUID().toString(),()->m.measure("RAG","answer",calls::incrementAndGet))).isEqualTo(1);
        assertThat(calls).hasValue(1);assertThat(m.snapshot().toString()).contains("calls=1");
    }
}
