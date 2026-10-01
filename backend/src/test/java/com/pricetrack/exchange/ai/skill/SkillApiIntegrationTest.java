package com.pricetrack.exchange.ai.skill;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.tool.ToolFixtures;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.*;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.WalletService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"app.ai.agent.enabled=true","app.ai.skills.enabled=true","app.ai.enabled=true","app.ai.tools.enabled=true",
    "app.ai.jdbc-url=jdbc:postgresql://127.0.0.1:1/exchange_ai_unavailable","app.ai.db-password=test","app.blockchain.enabled=false",
    "app.blockchain.price-report.enabled=false","app.admin.password=","spring.datasource.url=jdbc:h2:mem:skill_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SkillApiIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;@Autowired OrderRepository orders;@Autowired PriceQuoteRepository quotes;
    @Autowired WalletService wallets;@Autowired OrderService trading;
    @MockBean AgentModelProvider model;@MockBean AuthorizedKnowledgeRetrieval retrieval;
    User owner,other,admin;com.pricetrack.exchange.order.Order order;String quoteId;
    @BeforeEach void setup(){
        owner=user(UserRole.USER);other=user(UserRole.USER);admin=user(UserRole.ADMIN);
        order=orders.saveAndFlush(ToolFixtures.order(owner.getId()));var quote=ToolFixtures.quote(owner.getId(),order.getId());
        quoteId="0x"+UUID.randomUUID().toString().replace("-","").repeat(2);quote.setQuoteId(quoteId);quotes.saveAndFlush(quote);
        reset(model,retrieval);when(retrieval.searchScoped(any(),any(),any(),any())).thenThrow(new RuntimeException("SECRET_AI_DB"));
    }
    User user(UserRole role){var user=new User();user.setLoginId("skill-"+UUID.randomUUID());user.setNickname("test");user.setRole(role);user.setPasswordHash("SECRET_HASH");return users.saveAndFlush(user);}
    JsonNode call(User user,Map<String,Object> body,int expected)throws Exception{
        return json.readTree(mvc.perform(post("/api/ai/agent/answers").header("Authorization","Bearer "+jwt.createToken(user.getId(),user.getLoginId()))
            .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    @Test void jwtAndSkillRolesAreServerEnforcedAndForeignEqualsMissing()throws Exception{
        mvc.perform(post("/api/ai/agent/answers").contentType("application/json").content("{\"question\":\"진단\",\"skillId\":\"market-availability-diagnosis\"}")).andExpect(status().isUnauthorized());
        call(owner,Map.of("question","진단","skillId","settlement-debugging","target",Map.of("orderId",order.getId())),403);
        var foreign=call(other,Map.of("question","진단","skillId","signed-quote-diagnosis","target",Map.of("quoteId",quoteId)),404);
        var missing=call(other,Map.of("question","진단","skillId","signed-quote-diagnosis","target",Map.of("quoteId","0x"+"ff".repeat(32))),404);
        assertThat(foreign.path("error")).isEqualTo(missing.path("error"));assertThat(foreign.hasNonNull("skill")).isFalse();verifyNoInteractions(model,retrieval);
    }
    @Test void adminAndUserReadOnlySkillsWorkEvenWithAiDbFailureAndMockTradingSurvives()throws Exception{
        var result=call(admin,Map.of("question","진단","skillId","settlement-debugging","target",Map.of("orderId",order.getId())),200);
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");assertThat(result.path("skill").path("id").asText()).isEqualTo("settlement-debugging");
        assertThat(result.path("skill").path("trace").toString()).doesNotContain(quoteId,"orderId","SECRET");
        var own=call(owner,Map.of("question","진단","skillId","signed-quote-diagnosis","target",Map.of("quoteId",quoteId)),200);
        assertThat(own.path("skill").path("id").asText()).isEqualTo("signed-quote-diagnosis");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_ONCHAIN);
        assertThat(quotes.findById(quoteId).orElseThrow().getStatus()).isEqualTo(PriceQuoteStatus.ISSUED);
        verifyNoInteractions(model);wallets.initializeBalances(other.getId());wallets.faucet(other.getId());
        assertThat(trading.buy(other.getId(),"mSEC",new BigDecimal("1000"),null).getStatus()).isEqualTo(OrderStatus.FILLED);
    }
    @Test void damagedLinkAndUntrustedProcedureParametersCannotExpandLookup()throws Exception{
        var quote=quotes.findById(quoteId).orElseThrow();var foreign=orders.saveAndFlush(ToolFixtures.order(other.getId()));quote.setOrderId(foreign.getId());quotes.saveAndFlush(quote);
        var result=call(owner,Map.of("question","진단","skillId","signed-quote-diagnosis","target",Map.of("quoteId",quoteId)),200);
        assertThat(result.path("skill").path("diagnosis").path("classification").asText()).isEqualTo("INCONSISTENCY_OBSERVED");
        assertThat(result.path("metrics").path("toolCalls").asInt()).isEqualTo(1);
        call(owner,Map.of("question","진단","skillId","market-availability-diagnosis","steps",List.of("WRITE_DB")),400);
    }
}
