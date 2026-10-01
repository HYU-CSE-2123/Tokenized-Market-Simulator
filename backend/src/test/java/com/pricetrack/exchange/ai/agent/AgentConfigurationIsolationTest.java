package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pricetrack.exchange.auth.*;
import com.pricetrack.exchange.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties={"app.ai.agent.enabled=true","app.ai.enabled=false","app.ai.tools.enabled=false",
        "app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
        "spring.datasource.url=jdbc:h2:mem:agent_bad_flags;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AgentConfigurationIsolationTest {
    @Autowired MockMvc mvc;@Autowired UserRepository users;@Autowired JwtTokenProvider jwt;
    @Test void wrongFlagCombinationReturns503WhileTradingApplicationStarts()throws Exception{
        var u=new User();u.setLoginId("agent-bad-flags");u.setNickname("flags");u.setPasswordHash("unused");u=users.saveAndFlush(u);
        var token=jwt.createToken(u.getId(),u.getLoginId());
        var result=mvc.perform(post("/api/ai/agent/answers").header("Authorization","Bearer "+token).content("{\"question\":\"정책\"}"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("AGENT_CONFIGURATION_UNAVAILABLE");
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
