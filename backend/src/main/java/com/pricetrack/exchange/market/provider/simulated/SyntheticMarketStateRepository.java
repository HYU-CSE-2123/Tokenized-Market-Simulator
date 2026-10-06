package com.pricetrack.exchange.market.provider.simulated;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SyntheticMarketStateRepository extends JpaRepository<SyntheticMarketState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SyntheticMarketState s where s.symbol = :symbol")
    Optional<SyntheticMarketState> lock(@Param("symbol") String symbol);
}
