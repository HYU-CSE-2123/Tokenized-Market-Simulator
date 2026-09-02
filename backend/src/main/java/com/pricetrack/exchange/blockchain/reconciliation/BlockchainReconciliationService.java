package com.pricetrack.exchange.blockchain.reconciliation;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.config.BlockchainReconciliationProperties;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.settlement.OnchainSettlementService;
import com.pricetrack.exchange.blockchain.settlement.OraclePriceSettlementService;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransaction;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionStatus;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import com.pricetrack.exchange.blockchain.contract.ContractEventParser.EventValidationException;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser.SettlementEvent;
import com.pricetrack.exchange.blockchain.settlement.OnchainSettlementService.SettlementConsistencyException;
import com.pricetrack.exchange.order.OrderRepository;

/**
 * DB에 남은 미완료 트랜잭션과 체인의 실제 상태를 주기적으로 일치시킨다.
 *
 * <p>{@code SIGNED}는 체인에 존재하는지 확인한 뒤 기존 raw transaction을
 * 재전송할 수 있고, {@code SUBMITTED}는 요구 confirmation을 충족한 receipt를
 * 이벤트 검증 및 정산 서비스로 전달한다.</p>
 *
 * <p>receipt 성공만으로 자산을 확정하지 않는다. 예상한 컨트랙트 이벤트와
 * 저장된 주문 정보가 일치해야 자동 정산하며, 불일치는 REVIEW_REQUIRED로 격리한다.</p>
 */
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

    /**
     * 생성 시각 순으로 미완료 거래를 처리한다.
     * 설정 오류나 일시적인 RPC 장애는 다음 주기에 재시도하지만, 이벤트·정산 불일치는
     * 반복 자동 처리하지 않도록 REVIEW_REQUIRED로 격리한다.
     */
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
            // receipt가 없거나 confirmation이 부족한 것은 실패가 아니라 아직 대기 중인 상태다.
            if (response.getTransactionReceipt().isEmpty()) return;
            TransactionReceipt receipt = response.getTransactionReceipt().get();
            if (!hasRequiredConfirmations(receipt)) return;

            long blockNumber = receipt.getBlockNumber().longValueExact();
            // EVM 실행 실패에는 신뢰할 이벤트 결과가 없으므로 성공 정산 경로로 들어가지 않는다.
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
            // 시스템 가격 갱신과 사용자 주문은 연결된 DB 원장이 달라 별도 정산 서비스로 보낸다.
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
