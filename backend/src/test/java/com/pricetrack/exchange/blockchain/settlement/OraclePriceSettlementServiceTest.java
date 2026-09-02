package com.pricetrack.exchange.blockchain.settlement;

import com.pricetrack.exchange.blockchain.contract.ContractEventParser.PriceUpdatedEvent;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransaction;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionStatus;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pricetrack.exchange.market.PriceTickRepository;

@SpringBootTest
class OraclePriceSettlementServiceTest {
    @Autowired OraclePriceSettlementService settlementService;
    @Autowired BlockchainTransactionRepository transactionRepository;
    @Autowired PriceTickRepository priceTickRepository;

    @Test
    void confirmationIsIdempotentAndStoresOneOnchainTick() {
        BigInteger target = new BigInteger("7520000000000");
        BlockchainTransaction transaction = new BlockchainTransaction();
        transaction.setType(BlockchainTransactionType.UPDATE_PRICE);
        transaction.setStatus(BlockchainTransactionStatus.SUBMITTED);
        transaction.setSenderAddress("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
        transaction.setNonce(810_001L);
        transaction.setTxHash("0x" + String.format("%064x", 810_001));
        transaction.setTargetValue(target);
        transactionRepository.save(transaction);

        PriceUpdatedEvent event = new PriceUpdatedEvent(target, BigInteger.valueOf(12345));
        settlementService.confirm(transaction.getId(), event, 25L);
        settlementService.confirm(transaction.getId(), event, 25L);

        BlockchainTransaction confirmed = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(BlockchainTransactionStatus.CONFIRMED);
        assertThat(priceTickRepository.findAll().stream()
                .filter(tick -> transaction.getId().equals(tick.getBlockchainTransactionId())).count())
                .isEqualTo(1);
        assertThat(priceTickRepository.findAll().stream()
                .filter(tick -> transaction.getId().equals(tick.getBlockchainTransactionId()))
                .findFirst().orElseThrow().getPrice()).isEqualByComparingTo("75200");
    }
}
