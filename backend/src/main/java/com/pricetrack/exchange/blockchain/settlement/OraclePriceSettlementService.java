package com.pricetrack.exchange.blockchain.settlement;

import com.pricetrack.exchange.blockchain.contract.ContractEventParser.PriceUpdatedEvent;
import com.pricetrack.exchange.blockchain.support.PriceUnits;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransaction;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionStatus;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.market.PriceSimulator;
import com.pricetrack.exchange.market.PriceTick;
import com.pricetrack.exchange.market.PriceTickRepository;

/**
 * 확정된 PriceUpdated 이벤트를 가격 이력과 트랜잭션 상태에 반영한다.
 * 저장된 목표 가격과 이벤트 가격을 다시 비교하며, blockchain transaction당
 * 하나의 price tick만 허용해 반복 reconciliation에도 멱등성을 보장한다.
 */
@Service
public class OraclePriceSettlementService {
    private final BlockchainTransactionRepository transactionRepository;
    private final PriceTickRepository priceTickRepository;

    public OraclePriceSettlementService(BlockchainTransactionRepository transactionRepository,
            PriceTickRepository priceTickRepository) {
        this.transactionRepository = transactionRepository;
        this.priceTickRepository = priceTickRepository;
    }

    @Transactional
    public void confirm(Long transactionId, PriceUpdatedEvent event, long blockNumber) {
        BlockchainTransaction transaction = transactionForUpdate(transactionId);
        if (transaction.getStatus() == BlockchainTransactionStatus.CONFIRMED) return;
        requirePendingUpdate(transaction);
        if (!event.priceE8().equals(transaction.getTargetValue())) {
            throw new OnchainSettlementService.SettlementConsistencyException(
                    "오라클 이벤트 가격과 저장된 목표 가격이 다릅니다.");
        }
        if (!priceTickRepository.existsByBlockchainTransactionId(transactionId)) {
            PriceTick tick = new PriceTick();
            tick.setBlockchainTransactionId(transactionId);
            tick.setSymbol(PriceSimulator.SYMBOL);
            tick.setPrice(PriceUnits.fromPriceE8(event.priceE8()));
            tick.setSource("ONCHAIN_ORACLE");
            priceTickRepository.save(tick);
        }
        transaction.setStatus(BlockchainTransactionStatus.CONFIRMED);
        transaction.setBlockNumber(blockNumber);
        transaction.setConfirmedAt(Instant.now());
        transaction.setErrorMessage(null);
    }

    @Transactional
    public void fail(Long transactionId, long blockNumber, String reason) {
        BlockchainTransaction transaction = transactionForUpdate(transactionId);
        if (transaction.getStatus() == BlockchainTransactionStatus.FAILED) return;
        requirePendingUpdate(transaction);
        transaction.setStatus(BlockchainTransactionStatus.FAILED);
        transaction.setBlockNumber(blockNumber);
        transaction.setConfirmedAt(Instant.now());
        transaction.setErrorMessage(reason);
    }

    private void requirePendingUpdate(BlockchainTransaction transaction) {
        if (transaction.getType() != BlockchainTransactionType.UPDATE_PRICE
                || transaction.getStatus() != BlockchainTransactionStatus.SUBMITTED) {
            throw new OnchainSettlementService.SettlementConsistencyException(
                    "정산 가능한 UPDATE_PRICE 상태가 아닙니다.");
        }
    }

    private BlockchainTransaction transactionForUpdate(Long id) {
        return transactionRepository.findForUpdate(id)
                .orElseThrow(() -> new OnchainSettlementService.SettlementConsistencyException(
                        "오라클 트랜잭션을 찾을 수 없습니다."));
    }
}
