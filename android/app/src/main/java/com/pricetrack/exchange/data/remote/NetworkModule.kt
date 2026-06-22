package com.pricetrack.exchange.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pricetrack.exchange.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Retrofit/OkHttp 구성 (기획서 §15 data/remote).
 * TODO: JWT 토큰 인터셉터 추가, DI(Hilt) 도입 시 모듈화.
 */
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }
    val marketApi: MarketApi by lazy { retrofit.create(MarketApi::class.java) }
    val quoteApi: QuoteApi by lazy { retrofit.create(QuoteApi::class.java) }
    val orderApi: OrderApi by lazy { retrofit.create(OrderApi::class.java) }
    val portfolioApi: PortfolioApi by lazy { retrofit.create(PortfolioApi::class.java) }
}
