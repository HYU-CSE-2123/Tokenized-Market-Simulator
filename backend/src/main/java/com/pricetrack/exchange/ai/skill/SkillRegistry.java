package com.pricetrack.exchange.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.*;

/** Deployment-only definitions. Both approved hash and code ceilings must match; no executable YAML. */
public final class SkillRegistry {
    public record Definition(String id,String name,String purpose,int version,Set<UserRole> roles,
            AgentModelProvider.Subject target,Set<String> tools,Set<String> domains,List<String> steps,String hash) {}
    private final Map<String,Definition> definitions;
    public SkillRegistry(ObjectMapper json) {this(json,path -> SkillRegistry.class.getClassLoader().getResourceAsStream(path));}
    @FunctionalInterface public interface Resources {InputStream open(String path) throws Exception;}
    public SkillRegistry(ObjectMapper json,Resources resources) {
        Map<String,Definition> loaded=new LinkedHashMap<>();
        try {
            var strict=json.copy().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var manifest=strict.readTree(read(resources,"ai/skills/manifest.json"));
            if(!manifest.isObject() || manifest.size()!=3)throw new IllegalArgumentException();
            for(var ceiling:ceilings())try{
                String content=read(resources,"ai/skills/"+ceiling.id()+".md").replace("\r\n","\n");
                String hash=KnowledgeLoader.hash(content);
                var entry=manifest.path(ceiling.id());
                if(!entry.isObject() || entry.size()!=2 || !entry.path("version").isIntegralNumber()
                        || entry.path("version").asInt()!=1 || !entry.path("hash").isTextual() || !hash.equals(entry.path("hash").asText()))throw new IllegalArgumentException();
                loaded.put(ceiling.id(),parse(content,ceiling,hash));
            }catch(Exception invalid){/* isolate this definition, never prevent trading startup */}
        }catch(Exception invalid){/* absent/corrupt manifest disables all Skills, not ordinary Agent */}
        definitions=Map.copyOf(loaded);
    }
    private static String read(Resources resources,String path)throws Exception {
        try(var in=resources.open(path)){
            if(in==null)throw new IllegalArgumentException();byte[] bytes=in.readNBytes(8193);
            if(bytes.length>8192)throw new IllegalArgumentException();return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    static Definition parse(String text,Definition ceiling,String hash) {
        if(!text.startsWith("---\n"))throw new IllegalArgumentException();int end=text.indexOf("\n---\n",4);
        if(end<0 || text.substring(end+5).isBlank())throw new IllegalArgumentException();
        Map<String,String> fields=new LinkedHashMap<>();
        for(String line:text.substring(4,end).split("\n")){
            int colon=line.indexOf(':');if(colon<1 || !line.matches("[A-Za-z]+: [A-Za-z0-9가-힣 _.,\\[\\]-]+"))throw new IllegalArgumentException();
            if(fields.putIfAbsent(line.substring(0,colon),line.substring(colon+2))!=null)throw new IllegalArgumentException();
        }
        if(!fields.keySet().equals(Set.of("id","name","purpose","version","allowedRoles","preconditions","allowedTools",
                "retrievalDomains","steps","stopConditions","forbiddenActions","outputSchema")))throw new IllegalArgumentException();
        if(fields.get("name").isBlank() || fields.get("purpose").isBlank())throw new IllegalArgumentException();
        if(!fields.get("id").equals(ceiling.id()) || !fields.get("version").equals("1")
                || !list(fields.get("allowedRoles")).equals(ceiling.roles().stream().map(Enum::name).sorted().toList())
                || !new HashSet<>(list(fields.get("allowedTools"))).equals(ceiling.tools())
                || !new HashSet<>(list(fields.get("retrievalDomains"))).equals(ceiling.domains())
                || !list(fields.get("steps")).equals(ceiling.steps())
                || !list(fields.get("preconditions")).equals(List.of(ceiling.target().name()+"_TARGET_REQUIRED","OWNED_OR_ADMIN_AUTHORIZED"))
                || !list(fields.get("stopConditions")).equals(List.of("AUTHORIZATION_DENIED","INVALID_LINK","DEADLINE_EXCEEDED"))
                || !list(fields.get("forbiddenActions")).equals(List.of("WRITE_DB","SIGN","BROADCAST","FORCE_SETTLEMENT","CHANGE_BALANCE"))
                || !fields.get("outputSchema").equals("diagnostic-v1"))throw new IllegalArgumentException();
        return new Definition(ceiling.id(),fields.get("name"),fields.get("purpose"),1,ceiling.roles(),ceiling.target(),
                ceiling.tools(),ceiling.domains(),ceiling.steps(),hash);
    }
    private static List<String> list(String value){
        if(!value.matches("\\[[A-Za-z0-9_-]+(?:, [A-Za-z0-9_-]+)*\\]"))throw new IllegalArgumentException();
        var values=List.of(value.substring(1,value.length()-1).split(", "));
        if(new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException();return values;
    }
    public static List<Definition> ceilings(){return List.of(
        new Definition("settlement-debugging","","",1,Set.of(UserRole.ADMIN),AgentModelProvider.Subject.ORDER,
            Set.of("getOrder","getQuote","getBlockchainTransaction","getReceiptSummary"),Set.of("trading","settlement","operations","support"),
            List.of("LOAD_ORDER","CHECK_ORDER","LOAD_LINKED_QUOTE","LOAD_TRANSACTION","LOAD_RECEIPT","CHECK_EVIDENCE","RETRIEVE_POLICY","SUMMARIZE"),""),
        new Definition("signed-quote-diagnosis","","",1,Set.of(UserRole.ADMIN,UserRole.USER),AgentModelProvider.Subject.QUOTE,
            Set.of("getQuote","getOrder","getBlockchainTransaction","getReceiptSummary"),Set.of("trading","settlement","support"),
            List.of("LOAD_QUOTE","LOAD_LINKED_ORDER","LOAD_TRANSACTION","LOAD_RECEIPT","CHECK_EVIDENCE","RETRIEVE_POLICY","SUMMARIZE"),""),
        new Definition("market-availability-diagnosis","","",1,Set.of(UserRole.ADMIN,UserRole.USER),AgentModelProvider.Subject.NONE,
            Set.of("getCurrentReferencePrice"),Set.of("market","trading"),
            List.of("LOAD_REFERENCE","CHECK_EVIDENCE","RETRIEVE_POLICY","SUMMARIZE"),""));}
    public Definition require(String id,UserRole role,AgentModelProvider.Subject target){
        var ceiling=ceilings().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() -> new AgentFailure("UNKNOWN_SKILL",400));
        if(!ceiling.roles().contains(role))throw new AgentFailure("SKILL_FORBIDDEN",403);
        if(target!=ceiling.target())throw new AgentFailure("SKILL_TARGET_INVALID",400);
        var definition=definitions.get(id);if(definition==null)throw new AgentFailure("SKILL_DEFINITION_UNAVAILABLE",503);return definition;
    }
    public List<String> eligible(UserRole role,AgentModelProvider.Subject target){return definitions.values().stream()
            .filter(d -> d.roles().contains(role) && d.target()==target).map(Definition::id).sorted().toList();}
    public void verify(Definition definition){if(!definition.equals(definitions.get(definition.id())))throw new AgentFailure("SKILL_DEFINITION_UNAVAILABLE",503);}
}
