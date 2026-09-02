package com.pricetrack.exchange.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import com.pricetrack.exchange.blockchain.ContractEventParser.EventValidationException;
import com.pricetrack.exchange.blockchain.ContractEventParser.SettlementEvent;
import com.pricetrack.exchange.blockchain.OnchainSettlementService.SettlementConsistencyException;
import com.pricetrack.exchange.order.OrderRepository;

@Service
public class BlockchainReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(BlockchainReconciliationService.class);

    private final BlockchainProperties properties;
    private final BlockchainReconciliationProperties reconciliationProperties;
    private final BlockchainTransactionRepository transactionRepository;
    private final BlockchainTransactionSender transactionSender;
    private final OrderRepository orderRepository;
    private final ContractEventParser eventParser;
    private final OnchainSettlementService settlementService;
    private final OraclePriceSettlementService oraclePriceSettlementService;
    private final Web3j web3j;

    public BlockchainReconciliationService(BlockchainProperties properties,
            BlockchainReconciliationProperties reconciliationProperties,
            BlockchainTransactionRepository transactionRepository,
            BlockchainTransactionSender transactionSender, OrderRepository orderRepository,
            ContractEventParser eventParser,
            OnchainSettlementService settlementService,
            OraclePriceSettlementService oraclePriceSettlementService, Web3j web3j) {
        this.properties = properties;
        this.reconciliationProperties = reconciliationProperties;
        this.transactionRepository = transactionRepository;
        this.transactionSender = transactionSender;
        this.orderRepository = orderRepository;
        this.eventParser = eventParser;
        this.settlementService = settlementService;
        this.oraclePriceSettlementService = oraclePriceSettlementService;
        this.web3j = web3j;
    }

    @Scheduled(fixedDelayString = "${app.blockchain.reconciliation.poll-interval-ms:1000}",
            initialDelayString = "${app.blockchain.reconciliation.initial-delay-ms:1000}")
    public void reconcilePendingTransactions() {
        if (!properties.enabled()) return;
        List<BlockchainTransaction> pending = transactionRepository.findAllByStatusInOrderByCreatedAtAsc(
                List.of(BlockchainTransactionStatus.SIGNED, BlockchainTransactionStatus.SUBMITTED));
        for (BlockchainTransaction transaction : pending) {
            try {
                if (transaction.getStatus() == BlockchainTransactionStatus.SIGNED) {
                    transactionSender.recoverSigned(transaction);
                } else {
                    reconcileSubmitted(transaction);
                }
            } catch (EventValidationException | SettlementConsistencyException exception) {
                log.error("Onchain settlement requires review: txHash={}", transaction.getTxHash(), exception);
                settlementService.markReviewRequired(transaction.getId(), exception.getMessage());
            } catch (RuntimeException exception) {
                log.warn("Onchain reconciliation will retry: txHash={}", transaction.getTxHash(), exception);
            }
        }
    }

    void reconcileSubmitted(BlockchainTransaction transaction) {
        try {
            var response = web3j.ethGetTransactionReceipt(transaction.getTxHash()).send();
            if (response.hasError()) {
                throw new BlockchainConfigurationException(
                        "receipt 조회 실패: " + response.getError().getMessage());
            }
            if (response.getTransactionReceipt().isEmpty()) return;
            TransactionReceipt receipt = response.getTransactionReceipt().get();
            if (!hasRequiredConfirmations(receipt)) return;

            long blockNumber = receipt.getBlockNumber().longValueExact();
            if (!receipt.isStatusOK()) {
                if (transaction.getType() == BlockchainTransactionType.UPDATE_PRICE) {
                    oraclePriceSettlementService.fail(transaction.getId(), blockNumber,
                            "온체인 receipt status가 실패입니다: " + receipt.getStatus());
                } else {
                    settlementService.settleFailure(transaction.getId(), blockNumber,
                            "온체인 receipt status가 실패입니다: " + receipt.getStatus());
                }
                return;
            }
            if (transaction.getType() == BlockchainTransactionType.UPDATE_PRICE) {
                var event = eventParser.parsePriceUpdated(receipt, properties.priceOracleAddress(),
                        requiredTargetValue(transaction));
                oraclePriceSettlementService.confirm(transaction.getId(), event, blockNumber);
                return;
            }
            SettlementEvent event = eventParser.parse(receipt, transaction.getType(),
                    properties.exchangeVaultAddress(), transaction.getSenderAddress(),
                    expectedInput(transaction));
            settlementService.settleSuccess(transaction.getId(), event, blockNumber);
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("receipt polling RPC 호출에 실패했습니다.", exception);
        } catch (ArithmeticException exception) {
            throw new BlockchainConfigurationException("블록 번호 범위가 너무 큽니다.", exception);
        }
    }

    private boolean hasRequiredConfirmations(TransactionReceipt receipt) throws IOException {
        BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
        BigInteger confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
        return confirmations.compareTo(BigInteger.valueOf(reconciliationProperties.requiredConfirmations())) >= 0;
    }

    private BigInteger expectedInput(BlockchainTransaction transaction) {
        if (transaction.getOrderId() == null) {
            throw new SettlementConsistencyException("트랜잭션에 연결된 주문이 없습니다.");
        }
        return orderRepository.findById(transaction.getOrderId())
                .map(order -> TokenUnits.toWei(order.getInputAmount()))
                .orElseThrow(() -> new SettlementConsistencyException("주문 입력 수량을 찾을 수 없습니다."));
    }

    private BigInteger requiredTargetValue(BlockchainTransaction transaction) {
        if (transaction.getTargetValue() == null) {
            throw new SettlementConsistencyException("UPDATE_PRICE 목표 가격이 없습니다.");
        }
        return transaction.getTargetValue();
    }
}
