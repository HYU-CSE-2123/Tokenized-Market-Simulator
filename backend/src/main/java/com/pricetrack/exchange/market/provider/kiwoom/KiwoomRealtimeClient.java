package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One connection generation, LOGIN before REG, bounded backoff and memory-only token refresh. */
public class KiwoomRealtimeClient {
    private final HttpClient http;
    private final KiwoomRestClient auth;
    private final KiwoomProtocol protocol;
    private final KiwoomPriceProperties properties;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"kiwoom-market-realtime");t.setDaemon(true);return t;});
    private volatile boolean running,connected;
    private volatile long generation;
    private int failures;
    private WebSocket socket;
    private CompletableFuture<WebSocket> pending;
    private ScheduledFuture<?> retry,deadline,renew;
    public record Frame(long generation, JsonNode payload) {}
    private Consumer<Frame> consumer;
    public KiwoomRealtimeClient(HttpClient http,KiwoomRestClient auth,KiwoomProtocol protocol,KiwoomPriceProperties properties) {
        this.http=http;this.auth=auth;this.protocol=protocol;this.properties=properties;
    }
    public synchronized void start(Consumer<Frame> consumer) {
        if(running)return;this.consumer=consumer;running=true;executor.execute(this::connect);
    }
    public boolean connected(){return connected;}
    public long connectionGeneration(){return generation;}
    private void connect() {
        final long id;
        synchronized(this){if(!running)return;id=++generation;connected=false;}
        String credential;
        try {credential=auth.accessToken();} catch(RuntimeException ignored){lost(id);return;}
        synchronized(this) {
            if(!running || id!=generation)return;
            deadline=executor.schedule(()->lost(id),10,TimeUnit.SECONDS);
            pending=http.newWebSocketBuilder().connectTimeout(properties.connectTimeout())
                .buildAsync(URI.create(properties.websocketUrl()),new Listener(id,credential));
            pending.whenComplete((ws,error)->{if(error!=null)lost(id);});
        }
    }
    private synchronized boolean active(long id){return running && id==generation;}
    private synchronized void lost(long id) {
        if(!active(id))return;
        generation++;connected=false;
        if(socket!=null){socket.abort();socket=null;}
        if(pending!=null){pending.cancel(true);pending=null;}
        cancel(deadline);cancel(renew);
        long seconds=Math.min(30,1L<<Math.min(5,failures++));
        cancel(retry);retry=executor.schedule(this::connect,seconds,TimeUnit.SECONDS);
    }
    public synchronized void stop() {
        running=false;connected=false;generation++;
        cancel(retry);cancel(deadline);cancel(renew);
        if(pending!=null)pending.cancel(true);
        if(socket!=null)socket.abort();
        executor.shutdownNow();
    }
    private static void cancel(ScheduledFuture<?> future){if(future!=null)future.cancel(false);}
    private final class Listener implements WebSocket.Listener {
        private final long id;
        private final String credential;
        private final StringBuilder buffer=new StringBuilder();
        private boolean loggedIn,subscribed;
        Listener(long id,String credential){this.id=id;this.credential=credential;}
        @Override public void onOpen(WebSocket ws) {
            synchronized(KiwoomRealtimeClient.this){
                if(!active(id)){ws.abort();return;}socket=ws;
            }
            ws.sendText(protocol.login(credential),true).whenComplete((v,error)->{if(error!=null)lost(id);});
            ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            if(!active(id))return null;
            if(buffer.length()+data.length()>1_048_576){lost(id);return null;}
            buffer.append(data);
            if(last) {
                String text=buffer.toString();buffer.setLength(0);
                try {
                    if("PING".equals(text.trim())) ws.sendText(text,true);
                    else {
                        JsonNode frame=protocol.parse(text);
                        String type=frame.path("trnm").asText();
                        if("PING".equals(type))ws.sendText(text,true);
                        else if("LOGIN".equals(type)) {
                            if(!KiwoomRestClient.success(frame)) {
                                if(KiwoomRestClient.expired(frame))auth.invalidate(credential);
                                lost(id);
                            } else if(!loggedIn) {
                                loggedIn=true;
                                ws.sendText(protocol.subscribe(),true).whenComplete((v,error)->{if(error!=null)lost(id);});
                            }
                        } else if("REG".equals(type)) {
                            if(!loggedIn || !KiwoomRestClient.success(frame)){lost(id);return null;}
                            synchronized(KiwoomRealtimeClient.this){
                                if(active(id)) {
                                    subscribed=true;connected=true;failures=0;cancel(deadline);
                                    long delay=Math.max(1,java.time.Duration.between(java.time.Instant.now(),auth.tokenExpiresAt().minusSeconds(30)).toMillis());
                                    cancel(renew);renew=executor.schedule(()->lost(id),delay,TimeUnit.MILLISECONDS);
                                }
                            }
                        } else if("REAL".equals(type) && subscribed && active(id))consumer.accept(new Frame(id,frame));
                    }
                } catch(RuntimeException ignored){lost(id);}
            }
            ws.request(1);return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer message){ws.request(1);return ws.sendPong(message);}
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){lost(id);return null;}
        @Override public void onError(WebSocket ws,Throwable failure){lost(id);}
    }
}
