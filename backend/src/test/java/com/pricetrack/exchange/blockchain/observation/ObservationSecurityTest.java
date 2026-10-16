package com.pricetrack.exchange.blockchain.observation;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc class ObservationSecurityTest {
    @Autowired MockMvc mvc;@Autowired UserRepository users;@Autowired JwtTokenProvider jwt;
    String token(UserRole role){var u=new User();u.setLoginId("obs-"+java.util.UUID.randomUUID());u.setPasswordHash("unused");u.setNickname("test");u.setRole(role);u=users.saveAndFlush(u);return jwt.createToken(u.getId(),u.getLoginId());}
    @Test void anonymousAndUserAreRejectedOnListAndDetail() throws Exception{
        var user=token(UserRole.USER);
        for(String path:java.util.List.of("/api/admin/transaction-observations","/api/admin/transaction-observations/1")){
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer "+user)).andExpect(status().isForbidden());}
    }
    @Test void adminReadsBoundedListAndInvalidRequestsDoNotLeak() throws Exception{
        var token=token(UserRole.ADMIN);
        mvc.perform(get("/api/admin/transaction-observations").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.warningSeconds").value(300));
        for(String query:java.util.List.of("?limit=0","?limit=51","?status=unknown","?attention=FAILED","?cursor=bad"))
            mvc.perform(get("/api/admin/transaction-observations"+query).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/transaction-observations/9223372036854775807").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
}
