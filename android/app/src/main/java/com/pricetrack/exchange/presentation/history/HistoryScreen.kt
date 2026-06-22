package com.pricetrack.exchange.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 내역 화면 (기획서 §14.6) — 주문/체결 내역, 주문 상태, 트랜잭션 해시(클릭 시 블록 익스플로러).
 * TODO: HistoryViewModel + 주문 목록 조회.
 */
@Composable
fun HistoryScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("주문/체결 내역 (구현 예정)")
        Text("시간 · 매수/매도 · 금액 · 수량 · 가격 · 수수료 · 상태 · txHash")
    }
}
