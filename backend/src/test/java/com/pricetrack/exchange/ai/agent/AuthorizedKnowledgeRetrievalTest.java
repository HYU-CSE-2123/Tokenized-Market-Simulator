package com.pricetrack.exchange.ai.agent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
class AuthorizedKnowledgeRetrievalTest {
    final KnowledgeLoader loader=mock(KnowledgeLoader.class);final KnowledgeStore store=mock(KnowledgeStore.class);
    final EmbeddingProvider embedding=mock(EmbeddingProvider.class);
    final AiProperties p=new AiProperties(true,"jdbc:postgresql://localhost/ai","x","x",".","manifest","x","http://localhost/",
            "text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    final AuthorizedKnowledgeRetrieval retrieval=new AuthorizedKnowledgeRetrieval(p,loader,store,embedding);
    final AuthenticatedUser user=new AuthenticatedUser(1L,"unused",UserRole.USER);
    float[] vector(){var v=new float[1536];v[0]=1;return v;}
    @BeforeEach void setup(){when(loader.load()).thenReturn(new KnowledgeCorpus("index",List.of(),List.of()));when(store.active("index")).thenReturn(true);
        when(embedding.embed(any(),any())).thenReturn(List.of(vector()));}
    @Test void userRoleIsSentToCandidateQueryAndAdminCanaryFailsClosed(){
        when(store.searchForRole(any(),any(),anyInt(),anyDouble(),eq(UserRole.USER),any())).thenReturn(List.of(new KnowledgeHit("secret","admin.md","t","h","1","ADMIN","ADMIN_SECRET_CANARY",.9)));
        assertThatThrownBy(() -> retrieval.search(user,"규칙",Duration.ofSeconds(5))).hasMessage("AI_ROLE_FILTER_INVALID");
        verify(store).searchForRole(eq("index"),any(),eq(40),eq(.25),eq(UserRole.USER),any());verify(store,never()).search(any(),any(),anyInt(),anyDouble());
    }
    @Test void inactiveIndexOrApprovalChangeCannotReachSynthesis(){
        when(store.active("index")).thenReturn(false);
        assertThatThrownBy(() -> retrieval.search(user,"규칙",Duration.ofSeconds(5))).hasMessage("AI_INDEX_NOT_READY");verifyNoInteractions(embedding);
        when(store.active("index")).thenReturn(true);when(store.searchForRole(any(),any(),anyInt(),anyDouble(),any(),any())).thenReturn(List.of());
        when(loader.load()).thenReturn(new KnowledgeCorpus("index",List.of(),List.of()),new KnowledgeCorpus("changed",List.of(),List.of()));
        assertThatThrownBy(() -> retrieval.search(user,"규칙",Duration.ofSeconds(5))).hasMessage("AI_APPROVAL_MISMATCH");
    }
    @Test void noPrincipalOrExpiredBudgetCallsNoEmbedding(){
        assertThatThrownBy(() -> retrieval.search(null,"규칙",Duration.ofSeconds(5))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> retrieval.search(user,"규칙",Duration.ZERO)).hasMessage("AI_QUERY_TIMEOUT");verifyNoInteractions(embedding);
    }
    @Test void secretAndIdRedactionDoesNotChangeExplicitTarget(){
        var request=new AgentRequest("주문 153 status Bearer SECRET_JWT sk-testSECRET mail@test.com password=SECRET",153L,null);
        assertThat(request.modelQuestion()).doesNotContain("153","SECRET_JWT","sk-testSECRET","mail@test.com","password=SECRET");
        assertThat(request.conflictingTarget()).isFalse();
    }
    @Test void scopedSearchNeverWidensAndDefensivelyChecksDocumentDomains(){
        var domains=Set.of("trading");
        var hit=new KnowledgeHit("h","public.md","t","h","1","USER","policy",.9);
        when(loader.load()).thenReturn(new KnowledgeCorpus("index",List.of(new KnowledgeCorpus.Document("public.md","hash",Map.of("domain","security"))),List.of()));
        when(store.searchForScope(any(),any(),anyInt(),anyDouble(),any(),any(),any())).thenReturn(List.of(hit));
        assertThatThrownBy(() -> retrieval.searchScoped(user,"규칙",Duration.ofSeconds(5),domains)).hasMessage("AI_DOMAIN_FILTER_INVALID");
        verify(store).searchForScope(eq("index"),any(),eq(40),eq(.25),eq(UserRole.USER),eq(domains),any());
        verify(store,never()).searchForRole(any(),any(),anyInt(),anyDouble(),any(),any());
        assertThatThrownBy(() -> retrieval.searchScoped(user,"규칙",Duration.ofSeconds(5),Set.of())).hasMessage("AI_DOMAIN_FILTER_INVALID");
    }
}
