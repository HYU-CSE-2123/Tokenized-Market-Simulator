package com.pricetrack.exchange.blockchain.oracle;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.config.BlockchainPriceSyncProperties;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionRepository;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionSender;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionStatus;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;

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

import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import java.time.Instant;

/** 최신 가격 제출과 처리 중 갱신을 건너뛰는 coalescing 정책을 검증한다. */
class BlockchainPriceSyncServiceTest {
    @Test
    void submitsLatestPriceWhenNoUpdateIsInFlight() {
        Fixture fixture = fixture(false, true);

        fixture.service.synchronizeLatestPrice();

        verify(fixture.sender).submitSystem(eq(BlockchainTransactionType.UPDATE_PRICE),
                eq(fixture.oracle), eq("0xencoded"), eq(new BigInteger("7520000000000")));
    }

    @Test
    void coalescesBySkippingWhileUpdateIsInFlight() {
        Fixture fixture = fixture(true, true);

        fixture.service.synchronizeLatestPrice();

        verify(fixture.sender, never()).submitSystem(any(), any(), any(), any());
    }

    @Test
    void skipsOracleSubmissionWhenMarketSettlementIsNotAllowed() {
        Fixture fixture = fixture(false, false);

        fixture.service.synchronizeLatestPrice();

        verify(fixture.sender, never()).submitSystem(any(), any(), any(), any());
        verify(fixture.repository, never()).existsByTypeAndStatusIn(any(), anyList());
    }

    private Fixture fixture(boolean inFlight, boolean settlementAllowed) {
        BlockchainProperties blockchainProperties = new BlockchainProperties(true, "rpc", "", "", "", "", "key");
        BlockchainPriceSyncProperties syncProperties = new BlockchainPriceSyncProperties(true, 3000, 3000);
        BlockchainTransactionRepository repository = mock(BlockchainTransactionRepository.class);
        BlockchainService blockchainService = mock(BlockchainService.class);
        BlockchainTransactionSender sender = mock(BlockchainTransactionSender.class);
        MarketPriceService marketPriceService = mock(MarketPriceService.class);
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
        MarketPriceSnapshot snapshot = new MarketPriceSnapshot("mSEC", new BigDecimal("75200"),
                new BigDecimal("75000"), new BigDecimal("200"), new BigDecimal("0.26666667"),
                MarketStatus.OPEN, PriceStatus.LIVE, "TOSS", Instant.EPOCH);
        when(marketPriceService.current()).thenReturn(snapshot);
        when(marketPriceService.isSettlementAllowed(snapshot)).thenReturn(settlementAllowed);
        BlockchainPriceSyncService service = new BlockchainPriceSyncService(blockchainProperties,
                syncProperties, repository, blockchainService, sender, marketPriceService);
        return new Fixture(service, sender, repository, oracle);
    }

    private record Fixture(BlockchainPriceSyncService service,
            BlockchainTransactionSender sender, BlockchainTransactionRepository repository,
            String oracle) {}
}
