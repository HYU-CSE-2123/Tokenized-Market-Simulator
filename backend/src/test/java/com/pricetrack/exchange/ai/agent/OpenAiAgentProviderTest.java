package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.AiProperties;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

class OpenAiAgentProviderTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server; String body,requested;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e -> {
            requested=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);
            try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
    }
    @AfterEach void close(){server.stop(0);}
    OpenAiAgentProvider provider(){
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/test","test","test",".",".","fake-test-key",
            "http://127.0.0.1:"+server.getAddress().getPort()+"/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        return new OpenAiAgentProvider(new OpenAiProvider(p,json),json);
    }
    void output(Object data) throws Exception {
        body=json.writeValueAsString(Map.of("status","completed","usage",Map.of("input_tokens",21,"output_tokens",9),
            "output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",json.writeValueAsString(data)))))));
    }
    @Test void closedPlanDisablesStorageAndArbitraryToolsAndRecordsActualUsage() throws Exception {
        output(Map.of("route","MIXED","subject","ORDER"));
        var result=provider().plan("왜 대기?",Subject.ORDER,Duration.ofSeconds(5));
        assertThat(result.route()).isEqualTo(Route.MIXED);assertThat(result.usage().inputTokens()).isEqualTo(21);
        var request=json.readTree(requested);
        assertThat(request.path("store").asBoolean(true)).isFalse();assertThat(request.has("tools")).isFalse();
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.path("text").path("format").path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(400);
    }
    @Test void sixFieldSynthesisParsesEvidenceAndRejectsUnexpectedFields() throws Exception {
        Map<String,Object> generated=new LinkedHashMap<>(Map.of("status","ANSWERED","interpretation","출처 근거 설명",
            "citationIds",List.of("knowledge-1","tool-1"),"facts",List.of(Map.of("evidenceId","tool-1","pointer","/status","value","PENDING_ONCHAIN")),
            "uncertainties",List.of(),"recommendedNextCheck",List.of()));
        output(generated);
        var result=provider().synthesize("왜?",Route.MIXED,json.createObjectNode(),Duration.ofSeconds(10));
        assertThat(result.status()).isEqualTo("ANSWERED");assertThat(result.facts()).hasSize(1);
        assertThat(result.usage().outputTokens()).isEqualTo(9);
        assertThat(json.readTree(requested).path("max_output_tokens").asInt()).isEqualTo(1600);
        generated.put("role","ADMIN");output(generated);
        assertThatThrownBy(() -> provider().synthesize("왜?",Route.MIXED,json.createObjectNode(),Duration.ofSeconds(10)))
            .hasMessage("INVALID_AGENT_EVIDENCE");
    }
    @Test void unknownPlanEnumAndZeroBudgetFailClosed() throws Exception {
        output(Map.of("route","EXECUTE_SQL","subject","ORDER"));
        assertThatThrownBy(() -> provider().plan("정책",Subject.NONE,Duration.ofSeconds(5))).hasMessage("INVALID_AGENT_PLAN");
        assertThatThrownBy(() -> provider().plan("정책",Subject.NONE,Duration.ZERO)).isInstanceOf(RuntimeException.class);
    }
}
