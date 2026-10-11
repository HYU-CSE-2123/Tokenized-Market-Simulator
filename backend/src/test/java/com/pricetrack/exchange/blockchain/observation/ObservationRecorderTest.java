package com.pricetrack.exchange.blockchain.observation;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ObservationRecorderTest {
    @Test void storageFailureIsContained(){
        var store=mock(ObservationStore.class);doThrow(new IllegalStateException("secret RPC detail")).when(store).save(any());
        var recorder=new ObservationRecorder(store);
        try{recorder.record(1L,Instant.now(),ObservationRecorder.Phase.SIGNED_RECOVERY,ObservationRecorder.Result.ATTEMPT_UNAVAILABLE);
            await(()->((Long)recorder.health().get("storageFailures"))==1);assertThat(recorder.health().toString()).doesNotContain("secret");}
        finally{recorder.close();}
    }
    @Test void boundedQueueDropsWithoutCallerFailureAndStartIsMonotonic() throws Exception {
        var store=mock(ObservationStore.class);var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);
        doAnswer(i->{entered.countDown();gate.await(5,TimeUnit.SECONDS);return null;}).when(store).save(any());
        var recorder=new ObservationRecorder(store);
        try{var start=recorder.start();assertThat(recorder.start()).isAfter(start);
            recorder.record(1L,start,ObservationRecorder.Phase.RECEIPT_CHECK,ObservationRecorder.Result.RECEIPT_NOT_FOUND);
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<101;i++)recorder.record(1L,recorder.start(),ObservationRecorder.Phase.RECEIPT_CHECK,ObservationRecorder.Result.RECEIPT_NOT_FOUND);
            assertThat(recorder.health().get("queued")).isEqualTo(100);assertThat(recorder.health().get("dropped")).isEqualTo(1L);
        }finally{gate.countDown();recorder.close();}
    }
    static void await(java.util.function.BooleanSupplier condition){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end){try{Thread.sleep(10);}catch(InterruptedException e){throw new AssertionError(e);}}
        assertThat(condition.getAsBoolean()).isTrue();}
}
