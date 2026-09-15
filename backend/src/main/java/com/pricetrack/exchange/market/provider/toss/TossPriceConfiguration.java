package com.pricetrack.exchange.market.provider.toss;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 실제 가격 모드에서만 토스증권 REST 클라이언트와 공급자를 등록한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.price.provider", havingValue = "toss")
@EnableConfigurationProperties(TossPriceProperties.class)
public class TossPriceConfiguration {

    @Bean
    RestClient tossRestClient(TossPriceProperties properties) {
        properties.validate();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    TossAuthClient tossAuthClient(RestClient tossRestClient, TossPriceProperties properties) {
        return new TossAuthClient(tossRestClient, properties);
    }

    @Bean
    TossMarketDataClient tossMarketDataClient(RestClient tossRestClient, TossAuthClient authClient) {
        return new TossMarketDataClient(tossRestClient, authClient);
    }

    @Bean
    TossPriceProvider tossPriceProvider(TossMarketDataClient marketDataClient,
            TossPriceProperties properties) {
        return new TossPriceProvider(marketDataClient, properties);
    }
}
