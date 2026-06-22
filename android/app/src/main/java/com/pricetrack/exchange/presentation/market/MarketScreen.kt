package com.pricetrack.exchange.presentation.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 마켓 화면 (기획서 §14.3) — 현재가, 등락률, 차트, 최근 체결, 매수/매도, WebSocket 연결 상태.
 * TODO: MarketViewModel + ObservePriceUseCase 로 실시간 가격 구독.
 */
@Composable
fun MarketScreen(onTrade: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Samsung Electronics Price-Tracking Token (mSEC)")
        Text("현재가: — 원")
        Text("등락률: —%")
        Text("[ 가격 차트 placeholder ]")
        Text("[ 최근 체결 내역 placeholder ]")
        Button(onClick = onTrade) { Text("거래하기") }
    }
}
