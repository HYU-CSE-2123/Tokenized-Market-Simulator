package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Prevents the summary matrix from silently naming nonexistent coverage or changing the golden set. */
class FinalEvaluationManifestTest {
    @Test void eightFamiliesReferenceExecutableTestsAndFrozenGolden()throws Exception{
        var root=new ObjectMapper().readTree(Path.of("src/test/resources/ai/final-evaluation-manifest.json").toFile());
        var families=root.path("families");assertThat(families).hasSize(8);Set<String> ids=new HashSet<>();
        for(var family:families){assertThat(ids.add(family.path("id").asText())).isTrue();assertThat(family.path("tests")).isNotEmpty();for(var test:family.path("tests"))check(test.asText());}
        for(var test:root.path("secretCanaries"))check(test.asText());
        assertThat(com.pricetrack.exchange.ai.knowledge.KnowledgeLoader.hash(com.pricetrack.exchange.ai.knowledge.KnowledgeLoader.normalize(Files.readString(Path.of("src/test/resources/ai/retrieval-golden.json")))))
            .isEqualTo("22006b55ed6256672195c873399df99fae5741fd47a9ba4319484fb97dd881ac");
    }
    void check(String reference)throws Exception{
        String[] parts=reference.split("\\.",2);Path file;
        try(var paths=Files.walk(Path.of("src/test/java/com/pricetrack/exchange/ai"))){file=paths.filter(p->p.getFileName().toString().equals(parts[0]+".java")).findFirst().orElseThrow();}
        if(parts.length>1)assertThat(Files.readString(file)).contains("void "+parts[1]+"(");
    }
}
