package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.diagnosis.DiagnosisSourceReader.Target;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.util.*;
import org.junit.jupiter.api.*;

class DiagnosisCoordinatorTest {
    final DiagnosisSourceReader source=mock(DiagnosisSourceReader.class);final PgDiagnosisStore store=mock(PgDiagnosisStore.class);
    final AgentService agent=mock(AgentService.class);final DiagnosisPayload payload=mock(DiagnosisPayload.class);
    final Target target=new Target(1,10L,"0x"+"aa".repeat(32),"REVIEW_REQUIRED","BUY");
    final PgDiagnosisStore.Job job=new PgDiagnosisStore.Job(1,target,"token",1);
    final AuthenticatedUser admin=new AuthenticatedUser(2L,"admin",UserRole.ADMIN);
    final DiagnosisProperties p=new DiagnosisProperties(true,"admin","test",5000,2,100,20,7);
    DiagnosisCoordinator coordinator;
    @BeforeEach void setup()throws Exception{
        coordinator=new DiagnosisCoordinator(p,source,store,agent,payload);
        when(store.claim(any())).thenReturn(job);when(source.actor("admin")).thenReturn(admin);
        when(source.current(1)).thenReturn(target);when(source.linked(target)).thenReturn(true);
        when(agent.answerAutomatic(admin,10)).thenReturn(response("PARTIAL",null,1));when(payload.capture(any())).thenReturn("{}");
    }
    AgentResponse response(String status,String error,int calls){return new AgentResponse("run",status,"MIXED","observed",null,List.of(),List.of(),List.of(),List.of(),List.of(),new AgentResponse.Metrics(calls,0,0,0,0,1),error,error==null?200:503);}
    @Test void fixedSkillResultOnlyWritesAiHistory()throws Exception{
        coordinator.dispatch();verify(agent).answerAutomatic(admin,10);verify(store).finish(job,"COMPLETED",null,"{}",2L,false);
        verify(source,times(2)).actor("admin");verify(source,never()).scan(anyLong(),anyInt());
    }
    @Test void staleAndDamagedTargetsSkipWithoutCallingAgent(){
        when(source.current(1)).thenReturn(new Target(1,10L,target.txHash(),"CONFIRMED","BUY"));coordinator.dispatch();
        verify(store).finish(job,"SKIPPED","SKIPPED_STALE_TARGET",null,2L,true);verifyNoInteractions(agent,payload);
        reset(store);when(store.claim(any())).thenReturn(job);when(source.current(1)).thenReturn(target);when(source.linked(target)).thenReturn(false);
        coordinator.dispatch();verify(store).finish(job,"SKIPPED","SKIPPED_INVALID_LINK",null,2L,false);
    }
    @Test void onlyZeroCallBusyRequeues(){
        when(agent.answerAutomatic(admin,10)).thenReturn(response("ERROR","AGENT_BUSY",0));coordinator.dispatch();verify(store).busy(job);
        reset(store);when(store.claim(any())).thenReturn(job);when(agent.answerAutomatic(admin,10)).thenReturn(response("ERROR","AGENT_TIMEOUT",1));
        coordinator.dispatch();verify(store,never()).busy(any());verify(store).finish(job,"FAILED","AGENT_TIMEOUT","{}",2L,false);
    }
    @Test void dbAndActorFailuresCannotEscapeIntoTrading(){
        when(store.claim(any())).thenThrow(new DiagnosisFailure("DIAGNOSIS_DATABASE_UNAVAILABLE"));assertThatCode(coordinator::dispatch).doesNotThrowAnyException();
        doReturn(job).when(store).claim(any());when(source.actor("admin")).thenThrow(new DiagnosisFailure("DIAGNOSIS_ACTOR_UNAVAILABLE"));
        coordinator.dispatch();verifyNoInteractions(agent);verify(store).finish(job,"FAILED","DIAGNOSIS_ACTOR_UNAVAILABLE",null,null,false);
    }
    @Test void keysetWrapsAndFailedScanDoesNotAdvancePastLostPage(){
        var second=new Target(2,20L,target.txHash(),"REVIEW_REQUIRED","BUY");when(source.scan(0,2)).thenReturn(List.of(target,second));when(source.scan(2,2)).thenReturn(List.of());
        coordinator.scan();coordinator.scan();coordinator.scan();verify(source,times(2)).scan(0,2);verify(source).scan(2,2);
        when(source.scan(2,2)).thenReturn(List.of(target,second));when(store.enqueue(any())).thenThrow(new DiagnosisFailure("DIAGNOSIS_DATABASE_UNAVAILABLE"));
        coordinator.scan();coordinator.scan();verify(source,times(3)).scan(2,2);
    }
    @Test void changedStateDuringRunMarksHistoricalResultStale(){
        when(source.current(1)).thenReturn(target,new Target(1,10L,target.txHash(),"CONFIRMED","BUY"));coordinator.dispatch();verify(store).finish(job,"COMPLETED",null,"{}",2L,true);
    }
    @Test void disabledAndInvalidSettingsDoNotReadOrClaim(){
        try(var off=new DiagnosisCoordinator(new DiagnosisProperties(false,"admin","test",5000,50,100,20,7),source,store,agent,payload)){off.scan();off.dispatch();}
        try(var bad=new DiagnosisCoordinator(new DiagnosisProperties(true,"","",5000,50,100,20,7),source,store,agent,payload)){bad.scan();bad.dispatch();}
        verifyNoInteractions(source,store,agent,payload);
    }
}
