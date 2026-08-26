package com.pricetrack.exchange.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface UserBalanceRepository extends JpaRepository<UserBalance, Long> {

    List<UserBalance> findAllByUserIdOrderBySymbol(Long userId);

    Optional<UserBalance> findByUserIdAndSymbol(Long userId, String symbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UserBalance b where b.userId = :userId and b.symbol = :symbol")
    Optional<UserBalance> findForUpdate(@Param("userId") Long userId, @Param("symbol") String symbol);

    void deleteAllByUserId(Long userId);
}
