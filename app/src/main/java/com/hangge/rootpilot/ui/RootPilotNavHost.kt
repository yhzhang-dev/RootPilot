package com.hangge.rootpilot.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hangge.rootpilot.AppContainer
import com.hangge.rootpilot.ui.agent.AgentScreen
import com.hangge.rootpilot.ui.apps.AppDetailScreen
import com.hangge.rootpilot.ui.apps.AppsScreen
import com.hangge.rootpilot.ui.dashboard.DashboardScreen
import com.hangge.rootpilot.ui.history.HistoryScreen
import com.hangge.rootpilot.ui.settings.SettingsScreen
import com.hangge.rootpilot.ui.components.RootPilotIcons

object Routes {
    const val DASHBOARD = "dashboard"
    const val APPS = "apps"
    const val APP_DETAIL = "apps/{packageName}"
    const val HISTORY = "history"
    const val AGENT = "agent"
    const val SETTINGS = "settings"

    fun appDetail(packageName: String): String = "apps/$packageName"
}

private data class TabDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    TabDestination(Routes.DASHBOARD, "总览", RootPilotIcons.Dashboard),
    TabDestination(Routes.APPS, "应用", RootPilotIcons.Apps),
    TabDestination(Routes.HISTORY, "历史", RootPilotIcons.History),
    TabDestination(Routes.AGENT, "Agent", RootPilotIcons.Agent),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootPilotApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBack = currentRoute == Routes.SETTINGS || currentRoute == Routes.APP_DETAIL
    val title = when (currentRoute) {
        Routes.APPS -> "应用"
        Routes.APP_DETAIL -> "应用详情"
        Routes.HISTORY -> "历史"
        Routes.AGENT -> "Agent"
        Routes.SETTINGS -> "设置"
        else -> "总览"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(RootPilotIcons.Back, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (currentRoute != Routes.SETTINGS) {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(RootPilotIcons.Settings, contentDescription = "设置")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (bottomTabs.any { it.route == currentRoute }) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    container = container,
                    onOpenAgent = {
                        navController.navigate(Routes.AGENT) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.APPS) {
                AppsScreen(
                    container = container,
                    onOpenApp = { packageName ->
                        navController.navigate(Routes.appDetail(packageName))
                    },
                )
            }
            composable(
                route = Routes.APP_DETAIL,
                arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
            ) { entry ->
                val packageName = entry.arguments?.getString("packageName").orEmpty()
                AppDetailScreen(
                    container = container,
                    packageName = packageName,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(container = container)
            }
            composable(Routes.AGENT) {
                AgentScreen(container = container)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container = container)
            }
        }
    }
}
