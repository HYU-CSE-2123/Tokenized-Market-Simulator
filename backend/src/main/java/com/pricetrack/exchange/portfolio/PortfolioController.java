package com.pricetrack.exchange.portfolio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포트폴리오 API (기획서 §12.5).
 * TODO(Phase 2): 사용자 잔고/평가금액/평가손익 계산.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    @GetMapping
    public Object portfolio() {
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }

    @GetMapping("/history")
    public Object history() {
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }
}
