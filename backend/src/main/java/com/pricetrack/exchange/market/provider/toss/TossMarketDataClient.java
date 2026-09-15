package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** 토스증권 현재가 REST 응답을 검증해 공급자 내부 값으로 변환한다. */
public class TossMarketDataClient {
    private final RestClient restClient;
    private final TossAuthClient authClient;

    public TossMarketDataClient(RestClient restClient, TossAuthClient authClient) {
        this.restClient = restClient;
        this.authClient = authClient;
    }

    public TossPrice currentPrice(String symbol) {
        String token = authClient.accessToken();
        try {
            return requestCurrentPrice(symbol, token);
        } catch (HttpClientErrorException.Unauthorized exception) {
            authClient.invalidate(token);
            return requestCurrentPrice(symbol, authClient.accessToken());
        }
    }

    private TossPrice requestCurrentPrice(String symbol, String token) {
        PriceResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/prices")
                            .queryParam("symbols", symbol).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(PriceResponse.class);
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TossApiException("토스증권 현재가 조회에 실패했습니다.", exception);
        }
        if (response == null || response.result() == null) {
            throw new TossApiException("토스증권 현재가 응답이 비어 있습니다.");
        }
        PriceItem item = response.result().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow(() -> new TossApiException("요청한 종목의 현재가가 응답에 없습니다."));
        return validateAndConvert(item);
    }

    private TossPrice validateAndConvert(PriceItem item) {
        if (!"KRW".equals(item.currency())) {
            throw new TossApiException("삼성전자 현재가의 통화가 KRW가 아닙니다.");
        }
        try {
            BigDecimal price = new BigDecimal(item.lastPrice());
            Instant observedAt = OffsetDateTime.parse(item.timestamp()).toInstant();
            if (price.signum() <= 0) throw new NumberFormatException("non-positive price");
            return new TossPrice(item.symbol(), price, observedAt);
        } catch (RuntimeException exception) {
            throw new TossApiException("토스증권 현재가 값 또는 관측 시각이 올바르지 않습니다.", exception);
        }
    }

    record PriceResponse(List<PriceItem> result) {}
    record PriceItem(String symbol, String timestamp, String lastPrice, String currency) {}
    public record TossPrice(String symbol, BigDecimal price, Instant observedAt) {}
}
