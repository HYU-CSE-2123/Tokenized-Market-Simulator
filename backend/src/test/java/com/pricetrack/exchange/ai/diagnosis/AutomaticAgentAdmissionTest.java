package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutomaticAgentAdmissionTest {
    @Test void nonCooperativeTimedOutAutomaticRunKeepsGateAndManualSlot()throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var tools=mock(ToolDispatcher.class);var model=mock(AgentModelProvider.class);
        var admin=new AuthenticatedUser(1L,"admin",UserRole.ADMIN);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(tools.invokeForAgent(any(),any(),any(),any(),any())).thenAnswer(c->{
            if(calls.incrementAndGet()==1)while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            return ToolResult.success(c.getArgument(1),"READ_ONLY",json.createObjectNode().put("orderId",10).put("status","PENDING_ONCHAIN"));
        });
        when(model.planWithSkills(any(),any(),any(),any())).thenReturn(new AgentModelProvider.Plan(AgentModelProvider.Route.STATE,AgentModelProvider.Subject.ORDER,AgentModelProvider.Usage.none(),"NONE"));
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->null,tools,json,Duration.ofMillis(250),new SkillProperties(true),new SkillRegistry(json))){
            assertThat(agent.answerAutomatic(admin,10).error()).isEqualTo("AGENT_TIMEOUT");
            var busy=agent.answerAutomatic(admin,10);assertThat(busy.error()).isEqualTo("AGENT_BUSY");assertThat(busy.metrics().toolCalls()).isZero();
            var manual=agent.answer(admin,"{\"question\":\"주문 조회\",\"target\":{\"orderId\":10}}".getBytes());
            assertThat(manual.status()).isIn("ANSWERED","PARTIAL");
            release.countDown();
        }finally{release.countDown();}
    }
    @Test void automaticEntryRequiresAdminAndFixedPositiveTarget(){
        var json=new ObjectMapper();var tools=mock(ToolDispatcher.class);var model=mock(AgentModelProvider.class);
        try(var agent=new AgentService(new AgentProperties(true),()->model,()->null,tools,json)){
            assertThat(agent.answerAutomatic(new AuthenticatedUser(1L,"user",UserRole.USER),1).httpStatus()).isEqualTo(403);
            assertThat(agent.answerAutomatic(new AuthenticatedUser(1L,"admin",UserRole.ADMIN),0).httpStatus()).isEqualTo(403);
            verifyNoInteractions(tools,model);
        }
    }
}
