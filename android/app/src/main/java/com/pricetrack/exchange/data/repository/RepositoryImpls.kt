package com.pricetrack.exchange.data.repository

import com.pricetrack.exchange.data.remote.AuthApi
import com.pricetrack.exchange.data.remote.LoginRequest
import com.pricetrack.exchange.data.remote.MarketApi
import com.pricetrack.exchange.data.remote.SignupRequest
import com.pricetrack.exchange.data.websocket.MarketSocketClient
import com.pricetrack.exchange.data.websocket.OrderSocketClient
import com.pricetrack.exchange.domain.model.Market
import com.pricetrack.exchange.domain.model.Order
import com.pricetrack.exchange.domain.model.Portfolio
import com.pricetrack.exchange.domain.model.PriceTick
import com.pricetrack.exchange.domain.model.Quote
import com.pricetrack.exchange.domain.repository.AuthRepository
import com.pricetrack.exchange.domain.repository.MarketRepository
import com.pricetrack.exchange.domain.repository.OrderRepository
import com.pricetrack.exchange.domain.repository.PortfolioRepository
import kotlinx.coroutines.flow.Flow

/**
 * 리포지토리 구현 (기획서 §15 data/repository).
 * 현재는 스캐폴딩 — REST/WebSocket 매핑은 Phase 5 에서 완성한다.
 */

class AuthRepositoryImpl(private val authApi: AuthApi) : AuthRepository {
    override suspend fun login(email: String, password: String): String =
        authApi.login(LoginRequest(email, password)).accessToken

    override suspend fun signup(email: String, password: String, nickname: String): String =
        authApi.signup(SignupRequest(email, password, nickname)).accessToken
}

class MarketRepositoryImpl(
    private val marketApi: MarketApi,
    private val socket: MarketSocketClient,
) : MarketRepository {
    override suspend fun getMarket(symbol: String): Market {
        val dto = marketApi.market(symbol)
        return Market(dto.symbol, dto.name, dto.price, dto.changeRate, dto.updatedAt)
    }

    override fun observePrice(symbol: String): Flow<PriceTick> = socket.priceStream(symbol)
}

class OrderRepositoryImpl(/* private val orderApi: OrderApi, quoteApi: QuoteApi */) : OrderRepository {
    override suspend fun getBuyQuote(symbol: String, krwAmount: String): Quote =
        TODO("Phase 5: quoteApi.buy 매핑")

    override suspend fun getSellQuote(symbol: String, tokenAmount: String): Quote =
        TODO("Phase 5: quoteApi.sell 매핑")

    override suspend fun buy(symbol: String, krwAmount: String): Order =
        TODO("Phase 5: orderApi.buy 매핑")

    override suspend fun sell(symbol: String, tokenAmount: String): Order =
        TODO("Phase 5: orderApi.sell 매핑")

    override suspend fun getOrders(): List<Order> = TODO("Phase 5: orderApi.orders 매핑")
}

class PortfolioRepositoryImpl(private val socket: OrderSocketClient) : PortfolioRepository {
    override suspend fun getPortfolio(): Portfolio = TODO("Phase 5: portfolioApi.portfolio 매핑")
    override fun observePortfolio(): Flow<Portfolio> = socket.portfolioStream()
}
