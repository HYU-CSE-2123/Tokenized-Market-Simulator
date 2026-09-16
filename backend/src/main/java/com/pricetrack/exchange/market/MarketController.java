package com.pricetrack.exchange.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;

import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.market.model.MarketStatus;
import com.pricetrack.exchange.market.model.PriceStatus;
import com.pricetrack.exchange.market.model.MarketCandlePage;

/** 마켓 API (기획서 §12.2). */
@RestController
@RequestMapping("/api/markets")
public class MarketController {

    private final MarketPriceService marketPriceService;
    private final PriceTickRepository priceTickRepository;
    private final MarketCandleService candleService;

    public MarketController(MarketPriceService marketPriceService, PriceTickRepository priceTickRepository,
            MarketCandleService candleService) {
        this.marketPriceService = marketPriceService;
        this.priceTickRepository = priceTickRepository;
        this.candleService = candleService;
    }

    public record MarketResponse(String symbol, String name, BigDecimal price,
                                 BigDecimal previousClose, BigDecimal change,
                                 BigDecimal changeRate, MarketStatus marketStatus,
                                 PriceStatus priceStatus, String provider, Instant observedAt,
                                 Instant updatedAt) {}

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
    public List<PriceTickResponse> ticks(@PathVariable String symbol) {
        return priceTickRepository.findTop100BySymbolOrderByCreatedAtDesc(symbol).stream()
                .map(PriceTickResponse::from).toList();
    }

    private MarketResponse current() {
        MarketPriceSnapshot snapshot = marketPriceService.current();
        return new MarketResponse(
                snapshot.symbol(),
                "Samsung Electronics Price-Tracking Token",
                snapshot.price(), snapshot.previousClose(), snapshot.change(),
                snapshot.changeRate(), snapshot.marketStatus(), snapshot.priceStatus(),
                snapshot.provider(), snapshot.observedAt(), snapshot.observedAt());
    }

    @GetMapping("/{symbol}/candles")
    public MarketCandlePage candles(@PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
        return candleService.candles(symbol, interval, count, before);
    }

    public record PriceTickResponse(BigDecimal price, String source, Instant createdAt) {
        static PriceTickResponse from(PriceTick tick) {
            return new PriceTickResponse(tick.getPrice(), tick.getSource(), tick.getCreatedAt());
        }
    }
}
