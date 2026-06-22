package com.pricetrack.exchange.domain.repository

import com.pricetrack.exchange.domain.model.Market
import com.pricetrack.exchange.domain.model.Order
import com.pricetrack.exchange.domain.model.Portfolio
import com.pricetrack.exchange.domain.model.PriceTick
import com.pricetrack.exchange.domain.model.Quote
import kotlinx.coroutines.flow.Flow

/** 도메인 리포지토리 인터페이스 (기획서 §15 domain/repository). */

interface AuthRepository {
    suspend fun login(email: String, password: String): String   // 반환: accessToken
    suspend fun signup(email: String, password: String, nickname: String): String
}

interface MarketRepository {
    suspend fun getMarket(symbol: String): Market
    fun observePrice(symbol: String): Flow<PriceTick>
}

interface OrderRepository {
    suspend fun getBuyQuote(symbol: String, krwAmount: String): Quote
    suspend fun getSellQuote(symbol: String, tokenAmount: String): Quote
    suspend fun buy(symbol: String, krwAmount: String): Order
    suspend fun sell(symbol: String, tokenAmount: String): Order
    suspend fun getOrders(): List<Order>
}

interface PortfolioRepository {
    suspend fun getPortfolio(): Portfolio
    fun observePortfolio(): Flow<Portfolio>
}
