package com.pricetrack.exchange.ai.diagnosis;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.ai.enabled=false","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:diagnosis_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class DiagnosisApiIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JwtTokenProvider jwt;@Autowired UserRepository users;
    @MockBean PgDiagnosisStore store;@MockBean DiagnosisPayload payload;
    User admin,user;
    @BeforeEach void setup(){admin=account(UserRole.ADMIN);user=account(UserRole.USER);}
    User account(UserRole role){User u=new User();u.setLoginId("diag"+UUID.randomUUID());u.setNickname("test");u.setRole(role);return users.saveAndFlush(u);}
    String token(User u){return "Bearer "+jwt.createToken(u.getId(),u.getLoginId());}
    @Test void jwtAndAdminEnforcedBeforeHistoryAccess()throws Exception{
        mvc.perform(get("/api/ai/diagnoses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/diagnoses").header("Authorization",token(user))).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/diagnoses/index").header("Authorization",token(user))).andExpect(status().isForbidden());verifyNoInteractions(store,payload);
    }
    @Test void adminListAndDetailNeverReturnStoredRawJsonAndNoMutationEndpoint()throws Exception{
        when(store.list(null,null,Long.MAX_VALUE,20)).thenReturn(List.of(new PgDiagnosisStore.Row(1,10L,"COMPLETED",Instant.now(),null,null,false,null,null)));
        mvc.perform(get("/api/ai/diagnoses").header("Authorization",token(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].jobStatus").value("COMPLETED"));
        when(store.detail(1)).thenReturn(new PgDiagnosisStore.Row(1,10L,"COMPLETED",Instant.now(),null,null,false,null,"SECRET_JSON"));
        when(payload.forRead("SECRET_JSON")).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("answer","safe"));
        var result=mvc.perform(get("/api/ai/diagnoses/1").header("Authorization",token(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("safe").doesNotContain("SECRET_JSON");
        mvc.perform(post("/api/ai/diagnoses/1/retry").header("Authorization",token(admin))).andExpect(status().isNotFound());
    }
    @Test void adminMissingAndAiFailureUseClosedCodes()throws Exception{
        when(store.detail(1)).thenThrow(new DiagnosisFailure("DIAGNOSIS_NOT_FOUND"));mvc.perform(get("/api/ai/diagnoses/1").header("Authorization",token(admin))).andExpect(status().isNotFound());
        when(store.list(any(),any(),anyLong(),anyInt())).thenThrow(new DiagnosisFailure("DIAGNOSIS_DATABASE_UNAVAILABLE"));
        mvc.perform(get("/api/ai/diagnoses").header("Authorization",token(admin))).andExpect(status().isServiceUnavailable());
    }
}
