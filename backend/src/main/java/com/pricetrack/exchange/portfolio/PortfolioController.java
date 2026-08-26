package com.pricetrack.exchange.portfolio;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.portfolio.PortfolioService.Portfolio;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public Portfolio portfolio(@AuthenticationPrincipal AuthenticatedUser user) {
        return portfolioService.portfolio(user.userId());
    }
}
