package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.DayZeroViewModel
import com.example.UiEvent
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.ui.components.feedback.SuccessConfirmOverlay
import com.example.ui.screens.AiConversationScreen
import com.example.ui.screens.AiRecordActionHandler
import com.example.ui.screens.AiRecordConversationEvent
import com.example.ui.screens.AiRecordHomeScreen
import com.example.ui.screens.AiRecordViewModel
import com.example.ui.screens.CalendarScreen
import com.example.ui.screens.TrendsScreen
import com.example.ui.theme.BrandGreen
import com.example.ui.theme.WarmBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Calendar : Screen("calendar", "Calendar", Icons.Filled.CalendarToday)
    data object AiRecord : Screen("ai_record", "AI", Icons.Filled.ChatBubbleOutline)
    data object Trends : Screen("trends", "Trends", Icons.Filled.AutoGraph)
}

private const val AI_CONVERSATION_ARG = "conversationId"
private const val AI_CONVERSATION_ROUTE = "ai_conversation/{$AI_CONVERSATION_ARG}"

private fun aiConversationRoute(conversationId: String): String = "ai_conversation/$conversationId"

val items = listOf(
    Screen.Calendar,
    Screen.AiRecord,
    Screen.Trends
)

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val viewModel: DayZeroViewModel = viewModel()
    val aiRecordViewModel: AiRecordViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val aiRecordUiState by aiRecordViewModel.uiState.collectAsState()
    val syncStatusUiState by viewModel.syncStatusUiState.collectAsState()
    val aiRecordActionHandler = remember(viewModel) {
        object : AiRecordActionHandler {
            override fun sendAiMessage(text: String) {
                viewModel.sendAiMessage(text)
            }

            override fun sendAiMessage(conversationId: String, text: String) {
                viewModel.sendAiMessage(conversationId, text)
            }

            override fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) {
                viewModel.startAssistantTurnForExistingUserMessage(conversationId, text)
            }

            override fun setActiveConversationId(conversationId: String?) {
                viewModel.setActiveConversationId(conversationId)
            }

            override fun sendInteractionResult(
                interactionId: String,
                actionType: String,
                optionId: String,
                optionLabel: String,
                field: String?,
                originalText: String?,
                confirmType: String?,
                payloadSummary: PayloadSummary?
            ) {
                viewModel.sendInteractionResult(
                    interactionId = interactionId,
                    actionType = actionType,
                    optionId = optionId,
                    optionLabel = optionLabel,
                    field = field,
                    originalText = originalText,
                    confirmType = confirmType,
                    payloadSummary = payloadSummary
                )
            }

            override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) {
                viewModel.handleDateMismatchGuardResult(guardId = guardId, approved = approved)
            }

            override fun updateFoodDraftCard(interactionId: String, weightKg: Double?, meals: List<ConfirmCardMeal>) {
                viewModel.updateFoodDraftCard(
                    interactionId = interactionId,
                    weightKg = weightKg,
                    meals = meals
                )
            }

            override fun clearChatMessages() {
                viewModel.clearChatMessages()
            }

            override fun clearLocalRecords() {
                viewModel.clearLocalRecords()
            }

            override fun clearAllData() {
                viewModel.clearAllData()
            }

            override fun clearCloudBackupForDebug() {
                viewModel.clearCloudBackupForDebug()
            }

            override fun markAssistantMessageRendered(message: AiChatMessage) {
                viewModel.markAssistantMessageRendered(message)
            }
        }
    }

    var showSuccessOverlay by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val isAiConversationRoute = currentRoute == AI_CONVERSATION_ROUTE
    val showBottomBar = !isAiConversationRoute

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

    LaunchedEffect(Unit) {
        aiRecordViewModel.events.collectLatest { event ->
            when (event) {
                is AiRecordConversationEvent.ConversationCreated -> {
                    navController.navigate(aiConversationRoute(event.conversationId)) {
                        launchSingleTop = true
                    }
                    viewModel.startAssistantTurnForExistingUserMessage(
                        conversationId = event.conversationId,
                        text = event.firstMessageText
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(durationMillis = 350)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(durationMillis = 350))
                ) {
                    NavigationBar(containerColor = WarmBackground) {
                        items.forEach { screen ->
                            val selected = when (screen) {
                                Screen.AiRecord -> currentRoute == Screen.AiRecord.route || isAiConversationRoute
                                else -> currentDestination?.hierarchy?.any { it.route == screen.route } == true
                            }
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
            val targetBottomPadding = innerPadding.calculateBottomPadding()
            val animatedBottomPadding by animateDpAsState(
                targetValue = if (showBottomBar) targetBottomPadding else 0.dp,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                label = "bottomPadding"
            )
            NavHost(
                navController = navController,
                startDestination = Screen.Calendar.route,
                modifier = Modifier
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = 0.dp
                    ),
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(durationMillis = 350))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { -it / 3 },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(durationMillis = 350))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { -it / 3 },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(durationMillis = 350))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(durationMillis = 350))
                }
            ) {
                composable(Screen.Calendar.route) {
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = animatedBottomPadding)) {
                        CalendarScreen(
                            uiState = uiState,
                            onNavigateToAi = {
                                navController.navigate(Screen.AiRecord.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
                composable(Screen.AiRecord.route) {
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = animatedBottomPadding)) {
                        AiRecordHomeScreen(
                            state = aiRecordUiState.history,
                            isAnalyzing = uiState.isAnalyzing,
                            onInputChange = aiRecordViewModel::updateHomeInput,
                            onSubmit = aiRecordViewModel::submitHomeInput,
                            onOpenConversation = { conversationId ->
                                aiRecordViewModel.openConversation(conversationId)
                                navController.navigate(aiConversationRoute(conversationId)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
                composable(
                    route = AI_CONVERSATION_ROUTE,
                    arguments = listOf(navArgument(AI_CONVERSATION_ARG) { type = NavType.StringType })
                ) { entry ->
                    val conversationId = entry.arguments?.getString(AI_CONVERSATION_ARG).orEmpty()
                    LaunchedEffect(conversationId) {
                        if (conversationId.isNotBlank()) {
                            aiRecordViewModel.openConversation(conversationId)
                        }
                    }
                    AiConversationScreen(
                        conversationId = conversationId,
                        detailState = aiRecordUiState.detail,
                        appState = uiState,
                        actionHandler = aiRecordActionHandler,
                        onBack = {
                            navController.navigate(Screen.AiRecord.route) {
                                popUpTo(Screen.AiRecord.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable(Screen.Trends.route) {
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = animatedBottomPadding)) {
                        TrendsScreen(
                            uiState = uiState,
                            syncStatusUiState = syncStatusUiState,
                            onManualSync = viewModel::runManualSync,
                            onManualRestoreCheck = viewModel::runManualRestoreCheck
                        )
                    }
                }
            }
        }

        SuccessConfirmOverlay(
            isVisible = showSuccessOverlay,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
