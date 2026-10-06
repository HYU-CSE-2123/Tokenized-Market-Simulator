package com.pricetrack.exchange.market.provider.simulated;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.pricetrack.exchange.market.provider.toss.*;
import static org.assertj.core.api.Assertions.*;

class SimulatedMarketConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SimulatedMarketConfiguration.class, TossPriceConfiguration.class)
            .withPropertyValues("spring.profiles.active=public");
    @Test void publicModeCreatesNoTossClientEvenWithCredentials() {
        runner.withPropertyValues("app.price.provider=simulated", "app.price.toss.client-id=unused",
                "app.price.toss.client-secret=unused").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(TossAuthClient.class);
            assertThat(context).doesNotHaveBean(TossRealtimeClient.class);
            assertThat(context).doesNotHaveBean(TossMarketDataClient.class);
        });
    }
    @Test void publicModeRejectsTossBeforeClientCreation() {
        runner.withPropertyValues("app.price.provider=toss").run(context ->
                assertThat(context).hasFailed().getFailure().hasMessage(
                        "public profile requires simulated market; Toss is disabled"));
    }
}
