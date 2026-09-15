package com.pricetrack.exchange.market.provider.toss;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    public TossMarketReference marketReference(String symbol, LocalDate date) {
        String token = authClient.accessToken();
        try {
            return requestMarketReference(symbol, date, token);
        } catch (HttpClientErrorException.Unauthorized exception) {
            authClient.invalidate(token);
            return requestMarketReference(symbol, date, authClient.accessToken());
        }
    }

    private TossMarketReference requestMarketReference(String symbol, LocalDate date, String token) {
        try {
            CalendarEnvelope calendar = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/market-calendar/KR")
                            .queryParam("date", date).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(CalendarEnvelope.class);
            if (calendar == null || calendar.result() == null || calendar.result().today() == null
                    || calendar.result().previousBusinessDay() == null) {
                throw new TossApiException("Toss market calendar response is empty.");
            }
            CandleEnvelope candles = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/candles")
                            .queryParam("symbol", symbol).queryParam("interval", "1d")
                            .queryParam("count", 10).queryParam("adjusted", true).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(CandleEnvelope.class);
            LocalDate previousDate = LocalDate.parse(calendar.result().previousBusinessDay().date());
            BigDecimal previousClose = requirePreviousClose(candles, previousDate);
            return new TossMarketReference(previousClose, sessions(calendar.result().today()));
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw exception;
        } catch (TossApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TossApiException("Failed to load Toss market reference data.", exception);
        }
    }

    private static BigDecimal requirePreviousClose(CandleEnvelope envelope, LocalDate expectedDate) {
        if (envelope == null || envelope.result() == null || envelope.result().candles() == null) {
            throw new TossApiException("Toss daily candle response is empty.");
        }
        return envelope.result().candles().stream()
                .filter(candle -> OffsetDateTime.parse(candle.timestamp()).toLocalDate().equals(expectedDate))
                .filter(candle -> "KRW".equals(candle.currency()))
                .map(candle -> new BigDecimal(candle.closePrice()))
                .filter(price -> price.signum() > 0)
                .findFirst()
                .orElseThrow(() -> new TossApiException("Previous business-day close is missing."));
    }

    private static List<MarketSession> sessions(MarketDay day) {
        if (day.integrated() == null) return List.of();
        List<MarketSession> result = new ArrayList<>();
        addSession(result, day.integrated().preMarket());
        addSession(result, day.integrated().regularMarket());
        addSession(result, day.integrated().afterMarket());
        return List.copyOf(result);
    }

    private static void addSession(List<MarketSession> sessions, Session session) {
        if (session != null) {
            Instant start = OffsetDateTime.parse(session.startTime()).toInstant();
            Instant end = OffsetDateTime.parse(session.endTime()).toInstant();
            if (!end.isAfter(start)) throw new TossApiException("Toss market session range is invalid.");
            sessions.add(new MarketSession(start, end));
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
    record CalendarEnvelope(CalendarResult result) {}
    record CalendarResult(MarketDay today, MarketDay previousBusinessDay, MarketDay nextBusinessDay) {}
    record MarketDay(String date, Integrated integrated) {}
    record Integrated(Session preMarket, Session regularMarket, Session afterMarket) {}
    record Session(String startTime, String endTime) {}
    record CandleEnvelope(CandlePage result) {}
    record CandlePage(List<Candle> candles) {}
    record Candle(String timestamp, String closePrice, String currency) {}
    public record MarketSession(Instant start, Instant end) {
        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }
    public record TossMarketReference(BigDecimal previousClose, List<MarketSession> sessions) {
        public TossMarketReference {
            if (previousClose == null || previousClose.signum() <= 0) {
                throw new IllegalArgumentException("previousClose must be positive");
            }
            sessions = List.copyOf(sessions);
        }

        public boolean isOpenAt(Instant instant) {
            return sessions.stream().anyMatch(session -> session.contains(instant));
        }
    }
}
