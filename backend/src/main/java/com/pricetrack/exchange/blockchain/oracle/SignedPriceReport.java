package com.pricetrack.exchange.blockchain.oracle;

import java.math.BigInteger;

/** Vault 호출에 함께 전달할 가격 보고서와 65바이트 ECDSA 서명이다. */
public record SignedPriceReport(PriceReport report, String signature, BigInteger fee) {}
