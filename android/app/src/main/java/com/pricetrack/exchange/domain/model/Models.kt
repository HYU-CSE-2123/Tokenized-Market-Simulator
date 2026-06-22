package com.pricetrack.exchange.domain.model

/**
 * 도메인 모델 (기획서 §15 domain/model).
 * 금액/가격은 정밀도 손실 방지를 위해 String 으로 다룬다 (API 도 문자열로 직렬화, §12).
 */

data class Market(
    val symbol: String,
    val name: String,
    val price: String,
    val changeRate: String,
    val updatedAt: String,
)

data class PriceTick(
    val symbol: String,
    val price: String,
    val timestamp: String,
)

data class Quote(
    val symbol: String,
    val side: OrderSide,
    val price: String,
    val inputAmount: String,
    val fee: String,
    val expectedOutputAmount: String,
)

enum class OrderSide { BUY, SELL }

enum class OrderStatus { REQUESTED, PENDING_ONCHAIN, FILLED, FAILED, CANCELED }

data class Order(
    val orderId: Long,
    val symbol: String,
    val side: OrderSide,
    val status: OrderStatus,
    val txHash: String?,
)

data class Trade(
    val orderId: Long,
    val symbol: String,
    val side: OrderSide,
    val price: String,
    val baseAmount: String,
    val quoteAmount: String,
    val fee: String,
    val txHash: String?,
)

data class PortfolioAsset(
    val symbol: String,
    val balance: String,
    val price: String?,
    val value: String,
    val profitRate: String?,
)

data class Portfolio(
    val totalValue: String,
    val assets: List<PortfolioAsset>,
)
