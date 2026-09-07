package com.pricetrack.exchange.wallet;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.BalanceNotFoundException;
import com.pricetrack.exchange.websocket.publisher.UserWebSocketPublisher;

/**
 * 사용자별 모의 자산 잔고를 초기화하고 변경한다.
 * 잔고가 실제로 바뀌는 faucet은 commit 후 개인 포트폴리오 알림도 요청한다.
 */
@Service
public class WalletService {

    public static final String KRW_SYMBOL = "mKRW";
    public static final String TOKEN_SYMBOL = "mSEC";
    public static final BigDecimal FAUCET_AMOUNT = new BigDecimal("1000000.000000000000000000");

    private final UserBalanceRepository balanceRepository;
    private final UserWebSocketPublisher userEvents;

    public WalletService(UserBalanceRepository balanceRepository, UserWebSocketPublisher userEvents) {
        this.balanceRepository = balanceRepository;
        this.userEvents = userEvents;
    }

    @Transactional
    public void initializeBalances(Long userId) {
        balanceRepository.save(new UserBalance(userId, KRW_SYMBOL));
        balanceRepository.save(new UserBalance(userId, TOKEN_SYMBOL));
    }

    @Transactional
    public FaucetResult faucet(Long userId) {
        UserBalance balance = getForUpdate(userId, KRW_SYMBOL);
        balance.setAmount(balance.getAmount().add(FAUCET_AMOUNT));
        userEvents.publishPortfolio(userId);
        return new FaucetResult(KRW_SYMBOL, FAUCET_AMOUNT, balance.getAmount());
    }

    public UserBalance getForUpdate(Long userId, String symbol) {
        return balanceRepository.findForUpdate(userId, symbol)
                .orElseThrow(BalanceNotFoundException::new);
    }

    public record FaucetResult(String symbol, BigDecimal receivedAmount, BigDecimal balance) {}

}
