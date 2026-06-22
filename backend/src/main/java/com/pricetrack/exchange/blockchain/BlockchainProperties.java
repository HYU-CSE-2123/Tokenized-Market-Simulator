package com.pricetrack.exchange.blockchain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** web3j 연동 설정 (기획서 §16 Phase 3 — application.yml 의 app.blockchain.*). */
@ConfigurationProperties(prefix = "app.blockchain")
public record BlockchainProperties(
        String rpcUrl,
        String mockKrwAddress,
        String mSecAddress,
        String priceOracleAddress,
        String exchangeVaultAddress,
        String operatorPrivateKey) {
}
