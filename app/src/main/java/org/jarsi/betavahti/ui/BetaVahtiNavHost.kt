package org.jarsi.betavahti.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.jarsi.betavahti.ui.appdetail.AppDetailScreen
import org.jarsi.betavahti.ui.applist.AppListScreen

object Routes {
    const val APPS = "apps"
    const val APP_DETAIL = "apps/{packageName}"
    fun appDetail(packageName: String) = "apps/$packageName"
}

@Composable
fun BetaVahtiNavHost() {
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
