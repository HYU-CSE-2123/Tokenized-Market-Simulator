package com.pricetrack.exchange.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 마켓 API (기획서 §12.2). */
@RestController
@RequestMapping("/api/markets")
public class MarketController {

    private final PriceSimulator priceSimulator;

    public MarketController(PriceSimulator priceSimulator) {
        this.priceSimulator = priceSimulator;
    }

    public record MarketResponse(String symbol, String name, BigDecimal price,
                                 BigDecimal changeRate, String updatedAt) {}

    @GetMapping
    public List<MarketResponse> markets() {
        return List.of(current());
    }

    @GetMapping("/{symbol}")
    public MarketResponse market(@PathVariable String symbol) {
        // MVP 단계: mSEC 단일 마켓
        return current();
    }

    @GetMapping("/{symbol}/ticks")
    public Object ticks(@PathVariable String symbol) {
        // TODO(Phase 2): price_ticks 조회
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }

    private MarketResponse current() {
        return new MarketResponse(
                PriceSimulator.SYMBOL,
                "Samsung Electronics Price-Tracking Token",
                priceSimulator.getCurrentPrice(),
                BigDecimal.ZERO,
                Instant.now().toString());
    }
}
