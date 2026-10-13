package com.pricetrack.exchange.market.provider.kiwoom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Mock WebSocket transport only, never a broker endpoint or credentials. */
class KiwoomRealtimeClientTest {
    @Test void loginBeforeRegPingEchoAndLateFrameIsolation() throws Exception {
        HttpClient http=mock(HttpClient.class);WebSocket.Builder builder=mock(WebSocket.Builder.class);
        WebSocket ws=mock(WebSocket.class);KiwoomRestClient auth=mock(KiwoomRestClient.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);when(builder.connectTimeout(any())).thenReturn(builder);
        when(auth.accessToken()).thenReturn("fixture-token");when(auth.tokenExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
        when(ws.sendText(anyString(),eq(true))).thenReturn(CompletableFuture.completedFuture(ws));
        AtomicReference<WebSocket.Listener> listener=new AtomicReference<>();
        when(builder.buildAsync(any(),any())).thenAnswer(call->{WebSocket.Listener l=call.getArgument(1);listener.set(l);l.onOpen(ws);return CompletableFuture.completedFuture(ws);});
        var protocol=new KiwoomProtocol(new ObjectMapper(),Clock.systemUTC());
        var client=new KiwoomRealtimeClient(http,auth,protocol,KiwoomRestClientTest.properties());
        AtomicInteger frames=new AtomicInteger();
        try {
            client.start(frame->frames.incrementAndGet());
            verify(ws,timeout(2000)).sendText(contains("LOGIN"),eq(true));
            verify(ws,never()).sendText(contains("REG"),eq(true));
            listener.get().onText(ws,"{\"trnm\":\"REAL\",\"data\":[]}",true);assertThat(frames.get()).isZero();
            listener.get().onText(ws,"{\"trnm\":\"LOGIN\",\"return_code\":0}",true);
            verify(ws,timeout(2000)).sendText(contains("REG"),eq(true));
            listener.get().onText(ws,"{\"trnm\":\"REG\",\"return_code\":0}",true);assertThat(client.connected()).isTrue();
            listener.get().onText(ws,"{\"trnm\":\"PING\"}",true);verify(ws).sendText("{\"trnm\":\"PING\"}",true);
            listener.get().onText(ws,"{\"trnm\":\"REAL\",\"data\":[]}",true);assertThat(frames.get()).isOne();
            client.stop();listener.get().onText(ws,"{\"trnm\":\"REAL\",\"data\":[]}",true);
            assertThat(frames.get()).isOne();assertThat(client.connected()).isFalse();
        } finally {client.stop();}
    }
    @Test void expiredLoginInvalidatesTokenAndReconnectsWithoutLeakingMessage() {
        HttpClient http=mock(HttpClient.class);WebSocket.Builder builder=mock(WebSocket.Builder.class);
        WebSocket ws=mock(WebSocket.class);KiwoomRestClient auth=mock(KiwoomRestClient.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);when(builder.connectTimeout(any())).thenReturn(builder);
        when(auth.accessToken()).thenReturn("fixture-token");when(ws.sendText(anyString(),eq(true))).thenReturn(CompletableFuture.completedFuture(ws));
        AtomicReference<WebSocket.Listener> listener=new AtomicReference<>();
        when(builder.buildAsync(any(),any())).thenAnswer(call->{WebSocket.Listener l=call.getArgument(1);listener.set(l);l.onOpen(ws);return CompletableFuture.completedFuture(ws);});
        var client=new KiwoomRealtimeClient(http,auth,new KiwoomProtocol(new ObjectMapper(),Clock.systemUTC()),KiwoomRestClientTest.properties());
        try {
            client.start(frame->{});verify(ws,timeout(2000)).sendText(contains("LOGIN"),eq(true));
            listener.get().onText(ws,"{\"trnm\":\"LOGIN\",\"return_code\":8005,\"return_msg\":\"fixture-secret\"}",true);
            verify(auth).invalidate("fixture-token");assertThat(client.connected()).isFalse();
            verify(builder,timeout(2500).times(2)).buildAsync(any(),any());
        } finally {client.stop();}
    }
}
