package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.pricetrack.exchange.market.model.MarketStatus;

/** Official 0B/0s fields only. HHmmss has no date: reject delayed/ambiguous frames rather than invent freshness. */
public class KiwoomProtocol {
    private final ObjectMapper mapper;
    private final Clock clock;
    public KiwoomProtocol(ObjectMapper mapper, Clock clock) { this.mapper=mapper; this.clock=clock; }
    public JsonNode parse(String text) {
        try { return mapper.readTree(text); } catch (Exception ignored) { throw new KiwoomApiException(); }
    }
    public String login(String token) { return json(Map.of("trnm","LOGIN","token",token)); }
    public String subscribe() { return json(Map.of("trnm","REG","grp_no","1","refresh","1","data",List.of(
        Map.of("item",List.of("005930"),"type",List.of("0B")),
        Map.of("item",List.of(""),"type",List.of("0s"))))); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ignored) { throw new KiwoomApiException(); } }
    public record Trade(BigDecimal price, BigDecimal change, BigDecimal rate, BigDecimal volume,
                        BigDecimal cumulative, Instant observedAt, boolean regular) {}
    public Trade trade(JsonNode entry) {
        if (!"0B".equals(entry.path("type").asText()) || !"005930".equals(entry.path("item").asText())) return null;
        JsonNode v=entry.path("values");
        BigDecimal price=positive(v.path("10").asText()),change=decimal(v.path("11").asText());
        BigDecimal volume=positive(v.path("15").asText()),cumulative=positive(v.path("13").asText());
        if (price.subtract(change).signum()<=0) throw new KiwoomApiException();
        return new Trade(price,change,decimal(v.path("12").asText()),volume,cumulative,time(v.path("20").asText()),"2".equals(v.path("290").asText()));
    }
    public MarketStatus state(JsonNode entry) {
        if (!"0s".equals(entry.path("type").asText())) return null;
        String item=entry.path("item").asText();
        if (!item.isEmpty()) return null; // KRX-wide registration only; never interpret NXT identifiers.
        time(entry.path("values").path("20").asText());
        return switch(entry.path("values").path("215").asText()) {
            case "3" -> MarketStatus.OPEN;
            case "0","2","4","8","9","a","b","c","d" -> MarketStatus.CLOSED;
            default -> MarketStatus.UNKNOWN;
        };
    }
    private Instant time(String time) {
        try {
            Instant now=clock.instant();
            Instant result=LocalDateTime.of(now.atZone(KiwoomRestClient.KST).toLocalDate(),
                    LocalTime.parse(time,DateTimeFormatter.ofPattern("HHmmss"))).atZone(KiwoomRestClient.KST).toInstant();
            Duration age=Duration.between(result,now);
            if (age.compareTo(Duration.ofSeconds(5))>0 || age.compareTo(Duration.ofSeconds(-2))<0) throw new KiwoomApiException();
            return result;
        } catch (RuntimeException ignored) { throw new KiwoomApiException(); }
    }
    static BigDecimal decimal(String value) {
        if (value == null || !value.trim().matches("[+-]?[0-9]+(\\.[0-9]+)?")) throw new KiwoomApiException();
        return new BigDecimal(value.trim());
    }
    static BigDecimal positive(String value) { BigDecimal result=decimal(value).abs(); if(result.signum()<=0)throw new KiwoomApiException(); return result; }
}
