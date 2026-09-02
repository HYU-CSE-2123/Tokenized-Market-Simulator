package com.pricetrack.exchange.blockchain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.market.PriceSimulator;

class BlockchainPriceSyncServiceTest {
    @Test
    void submitsLatestPriceWhenNoUpdateIsInFlight() {
        Fixture fixture = fixture(false);

        fixture.service.synchronizeLatestPrice();

        verify(fixture.sender).submitSystem(eq(BlockchainTransactionType.UPDATE_PRICE),
                eq(fixture.oracle), eq("0xencoded"), eq(new BigInteger("7520000000000")));
    }

    @Test
    void coalescesBySkippingWhileUpdateIsInFlight() {
        Fixture fixture = fixture(true);

        fixture.service.synchronizeLatestPrice();

        verify(fixture.sender, never()).submitSystem(any(), any(), any(), any());
    }

    private Fixture fixture(boolean inFlight) {
        BlockchainProperties blockchainProperties = new BlockchainProperties(true, "rpc", "", "", "", "", "key");
        BlockchainPriceSyncProperties syncProperties = new BlockchainPriceSyncProperties(true, 3000, 3000);
        BlockchainTransactionRepository repository = mock(BlockchainTransactionRepository.class);
        BlockchainService blockchainService = mock(BlockchainService.class);
        BlockchainTransactionSender sender = mock(BlockchainTransactionSender.class);
        PriceSimulator simulator = mock(PriceSimulator.class);
        String operator = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String oracle = "0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0";
        when(repository.existsByTypeAndStatusIn(eq(BlockchainTransactionType.UPDATE_PRICE), anyList()))
                .thenReturn(inFlight);
        when(blockchainService.operatorAddress()).thenReturn(operator);
        when(blockchainService.oracleOwner()).thenReturn(operator);
        when(blockchainService.oracleAddress()).thenReturn(oracle);
        when(blockchainService.oraclePrice()).thenReturn(
                new ContractGateway.OraclePrice(new BigInteger("7500000000000"), BigInteger.ONE));
        when(blockchainService.encodeUpdatePrice(any())).thenReturn("0xencoded");
        when(simulator.getCurrentPrice()).thenReturn(new BigDecimal("75200"));
        BlockchainPriceSyncService service = new BlockchainPriceSyncService(blockchainProperties,
                syncProperties, repository, blockchainService, sender, simulator);
        return new Fixture(service, sender, oracle);
    }

    private record Fixture(BlockchainPriceSyncService service,
            BlockchainTransactionSender sender, String oracle) {}
}
