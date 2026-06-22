package com.pricetrack.exchange.presentation.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 거래 화면 (기획서 §14.4) — 매수/매도 탭, 금액 입력 시 300ms debounce 로 견적 API 호출.
 * TODO: TradeViewModel + GetBuyQuoteUseCase / BuyTokenUseCase / SellTokenUseCase.
 */
@Composable
fun TradeScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("매수") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("매도") })
        }
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tab == 0) {
                Text("매수: 사용할 mKRW 금액 입력 → 예상 mSEC 수량/수수료 표시")
            } else {
                Text("매도: 판매할 mSEC 수량 입력 → 예상 수령 mKRW/수수료 표시")
            }
            Text("※ 최종 체결 수량은 가격 변동에 따라 달라질 수 있음 (기획서 §18.3)")
        }
    }
}
