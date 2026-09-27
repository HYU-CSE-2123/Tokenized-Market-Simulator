package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.user.*;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.order.OrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties={"app.ai.enabled=true","app.ai.knowledge-root=../docs/ai-knowledge",
        "app.ai.manifest=../docs/ai/ingest-manifest.json","app.blockchain.enabled=false"})
class AiProviderIsolationIntegrationTest {
    @Autowired RagService rag;
    @Autowired UserRepository users;
    @Autowired WalletService wallets;
    @Autowired OrderService orders;
    @MockBean PgKnowledgeStore store;
    @MockBean OpenAiProvider provider;
    @Test void aiProviderFailureDoesNotAffectCommittedTrade() {
        when(store.active(any())).thenReturn(true);
        when(provider.embed(any())).thenThrow(new AiFailure("AI_PROVIDER_UNAVAILABLE"));
        assertThatThrownBy(() -> rag.search(AiFixtures.ADMIN,"견적 정책")).hasMessage("AI_PROVIDER_UNAVAILABLE");
        User user=new User();user.setLoginId("ai-provider-"+System.nanoTime());user.setNickname("Provider isolation");
        user=users.saveAndFlush(user);wallets.initializeBalances(user.getId());wallets.faucet(user.getId());
        assertThat(orders.buy(user.getId(),"mSEC",new BigDecimal("10000"),null).getStatus().name()).isEqualTo("FILLED");
    }
}

