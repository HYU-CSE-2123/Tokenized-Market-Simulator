package com.pricetrack.exchange.websocket.event;

import java.math.BigDecimal;

/** REST 포트폴리오 응답과 같은 의미의 사용자별 평가 정보다. */
public record PortfolioEventPayload(BigDecimal krwBalance, BigDecimal tokenBalance,
        BigDecimal currentPrice, BigDecimal averageBuyPrice, BigDecimal tokenValue,
        BigDecimal unrealizedProfit, BigDecimal totalValue) {
}
