package com.pricetrack.exchange.reserve;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties={"app.reserve.enabled=true","app.reserve.execution-id=h2-isolated",
    "spring.datasource.url=jdbc:h2:mem:reserve-unit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.price.initial-delay-ms=2147483647","app.blockchain.enabled=false","app.blockchain.price-report.enabled=false"})
@AutoConfigureMockMvc @DirtiesContext
class ReserveIntegrationTest {
    @Autowired ReserveService service; @Autowired ReserveStore store; @Autowired MockMvc mvc;
    @Autowired WalletService wallet; @Autowired FaucetGrantRepository grants; @Autowired UserBalanceRepository balances;
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager transactions;
    @MockBean ReserveChainReader reader;
    @BeforeEach void setup() {
        for(String table:List.of("reserve_runs","reserve_baselines","faucet_grants","trades","blockchain_transactions","orders","user_balances"))
            jdbc.update("DELETE FROM "+table);
        when(reader.head()).thenReturn(ReserveServiceTest.BLOCK); when(reader.canonical(any())).thenReturn(true);
        when(reader.read(any())).thenReturn(ReserveServiceTest.INITIAL);
    }
    static UsernamePasswordAuthenticationToken actor(UserRole role) {
        return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(1L,"actor",role),"unused",
            List.of(new SimpleGrantedAuthority("ROLE_"+role.name())));
    }
    @Test void adminOnlyAcrossCreateReadRunAndDetail() throws Exception {
        for(String url:List.of("/api/admin/reserve-reconciliations","/api/admin/reserve-reconciliations/baseline","/api/admin/reserve-reconciliations/abc")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(authentication(actor(UserRole.USER)))).andExpect(status().isForbidden());
            mvc.perform(post(url).with(authentication(actor(UserRole.USER)))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/reserve-reconciliations/baseline").with(authentication(actor(UserRole.ADMIN))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.executionId").value("h2-isolated"));
        mvc.perform(post("/api/admin/reserve-reconciliations/baseline").with(authentication(actor(UserRole.ADMIN)))).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/reserve-reconciliations").with(authentication(actor(UserRole.ADMIN))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCH"));
        mvc.perform(get("/api/admin/reserve-reconciliations").with(authentication(actor(UserRole.ADMIN))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("MATCH"));
    }
    @Test void faucetEvidenceCommitsAndRollsBackWithBalance() {
        wallet.initializeBalances(42L); wallet.faucet(42L);
        assertThat(grants.count()).isEqualTo(1);
        var tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(s->{ wallet.faucet(42L); s.setRollbackOnly(); });
        assertThat(grants.count()).isEqualTo(1);
        assertThat(balances.findByUserIdAndSymbol(42L,"mKRW").orElseThrow().getAmount()).isEqualByComparingTo("1000000");
    }
    @Test void snapshotDeclaresRepeatableReadReadOnlyAndCapturesActualJdbcData() throws Exception {
        var annotation=ReserveStore.class.getMethod("cut").getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(annotation.readOnly()).isTrue();
        assertThat(annotation.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        assertThat(annotation.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        service.createBaseline(1); wallet.initializeBalances(42L); wallet.faucet(42L);
        var before=store.cut(); assertThat(before.grants()).hasSize(1);
        var result=service.run(1); assertThat(result.status()).isEqualTo("MATCH");
        assertThat(store.cut().balances()).isEqualTo(before.balances());
        assertThat(store.cut().orders()).isEmpty(); assertThat(store.history()).hasSize(1);
        assertThat(store.result(result.id())).contains(result);
    }
    @Test void legacyOrderDefaultsToUnknown() {
        jdbc.update("INSERT INTO orders(user_id,symbol,side,order_type,input_amount,status,created_at,updated_at,execution_mode) VALUES(42,'mSEC','BUY','MARKET',1,'FAILED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'UNKNOWN')");
        assertThat(store.cut().orders().getFirst().mode()).isEqualTo("UNKNOWN");
    }
}
