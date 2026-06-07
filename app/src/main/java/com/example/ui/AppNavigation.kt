package com.example.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.DayZeroViewModel
import com.example.ui.screens.AiRecordScreen
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.TrendsScreen
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.WarmBackground

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Calendar : Screen("calendar", "日历", Icons.Filled.CalendarToday)
    object AiRecord : Screen("ai_record", "AI记录", Icons.Filled.ChatBubbleOutline)
    object Trends : Screen("trends", "趋势", Icons.Filled.AutoGraph)
}

val items = listOf(
    Screen.Calendar,
    Screen.AiRecord,
    Screen.Trends
)

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: DayZeroViewModel = viewModel()

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = WarmBackground
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = WarmBackground,
                            selectedTextColor = BrandGreen,
                            indicatorColor = BrandGreen,
                            unselectedIconColor = BrandGreen.copy(alpha = 0.5f),
                            unselectedTextColor = BrandGreen.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Calendar.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.slideInVertically(initialOffsetY = { 50 }, animationSpec = androidx.compose.animation.core.tween(300)) },
            exitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) },
            popExitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.slideOutVertically(targetOffsetY = { 50 }, animationSpec = androidx.compose.animation.core.tween(300)) }
        ) {
            composable(Screen.Calendar.route) {
                CalendarScreen(viewModel, onNavigateToAi = {
                    navController.navigate(Screen.AiRecord.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(Screen.AiRecord.route) {
                AiRecordScreen(viewModel)
            }
            composable(Screen.Trends.route) {
                TrendsScreen(viewModel)
            }
        }
    }
}
