package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeLoaderTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    String document(String role,String status,String body) {
        return "---\ntitle: Test\ndomain: trading\ntype: policy\nversion: 1\nstatus: "+status
                +"\nminimum_role: "+role+"\nupdated_at: 2026-09-27\n---\n# Test\n"+body;
    }
    KnowledgeLoader setup(String text,String path,String expected) throws Exception {
        Files.writeString(directory.resolve("policy.md"),text);
        Path manifest=directory.resolve("manifest.json");
        json.writeValue(manifest.toFile(),Map.of("version",1,"documents",List.of(Map.of("path",path,
                "documentVersion","1","sha256",expected))));
        return new KnowledgeLoader(AiFixtures.properties(directory,manifest,"jdbc:postgresql://127.0.0.1:1/ai"),json);
    }
    @Test void approvedCorpusIsDeterministicAndKeepsRoles() throws Exception {
        String doc=document("ADMIN","active","## Rules\nA rule.");
        var loader=setup(doc,"policy.md",KnowledgeLoader.hash(doc));
        var first=loader.load();
        assertThat(first).isEqualTo(loader.load());
        assertThat(first.chunks()).hasSize(1);
        assertThat(first.chunks().getFirst().role()).isEqualTo("ADMIN");
        assertThat(first.chunks().getFirst().heading()).isEqualTo("Test > Rules");
    }
    @Test void hashMismatchFailsClosed() throws Exception {
        var loader=setup(document("USER","active","rule"),"policy.md","incorrect");
        assertThatThrownBy(loader::load).isInstanceOf(AiFailure.class).hasMessage("AI_APPROVAL_MISMATCH");
    }
    @Test void traversalIsRejected() throws Exception {
        var loader=setup("content","../policy.md","incorrect");
        assertThatThrownBy(loader::load).hasMessage("AI_MANIFEST_INVALID");
    }
    @Test void draftAndInvalidRoleAreRejected() throws Exception {
        for(var values:List.of(List.of("USER","draft"),List.of("ROOT","active"))) {
            String doc=document(values.get(0),values.get(1),"rule");
            assertThatThrownBy(setup(doc,"policy.md",KnowledgeLoader.hash(doc))::load).isInstanceOf(AiFailure.class);
        }
    }
    @Test void missingMetadataAndDuplicateKeysAreRejected() throws Exception {
        for(String doc:List.of(document("USER","active","rule").replace("domain: trading\n",""),
                document("USER","active","rule").replace("domain: trading","domain: trading\ndomain: other"))) {
            assertThatThrownBy(setup(doc,"policy.md",KnowledgeLoader.hash(doc))::load).isInstanceOf(AiFailure.class);
        }
    }
    @Test void crlfHashIsPortable() throws Exception {
        String doc=document("USER","active","## One\nrule\n");
        assertThat(setup(doc.replace("\n","\r\n"),"policy.md",KnowledgeLoader.hash(doc)).load().documents()).hasSize(1);
    }
    @Test void deletedDocumentIsNotSilentlyServed() throws Exception {
        String doc=document("USER","active","rule");
        var loader=setup(doc,"policy.md",KnowledgeLoader.hash(doc));
        Files.delete(directory.resolve("policy.md"));
        assertThatThrownBy(loader::load).isInstanceOf(AiFailure.class);
    }
    @Test void realApprovedNineDocumentsLoadAndStayWithinBounds() {
        var loader=new KnowledgeLoader(AiFixtures.properties(Path.of("../docs/ai-knowledge"),
                Path.of("../docs/ai/ingest-manifest.json"),"jdbc:postgresql://127.0.0.1:1/ai"),json);
        var corpus=loader.load();
        assertThat(corpus.documents()).hasSize(9);
        assertThat(corpus.documents().stream().filter(d -> d.metadata().get("minimum_role").equals("ADMIN")))
                .extracting(KnowledgeCorpus.Document::path).containsExactly("transaction-recovery-runbook.md");
        assertThat(corpus.documents().stream().filter(d -> d.path().equals("authorization-policy.md") || d.path().equals("system-overview.md")))
                .allSatisfy(d -> assertThat(d.metadata().get("version")).isEqualTo(d.path().equals("system-overview.md") ? "7" : "6"));
        assertThat(corpus.documents().stream().filter(d -> d.path().equals("market-data-policy.md")))
                .allSatisfy(d -> assertThat(d.metadata().get("version")).isEqualTo("2"));
        assertThat(corpus.chunks().stream().map(KnowledgeCorpus.Chunk::content).reduce("", String::concat))
                .contains("Phase 4", "getPortfolio", "제한된 RAG+Tool Agent", "settlement-debugging", "AI_SKILLS_ENABLED", "AI_AUTO_DIAGNOSIS_ENABLED", "장기 기억은 구현하지 않았다")
                .doesNotContain("개인 데이터 Tool은 아직 구현되지 않았다", "Tool·Agent는 아직 구현하지 않았다");
        assertThat(corpus.chunks()).allSatisfy(c -> {
            assertThat(c.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(800);
            assertThat(c.heading()).isNotBlank();
        });
    }
    @Test void longUnicodeSectionAndTableAreBounded() {
        var meta=Map.of("title","제목","version","1","minimum_role","USER","domain","test","type","policy");
        var chunks=new MarkdownChunker(300,30).split("p.md","hash",meta,
                "# 제목\n## 표\n| 코드 | 뜻 |\n|---|---|\n"+"| 값 | 긴 설명입니다 |\n".repeat(100));
        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(c -> {
            assertThat(c.content()).contains("| 코드 | 뜻 |").doesNotContain("\uFFFD");
            assertThat(c.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(300);
        });
    }
}
