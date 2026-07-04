package org.jarsi.betascout.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.jarsi.betascout.ui.appdetail.AppDetailScreen
import org.jarsi.betascout.ui.applist.AppListScreen

object Routes {
    const val APPS = "apps"
    const val APP_DETAIL = "apps/{packageName}"
    fun appDetail(packageName: String) = "apps/$packageName"
}

@Composable
fun BetaScoutNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.APPS) {
        composable(Routes.APPS) {
            AppListScreen(onAppClick = { navController.navigate(Routes.appDetail(it)) })
        }
        composable(Routes.APP_DETAIL) {
            AppDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
