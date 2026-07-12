package org.jarsi.betascout.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.jarsi.betascout.R
import org.jarsi.betascout.ui.account.AccountScreen
import org.jarsi.betascout.ui.appdetail.AppDetailScreen
import org.jarsi.betascout.ui.applist.AppListScreen
import org.jarsi.betascout.ui.watchlist.WatchlistScreen

object Routes {
    const val APPS = "apps"
    const val WATCHLIST = "watchlist"
    const val ACCOUNT = "account"
    const val APP_DETAIL = "apps/{packageName}"
    fun appDetail(packageName: String) = "apps/$packageName"
}

private data class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.APPS, Icons.AutoMirrored.Filled.List, R.string.tab_apps),
    TopLevelDestination(Routes.WATCHLIST, Icons.Filled.Star, R.string.tab_watchlist),
)

@Composable
fun BetaScoutNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevelDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.APPS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.APPS) {
                AppListScreen(
                    onAppClick = { navController.navigate(Routes.appDetail(it)) },
                    onAccountClick = { navController.navigate(Routes.ACCOUNT) },
                )
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WATCHLIST) {
                WatchlistScreen(onAppClick = { navController.navigate(Routes.appDetail(it)) })
            }
            composable(Routes.APP_DETAIL) {
                AppDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
