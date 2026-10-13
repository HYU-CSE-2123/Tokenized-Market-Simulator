package com.pricetrack.exchange.market.provider.kiwoom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.market.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class KiwoomCandleProviderTest {
    final KiwoomRestClient rest=mock(KiwoomRestClient.class);
    final KiwoomProtocol protocol=new KiwoomProtocol(new ObjectMapper(),Clock.fixed(KiwoomProtocolTest.NOW,ZoneOffset.UTC));
    final KiwoomCandleProvider provider=new KiwoomCandleProvider(rest,Clock.fixed(KiwoomProtocolTest.NOW,ZoneOffset.UTC));
    String row(String field,String time){return "{\""+field+"\":\""+time+"\",\"open_pric\":\"-74000\",\"high_pric\":\"+76000\",\"low_pric\":\"73000\",\"cur_prc\":\"-75000\",\"trde_qty\":\"42\"}";}
    @Test void mapsAllSixNativeIntervalsWithKstVolumeAndExclusiveBefore() {
        for(CandleInterval interval:CandleInterval.values()) {
            boolean daily=interval==CandleInterval.ONE_DAY;
            String name=daily?"stk_dt_pole_chart_qry":"stk_min_pole_chart_qry";
            when(rest.chart(daily,interval.minutes(),"20261013",null)).thenReturn(new KiwoomRestClient.Page(
                protocol.parse("{\""+name+"\":["+row(daily?"dt":"cntr_tm",daily?"20261012":"20261013090000")+"]}"),false,null));
            var page=provider.candles(interval,1,null);assertThat(page.provider()).isEqualTo("KIWOOM");
            assertThat(page.candles()).hasSize(1);assertThat(page.candles().getFirst().volume()).isEqualByComparingTo("42");
            assertThat(page.candles().getFirst().close()).isEqualByComparingTo("75000");
        }
    }
    @Test void repeatedCursorFailsRatherThanClaimingExhaustedHistory() {
        var page=new KiwoomRestClient.Page(protocol.parse("{\"stk_min_pole_chart_qry\":[]}"),true,"same");
        when(rest.chart(false,1,"20261013",null)).thenReturn(page);when(rest.chart(false,1,"20261013","same")).thenReturn(page);
        assertThatThrownBy(()->provider.candles(CandleInterval.ONE_MINUTE,2,null)).isInstanceOf(KiwoomApiException.class);
    }
    @Test void invalidOhlcvIsNotPublished() {
        when(rest.chart(false,1,"20261013",null)).thenReturn(new KiwoomRestClient.Page(protocol.parse(
            "{\"stk_min_pole_chart_qry\":["+row("cntr_tm","20261013090000").replace("+76000","72000")+"]}"),false,null));
        assertThatThrownBy(()->provider.candles(CandleInterval.ONE_MINUTE,1,null)).isInstanceOf(KiwoomApiException.class);
    }
}
