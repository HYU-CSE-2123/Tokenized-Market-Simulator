package com.pricetrack.exchange.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.common.exception.InsufficientBalanceException;
import com.pricetrack.exchange.common.exception.OrderNotFoundException;
import com.pricetrack.exchange.common.exception.UnsupportedSymbolException;
import com.pricetrack.exchange.blockchain.BlockchainProperties;
import com.pricetrack.exchange.market.PriceSimulator;
import com.pricetrack.exchange.quote.TradeCalculator;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.trade.TradeRepository;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.WalletService;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final WalletService walletService;
    private final PriceSimulator priceSimulator;
    private final TradeCalculator tradeCalculator;
    private final BlockchainProperties blockchainProperties;
    private final OnchainOrderService onchainOrderService;

    public OrderService(OrderRepository orderRepository, TradeRepository tradeRepository,
            WalletService walletService, PriceSimulator priceSimulator, TradeCalculator tradeCalculator,
            BlockchainProperties blockchainProperties, OnchainOrderService onchainOrderService) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.walletService = walletService;
        this.priceSimulator = priceSimulator;
        this.tradeCalculator = tradeCalculator;
        this.blockchainProperties = blockchainProperties;
        this.onchainOrderService = onchainOrderService;
    }

    @Transactional(noRollbackFor = InsufficientBalanceException.class)
    public Order buy(Long userId, String symbol, BigDecimal krwAmount) {
        validateSymbol(symbol);
        if (blockchainProperties.enabled()) return onchainOrderService.buy(userId, krwAmount);
        BigDecimal price = priceSimulator.getCurrentPrice();
        TradeCalculator.BuyCalculation calculation = tradeCalculator.buy(krwAmount, price);
        UserBalance krw = walletService.getForUpdate(userId, WalletService.KRW_SYMBOL);
        UserBalance token = walletService.getForUpdate(userId, WalletService.TOKEN_SYMBOL);
        Order order = createOrder(userId, OrderSide.BUY, krwAmount, calculation.tokenAmount());
        requireBalanceOrFail(order, krw, krwAmount);
        krw.setAmount(krw.getAmount().subtract(krwAmount));
        updateAverageBuyPrice(token, calculation.tokenAmount(), krwAmount);
        token.setAmount(token.getAmount().add(calculation.tokenAmount()));
        fill(order, price, calculation.tokenAmount(), krwAmount, calculation.fee());
        return order;
    }

    @Transactional(noRollbackFor = InsufficientBalanceException.class)
    public Order sell(Long userId, String symbol, BigDecimal tokenAmount) {
        validateSymbol(symbol);
        if (blockchainProperties.enabled()) return onchainOrderService.sell(userId, tokenAmount);
        BigDecimal price = priceSimulator.getCurrentPrice();
        TradeCalculator.SellCalculation calculation = tradeCalculator.sell(tokenAmount, price);
        UserBalance krw = walletService.getForUpdate(userId, WalletService.KRW_SYMBOL);
        UserBalance token = walletService.getForUpdate(userId, WalletService.TOKEN_SYMBOL);
        Order order = createOrder(userId, OrderSide.SELL, tokenAmount, calculation.netKrw());
        requireBalanceOrFail(order, token, tokenAmount);
        token.setAmount(token.getAmount().subtract(tokenAmount));
        if (token.getAmount().signum() == 0) token.setAverageBuyPrice(BigDecimal.ZERO);
        krw.setAmount(krw.getAmount().add(calculation.netKrw()));
        fill(order, price, tokenAmount, calculation.netKrw(), calculation.fee());
        return order;
    }

    public List<Order> findAll(Long userId) {
        return orderRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    public Order findOne(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId).orElseThrow(OrderNotFoundException::new);
    }

    private Order createOrder(Long userId, OrderSide side, BigDecimal input, BigDecimal output) {
        Order order = new Order();
        order.setUserId(userId);
        order.setSymbol(WalletService.TOKEN_SYMBOL);
        order.setSide(side);
        order.setInputAmount(input);
        order.setExpectedOutputAmount(output);
        return orderRepository.save(order);
    }

    private void fill(Order order, BigDecimal price, BigDecimal baseAmount,
            BigDecimal quoteAmount, BigDecimal fee) {
        order.setStatus(OrderStatus.FILLED);
        order.setUpdatedAt(Instant.now());
        Trade trade = new Trade();
        trade.setOrderId(order.getId());
        trade.setUserId(order.getUserId());
        trade.setSymbol(order.getSymbol());
        trade.setSide(order.getSide());
        trade.setPrice(price);
        trade.setBaseAmount(baseAmount);
        trade.setQuoteAmount(quoteAmount);
        trade.setFee(fee);
        tradeRepository.save(trade);
    }

    private void updateAverageBuyPrice(UserBalance token, BigDecimal addedTokens, BigDecimal cost) {
        BigDecimal oldCost = token.getAmount().multiply(token.getAverageBuyPrice());
        BigDecimal newAmount = token.getAmount().add(addedTokens);
        token.setAverageBuyPrice(oldCost.add(cost).divide(newAmount, 8, RoundingMode.HALF_UP));
    }

    private void requireBalanceOrFail(Order order, UserBalance balance, BigDecimal required) {
        if (balance.getAmount().compareTo(required) < 0) {
            order.setStatus(OrderStatus.FAILED);
            order.setUpdatedAt(Instant.now());
            throw new InsufficientBalanceException(balance.getSymbol());
        }
    }

    private void validateSymbol(String symbol) {
        if (!WalletService.TOKEN_SYMBOL.equals(symbol)) throw new UnsupportedSymbolException();
    }
}
