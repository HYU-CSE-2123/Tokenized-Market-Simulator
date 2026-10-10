package com.pricetrack.exchange.tradeaudit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.user.*;

@SpringBootTest(properties={"app.trade-audit.enabled=false","app.admin.password="}) @AutoConfigureMockMvc @DirtiesContext
class AuditSecurityTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserRepository users;
    @Test void permissionAndDisabledApiAreExplicitJson() throws Exception {
        mvc.perform(get("/api/admin/trade-audits")).andExpect(status().isUnauthorized());
        var signup=mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"audit_security\",\"password\":\"password123\",\"nickname\":\"test\"}")).andExpect(status().isCreated()).andReturn();
        String user=json.readTree(signup.getResponse().getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/admin/trade-audits").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
        var entity=users.findByLoginId("audit_security").orElseThrow();entity.setRole(UserRole.ADMIN);users.save(entity);
        var login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"audit_security\",\"password\":\"password123\"}")).andExpect(status().isOk()).andReturn();
        String admin=json.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/admin/trade-audits").header("Authorization","Bearer "+admin)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TRADE_AUDIT_DISABLED"));
        mvc.perform(get("/api/admin/trade-audits/not-a-uuid").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIT_QUERY"));
    }
}
