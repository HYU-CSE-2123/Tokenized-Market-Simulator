package com.pricetrack.exchange.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class OpenAiProviderTest {
    HttpServer server;
    final ObjectMapper json=new ObjectMapper();
    String body; int status=200; String requested;
    long delayMillis; boolean chunked;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            requested=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,chunked ? 0 : bytes.length);
            try {
                if(delayMillis>0)Thread.sleep(delayMillis);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            catch(java.io.IOException expectedClientCancellation) { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    OpenAiProvider provider() {
        return new OpenAiProvider(new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/test","test","test",".",".","secret",
                "http://127.0.0.1:"+server.getAddress().getPort()+"/","text-embedding-3-small","gpt-5.6-terra",800,60,5,.3,1),json);
    }
    @Test void embeddingsValidateDimensionsAndRestoreBatchOrder() throws Exception {
        body=json.writeValueAsString(Map.of("data",List.of(Map.of("index",1,"embedding",AiFixtures.vector(1)),
                Map.of("index",0,"embedding",AiFixtures.vector(0)))));
        var result=provider().embed(List.of("a","b"));
        assertThat(result.get(0)[0]).isEqualTo(1);assertThat(result.get(1)[1]).isEqualTo(1);
        assertThat(json.readTree(requested).path("dimensions").asInt()).isEqualTo(1536);
    }
    @Test void malformedVectorAndHttpErrorAreSanitized() {
        body="{\"data\":[{\"index\":0,\"embedding\":[1]}]}";
        assertThatThrownBy(() -> provider().embed(List.of("a"))).hasMessage("AI_PROVIDER_RESPONSE_INVALID");
        status=429;body="secret raw error";
        assertThatThrownBy(() -> provider().embed(List.of("a"))).hasMessage("AI_PROVIDER_UNAVAILABLE");
    }
    @Test void structuredAnswersDisableStorageAndTools() throws Exception {
        body=json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(
                Map.of("type","output_text","text",json.writeValueAsString(Map.of("status","INSUFFICIENT_EVIDENCE","answer","확인 불가","citationIds",List.of()))))))));
        assertThat(provider().answer("question",List.of()).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        var request=json.readTree(requested);
        assertThat(request.path("store").asBoolean(true)).isFalse();
        assertThat(request.has("tools")).isFalse();
        assertThat(request.path("model").asText()).isEqualTo("gpt-5.6-terra");
    }
    @Test void incompleteModelResponseIsRejected() {
        body="{\"status\":\"incomplete\",\"output\":[]}";
        assertThatThrownBy(() -> provider().answer("question",List.of())).hasMessage("AI_PROVIDER_RESPONSE_INVALID");
    }
    @Test void oversizedChunkedSuccessAndErrorBodiesAreRejected() {
        chunked=true;body="x".repeat(2_100_000);
        for(int code:List.of(200,503)) {
            status=code;
            assertThatThrownBy(() -> provider().embed(List.of("a"))).isInstanceOf(AiFailure.class);
        }
    }
    @Test void slowBodyHasWholeRequestDeadline() {
        body="{}";delayMillis=3000;chunked=true;
        long start=System.nanoTime();
        assertThatThrownBy(() -> provider().embed(List.of("a"))).hasMessage("AI_PROVIDER_UNAVAILABLE");
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThan(2500);
    }
}
