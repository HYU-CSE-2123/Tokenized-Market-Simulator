package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Fixed read-only TR allowlist. Tokens are memory-only, expiry is vendor KST, retries bounded to one. */
public class KiwoomRestClient {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final RestClient http;
    private final KiwoomPriceProperties properties;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;
    public KiwoomRestClient(RestClient http, KiwoomPriceProperties properties, Clock clock) {
        this.http=http; this.properties=properties; this.clock=clock;
    }
    public synchronized String accessToken() {
        if (token != null && clock.instant().plusSeconds(30).isBefore(expiresAt)) return token;
        try {
            JsonNode body=http.post().uri("/oauth2/token").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("grant_type","client_credentials","appkey",properties.appKey(),"secretkey",properties.secretKey()))
                .retrieve().body(JsonNode.class);
            if (body == null || !success(body) || body.path("token").asText().isBlank()
                    || !"bearer".equalsIgnoreCase(body.path("token_type").asText())) throw new KiwoomApiException();
            Instant expiry=LocalDateTime.parse(body.path("expires_dt").asText(),DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atZone(KST).toInstant();
            if (!clock.instant().plusSeconds(30).isBefore(expiry)) throw new KiwoomApiException();
            token=body.path("token").asText(); expiresAt=expiry; return token;
        } catch (RuntimeException ignored) { throw new KiwoomApiException(); }
    }
    public synchronized void invalidate(String rejected) { if (rejected != null && rejected.equals(token)) token=null; }
    public synchronized Instant tokenExpiresAt() { return expiresAt; }
    public record Page(JsonNode body, boolean more, String nextKey) {}
    public Page currentPrice() { return query("ka10001",Map.of("stk_cd","005930"),null); }
    public Page chart(boolean daily, int minutes, String date, String cursor) {
        return query(daily?"ka10081":"ka10080", daily
            ? Map.of("stk_cd","005930","base_dt",date,"upd_stkpc_tp","0")
            : Map.of("stk_cd","005930","base_dt",date,"tic_scope",String.valueOf(minutes),"upd_stkpc_tp","0"),cursor);
    }
    private Page query(String tr, Map<String,String> body, String cursor) {
        String path=tr.equals("ka10001")?"/api/dostk/stkinfo":"/api/dostk/chart";
        for (int attempt=0;attempt<2;attempt++) {
            String credential=accessToken();
            try {
                var response=http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("authorization","Bearer "+credential).header("api-id",tr)
                    .header("cont-yn",cursor==null?"N":"Y").header("next-key",cursor==null?"":cursor)
                    .body(body).retrieve().toEntity(JsonNode.class);
                JsonNode result=response.getBody();
                if (result == null) throw new KiwoomApiException();
                if (!success(result)) {
                    if (attempt==0 && expired(result)) { invalidate(credential); continue; }
                    throw new KiwoomApiException();
                }
                boolean more="Y".equals(response.getHeaders().getFirst("cont-yn"));
                String next=response.getHeaders().getFirst("next-key");
                if (more && (next==null || next.isBlank())) throw new KiwoomApiException();
                return new Page(result,more,next);
            } catch (RestClientResponseException failure) {
                if (attempt==0 && failure.getStatusCode().value()==401) { invalidate(credential); continue; }
                throw new KiwoomApiException();
            } catch (RuntimeException ignored) { throw new KiwoomApiException(); }
        }
        throw new KiwoomApiException();
    }
    static boolean success(JsonNode body) { return body.has("return_code") && "0".equals(body.path("return_code").asText()); }
    static boolean expired(JsonNode body) {
        String code=body.path("return_code").asText(),message=body.path("return_msg").asText();
        for (String expired : new String[]{"8005","8031","8103"})
            if (code.equals(expired) || message.contains("["+expired+":") || message.contains("CODE="+expired)) return true;
        return false;
    }
}
