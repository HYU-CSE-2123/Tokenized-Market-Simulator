package com.pricetrack.exchange.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.UserBalanceRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-account;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.admin.login-id=Root_Admin",
        "app.admin.password=admin-password-123",
        "app.admin.nickname=Test Admin"
})
@AutoConfigureMockMvc
class AdminAccountIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired UserBalanceRepository balanceRepository;
    @Autowired AdminAccountInitializer initializer;

    @Test
    void createsAdminOnceWithHashedPasswordBalancesAndAdminRole() throws Exception {
        User admin = userRepository.findByLoginId("root_admin").orElseThrow();
        String originalHash = admin.getPasswordHash();

        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(originalHash).isNotEqualTo("admin-password-123");
        assertThat(balanceRepository.findAllByUserIdOrderBySymbol(admin.getId())).hasSize(2);

        initializer.run(new DefaultApplicationArguments(new String[0]));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findByLoginId("root_admin").orElseThrow().getPasswordHash())
                .isEqualTo(originalHash);

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"ROOT_ADMIN","password":"admin-password-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginResponse).get("accessToken").asText();

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId", is("root_admin")))
                .andExpect(jsonPath("$.role", is("ADMIN")));
    }
}
