package com.pricetrack.exchange.blockchain;

import java.io.IOException;
import java.math.BigInteger;

import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

/** 단일 운영자 지갑의 nonce 충돌을 막으며 서명 트랜잭션을 저장 후 전송한다. */
@Service
public class BlockchainTransactionSender {
    private static final BigInteger GAS_BUFFER_PERCENT = BigInteger.valueOf(120);
    private static final BigInteger PERCENT = BigInteger.valueOf(100);

    private final BlockchainProperties properties;
    private final Web3j web3j;
    private final BlockchainTransactionPersistence persistence;

    public BlockchainTransactionSender(BlockchainProperties properties, Web3j web3j,
            BlockchainTransactionPersistence persistence) {
        this.properties = properties;
        this.web3j = web3j;
        this.persistence = persistence;
    }

    public synchronized Submission submit(Long orderId, BlockchainTransactionType type,
            String destination, String encodedFunction) {
        Credentials credentials = credentials();
        String sender = credentials.getAddress();
        try {
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            BigInteger nonce = web3j.ethGetTransactionCount(sender, DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger gasLimit = estimateGas(sender, nonce, gasPrice, destination, encodedFunction);

            RawTransaction raw = RawTransaction.createTransaction(nonce, gasPrice, gasLimit,
                    destination, BigInteger.ZERO, encodedFunction);
            byte[] signed = TransactionEncoder.signMessage(raw, chainId.longValueExact(), credentials);
            String rawHex = Numeric.toHexString(signed);
            String expectedHash = Numeric.toHexString(Hash.sha3(signed));

            persistence.saveSigned(orderId, type, sender, nonce.longValueExact(), rawHex, expectedHash);
            EthSendTransaction response = web3j.ethSendRawTransaction(rawHex).send();
            if (response.hasError()) {
                throw new BlockchainConfigurationException(
                        "온체인 트랜잭션 전송이 거부되었습니다: " + response.getError().getMessage());
            }
            String rpcHash = response.getTransactionHash();
            if (!expectedHash.equalsIgnoreCase(rpcHash)) {
                throw new BlockchainConfigurationException("서명 시 계산한 txHash와 RPC 응답이 일치하지 않습니다.");
            }
            persistence.markSubmitted(orderId, rpcHash);
            return new Submission(rpcHash, nonce);
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("온체인 트랜잭션 RPC 호출에 실패했습니다.", exception);
        } catch (ArithmeticException exception) {
            throw new BlockchainConfigurationException("chain ID 또는 nonce 범위가 너무 큽니다.", exception);
        }
    }

    public synchronized void recoverSigned(BlockchainTransaction transaction) {
        if (transaction.getStatus() != BlockchainTransactionStatus.SIGNED) return;
        if (transaction.getTxHash() == null || transaction.getRawTransaction() == null) {
            throw new BlockchainConfigurationException("SIGNED 트랜잭션에 txHash 또는 서명 원문이 없습니다.");
        }
        try {
            var lookup = web3j.ethGetTransactionByHash(transaction.getTxHash()).send();
            if (lookup.hasError()) {
                throw new BlockchainConfigurationException(
                        "트랜잭션 조회 실패: " + lookup.getError().getMessage());
            }
            if (lookup.getTransaction().isEmpty()) {
                EthSendTransaction response = web3j.ethSendRawTransaction(transaction.getRawTransaction()).send();
                if (response.hasError() && !isAlreadyKnown(response.getError().getMessage())) {
                    throw new BlockchainConfigurationException(
                            "서명 트랜잭션 재전송 실패: " + response.getError().getMessage());
                }
                if (!response.hasError()
                        && !transaction.getTxHash().equalsIgnoreCase(response.getTransactionHash())) {
                    throw new BlockchainConfigurationException("재전송 txHash가 저장값과 일치하지 않습니다.");
                }
            }
            persistence.markSubmitted(transaction.getOrderId(), transaction.getTxHash());
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("SIGNED 트랜잭션 복구 RPC 호출에 실패했습니다.", exception);
        }
    }

    private BigInteger estimateGas(String sender, BigInteger nonce, BigInteger gasPrice,
            String destination, String encodedFunction) throws IOException {
        Transaction call = Transaction.createFunctionCallTransaction(sender, nonce, gasPrice, null,
                destination, BigInteger.ZERO, encodedFunction);
        EthEstimateGas response = web3j.ethEstimateGas(call).send();
        if (response.hasError()) {
            throw new OperatorNotReadyException(
                    "온체인 실행 예상 단계에서 거부되었습니다: " + response.getError().getMessage());
        }
        return response.getAmountUsed().multiply(GAS_BUFFER_PERCENT).divide(PERCENT);
    }

    private Credentials credentials() {
        try {
            return Credentials.create(properties.operatorPrivateKey());
        } catch (RuntimeException exception) {
            throw new BlockchainConfigurationException("OPERATOR_PRIVATE_KEY 형식이 올바르지 않습니다.", exception);
        }
    }

    private boolean isAlreadyKnown(String message) {
        return message != null && message.toLowerCase().contains("already known");
    }

    public record Submission(String txHash, BigInteger nonce) {}
}
