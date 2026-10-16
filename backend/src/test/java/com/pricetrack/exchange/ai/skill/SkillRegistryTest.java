package com.pricetrack.exchange.ai.skill;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject;
import com.pricetrack.exchange.user.UserRole;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {
    final ObjectMapper json=new ObjectMapper();
    String resource(String name)throws Exception{
        // Canonical test input before explicitly exercising both newline formats; never alter build resources.
        try(var in=getClass().getClassLoader().getResourceAsStream("ai/skills/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");}
    }
    @Test void parserRejectsDuplicateUnknownYamlAliasTagsAndExpandedCapabilities()throws Exception{
        String good=resource("settlement-debugging.md");var ceiling=SkillRegistry.ceilings().getFirst();
        for(String bad:List.of(good.replace("version: 1","version: 1\nversion: 1"),good.replace("version: 1","version: 2"),
                good.replace("version: 1","version: 1\nunknown: value"),good.replace("allowedRoles: [ADMIN]","allowedRoles: [ADMIN, USER]"),
                good.replace("getOrder, getQuote","getOrder, broadcast"),good.replace("operations, support","operations, security"),
                good.replace("LOAD_ORDER, CHECK_ORDER","CHECK_ORDER, LOAD_ORDER"),good.replace("name: 온체인 정산 조사","name: !exec danger"),
                good.replace("name: 온체인 정산 조사","name: &anchor alias"),good.replace("name: 온체인 정산 조사","name: ../outside")))
            assertThatThrownBy(() -> SkillRegistry.parse(bad,ceiling,KnowledgeLoader.hash(bad))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hashMismatchDisablesOnlyAffectedSkillAndCrLfIsPortable()throws Exception{
      for(String newline:List.of("\n","\r\n")) {
        var registry=new SkillRegistry(json,path -> {
            String text=resource(path.substring(path.lastIndexOf('/')+1));
            if(path.endsWith("settlement-debugging.md"))text+="tampered";else text=text.replace("\n",newline);
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        assertThatThrownBy(() -> registry.require("settlement-debugging",UserRole.ADMIN,Subject.ORDER)).hasMessage("SKILL_DEFINITION_UNAVAILABLE");
        assertThat(registry.require("signed-quote-diagnosis",UserRole.USER,Subject.QUOTE).version()).isEqualTo(1);
        assertThat(registry.require("market-availability-diagnosis",UserRole.USER,Subject.NONE).version()).isEqualTo(1);
      }
    }
    @Test void oversizedMissingAndDuplicateManifestDisableSafely()throws Exception{
        for(String invalid:List.of("x".repeat(8193),"{} {}","{\"x\":1,\"x\":2}")){
            var registry=new SkillRegistry(json,path -> new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)));
            assertThat(registry.eligible(UserRole.ADMIN,Subject.ORDER)).isEmpty();
        }
    }
    @Test void manifestVersionMustBeIntegralAndDefinitionLabelsCannotBeBlank()throws Exception{
        for(String version:List.of("\"1\"","1.2")){
            var registry=new SkillRegistry(json,path -> {
                String value=resource(path.substring(path.lastIndexOf('/')+1));
                if(path.endsWith("manifest.json"))value=value.replace("\"version\": 1","\"version\": "+version);
                return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
            });
            assertThat(registry.eligible(UserRole.ADMIN,Subject.ORDER)).isEmpty();
        }
        String blank=resource("settlement-debugging.md").replace("name: 온체인 정산 조사","name:  ");
        assertThatThrownBy(() -> SkillRegistry.parse(blank,SkillRegistry.ceilings().getFirst(),KnowledgeLoader.hash(blank))).isInstanceOf(IllegalArgumentException.class);
    }
}
