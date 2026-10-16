package com.pricetrack.exchange.market.provider.kiwoom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.market.model.*;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import java.time.*;
import org.junit.jupiter.api.*;

class KiwoomPriceProviderTest {
    final Clock clock=mock(Clock.class);
    final KiwoomRestClient rest=mock(KiwoomRestClient.class);
    final KiwoomRealtimeClient ws=mock(KiwoomRealtimeClient.class);
    final MarketWebSocketPublisher publisher=mock(MarketWebSocketPublisher.class);
    final KiwoomProtocol protocol=new KiwoomProtocol(new ObjectMapper(),clock);
    final KiwoomPriceProvider provider=new KiwoomPriceProvider(rest,ws,protocol,KiwoomRestClientTest.properties(),publisher,clock);
    @BeforeEach void setup(){when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW);when(ws.connected()).thenReturn(true);}
    void accept(String entries){provider.accept(new KiwoomRealtimeClient.Frame(ws.connectionGeneration(),protocol.parse("{\"trnm\":\"REAL\",\"data\":["+entries+"]}")));}
    @Test void reconnectRequiresSameGenerationMarketConfirmationEvenIfOldOrNewTickIsFresh() {
        when(ws.connectionGeneration()).thenReturn(1L);
        accept(KiwoomProtocolTest.state("3")+","+KiwoomProtocolTest.tick("100000","1234"));
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.OPEN);
        when(ws.connected()).thenReturn(false);when(ws.connectionGeneration()).thenReturn(2L);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        when(ws.connected()).thenReturn(true);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        accept(KiwoomProtocolTest.tick("100000","1235"));
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        provider.accept(new KiwoomRealtimeClient.Frame(1L,protocol.parse("{\"data\":["+KiwoomProtocolTest.state("3")+"]}")));
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        accept(KiwoomProtocolTest.state("3"));assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.OPEN);
    }
    @Test void restBootstrapIsNotLiveOrOpen() {
        when(rest.currentPrice()).thenReturn(new KiwoomRestClient.Page(protocol.parse("{\"cur_prc\":\"-75000\",\"pred_pre\":\"-100\",\"flu_rt\":\"-0.13\"}"),false,null));
        provider.start();assertThat(provider.current().previousClose()).isEqualByComparingTo("75100");
        assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.INITIALIZING);
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
    }
    @Test void explicitStateAndFreshTickEnableLiveWhileDisconnectOrAgeDegrades() {
        accept(KiwoomProtocolTest.state("3")+","+KiwoomProtocolTest.tick("100000","1234"));
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.OPEN);assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.LIVE);
        when(ws.connected()).thenReturn(false);assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.DEGRADED);
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.plusSeconds(60));assertThat(provider.current().priceStatus()).isEqualTo(PriceStatus.STALE);
    }
    @Test void sameSecondDistinctCumulativeTradesPublishButDuplicatesDoNot() {
        accept(KiwoomProtocolTest.tick("100000","1234"));accept(KiwoomProtocolTest.tick("100000","1235"));
        accept(KiwoomProtocolTest.tick("100000","1235"));accept(KiwoomProtocolTest.tick("095959","1236"));
        verify(publisher,times(2)).publishPrice(any(),any());
        assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
    }
    @Test void closedAndDateRolloverNeverInventOpen() {
        accept(KiwoomProtocolTest.state("3")+","+KiwoomProtocolTest.tick("100000","1234"));
        accept(KiwoomProtocolTest.state("8"));assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.CLOSED);
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.plusSeconds(86400));assertThat(provider.current().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
    }

    @Test void regularTickAllowsMockQuotesButNeverInventsOpen() {
        accept(KiwoomProtocolTest.tick("100000","1234"));
        assertThat(provider.currentForTrading().allowed()).isTrue();
        assertThat(provider.currentForTrading().snapshot().marketStatus()).isEqualTo(MarketStatus.UNKNOWN);
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.plusSeconds(6));
        assertThat(provider.currentForTrading().allowed()).isFalse();
    }
    @Test void invalidMarketEvidenceVetoSurvivesFreshTickAndReconnect() {
        accept(KiwoomProtocolTest.tick("100000","1234"));
        assertThatThrownBy(()->accept(KiwoomProtocolTest.state("3").replace("100000","095900")))
            .isInstanceOf(KiwoomApiException.class);
        accept(KiwoomProtocolTest.tick("100000","1235"));
        assertThat(provider.currentForTrading().reason()).isEqualTo("INVALID_MARKET_STATE");
        when(ws.connectionGeneration()).thenReturn(2L);
        accept(KiwoomProtocolTest.tick("100000","1236"));
        assertThat(provider.currentForTrading().allowed()).isFalse();
    }
    @Test void readinessHonorsExactFreshnessAndFutureBoundaries() {
        accept(KiwoomProtocolTest.tick("100000","1234"));
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.plusSeconds(5));
        assertThat(provider.currentForTrading().allowed()).isTrue();
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.plusSeconds(5).plusNanos(1));
        assertThat(provider.currentForTrading().allowed()).isFalse();
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.minusSeconds(2));
        assertThat(provider.currentForTrading().allowed()).isTrue();
        when(clock.instant()).thenReturn(KiwoomProtocolTest.NOW.minusSeconds(2).minusNanos(1));
        assertThat(provider.currentForTrading().allowed()).isFalse();
    }
    @Test void generationChangeWhileReadingReadinessFailsClosed() {
        when(ws.connectionGeneration()).thenReturn(1L);
        accept(KiwoomProtocolTest.tick("100000","1234"));
        when(ws.connectionGeneration()).thenReturn(1L,1L,2L);
        var context=provider.currentForTrading();
        assertThat(context.allowed()).isFalse();
        assertThat(context.reason()).isEqualTo("CONNECTION_CHANGED");
    }
    @Test void oldGenerationPriceCannotEnableQuotesAfterReconnectButNewTickCan() {
        when(ws.connectionGeneration()).thenReturn(1L);accept(KiwoomProtocolTest.tick("100000","1234"));
        when(ws.connected()).thenReturn(false);assertThat(provider.currentForTrading().allowed()).isFalse();
        when(ws.connectionGeneration()).thenReturn(2L);when(ws.connected()).thenReturn(true);
        assertThat(provider.currentForTrading().allowed()).isFalse();
        provider.accept(new KiwoomRealtimeClient.Frame(1L,protocol.parse("{\"data\":["+KiwoomProtocolTest.tick("100000","1235")+"]}")));
        assertThat(provider.currentForTrading().allowed()).isFalse();
        accept(KiwoomProtocolTest.tick("100000","1235"));assertThat(provider.currentForTrading().allowed()).isTrue();
    }
    @Test void closedAndUnsupportedVetoBeatFreshTicksAndSurviveReconnect() {
        for(String code:new String[]{"8","P"}) {
            accept(KiwoomProtocolTest.state(code));accept(KiwoomProtocolTest.tick("100000",code.equals("8")?"1234":"1236"));
            assertThat(provider.currentForTrading().allowed()).isFalse();
            when(ws.connectionGeneration()).thenReturn(2L);
            accept(KiwoomProtocolTest.tick("100000",code.equals("8")?"1235":"1237"));
            assertThat(provider.currentForTrading().allowed()).isFalse();
        }
        accept(KiwoomProtocolTest.state("3"));assertThat(provider.currentForTrading().allowed()).isTrue();
    }
    @Test void timeOutsideRegularSessionAndRestOnlyNeverEnableQuotes() {
        when(rest.currentPrice()).thenReturn(new KiwoomRestClient.Page(protocol.parse("{\"cur_prc\":\"75000\",\"pred_pre\":\"0\",\"flu_rt\":\"0\"}"),false,null));
        provider.start();assertThat(provider.currentForTrading().allowed()).isFalse();
        accept(KiwoomProtocolTest.tick("100000","1234").replace("\"290\":\"2\"","\"290\":\"3\""));
        assertThat(provider.currentForTrading().allowed()).isFalse();
    }
}
