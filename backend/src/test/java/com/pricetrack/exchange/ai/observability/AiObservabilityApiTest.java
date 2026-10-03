package com.pricetrack.exchange.ai.observability;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pricetrack.exchange.ai.diagnosis.*;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.ai.enabled=false","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:obs_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AiObservabilityApiTest {
    @Autowired MockMvc mvc;@Autowired JwtTokenProvider jwt;@Autowired UserRepository users;
    @MockBean PgDiagnosisStore store;
    String token(UserRole role){User u=new User();u.setLoginId("obs"+UUID.randomUUID());u.setNickname("test");u.setRole(role);users.saveAndFlush(u);return "Bearer "+jwt.createToken(u.getId(),u.getLoginId());}
    @Test void adminOnlyAndAiOutageDoesNotHideLocalCounters()throws Exception{
        mvc.perform(get("/api/ai/observability")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/observability").header("Authorization",token(UserRole.USER))).andExpect(status().isForbidden());verifyNoInteractions(store);
        when(store.observation()).thenThrow(new DiagnosisFailure("SECRET_DB_PASSWORD"));
        String response=mvc.perform(get("/api/ai/observability").header("Authorization",token(UserRole.ADMIN))).andExpect(status().isOk())
            .andExpect(jsonPath("$.diagnosis.status").value("UNAVAILABLE")).andExpect(jsonPath("$.scope").value("PROCESS_LOCAL_RESET_ON_RESTART")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("SECRET_DB_PASSWORD","claimsTodayUtcGlobal");
    }
}
