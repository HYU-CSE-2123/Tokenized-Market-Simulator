package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;

class RagServiceTest {
    final KnowledgeLoader loader=mock(KnowledgeLoader.class);
    final KnowledgeStore store=mock(KnowledgeStore.class);
    final EmbeddingProvider embedding=mock(EmbeddingProvider.class);
    final ChatModelProvider chat=mock(ChatModelProvider.class);
    final AiProperties p=AiFixtures.properties(Path.of("."),Path.of("manifest"),"jdbc:postgresql://127.0.0.1:1/ai");
    final RagService service=new RagService(p,loader,store,embedding,chat);
    final KnowledgeCorpus.Chunk chunk=new KnowledgeCorpus.Chunk("id","a.md","A","Section","1","USER","d","policy","hash","content");
    final KnowledgeCorpus corpus=new KnowledgeCorpus("v1",List.of(),List.of(chunk));
    final KnowledgeHit hit=new KnowledgeHit("id","a.md","A","Section","1","USER","content",.9);
    @BeforeEach void setup() {
        when(loader.load()).thenReturn(corpus); when(store.active("v1")).thenReturn(true);
        when(embedding.embed(any())).thenReturn(List.of(AiFixtures.vector(0)));
        when(store.search(eq("v1"),any(),eq(40),eq(.3))).thenReturn(List.of(hit));
    }
    @Test void userCannotInvokeInternalService() {
        assertThatThrownBy(() -> service.search(new AuthenticatedUser(2L,"user",UserRole.USER),"question"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(loader,store,embedding,chat);
    }
    @Test void unchangedIndexDoesNotEmbedAgain() {
        assertThat(service.ingest(AiFixtures.ADMIN).unchanged()).isTrue();
        verifyNoInteractions(embedding,chat); verify(store,never()).publish(any(),any(),any());
    }
    @Test void providerFailureNeverPublishesPartialIndex() {
        when(store.active("v1")).thenReturn(false);
        when(store.cached(any())).thenReturn(Map.of());
        when(embedding.embed(any())).thenThrow(new AiFailure("AI_PROVIDER_UNAVAILABLE"));
        assertThatThrownBy(() -> service.ingest(AiFixtures.ADMIN)).hasMessage("AI_PROVIDER_UNAVAILABLE");
        verify(store,never()).publish(any(),any(),any());
    }
    @Test void changedCorpusDuringIndexingIsRejected() {
        when(store.active("v1")).thenReturn(false); when(store.cached(any())).thenReturn(Map.of());
        when(loader.load()).thenReturn(corpus,new KnowledgeCorpus("v2",List.of(),List.of()));
        assertThatThrownBy(() -> service.ingest(AiFixtures.ADMIN)).hasMessage("AI_APPROVAL_MISMATCH");
        verify(store,never()).publish(any(),any(),any());
    }
    @Test void cacheReusesEmbeddingAndPublishes() {
        when(store.active("v1")).thenReturn(false);
        when(store.cached(any())).thenReturn(Map.of("id",AiFixtures.vector(0)));
        assertThat(service.ingest(AiFixtures.ADMIN).unchanged()).isFalse();
        verifyNoInteractions(embedding); verify(store).publish(eq(corpus),any(),eq(p.embeddingModel()));
    }
    @Test void answersContainOnlyValidatedCitations() {
        when(chat.answer(any(),any())).thenReturn(new ChatModelProvider.Generated("ANSWERED","근거 답변",List.of("id")));
        assertThat(service.answer(AiFixtures.ADMIN,"왜 대기 상태가 필요한가?").sources()).containsExactly(hit);
    }
    @Test void inventedCitationIsRejected() {
        when(chat.answer(any(),any())).thenReturn(new ChatModelProvider.Generated("ANSWERED","답변",List.of("invented")));
        assertThatThrownBy(() -> service.answer(AiFixtures.ADMIN,"정산 정책?")).hasMessage("AI_CITATION_INVALID");
    }
    @Test void noEvidenceDoesNotCallLlm() {
        when(store.search(any(),any(),anyInt(),anyDouble())).thenReturn(List.of());
        assertThat(service.answer(AiFixtures.ADMIN,"없는 정책?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(chat);
    }
    @Test void liveQuestionDoesNotCallAnyExternalDependency() {
        assertThat(service.answer(AiFixtures.ADMIN,"내 주문 지금 체결됐어?").status()).isEqualTo("LIVE_DATA_REQUIRED");
        verifyNoInteractions(loader,store,embedding,chat);
    }
    @Test void staticQuestionsWithCurrentOrPersonalWordingStillUseKnowledge() {
        when(chat.answer(any(),any())).thenReturn(new ChatModelProvider.Generated("ANSWERED","정책 답변",List.of("id")));
        for(String question:List.of("현재가와 견적 가격은 왜 다를 수 있어?",
                "현재 가격의 stale 기준은 무엇이야?","내 주문을 다른 사용자가 조회할 수 있어?"))
            assertThat(service.answer(AiFixtures.ADMIN,question).status()).isEqualTo("ANSWERED");
        verify(chat,times(3)).answer(any(),any());
    }
    @Test void unrecognizedLivePhrasingCanBeClassifiedByModelWithoutReturningInventedValues() {
        when(chat.answer(any(),any())).thenReturn(new ChatModelProvider.Generated("LIVE_DATA_REQUIRED","가짜 잔고 100원",List.of()));
        var answer=service.answer(AiFixtures.ADMIN,"계정에 남아 있는 자산을 알려줄래");
        assertThat(answer.status()).isEqualTo("LIVE_DATA_REQUIRED");
        assertThat(answer.answer()).doesNotContain("100원");
    }
    @Test void staleIndexCannotBeQueried() {
        when(store.active("v1")).thenReturn(false);
        assertThatThrownBy(() -> service.search(AiFixtures.ADMIN,"정책")).hasMessage("AI_INDEX_NOT_READY");
        verifyNoInteractions(embedding,chat);
    }
    @Test void diversityUsesWideSearchBeforeFinalSelection() {
        var candidates=new ArrayList<KnowledgeHit>();
        for(int i=0;i<7;i++)candidates.add(new KnowledgeHit("c"+i,i<4?"a.md":i<6?"b.md":"c.md","Title","Section","1","USER","content",.9-i*.01));
        when(store.search(eq("v1"),any(),eq(40),eq(.3))).thenReturn(candidates);
        assertThat(service.search(AiFixtures.ADMIN,"정책")).extracting(KnowledgeHit::id).containsExactly("c0","c1","c4","c5","c6");
    }
    @Test void approvalChangedDuringSearchFailsClosedBeforeAnswer() {
        when(loader.load()).thenReturn(corpus,new KnowledgeCorpus("v2",List.of(),List.of()));
        assertThatThrownBy(() -> service.answer(AiFixtures.ADMIN,"정책")).hasMessage("AI_APPROVAL_MISMATCH");
        verifyNoInteractions(chat);
    }
    @Test void modelUncertaintyUsesFixedSafeResponse() {
        when(chat.answer(any(),any())).thenReturn(new ChatModelProvider.Generated("INSUFFICIENT_EVIDENCE","invented raw",List.of()));
        assertThat(service.answer(AiFixtures.ADMIN,"정책?").answer()).doesNotContain("invented raw");
    }
}
