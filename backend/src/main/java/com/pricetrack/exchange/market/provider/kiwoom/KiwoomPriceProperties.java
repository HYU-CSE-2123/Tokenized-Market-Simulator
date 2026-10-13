package com.pricetrack.exchange.market.provider.kiwoom;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Only KRX Samsung and official endpoints are allowed; no credential-bearing toString. */
@ConfigurationProperties("app.price.kiwoom")
public record KiwoomPriceProperties(String baseUrl, String websocketUrl, String appKey, String secretKey,
        Duration connectTimeout, Duration readTimeout, Duration degradedAfter, Duration staleAfter) {
    public void validate() {
        if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank())
            throw new IllegalStateException("KIWOOM_APP_KEY와 KIWOOM_SECRET_KEY가 필요합니다.");
        URI rest = URI.create(baseUrl), ws = URI.create(websocketUrl);
        String host = rest.getHost();
        if (!("api.kiwoom.com".equals(host) || "mockapi.kiwoom.com".equals(host))
                || !"https".equals(rest.getScheme()) || rest.getUserInfo() != null
                || rest.getQuery() != null || rest.getFragment() != null
                || !(rest.getPort() == -1 || rest.getPort() == 443)
                || !(rest.getPath().isEmpty() || rest.getPath().equals("/"))
                || !host.equals(ws.getHost()) || !"wss".equals(ws.getScheme())
                || ws.getPort() != 10000 || !"/api/dostk/websocket".equals(ws.getPath())
                || ws.getUserInfo() != null || ws.getQuery() != null || ws.getFragment() != null)
            throw new IllegalStateException("키움 공식 REST/WS endpoint 조합이 필요합니다.");
        for (Duration d : new Duration[]{connectTimeout, readTimeout, degradedAfter, staleAfter})
            if (d == null || d.isNegative() || d.isZero()) throw new IllegalStateException("키움 timeout은 양수여야 합니다.");
        if (staleAfter.compareTo(degradedAfter) <= 0) throw new IllegalStateException("키움 stale 기준이 degraded보다 커야 합니다.");
    }
    @Override public String toString() { return "KiwoomPriceProperties[credentials=REDACTED]"; }
}
