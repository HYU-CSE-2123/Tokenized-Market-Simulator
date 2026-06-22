package com.pricetrack.exchange.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pricetrack.exchange.presentation.history.HistoryScreen
import com.pricetrack.exchange.presentation.login.LoginScreen
import com.pricetrack.exchange.presentation.login.SignupScreen
import com.pricetrack.exchange.presentation.market.MarketScreen
import com.pricetrack.exchange.presentation.portfolio.PortfolioScreen
import com.pricetrack.exchange.presentation.trade.TradeScreen

/** 라우트 정의 (기획서 §14.2). */
object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val MARKET = "market"
    const val TRADE = "trade"
    const val PORTFOLIO = "portfolio"
    const val HISTORY = "history"
    const val MYPAGE = "mypage"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    TabItem(Routes.MARKET, "마켓", Icons.AutoMirrored.Filled.ShowChart),
    TabItem(Routes.TRADE, "거래", Icons.Filled.ShoppingCart),
    TabItem(Routes.PORTFOLIO, "자산", Icons.Filled.Wallet),
    TabItem(Routes.HISTORY, "내역", Icons.AutoMirrored.Filled.List),
    TabItem(Routes.MYPAGE, "MY", Icons.Filled.AccountCircle),
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomTabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val current = backStackEntry?.destination
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LOGIN,
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = { navController.navigate(Routes.MARKET) },
                    onNavigateToSignup = { navController.navigate(Routes.SIGNUP) },
                )
            }
            composable(Routes.SIGNUP) {
                SignupScreen(onSignupSuccess = { navController.navigate(Routes.MARKET) })
            }
            composable(Routes.MARKET) {
                MarketScreen(onTrade = { navController.navigate(Routes.TRADE) })
            }
            composable(Routes.TRADE) { TradeScreen() }
            composable(Routes.PORTFOLIO) { PortfolioScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.MYPAGE) { PlaceholderScreen("마이페이지") }
        }
    }
}
