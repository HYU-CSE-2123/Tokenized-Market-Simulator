package com.pricetrack.exchange.wallet;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.BalanceNotFoundException;

/**
 * 사용자 지갑 주소 관리 (기획서 §10, §5.1 — 지갑 생성/연결).
 * TODO(Phase 2): 회원가입 시 지갑 주소 발급/연결, mKRW faucet 트리거.
 */
@Service
public class WalletService {

    public static final String KRW_SYMBOL = "mKRW";
    public static final String TOKEN_SYMBOL = "mSEC";
    public static final BigDecimal FAUCET_AMOUNT = new BigDecimal("1000000.000000000000000000");

    private final UserBalanceRepository balanceRepository;

    public WalletService(UserBalanceRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
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
        return new FaucetResult(KRW_SYMBOL, FAUCET_AMOUNT, balance.getAmount());
    }

    public UserBalance getForUpdate(Long userId, String symbol) {
        return balanceRepository.findForUpdate(userId, symbol)
                .orElseThrow(BalanceNotFoundException::new);
    }

    public record FaucetResult(String symbol, BigDecimal receivedAmount, BigDecimal balance) {}

}
