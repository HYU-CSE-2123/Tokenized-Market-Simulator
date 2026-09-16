package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossMarketDataClientTest {
    @Test
    void convertsTossMinuteCandlesToChronologicalMarketCandles() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossAuthClient auth = mock(TossAuthClient.class);
        when(auth.accessToken()).thenReturn("token");
        TossMarketDataClient client = new TossMarketDataClient(builder.build(), auth,
                Clock.fixed(Instant.parse("2026-09-16T00:01:30Z"), ZoneOffset.UTC));
        server.expect(requestTo("https://openapi.test/api/v1/candles?symbol=005930&interval=1m&count=2&before=2026-09-16T00:02:00Z&adjusted=true"))
                .andRespond(withSuccess("""
                        {"result":{"candles":[
                          {"timestamp":"2026-09-16T09:02:00+09:00","openPrice":"72000","highPrice":"72200","lowPrice":"71900","closePrice":"72100","volume":"20","currency":"KRW"},
                          {"timestamp":"2026-09-16T09:01:00+09:00","openPrice":"71800","highPrice":"72100","lowPrice":"71700","closePrice":"72000","volume":"10","currency":"KRW"}],
                          "nextBefore":"2026-09-16T09:00:00+09:00"}}
                        """, MediaType.APPLICATION_JSON));

        TossMarketDataClient.TossCandlePage page = client.candles("005930",
                com.pricetrack.exchange.market.model.CandleInterval.ONE_MINUTE, 2,
                Instant.parse("2026-09-16T00:02:00Z"));

        assertThat(page.candles()).hasSize(2);
        assertThat(page.candles().get(0).startedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(page.candles().get(0).closed()).isTrue();
        assertThat(page.candles().get(1).startedAt()).isEqualTo(Instant.parse("2026-09-16T00:01:00Z"));
        assertThat(page.candles().get(1).closed()).isFalse();
        assertThat(page.nextBefore()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        server.verify();
    }

    @Test
    void rejectsInvalidTossCandleOhlc() {
        Fixture fixture = fixture();
        fixture.server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withSuccess("""
                        {"result":{"candles":[{"timestamp":"2026-09-15T09:01:00+09:00",
                        "openPrice":"72000","highPrice":"71000","lowPrice":"70000",
                        "closePrice":"72000","volume":"1","currency":"KRW"}]}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.candles("005930",
                com.pricetrack.exchange.market.model.CandleInterval.ONE_MINUTE, 1, Instant.now()))
                .isInstanceOf(TossApiException.class).hasMessageContaining("OHLCV");
    }

    @Test
    void retriesCandleRequestOnceWithNewTokenWhenServerRejectsToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossAuthClient authClient = mock(TossAuthClient.class);
        when(authClient.accessToken()).thenReturn("expired-token", "new-token");
        String uri = "https://openapi.test/api/v1/candles?symbol=005930&interval=1d&count=1&adjusted=true";
        server.expect(requestTo(uri))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andRespond(withUnauthorizedRequest());
        server.expect(requestTo(uri))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new-token"))
                .andRespond(withSuccess("""
                        {"result":{"candles":[{"timestamp":"2026-09-15T00:00:00+09:00",
                        "openPrice":"72000","highPrice":"72100","lowPrice":"71900",
                        "closePrice":"72050","volume":"10","currency":"KRW"}]}}
                        """, MediaType.APPLICATION_JSON));
        TossMarketDataClient client = new TossMarketDataClient(builder.build(), authClient);

        assertThat(client.candles("005930",
                com.pricetrack.exchange.market.model.CandleInterval.ONE_DAY, 1, null).candles())
                .hasSize(1);
        verify(authClient).invalidate("expired-token");
        server.verify();
    }

    @Test
    void loadsPreviousCloseAndTradingSessions() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://openapi.test/api/v1/market-calendar/KR?date=2026-09-15"))
                .andRespond(withSuccess("""
                        {"result":{"today":{"date":"2026-09-15","integrated":{
                          "preMarket":{"startTime":"2026-09-15T08:00:00+09:00","endTime":"2026-09-15T09:00:00+09:00"},
                          "regularMarket":{"startTime":"2026-09-15T09:00:00+09:00","endTime":"2026-09-15T15:30:00+09:00"},
                          "afterMarket":null}},"previousBusinessDay":{"date":"2026-09-14"},
                          "nextBusinessDay":{"date":"2026-09-16"}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://openapi.test/api/v1/candles?symbol=005930&interval=1d&count=10&adjusted=true"))
                .andRespond(withSuccess("""
                        {"result":{"candles":[
                          {"timestamp":"2026-09-15T00:00:00+09:00","closePrice":"72500","currency":"KRW"},
                          {"timestamp":"2026-09-14T00:00:00+09:00","closePrice":"71000","currency":"KRW"}]}}
                        """, MediaType.APPLICATION_JSON));

        TossMarketDataClient.TossMarketReference result =
                fixture.client.marketReference("005930", LocalDate.parse("2026-09-15"));

        assertThat(result.previousClose()).isEqualByComparingTo("71000");
        assertThat(result.isOpenAt(Instant.parse("2026-09-15T00:00:00Z"))).isTrue();
        assertThat(result.isOpenAt(Instant.parse("2026-09-15T06:30:00Z"))).isFalse();
        fixture.server.verify();
    }

    @Test
    void rejectsReferenceDataWithoutPreviousBusinessDayCandle() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://openapi.test/api/v1/market-calendar/KR?date=2026-09-15"))
                .andRespond(withSuccess("""
                        {"result":{"today":{"date":"2026-09-15","integrated":null},
                        "previousBusinessDay":{"date":"2026-09-14"},"nextBusinessDay":{"date":"2026-09-16"}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://openapi.test/api/v1/candles?symbol=005930&interval=1d&count=10&adjusted=true"))
                .andRespond(withSuccess("{" + "\"result\":{\"candles\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.marketReference("005930", LocalDate.parse("2026-09-15")))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("Previous business-day close");
    }
    @Test
    void convertsSamsungPriceResponse() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://openapi.test/api/v1/prices?symbols=005930"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withSuccess("""
                        {"result":[{"symbol":"005930","timestamp":"2026-09-14T09:30:00.123+09:00",
                        "lastPrice":"72000","currency":"KRW"}]}
                        """, MediaType.APPLICATION_JSON));

        TossMarketDataClient.TossPrice result = fixture.client.currentPrice("005930");

        assertThat(result.symbol()).isEqualTo("005930");
        assertThat(result.price()).isEqualByComparingTo("72000");
        assertThat(result.observedAt()).isEqualTo(Instant.parse("2026-09-14T00:30:00.123Z"));
        fixture.server.verify();
    }

    @Test
    void retriesOnceWithNewTokenWhenServerRejectsToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossAuthClient authClient = mock(TossAuthClient.class);
        when(authClient.accessToken()).thenReturn("expired-token", "new-token");
        server.expect(requestTo("https://openapi.test/api/v1/prices?symbols=005930"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andRespond(withUnauthorizedRequest());
        server.expect(requestTo("https://openapi.test/api/v1/prices?symbols=005930"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new-token"))
                .andRespond(withSuccess("""
                        {"result":[{"symbol":"005930","timestamp":"2026-09-14T09:30:00+09:00",
                        "lastPrice":"72000","currency":"KRW"}]}
                        """, MediaType.APPLICATION_JSON));
        TossMarketDataClient client = new TossMarketDataClient(builder.build(), authClient);

        assertThat(client.currentPrice("005930").price()).isEqualByComparingTo("72000");
        verify(authClient).invalidate("expired-token");
        server.verify();
    }

    @Test
    void rejectsNonPositivePrice() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://openapi.test/api/v1/prices?symbols=005930"))
                .andRespond(withSuccess("""
                        {"result":[{"symbol":"005930","timestamp":"2026-09-14T09:30:00+09:00",
                        "lastPrice":"0","currency":"KRW"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.currentPrice("005930"))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("올바르지 않습니다");
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossAuthClient authClient = mock(TossAuthClient.class);
        when(authClient.accessToken()).thenReturn("token");
        return new Fixture(new TossMarketDataClient(builder.build(), authClient), server);
    }

    private record Fixture(TossMarketDataClient client, MockRestServiceServer server) {}
}
