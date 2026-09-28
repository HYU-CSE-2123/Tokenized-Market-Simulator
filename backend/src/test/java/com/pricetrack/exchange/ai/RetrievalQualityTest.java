package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.retrieval.EvidenceSelector;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class RetrievalQualityTest {
    final Map<String,String> meta=Map.of("title","제목","version","1","minimum_role","USER","domain","trading","type","policy");
    KnowledgeHit hit(String id,String path) {return new KnowledgeHit(id,path,"title","heading","1","USER","content",.5);}
    @Test void diversityFillsTopKFromCandidatesBeyondFirstFive() {
        var candidates=List.of(hit("1","a"),hit("2","a"),hit("3","a"),hit("4","a"),hit("5","b"),hit("6","b"),hit("7","c"));
        assertThat(EvidenceSelector.select(candidates,5,2)).extracting(KnowledgeHit::id).containsExactly("1","2","5","6","7");
    }
    @Test void duplicateChunksDoNotConsumeQuotaAndInputOrderIsStable() {
        var first=hit("1","a");
        assertThat(EvidenceSelector.select(List.of(first,first,hit("2","a"),hit("3","b")),5,2))
                .extracting(KnowledgeHit::id).containsExactly("1","2","3");
    }
    @Test void linkOnlyEvidenceIsRemovedButExplanationsAndSourceHashStay() {
        String body="# 제목\n## 근거\n- [Code](../code.java)\n## 정책\n설명입니다.\n";
        var cleaned=new MarkdownChunker(300,30).split("a.md","hash",meta,body);
        var legacy=new MarkdownChunker(300,30,MarkdownChunker.Mode.LEGACY).split("a.md","hash",meta,body);
        assertThat(cleaned).hasSize(1);assertThat(legacy).hasSize(2);
        assertThat(cleaned.getFirst().sourceHash()).isEqualTo("hash");
        assertThat(new MarkdownChunker(300,30).split("a.md","hash",meta,
                "# 제목\n## 근거\n이 문장은 근거의 의미를 설명한다.\n- [Code](../code.java)")).hasSize(1);
    }
    @Test void overlapNeverStartsInsideAnOtherwiseBoundedSentence() {
        String sentence="이 문장은 완전한 설명을 포함합니다. ";
        var chunks=new MarkdownChunker(256,50).split("a.md","hash",meta,"# 제목\n## 정책\n"+sentence.repeat(40));
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> {
            String body=c.content().split("\n",3)[2];
            assertThat(body).startsWith("이 문장은");
            assertThat(c.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
        });
    }
    @Test void oversizeSingleSentenceMakesProgressWithoutBreakingUnicode() {
        var chunks=new MarkdownChunker(256,40).split("a.md","hash",meta,"# 제목\n"+"가😀".repeat(200));
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> {
            assertThat(c.content()).doesNotContain("\uFFFD");
            assertThat(c.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
        });
    }
    @Test void variantIdentitySeparatesAllChunkingExperiments() {
        assertThat(Arrays.stream(MarkdownChunker.Mode.values()).map(m -> new MarkdownChunker(800,60,m).version()).distinct().count()).isEqualTo(4);
    }
    @Test void mrrUsesActualChunkRankAndMissesContributeZero() {
        var hits=List.of(hit("1","a"),hit("2","a"),hit("3","b"));
        assertThat(RetrievalExperimentTest.rank(hits,List.of("b"))).isEqualTo(3);
        assertThat(RetrievalExperimentTest.rank(hits,List.of("missing"))).isZero();
    }
}
