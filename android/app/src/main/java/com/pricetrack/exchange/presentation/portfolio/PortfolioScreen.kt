package com.pricetrack.exchange.presentation.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 포트폴리오 화면 (기획서 §14.5) — 총 평가금액, 보유 mKRW/mSEC, 평균 매수가, 평가손익, 수익률.
 * TODO: PortfolioViewModel + GetPortfolioUseCase, 가격/체결 이벤트 시 자동 갱신.
 */
@Composable
fun PortfolioScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("총 평가금액: — 원")
        Text("보유 mKRW: —")
        Text("보유 mSEC: —")
        Text("평균 매수가: — / 평가손익: — / 수익률: —%")
    }
}
