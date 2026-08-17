package com.shelfit.sentinel.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.ui.dashboard.DashboardRoute
import com.shelfit.sentinel.ui.settings.SettingsRoute

/**
 * Screen identifiers. String routes keep the navigation dependency minimal; if the
 * graph grows arguments, revisit type-safe routes.
 */
object Destination {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
}

@Composable
fun SentinelNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Destination.DASHBOARD) {
        composable(Destination.DASHBOARD) {
            DashboardRoute(
                container = container,
                onOpenSettings = { navController.navigate(Destination.SETTINGS) },
            )
        }
        composable(Destination.SETTINGS) {
            SettingsRoute(
                container = container,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
