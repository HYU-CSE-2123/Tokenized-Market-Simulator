package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.market.provider.MarketCandleProvider;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Native unadjusted KRX OHLCV. Bounded continuation; never invent missing vendor history. */
public class KiwoomCandleProvider implements MarketCandleProvider {
    private final KiwoomRestClient client;
    private final Clock clock;
    public KiwoomCandleProvider(KiwoomRestClient client, Clock clock) { this.client=client; this.clock=clock; }
    public MarketCandlePage candles(CandleInterval interval,int count,Instant before) {
        if (count<=0 || count>interval.maxCount()) throw new IllegalArgumentException("Invalid candle count");
        Instant now=clock.instant();
        String date=(before==null?now:before).atZone(KiwoomRestClient.KST).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        TreeMap<Instant,MarketCandle> rows=new TreeMap<>();
        Set<String> seen=new HashSet<>(); String cursor=null; boolean more=false;
        for(int page=0;page<8;page++) {
            var response=client.chart(interval==CandleInterval.ONE_DAY,interval.minutes(),date,cursor);
            JsonNode entries=response.body().path(interval==CandleInterval.ONE_DAY?"stk_dt_pole_chart_qry":"stk_min_pole_chart_qry");
            if (!entries.isArray()) throw new KiwoomApiException();
            for(JsonNode entry:entries) {
                MarketCandle c=candle(entry,interval,now);
                if(before==null || c.startedAt().isBefore(before)) {
                    MarketCandle previous=rows.putIfAbsent(c.startedAt(),c);
                    if(previous!=null && !previous.equals(c)) throw new KiwoomApiException();
                }
            }
            more=response.more();
            if(!more || rows.size()>count) break;
            cursor=response.nextKey();
            if(!seen.add(cursor)) throw new KiwoomApiException();
            if(page==7) throw new KiwoomApiException(); // incomplete search is not an exhausted history page
        }
        List<MarketCandle> all=new ArrayList<>(rows.values());
        int start=Math.max(0,all.size()-count);
        List<MarketCandle> result=all.subList(start,all.size());
        Instant next=(more || start>0) && !result.isEmpty()?result.getFirst().startedAt():null;
        return new MarketCandlePage("mSEC",interval,"KIWOOM",result,next,now);
    }
    private MarketCandle candle(JsonNode row,CandleInterval interval,Instant now) {
        try {
            Instant at=interval==CandleInterval.ONE_DAY
                ? LocalDate.parse(row.path("dt").asText(),DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(KiwoomRestClient.KST).toInstant()
                : LocalDateTime.parse(row.path("cntr_tm").asText(),DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(KiwoomRestClient.KST).toInstant();
            // Vendor timestamp is retained; no unverified Toss-style end-to-start subtraction.
            if(interval!=CandleInterval.ONE_DAY && (at.atZone(KiwoomRestClient.KST).getSecond()!=0
                    || at.atZone(KiwoomRestClient.KST).getMinute()%interval.minutes()!=0)) throw new KiwoomApiException();
            if(at.isAfter(now)) throw new KiwoomApiException();
            BigDecimal o=KiwoomProtocol.positive(row.path("open_pric").asText()),h=KiwoomProtocol.positive(row.path("high_pric").asText()),
                l=KiwoomProtocol.positive(row.path("low_pric").asText()),c=KiwoomProtocol.positive(row.path("cur_prc").asText()),
                v=KiwoomProtocol.decimal(row.path("trde_qty").asText());
            if(v.signum()<0 || h.compareTo(o.max(c))<0 || l.compareTo(o.min(c))>0 || l.compareTo(h)>0) throw new KiwoomApiException();
            return new MarketCandle(at,o,h,l,c,v,!at.plusSeconds(interval.minutes()*60L).isAfter(now));
        } catch(RuntimeException ignored) { throw new KiwoomApiException(); }
    }
}
