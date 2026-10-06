package com.pricetrack.exchange.market.provider.simulated;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class SimulatedMarketConfiguration {
    /** Checks public mode before any Toss HTTP/WS client can be instantiated. */
    @Bean @Profile("public")
    static BeanFactoryPostProcessor publicMarketGuard(Environment environment) {
        return factory -> {
            if (!"simulated".equals(environment.getProperty("app.price.provider", "simulated")))
                throw new IllegalStateException("public profile requires simulated market; Toss is disabled");
        };
    }
}
