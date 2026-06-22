package com.pricetrack.exchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pricetrack.exchange.presentation.navigation.AppNavigation
import com.pricetrack.exchange.presentation.theme.PriceTrackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PriceTrackTheme {
                AppNavigation()
            }
        }
    }
}
