package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.DayZeroViewModel
import com.example.UiEvent
import com.example.ui.components.feedback.SuccessConfirmOverlay
import com.example.ui.screens.AiRecordScreen
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.TrendsScreen
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.WarmBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Calendar : Screen("calendar", "日历", Icons.Filled.CalendarToday)
    data object AiRecord : Screen("ai_record", "AI记录", Icons.Filled.ChatBubbleOutline)
    data object Trends : Screen("trends", "趋势", Icons.Filled.AutoGraph)
}

val items = listOf(
    Screen.Calendar,
    Screen.AiRecord,
    Screen.Trends
)

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: DayZeroViewModel = viewModel(factory = DayZeroViewModel.Factory)
    
    var showSuccessOverlay by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isAiRecordSelected = currentDestination?.hierarchy?.any { it.route == Screen.AiRecord.route } == true
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = !(isAiRecordSelected && isKeyboardVisible)

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is UiEvent.RecordConfirmed -> {
                    showSuccessOverlay = true
                    delay(1400)
                    showSuccessOverlay = false
                }
                is UiEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = WarmBackground
                    ) {
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
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Calendar.route,
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
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

        SuccessConfirmOverlay(
            isVisible = showSuccessOverlay,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
