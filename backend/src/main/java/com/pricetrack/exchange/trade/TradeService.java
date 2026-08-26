package com.pricetrack.exchange.trade;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 체결 내역 관리 (기획서 §11.5 trades, §7).
 * TODO(Phase 3): 온체인 Bought/Sold 이벤트 확정 시 체결 내역 저장 및 WebSocket 발행.
 */
@Service
public class TradeService {
    private final TradeRepository tradeRepository;

    public TradeService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    public List<Trade> findAll(Long userId) {
        return tradeRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }
}
