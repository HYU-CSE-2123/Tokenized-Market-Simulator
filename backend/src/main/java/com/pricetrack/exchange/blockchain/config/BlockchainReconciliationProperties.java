package com.pricetrack.exchange.blockchain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.blockchain.reconciliation")
public record BlockchainReconciliationProperties(
        long pollIntervalMs,
        long initialDelayMs,
        int requiredConfirmations) {

    public BlockchainReconciliationProperties {
        if (pollIntervalMs <= 0) pollIntervalMs = 1_000;
        if (initialDelayMs < 0) initialDelayMs = 1_000;
        if (requiredConfirmations <= 0) requiredConfirmations = 1;
    }
}
