package com.pricetrack.exchange.blockchain.oracle;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.config.BlockchainPriceSyncProperties;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.support.OperatorNotReadyException;
import com.pricetrack.exchange.blockchain.support.PriceUnits;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionStatus;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

import java.math.BigInteger;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pricetrack.exchange.market.PriceSimulator;

/**
 * 가격 시뮬레이터의 최신 값을 운영자 지갑으로 PriceOracle에 동기화한다.
 *
 * <p>처리 중인 가격 갱신이 있으면 새 트랜잭션을 쌓지 않는다. 이전 거래가
 * 끝난 뒤 그 시점의 최신 가격 하나만 보내 중간 가격을 합치고 nonce 적체를 막는다.</p>
 *
 * <p>운영자가 Oracle owner인지 확인하고 온체인 가격과 목표 가격이 다를 때만
 * updatePrice를 제출한다.</p>
 */
@Service
public class BlockchainPriceSyncService {
    private static final Logger log = LoggerFactory.getLogger(BlockchainPriceSyncService.class);
    private static final List<BlockchainTransactionStatus> BLOCKING_STATUSES = List.of(
            BlockchainTransactionStatus.SIGNED,
            BlockchainTransactionStatus.SUBMITTED,
            BlockchainTransactionStatus.REVIEW_REQUIRED);

    private final BlockchainProperties blockchainProperties;
    private final BlockchainPriceSyncProperties syncProperties;
    private final BlockchainTransactionRepository transactionRepository;
    private final BlockchainService blockchainService;
    private final BlockchainTransactionSender transactionSender;
    private final PriceSimulator priceSimulator;

    public BlockchainPriceSyncService(BlockchainProperties blockchainProperties,
            BlockchainPriceSyncProperties syncProperties,
            BlockchainTransactionRepository transactionRepository,
            BlockchainService blockchainService, BlockchainTransactionSender transactionSender,
            PriceSimulator priceSimulator) {
        this.blockchainProperties = blockchainProperties;
        this.syncProperties = syncProperties;
        this.transactionRepository = transactionRepository;
        this.blockchainService = blockchainService;
        this.transactionSender = transactionSender;
        this.priceSimulator = priceSimulator;
    }

    @Scheduled(fixedDelayString = "${app.blockchain.price-sync.interval-ms:3000}",
            initialDelayString = "${app.blockchain.price-sync.initial-delay-ms:3000}")
    public synchronized void synchronizeLatestPrice() {
        if (!blockchainProperties.enabled() || !syncProperties.enabled()) return;
        try {
            if (transactionRepository.existsByTypeAndStatusIn(
                    BlockchainTransactionType.UPDATE_PRICE, BLOCKING_STATUSES)) return;
            String operator = blockchainService.operatorAddress();
            String owner = blockchainService.oracleOwner();
            if (!operator.equalsIgnoreCase(owner)) {
                throw new OperatorNotReadyException("운영자 주소가 PriceOracle owner가 아닙니다.");
            }
            BigInteger targetPriceE8 = PriceUnits.toPriceE8(priceSimulator.getCurrentPrice());
            if (blockchainService.oraclePrice().priceE8().equals(targetPriceE8)) return;
            transactionSender.submitSystem(BlockchainTransactionType.UPDATE_PRICE,
                    blockchainService.oracleAddress(), blockchainService.encodeUpdatePrice(targetPriceE8),
                    targetPriceE8);
        } catch (RuntimeException exception) {
            log.warn("Latest simulated price could not be submitted to PriceOracle", exception);
        }
    }
}
