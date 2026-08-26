package com.pricetrack.exchange.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.market.PriceSimulator;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.UserBalanceRepository;
import com.pricetrack.exchange.wallet.WalletService;

@Service
@Transactional(readOnly = true)
public class PortfolioService {
    private final UserBalanceRepository balanceRepository;
    private final PriceSimulator priceSimulator;

    public PortfolioService(UserBalanceRepository balanceRepository, PriceSimulator priceSimulator) {
        this.balanceRepository = balanceRepository;
        this.priceSimulator = priceSimulator;
    }

    public Portfolio portfolio(Long userId) {
        Map<String, UserBalance> balances = balanceRepository.findAllByUserIdOrderBySymbol(userId).stream()
                .collect(Collectors.toMap(UserBalance::getSymbol, Function.identity()));
        UserBalance krw = balances.getOrDefault(WalletService.KRW_SYMBOL,
                new UserBalance(userId, WalletService.KRW_SYMBOL));
        UserBalance token = balances.getOrDefault(WalletService.TOKEN_SYMBOL,
                new UserBalance(userId, WalletService.TOKEN_SYMBOL));
        BigDecimal price = priceSimulator.getCurrentPrice();
        BigDecimal tokenValue = token.getAmount().multiply(price).setScale(18, RoundingMode.HALF_UP);
        BigDecimal costBasis = token.getAmount().multiply(token.getAverageBuyPrice()).setScale(18, RoundingMode.HALF_UP);
        return new Portfolio(krw.getAmount(), token.getAmount(), price, token.getAverageBuyPrice(),
                tokenValue, tokenValue.subtract(costBasis), krw.getAmount().add(tokenValue));
    }

    public record Portfolio(BigDecimal krwBalance, BigDecimal tokenBalance, BigDecimal currentPrice,
            BigDecimal averageBuyPrice, BigDecimal tokenValue, BigDecimal unrealizedProfit,
            BigDecimal totalValue) {}
}
