package com.pricetrack.exchange.blockchain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.blockchain.price-sync")
public record BlockchainPriceSyncProperties(boolean enabled, long intervalMs, long initialDelayMs) {
    public BlockchainPriceSyncProperties {
        if (intervalMs <= 0) intervalMs = 3_000;
        if (initialDelayMs < 0) initialDelayMs = 3_000;
    }
}
