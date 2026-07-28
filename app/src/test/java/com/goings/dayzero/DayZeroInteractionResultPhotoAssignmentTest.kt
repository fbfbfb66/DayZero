package com.goings.dayzero

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.repository.RemoteAiDraftRepository
import com.goings.dayzero.data.telemetry.AiLatencyTraceLogger
import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.MealType
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.Conversation
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantRequest
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantTurn
import com.goings.dayzero.domain.model.ai.assistant.AiIntent
import com.goings.dayzero.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.goings.dayzero.domain.model.ai.assistant.AskMissingInfoOption
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardItem
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardOption
import com.goings.dayzero.domain.model.ai.assistant.ShowConfirmCardPayload
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId
import com.goings.dayzero.domain.repository.AiAssistantRepository
import com.goings.dayzero.domain.repository.ConversationRepository
import com.goings.dayzero.domain.repository.RecordRepository
import com.goings.dayzero.domain.time.CurrentDateProvider
import com.goings.dayzero.domain.usecase.ClearLocalDataUseCase
import com.goings.dayzero.domain.usecase.CreateConversationWithFirstMessageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/**
 * Production-path test for Phase 4C-F1 root cause (b): an image-only turn's confirm card is
 * produced by a follow-up interaction_result turn (the user answering the meal-type question),
 * which is finalized by [DayZeroViewModel.completeAssistantMessage] — NOT the vision orchestrator.
 *
 * This exercises the real interaction_result path against real Room ([RemoteAiDraftRepository] +
 * [DayZeroDatabase]) and asserts that the persisted assistant confirm card carries the originating
 * image message's `sourceMediaIds` (resolved via the deterministic assistantPlaceholderId pairing),
 * with a real non-empty JSON array in `assistantCardsJson`. Covers both streaming and fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayZeroInteractionResultPhotoAssignmentTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var database: DayZeroDatabase
    private lateinit var aiDraftRepository: RemoteAiDraftRepository

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun interactionResult_streaming_confirmCard_getsOriginImageMediaIds() =
        runInteractionResultAssignmentTest(useFallback = false)

    @Test
    fun interactionResult_edgeWinner_confirmCard_getsOriginImageMediaIds() =
        runInteractionResultAssignmentTest(useFallback = true)

    private fun runInteractionResultAssignmentTest(useFallback: Boolean) =
        runTest(mainDispatcherRule.testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            // Synchronous executors so Room.withTransaction runs inline and does not deadlock when
            // invoked from viewModelScope coroutines under a test dispatcher.
            val directExecutor = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(directExecutor)
                .setTransactionExecutor(directExecutor)
                .build()
            aiDraftRepository = RemoteAiDraftRepository(
                apiService = ThrowingAiDraftApiService(),
                database = database
            )

            val conversationId = "conv-img"
            val imageUserMessageId = "user-image-1"
            val mediaId = "media-abc"
            val askCardId = "ask-mealtype-1"

            // Conversation row must exist for the chat-message foreign key in real Room.
            database.conversationDao().insertConversation(
                ConversationEntity(
                    id = conversationId,
                    conversationDate = LocalDate.of(2026, 7, 7).toString(),
                    title = "img",
                    lastMessagePreview = "",
                    lastActivityAt = 1000L,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )

            // Persisted image user message: authoritative media source (real Room contentJson).
            aiDraftRepository.insertChatMessage(
                conversationId,
                AiChatMessage(
                    id = imageUserMessageId,
                    conversationId = conversationId,
                    role = ChatRole.User,
                    text = "",
                    sourceMediaIds = listOf(mediaId)
                )
            )
            // Vision turn placeholder holding the meal-type question. Its id is the deterministic
            // pairing of the image user message id — the link the fix relies on.
            aiDraftRepository.insertChatMessage(
                conversationId,
                AiChatMessage(
                    id = assistantPlaceholderId(imageUserMessageId),
                    conversationId = conversationId,
                    role = ChatRole.Assistant,
                    text = "这些图片是哪一餐呢？",
                    assistantCards = listOf(
                        AskMissingInfoCardPayload(
                            id = askCardId,
                            title = "选择餐次",
                            message = "这是哪一餐？",
                            field = "mealType",
                            originalText = "",
                            options = listOf(
                                AskMissingInfoOption("breakfast", "早餐"),
                                AskMissingInfoOption("lunch", "午餐"),
                                AskMissingInfoOption("dinner", "晚餐"),
                                AskMissingInfoOption("snack", "加餐")
                            )
                        )
                    )
                )
            )

            val assistantRepository = SingleMealConfirmCardAssistantRepository(useFallback)
            val viewModel = buildViewModel(context, assistantRepository)

            viewModel.sendInteractionResult(
                interactionId = askCardId,
                actionType = "ask_missing_info_card",
                optionId = "lunch",
                optionLabel = "午餐",
                field = "mealType",
                originalText = ""
            )
            advanceUntilIdle()

            // Locate the new assistant message that carries the confirm card.
            val confirmMessage = aiDraftRepository.getRecentChatMessages(conversationId, 50)
                .lastOrNull { message ->
                    message.role == ChatRole.Assistant &&
                        message.assistantCards.any { it is ShowConfirmCardPayload }
                }
            assertNotNull("interaction_result should persist a confirm card", confirmMessage)

            val confirmCard = confirmMessage!!.assistantCards
                .filterIsInstance<ShowConfirmCardPayload>().single()
            assertEquals(
                "single meal must receive the origin image media id",
                listOf(mediaId),
                confirmCard.meals?.single()?.sourceMediaIds
            )

            // Raw Room JSON must contain a real non-empty sourceMediaIds array.
            val rawJson = database.aiChatMessageDao().getMessageById(confirmMessage.id)?.assistantCardsJson
            assertNotNull(rawJson)
            assertTrue(
                "assistantCardsJson must contain a non-empty sourceMediaIds array, was: $rawJson",
                rawJson!!.contains("\"sourceMediaIds\":[\"$mediaId\"]")
            )
        }

    private fun buildViewModel(
        context: android.content.Context,
        assistantRepository: AiAssistantRepository
    ): DayZeroViewModel {
        val conversationRepository = EmptyConversationRepository()
        val recordRepository = InMemoryRecordRepository()
        return DayZeroViewModel(
            recordRepository = recordRepository,
            aiDraftRepository = aiDraftRepository,
            aiAssistantRepository = assistantRepository,
            latencyLogger = AiLatencyTraceLogger(context),
            clearLocalDataUseCase = ClearLocalDataUseCase(recordRepository, aiDraftRepository),
            confirmFoodCardUseCase = testConfirmFoodCardUseCase(
                aiDraftRepository, conversationRepository, recordRepository
            ),
            createConversationWithFirstMessageUseCase =
                CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            conversationRepository = conversationRepository,
            currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 7, 7)),
            syncScheduler = NoOpSyncScheduler(),
            visionAssistantTurnOrchestrator =
                com.goings.dayzero.assistant.fakeVisionAssistantTurnOrchestrator(context),
            networkAvailabilityProvider = com.goings.dayzero.domain.network.NetworkAvailabilityProvider { true }
        )
    }

    /** Streams (or, on fallback, returns) a single-meal confirm card that omits sourceMediaIds. */
    private class SingleMealConfirmCardAssistantRepository(
        private val useFallback: Boolean
    ) : AiAssistantRepository {
        private fun confirmTurn(): AiAssistantTurn {
            val meal = ConfirmCardMeal(
                mealType = "lunch",
                mealLabel = "午餐",
                subtotalCalories = 520,
                items = listOf(
                    ConfirmCardItem(
                        id = "item-1",
                        name = "螺蛳粉",
                        amountText = "1份",
                        calories = 520,
                        calorieConfidence = "estimated"
                    )
                ),
                sourceMediaIds = null // remote omitted assignment (deployed edge does not set it)
            )
            val card = ShowConfirmCardPayload(
                id = "confirm-1",
                confirmType = "food_record",
                title = "确认记录",
                message = "帮你识别到以下食物",
                originalText = "",
                mealType = null,
                items = emptyList(),
                meals = listOf(meal),
                buttons = listOf(
                    ConfirmCardOption("cancel", "取消"),
                    ConfirmCardOption("confirm", "确认")
                )
            )
            return AiAssistantTurn(
                id = "turn-confirm",
                intent = AiIntent.GeneralChat,
                replyText = "这是我为你生成的午餐记录草稿。",
                cards = listOf(card),
                suggestedReplies = emptyList()
            )
        }

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn = confirmTurn()

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            onDelta("这是我为你生成的午餐记录草稿。")
            return confirmTurn()
        }
    }

    private class EmptyConversationRepository : ConversationRepository {
        override suspend fun insertConversation(conversation: Conversation) = Unit
        override suspend fun getConversationById(id: String): Conversation? = null
        override fun observeConversations(): Flow<List<Conversation>> = MutableStateFlow(emptyList())
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> =
            MutableStateFlow(emptyList())
        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) = Unit
        override suspend fun softDeleteConversation(id: String, deletedAt: Long) = Unit
    }

    private class InMemoryRecordRepository : RecordRepository {
        private val records = MutableStateFlow<List<DailyRecord>>(emptyList())
        override fun observeRecords(): Flow<List<DailyRecord>> = records.asStateFlow()
        override suspend fun upsertRecord(record: DailyRecord) {
            records.update { current -> current.filterNot { it.id == record.id } + record }
        }
        override suspend fun deleteRecordById(recordId: String) = Unit
        override suspend fun getRecordById(recordId: String): DailyRecord? =
            records.value.find { it.id == recordId }
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? =
            records.value.find { it.date == date && it.status == status }
        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) = Unit
        override suspend fun clearAllRecords() { records.value = emptyList() }
    }

    private class NoOpSyncScheduler : com.goings.dayzero.data.sync.SyncScheduler {
        override fun requestSync(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
        override fun requestBackfill(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
        override fun requestSyncAndBackfill(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
        override fun requestPull(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
        override fun requestInitialRestore(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
        override fun requestSyncAndPull(reason: com.goings.dayzero.data.sync.SyncTriggerReason) = null
    }

    private class FixedCurrentDateProvider(private val date: LocalDate) : CurrentDateProvider {
        override fun currentDate(): LocalDate = date
    }

    private class ThrowingAiDraftApiService : com.goings.dayzero.data.remote.api.AiDraftApiService {
        override suspend fun generateDraft(
            request: com.goings.dayzero.data.remote.dto.AiDraftRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun generateDailySummary(
            request: com.goings.dayzero.data.remote.dto.AiSummaryRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun sendAssistantTurnV2WithResponse(
            request: com.goings.dayzero.data.remote.dto.assistant.AiAssistantRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun classifyUserIntent(
            request: com.goings.dayzero.data.remote.dto.IntentClassifierRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
    }
}
