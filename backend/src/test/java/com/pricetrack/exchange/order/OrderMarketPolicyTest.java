package com.pricetrack.exchange.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.market.MarketClosedException;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.StalePriceException;
import com.pricetrack.exchange.quote.TradeCalculator;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.wallet.WalletService;
import com.pricetrack.exchange.websocket.publisher.MarketWebSocketPublisher;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;

class OrderMarketPolicyTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final TradeRepository trades = mock(TradeRepository.class);
    private final WalletService wallet = mock(WalletService.class);
    private final MarketPriceService prices = mock(MarketPriceService.class);
    private final OnchainOrderService onchain = mock(OnchainOrderService.class);
    private final OrderService service = new OrderService(orders, trades, wallet, prices,
            mock(TradeCalculator.class), mock(BlockchainProperties.class), onchain,
            mock(MarketWebSocketPublisher.class), mock(UserWebSocketPublisher.class));

    @Test
    void closedMarketRejectsBuyBeforeAnyOrderSideEffect() {
        when(prices.requireTradableSnapshot()).thenThrow(new MarketClosedException());

        assertThatThrownBy(() -> service.buy(1L, "mSEC", new BigDecimal("100000")))
                .isInstanceOf(MarketClosedException.class);

        verifyNoInteractions(orders, trades, wallet, onchain);
    }

    @Test
    void stalePriceRejectsSellBeforeAnyOrderSideEffect() {
        when(prices.requireTradableSnapshot()).thenThrow(new StalePriceException());

        assertThatThrownBy(() -> service.sell(1L, "mSEC", BigDecimal.ONE))
                .isInstanceOf(StalePriceException.class);

        verifyNoInteractions(orders, trades, wallet, onchain);
    }
}
