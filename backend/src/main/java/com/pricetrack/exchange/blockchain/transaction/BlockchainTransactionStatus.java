package com.pricetrack.exchange.blockchain.transaction;

public enum BlockchainTransactionStatus {
    CREATED,
    SIGNED,
    SUBMITTED,
    CONFIRMED,
    FAILED,
    REVIEW_REQUIRED
}
