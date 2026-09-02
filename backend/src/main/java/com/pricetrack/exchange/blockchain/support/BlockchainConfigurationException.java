package com.pricetrack.exchange.blockchain.support;

public class BlockchainConfigurationException extends RuntimeException {
    public BlockchainConfigurationException(String message) { super(message); }
    public BlockchainConfigurationException(String message, Throwable cause) { super(message, cause); }
}
