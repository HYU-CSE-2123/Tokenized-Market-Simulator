package com.pricetrack.exchange.wallet;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_balances", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_balances_user_symbol", columnNames = {"user_id", "symbol"}))
@Getter
@Setter
@NoArgsConstructor
public class UserBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, precision = 30, scale = 18)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "locked_amount", nullable = false, precision = 30, scale = 18)
    private BigDecimal lockedAmount = BigDecimal.ZERO;

    @Column(name = "average_buy_price", nullable = false, precision = 30, scale = 8)
    private BigDecimal averageBuyPrice = BigDecimal.ZERO;

    public UserBalance(Long userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }

    public BigDecimal getAvailableAmount() {
        return amount.subtract(lockedAmount);
    }

    public void lock(BigDecimal value) {
        lockedAmount = lockedAmount.add(value);
    }

    public void unlock(BigDecimal value) {
        lockedAmount = lockedAmount.subtract(value);
    }
}
