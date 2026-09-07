package com.pricetrack.exchange.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.websocket.event.PortfolioEventPayload;
import com.pricetrack.exchange.websocket.event.WebSocketDestinations;
import com.pricetrack.exchange.websocket.event.WebSocketEventType;
import com.pricetrack.exchange.websocket.publisher.WebSocketEventPublisher;

/** Verifies that a balance mutation is reflected in the emitted portfolio snapshot. */
@SpringBootTest
class WalletWebSocketIntegrationTest {
    @Autowired WalletService walletService;
    @Autowired UserRepository userRepository;
    @MockBean WebSocketEventPublisher eventPublisher;

    @Test
    void faucetPublishesPortfolioContainingCommittedBalance() {
        User user = new User();
        user.setLoginId("wallet-ws-" + System.nanoTime());
        user.setNickname("Wallet WebSocket");
        user = userRepository.saveAndFlush(user);
        walletService.initializeBalances(user.getId());
        ArgumentCaptor<PortfolioEventPayload> payload =
                ArgumentCaptor.forClass(PortfolioEventPayload.class);

        walletService.faucet(user.getId());

        verify(eventPublisher).publishToUser(
                eq(user.getId()),
                eq(WebSocketDestinations.PORTFOLIO_QUEUE),
                eq(WebSocketEventType.PORTFOLIO_UPDATED),
                payload.capture());
        assertThat(payload.getValue().krwBalance()).isEqualByComparingTo(WalletService.FAUCET_AMOUNT);
        assertThat(payload.getValue().tokenBalance()).isZero();
        assertThat(payload.getValue().totalValue()).isEqualByComparingTo(WalletService.FAUCET_AMOUNT);
    }
}
