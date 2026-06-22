package com.pricetrack.exchange.domain.usecase

import com.pricetrack.exchange.domain.model.Market
import com.pricetrack.exchange.domain.model.Order
import com.pricetrack.exchange.domain.model.Portfolio
import com.pricetrack.exchange.domain.model.PriceTick
import com.pricetrack.exchange.domain.model.Quote
import com.pricetrack.exchange.domain.repository.MarketRepository
import com.pricetrack.exchange.domain.repository.OrderRepository
import com.pricetrack.exchange.domain.repository.PortfolioRepository
import kotlinx.coroutines.flow.Flow

/** 유즈케이스 (기획서 §15 domain/usecase). 화면 ViewModel 에서 사용. */

class GetMarketUseCase(private val repo: MarketRepository) {
    suspend operator fun invoke(symbol: String): Market = repo.getMarket(symbol)
}

class ObservePriceUseCase(private val repo: MarketRepository) {
    operator fun invoke(symbol: String): Flow<PriceTick> = repo.observePrice(symbol)
}

class GetBuyQuoteUseCase(private val repo: OrderRepository) {
    suspend operator fun invoke(symbol: String, krwAmount: String): Quote =
        repo.getBuyQuote(symbol, krwAmount)
}

class BuyTokenUseCase(private val repo: OrderRepository) {
    suspend operator fun invoke(symbol: String, krwAmount: String): Order =
        repo.buy(symbol, krwAmount)
}

class SellTokenUseCase(private val repo: OrderRepository) {
    suspend operator fun invoke(symbol: String, tokenAmount: String): Order =
        repo.sell(symbol, tokenAmount)
}

class GetPortfolioUseCase(private val repo: PortfolioRepository) {
    suspend operator fun invoke(): Portfolio = repo.getPortfolio()
}
