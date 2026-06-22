package com.pricetrack.exchange.data.websocket

import com.pricetrack.exchange.domain.model.Portfolio
import com.pricetrack.exchange.domain.model.PriceTick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * WebSocket(STOMP) 클라이언트 (기획서 §13, §15 data/websocket).
 * TODO(Phase 4): STOMP over OkHttp WebSocket 연결, 토픽 구독:
 *   /topic/markets/mSEC/price, /topic/markets/mSEC/trades,
 *   /user/queue/orders, /user/queue/portfolio
 */
class MarketSocketClient {
    fun connect() { /* TODO */ }
    fun disconnect() { /* TODO */ }
    fun priceStream(symbol: String): Flow<PriceTick> = emptyFlow()
}

class OrderSocketClient {
    fun connect() { /* TODO */ }
    fun disconnect() { /* TODO */ }
    fun portfolioStream(): Flow<Portfolio> = emptyFlow()
}
