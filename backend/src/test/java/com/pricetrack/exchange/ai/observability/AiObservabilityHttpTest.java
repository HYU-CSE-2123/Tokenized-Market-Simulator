package com.pricetrack.exchange.ai.observability;

import static org.assertj.core.api.Assertions.assertThat;
import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

/** Real embedded server/error-dispatch regression, not just MockMvc's filter dispatch. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "app.ai.enabled=false", "app.blockchain.enabled=false", "app.blockchain.price-report.enabled=false",
    "app.admin.password=", "spring.datasource.url=jdbc:h2:mem:obs_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class AiObservabilityHttpTest {
    @Autowired TestRestTemplate rest;
    @Autowired JwtTokenProvider jwt;
    @Autowired UserRepository users;
    @Test void realHttpPreservesAnonymous401User403AndAdmin200() {
        assertThat(rest.getForEntity("/api/ai/observability", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (UserRole role : new UserRole[]{UserRole.USER, UserRole.ADMIN}) {
            User user = new User(); user.setLoginId("http" + UUID.randomUUID()); user.setNickname("test"); user.setRole(role);
            users.saveAndFlush(user);
            HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(jwt.createToken(user.getId(), user.getLoginId()));
            var response = rest.exchange("/api/ai/observability", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(role == UserRole.ADMIN ? HttpStatus.OK : HttpStatus.FORBIDDEN);
            if (role == UserRole.USER) assertThat(response.getBody()).contains("ACCESS_DENIED").doesNotContain("/error");
        }
    }
}
