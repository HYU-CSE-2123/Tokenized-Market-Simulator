package com.pricetrack.exchange.blockchain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 거래 전송 키와 분리된 EIP-712 가격 보고서 서명 키 설정이다. */
@ConfigurationProperties(prefix = "app.blockchain.price-report")
public record PriceReportProperties(boolean enabled, String signerPrivateKey) {}
