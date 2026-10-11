package com.pricetrack.exchange.wallet.faucet;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.pricetrack.exchange.wallet.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/** H2 contract/rollback checks only, NOT evidence of PostgreSQL concurrency. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:faucet-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.price.initial-delay-ms=2147483647"})
@AutoConfigureMockMvc @DirtiesContext
class FaucetIntegrationTest {
    @Autowired FaucetService service;@Autowired WalletService wallet;@Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;@Autowired PlatformTransactionManager tx;
    @BeforeEach void empty() {for(String t:List.of("faucet_claims","faucet_grants","faucet_account_usage","user_balances"))jdbc.update("DELETE FROM "+t);jdbc.update("UPDATE faucet_budget_usage SET spent=0,grant_count=0,active_version='kst-v1',initialized=TRUE");wallet.initializeBalances(100L);wallet.initializeBalances(200L);}
    static UsernamePasswordAuthenticationToken actor(long uid,UserRole role){return new UsernamePasswordAuthenticationToken(new AuthenticatedUser(uid,"fixture",role),"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role.name())));}
    @Test void creditReplayLimitsAndOwnership() throws Exception {
        var a=wallet.faucet(100L,"same-key-12345678");var b=wallet.faucet(100L,"same-key-12345678");
        assertThat(a.replayed()).isFalse();assertThat(b.replayed()).isTrue();assertThat(b.grantId()).isEqualTo(a.grantId());assertThat(service.status(100L).remainingCount()).isEqualTo(4);
        assertThatThrownBy(()->wallet.faucet(100L,"other-key-12345678")).hasMessage("FAUCET_DAILY_LIMIT");
        mvc.perform(get("/api/wallet/faucet/claims").header("Idempotency-Key","same-key-12345678").with(authentication(actor(200,UserRole.USER)))).andExpect(status().isNotFound());
        mvc.perform(post("/api/wallet/faucet").header("Idempotency-Key","same-key-12345678").contentType("application/json").content("{\"amount\":1}").with(authentication(actor(100,UserRole.USER)))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isEqualTo(1);
    }
    @Test void missingInvalidAndChangedBodyCannotPay() throws Exception {
        mvc.perform(post("/api/wallet/faucet").with(authentication(actor(100,UserRole.USER)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/wallet/faucet").header("Idempotency-Key","bad").with(authentication(actor(100,UserRole.USER)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/wallet/faucet").header("Idempotency-Key","valid-key-12345678").contentType("application/json").content("{\"userId\":200}").with(authentication(actor(100,UserRole.USER)))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isZero();
    }
    @Test void rollbackAllEvidenceAndLockedAmount() {
        jdbc.update("UPDATE user_balances SET locked_amount=10 WHERE user_id=100 AND symbol='mKRW'");
        new TransactionTemplate(tx).executeWithoutResult(s->{wallet.faucet(100L,"rollback-key-12345");s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_grants",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM faucet_claims",Long.class)).isZero();assertThat(service.admin().spent()).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_amount FROM user_balances WHERE user_id=100 AND symbol='mKRW'",java.math.BigDecimal.class)).isEqualByComparingTo("10");
        wallet.faucet(100L,"rollback-key-12345");assertThat(service.admin().grantCount()).isEqualTo(1);
    }
    @Test void configurationAndAccountingFailClosedButReplaySurvives() {
        wallet.faucet(100L,"old-success-123456");jdbc.update("UPDATE faucet_budget_usage SET active_version='different'");
        assertThat(wallet.faucet(100L,"old-success-123456").replayed()).isTrue();assertThatThrownBy(()->wallet.faucet(200L,"new-request-123456")).hasMessage("FAUCET_POLICY_UNAVAILABLE");
        jdbc.update("UPDATE faucet_budget_usage SET active_version='kst-v1',spent=0");assertThat(service.admin().journalConsistent()).isFalse();assertThatThrownBy(()->wallet.faucet(200L,"new-request-123456")).hasMessage("FAUCET_ACCOUNTING_INCONSISTENT");
    }
    @Test void adminReadOnlyAndUserCannotObserveOtherLedger() throws Exception {
        mvc.perform(get("/api/admin/faucet")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/faucet").with(authentication(actor(100,UserRole.USER)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/faucet").with(authentication(actor(100,UserRole.ADMIN)))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/faucet").with(authentication(actor(100,UserRole.ADMIN)))).andExpect(status().isMethodNotAllowed());
    }
}
