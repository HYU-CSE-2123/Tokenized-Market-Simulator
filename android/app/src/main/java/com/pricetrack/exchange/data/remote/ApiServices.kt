package com.pricetrack.exchange.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** REST API 서비스 (기획서 §12, §15 data/remote). DTO 는 JSON 예시에 맞춰 문자열 금액 사용. */

interface AuthApi {
    @POST("api/auth/signup")
    suspend fun signup(@Body body: SignupRequest): TokenResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse
}

interface MarketApi {
    @GET("api/markets")
    suspend fun markets(): List<MarketDto>

    @GET("api/markets/{symbol}")
    suspend fun market(@Path("symbol") symbol: String): MarketDto
}

interface QuoteApi {
    @POST("api/quotes/buy")
    suspend fun buy(@Body body: BuyQuoteRequest): BuyQuoteResponse

    @POST("api/quotes/sell")
    suspend fun sell(@Body body: SellQuoteRequest): SellQuoteResponse
}

interface OrderApi {
    @POST("api/orders/buy")
    suspend fun buy(@Body body: BuyOrderRequest): OrderDto

    @POST("api/orders/sell")
    suspend fun sell(@Body body: SellOrderRequest): OrderDto

    @GET("api/orders")
    suspend fun orders(): List<OrderDto>
}

interface PortfolioApi {
    @GET("api/portfolio")
    suspend fun portfolio(): PortfolioDto
}

// ----- DTOs -----
@Serializable data class SignupRequest(val email: String, val password: String, val nickname: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class TokenResponse(val accessToken: String)

@Serializable data class MarketDto(
    val symbol: String, val name: String, val price: String,
    val changeRate: String, val updatedAt: String,
)

@Serializable data class BuyQuoteRequest(val symbol: String, val krwAmount: String)
@Serializable data class SellQuoteRequest(val symbol: String, val tokenAmount: String)
@Serializable data class BuyQuoteResponse(
    val symbol: String, val side: String, val price: String,
    val inputAmount: String, val fee: String, val expectedTokenAmount: String,
)
@Serializable data class SellQuoteResponse(
    val symbol: String, val side: String, val price: String,
    val inputAmount: String, val fee: String, val expectedKrwAmount: String,
)

@Serializable data class BuyOrderRequest(val symbol: String, val krwAmount: String)
@Serializable data class SellOrderRequest(val symbol: String, val tokenAmount: String)
@Serializable data class OrderDto(
    val orderId: Long, val symbol: String, val side: String,
    val status: String, val txHash: String? = null,
)

@Serializable data class PortfolioDto(val totalValue: String, val assets: List<PortfolioAssetDto>)
@Serializable data class PortfolioAssetDto(
    val symbol: String, val balance: String, val price: String? = null,
    val value: String, val profitRate: String? = null,
)
