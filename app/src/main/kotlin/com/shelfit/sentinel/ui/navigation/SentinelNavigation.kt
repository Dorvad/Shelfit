package com.shelfit.sentinel.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.ui.calibration.CalibrationRoute
import com.shelfit.sentinel.ui.claplab.ClapLabRoute
import com.shelfit.sentinel.ui.dashboard.DashboardRoute
import com.shelfit.sentinel.ui.health.SensorHealthRoute
import com.shelfit.sentinel.ui.settings.SettingsRoute

/**
 * Screen identifiers. String routes keep the navigation dependency minimal; if the
 * graph grows arguments, revisit type-safe routes.
 */
object Destination {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"

    /** Developer screen for tuning and verifying clap detection. */
    const val CLAP_LAB = "clap-lab"

    /** Guided calibration. */
    const val CALIBRATION = "calibration"

    /** Setup and health checks for unattended operation. */
    const val HEALTH = "health"
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
                onOpenClapLab = { navController.navigate(Destination.CLAP_LAB) },
                onOpenHealth = { navController.navigate(Destination.HEALTH) },
            )
        }
        composable(Destination.SETTINGS) {
            SettingsRoute(
                container = container,
                onNavigateBack = { navController.popBackStack() },
                onOpenCalibration = { navController.navigate(Destination.CALIBRATION) },
                onOpenClapLab = { navController.navigate(Destination.CLAP_LAB) },
            )
        }
        composable(Destination.CALIBRATION) {
            CalibrationRoute(
                container = container,
                onFinished = { navController.popBackStack() },
            )
        }
        composable(Destination.HEALTH) {
            SensorHealthRoute(
                container = container,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Destination.CLAP_LAB) {
            ClapLabRoute(
                container = container,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
