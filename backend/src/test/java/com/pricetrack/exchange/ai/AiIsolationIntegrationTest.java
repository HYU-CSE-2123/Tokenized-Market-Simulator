package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.order.OrderService;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.ai.enabled=true","app.ai.jdbc-url=jdbc:postgresql://127.0.0.1:1/ai_unavailable",
        "app.ai.db-password=test","app.ai.knowledge-root=../docs/ai-knowledge",
        "app.ai.manifest=../docs/ai/ingest-manifest.json","app.blockchain.enabled=false"})
@AutoConfigureMockMvc
class AiIsolationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired UserRepository users;
    @Autowired WalletService wallets;
    @Autowired OrderService orders;
    private UsernamePasswordAuthenticationToken auth(UserRole role) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L,"test",role),"",
                List.of(new SimpleGrantedAuthority("ROLE_"+role.name())));
    }
    @Test void aiUnavailableDoesNotReplaceJpaDatasourceOrBlockTrading() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        mvc.perform(post("/api/ai/index").with(authentication(auth(UserRole.ADMIN))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_DATABASE_UNAVAILABLE"));
        User user=new User();user.setLoginId("ai-isolation-"+System.nanoTime());user.setNickname("AI isolation");
        user=users.saveAndFlush(user);wallets.initializeBalances(user.getId());wallets.faucet(user.getId());
        assertThat(orders.buy(user.getId(),"mSEC",new BigDecimal("10000"),null).getStatus().name()).isEqualTo("FILLED");
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/markets")).andExpect(status().isOk());
    }
    @Test void aiEndpointsRejectAnonymousAndUserBeforeDatabaseAccess() throws Exception {
        mvc.perform(post("/api/ai/index")).andExpect(status().isUnauthorized());
        for(String endpoint:List.of("/api/ai/index","/api/ai/search","/api/ai/answers"))
            mvc.perform(post(endpoint).with(authentication(auth(UserRole.USER))).contentType("application/json")
                    .content("{\"question\":\"정산 정책\"}")).andExpect(status().isForbidden());
    }
}

