package com.pricetrack.exchange.wallet;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.BalanceNotFoundException;
import com.pricetrack.exchange.wallet.faucet.FaucetService;
import com.pricetrack.exchange.wallet.faucet.FaucetStore.Result;

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
    private final FaucetService faucetService;

    public WalletService(UserBalanceRepository balanceRepository, FaucetService faucetService) {
        this.balanceRepository = balanceRepository;
        this.faucetService = faucetService;
    }

    @Transactional
    public void initializeBalances(Long userId) {
        balanceRepository.save(new UserBalance(userId, KRW_SYMBOL));
        balanceRepository.save(new UserBalance(userId, TOKEN_SYMBOL));
    }

    public Result faucet(Long userId, String key) {
        return faucetService.credit(userId,key,FaucetService.REQUEST);
    }

    public UserBalance getForUpdate(Long userId, String symbol) {
        return balanceRepository.findForUpdate(userId, symbol)
                .orElseThrow(BalanceNotFoundException::new);
    }


}
