package com.pricetrack.exchange.blockchain.support;

/** RPC, 주소, 개인키 또는 ABI 응답이 온체인 작업을 안전하게 계속할 수 없을 때 사용한다. */
public class BlockchainConfigurationException extends RuntimeException {
    public BlockchainConfigurationException(String message) { super(message); }
    public BlockchainConfigurationException(String message, Throwable cause) { super(message, cause); }
}
