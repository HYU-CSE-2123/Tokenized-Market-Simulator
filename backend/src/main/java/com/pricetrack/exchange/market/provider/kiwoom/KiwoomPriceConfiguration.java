package com.pricetrack.exchange.market.provider.kiwoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.price.provider",havingValue="kiwoom")
@EnableConfigurationProperties(KiwoomPriceProperties.class)
public class KiwoomPriceConfiguration {
    @Bean KiwoomRestClient kiwoomRestClient(KiwoomPriceProperties p) {
        p.validate();var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.connectTimeout());factory.setReadTimeout(p.readTimeout());
        return new KiwoomRestClient(RestClient.builder().baseUrl(p.baseUrl()).requestFactory(factory).build(),p,Clock.systemUTC());
    }
    @Bean KiwoomProtocol kiwoomProtocol(ObjectMapper mapper){return new KiwoomProtocol(mapper,Clock.systemUTC());}
    @Bean KiwoomRealtimeClient kiwoomRealtimeClient(KiwoomRestClient rest,KiwoomProtocol protocol,KiwoomPriceProperties p) {
        return new KiwoomRealtimeClient(HttpClient.newBuilder().connectTimeout(p.connectTimeout()).build(),rest,protocol,p);
    }
    @Bean KiwoomPriceProvider kiwoomPriceProvider(KiwoomRestClient rest,KiwoomRealtimeClient realtime,
            KiwoomProtocol protocol,KiwoomPriceProperties p,MarketWebSocketPublisher publisher) {
        return new KiwoomPriceProvider(rest,realtime,protocol,p,publisher,Clock.systemUTC());
    }
    @Bean KiwoomCandleProvider kiwoomCandleProvider(KiwoomRestClient rest){return new KiwoomCandleProvider(rest,Clock.systemUTC());}
}
