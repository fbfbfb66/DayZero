package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.VisionAssistantTurnOrchestrator
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MediaAssetEntity
import com.example.data.media.AiImageDerivativeProcessor
import com.example.data.media.AndroidMediaFileStore
import com.example.data.media.ProcessedImageMetadata
import com.example.data.repository.AndroidVisionAttachmentPreparationRepository
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomChatMediaTransactionRepository
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.AskMissingInfoCardPayload
import com.example.domain.model.ai.assistant.AskMissingInfoOption
import com.example.domain.model.ai.assistant.ConfirmCardItem
import com.example.domain.model.ai.assistant.ConfirmCardMeal
import com.example.domain.model.ai.assistant.ConfirmCardOption
import com.example.domain.model.ai.assistant.ShowConfirmCardPayload
import com.example.domain.model.ai.assistant.VisionAssistantTurnResult
import com.example.domain.model.ai.assistant.assistantPlaceholderId
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.PrepareVisionAttachmentsForMessageUseCase
import com.example.domain.usecase.ReleasePreparedVisionAttachmentsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/**
 * Full real production-path integration test for the vision-direct entry point (when the user's
 * message already implies a meal type and the confirm card comes straight from the vision turn).
 *
 * Real chain: persisted image user message (production [RoomChatMediaTransactionRepository] entry,
 * real READY [MediaAssetEntity], real master file) -> real
 * [AndroidVisionAttachmentPreparationRepository] -> [VisionAssistantTurnOrchestrator] finalize ->
 * real [RemoteAiDraftRepository] Room persist -> re-read `assistantCardsJson` + mapped domain card.
 * Covers both streaming final and fallback final via the same finalize/persist path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionSingleMealPhotoAssignmentProductionPathTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var database: DayZeroDatabase

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun visionDirect_streaming_singleMeal_assignsSourceMediaIds() =
        runVisionAssignmentTest(useFallback = false)

    @Test
    fun visionDirect_edgeWinner_singleMeal_assignsSourceMediaIds() =
        runVisionAssignmentTest(useFallback = true)

    @Test
    fun visionDirect_edgeWinner_threeMeals_sanitizesAskKeepsOnePlaceholderAndLeavesPhotosUnassigned() =
        runTest(mainDispatcherRule.testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            val prepRepository = AndroidVisionAttachmentPreparationRepository(
                context = context,
                messageDao = database.aiChatMessageDao(),
                mediaDao = database.mediaAssetDao(),
                fileStore = AndroidMediaFileStore(context),
                derivativeProcessor = FakeAiImageDerivativeProcessor()
            )
            val chatTransactionRepository = RoomChatMediaTransactionRepository(
                database = database,
                identityProvider = StaticIdentityProvider(),
                chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao())
            )
            val aiDraftRepository = RemoteAiDraftRepository(
                apiService = ThrowingAiDraftApiService(),
                database = database
            )
            val assistantRepository = ThreeMealFallbackAssistantRepository()
            val orchestrator = VisionAssistantTurnOrchestrator(
                prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(prepRepository),
                releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(prepRepository),
                aiAssistantRepository = assistantRepository,
                aiDraftRepository = aiDraftRepository,
                recordRepository = InMemoryRecordRepository(),
                conversationRepository = EmptyConversationRepository(),
                currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 7, 9)),
                latencyLogger = AiLatencyTraceLogger(context)
            )

            val conversationId = insertConversation(context)
            val mediaIds = (1..3).map { order ->
                createReadyMasterImage(context, conversationId, order.toLong())
            }
            val userMessageId = commitImageUserMessage(
                chatTransactionRepository = chatTransactionRepository,
                conversationId = conversationId,
                mediaIds = mediaIds,
                text = "第一张早餐，第二张午餐，第三张晚餐，帮我记录一日三餐"
            )

            val result = orchestrator.runVisionTurn(conversationId, userMessageId)
            advanceUntilIdle()

            assertTrue(result is VisionAssistantTurnResult.Success)
            assertEquals(mediaIds, assistantRepository.streamAttachmentIds)
            assertTrue(assistantRepository.fallbackAttachmentIds.isEmpty())
            assertTrue(assistantRepository.fallbackEffectiveText.isEmpty())

            val messages = database.aiChatMessageDao().observeMessagesByConversationId(conversationId).first()
            assertEquals(1, messages.count { it.role == "User" })
            assertEquals(1, messages.count { it.role == "Assistant" })

            val assistantMessageId = assistantPlaceholderId(userMessageId)
            val finalMessage = aiDraftRepository.getChatMessageById(assistantMessageId)!!
            val confirmCard = finalMessage.assistantCards.filterIsInstance<ShowConfirmCardPayload>().single()
            assertEquals(3, confirmCard.meals!!.size)
            confirmCard.meals!!.forEach { meal -> assertNull(meal.sourceMediaIds) }
            assertEquals(0, finalMessage.assistantCards.filterIsInstance<AskMissingInfoCardPayload>().size)
            assertEquals(mediaIds, aiDraftRepository.getChatMessageById(userMessageId)!!.sourceMediaIds)

            val rawJson = database.aiChatMessageDao().getMessageById(assistantMessageId)!!.assistantCardsJson!!
            assertTrue(rawJson.contains("\"show_confirm_card\""))
            assertTrue(!rawJson.contains("ask_missing_info_card"))
        }

    @Test
    fun visionDirect_edgeWinner_threeMeals_preservesLegalRemotePhotoAssignments() =
        runTest(mainDispatcherRule.testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            val prepRepository = AndroidVisionAttachmentPreparationRepository(
                context = context,
                messageDao = database.aiChatMessageDao(),
                mediaDao = database.mediaAssetDao(),
                fileStore = AndroidMediaFileStore(context),
                derivativeProcessor = FakeAiImageDerivativeProcessor()
            )
            val chatTransactionRepository = RoomChatMediaTransactionRepository(
                database = database,
                identityProvider = StaticIdentityProvider(),
                chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao())
            )
            val aiDraftRepository = RemoteAiDraftRepository(
                apiService = ThrowingAiDraftApiService(),
                database = database
            )
            val conversationId = insertConversation(context)
            val mediaIds = (1..3).map { order ->
                createReadyMasterImage(context, conversationId, order.toLong())
            }
            val userMessageId = commitImageUserMessage(
                chatTransactionRepository = chatTransactionRepository,
                conversationId = conversationId,
                mediaIds = mediaIds,
                text = "第一张早餐，第二张午餐，第三张晚餐，帮我记录一日三餐"
            )
            val orchestrator = VisionAssistantTurnOrchestrator(
                prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(prepRepository),
                releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(prepRepository),
                aiAssistantRepository = ThreeMealFallbackAssistantRepository(assignedMediaIds = mediaIds),
                aiDraftRepository = aiDraftRepository,
                recordRepository = InMemoryRecordRepository(),
                conversationRepository = EmptyConversationRepository(),
                currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 7, 9)),
                latencyLogger = AiLatencyTraceLogger(context)
            )

            assertTrue(orchestrator.runVisionTurn(conversationId, userMessageId) is VisionAssistantTurnResult.Success)
            advanceUntilIdle()

            val confirmCard = aiDraftRepository.getChatMessageById(assistantPlaceholderId(userMessageId))!!
                .assistantCards.filterIsInstance<ShowConfirmCardPayload>().single()
            assertEquals(listOf(mediaIds[0]), confirmCard.meals!![0].sourceMediaIds)
            assertEquals(listOf(mediaIds[1]), confirmCard.meals!![1].sourceMediaIds)
            assertEquals(listOf(mediaIds[2]), confirmCard.meals!![2].sourceMediaIds)
        }

    private fun runVisionAssignmentTest(useFallback: Boolean) =
        runTest(mainDispatcherRule.testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
                .allowMainThreadQueries()
                .build()

            val fileStore = AndroidMediaFileStore(context)
            val prepRepository = AndroidVisionAttachmentPreparationRepository(
                context = context,
                messageDao = database.aiChatMessageDao(),
                mediaDao = database.mediaAssetDao(),
                fileStore = fileStore,
                derivativeProcessor = FakeAiImageDerivativeProcessor()
            )
            val chatTransactionRepository = RoomChatMediaTransactionRepository(
                database = database,
                identityProvider = StaticIdentityProvider(),
                chatSyncQueueWriter = ChatSyncQueueWriter(database.syncQueueDao())
            )
            val aiDraftRepository = RemoteAiDraftRepository(
                apiService = ThrowingAiDraftApiService(),
                database = database
            )
            val assistantRepository = SingleMealConfirmCardAssistantRepository(useFallback)
            val orchestrator = VisionAssistantTurnOrchestrator(
                prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(prepRepository),
                releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(prepRepository),
                aiAssistantRepository = assistantRepository,
                aiDraftRepository = aiDraftRepository,
                recordRepository = InMemoryRecordRepository(),
                conversationRepository = EmptyConversationRepository(),
                currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 7, 7)),
                latencyLogger = AiLatencyTraceLogger(context)
            )

            val conversationId = insertConversation(context)
            val mediaId = createReadyMasterImage(context, conversationId)
            val userMessageId = commitImageUserMessage(chatTransactionRepository, conversationId, mediaId)

            val result = orchestrator.runVisionTurn(conversationId, userMessageId)
            advanceUntilIdle()
            assertTrue("vision turn should succeed", result is VisionAssistantTurnResult.Success)

            val assistantMessageId = assistantPlaceholderId(userMessageId)
            val finalMessage = aiDraftRepository.getChatMessageById(assistantMessageId)
            assertNotNull(finalMessage)
            val confirmCard = finalMessage!!.assistantCards
                .filterIsInstance<ShowConfirmCardPayload>().single()
            assertEquals(
                "single meal must receive the persisted image media id",
                listOf(mediaId),
                confirmCard.meals?.single()?.sourceMediaIds
            )

            val rawJson = database.aiChatMessageDao().getMessageById(assistantMessageId)?.assistantCardsJson
            assertNotNull(rawJson)
            assertTrue(
                "assistantCardsJson must contain a non-empty sourceMediaIds array, was: $rawJson",
                rawJson!!.contains("\"sourceMediaIds\":[\"$mediaId\"]")
            )
        }

    private suspend fun insertConversation(context: Context): String {
        val id = UUID.randomUUID().toString()
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = LocalDate.of(2026, 7, 7).toString(),
                title = "Test",
                lastMessagePreview = "",
                lastActivityAt = 1000L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        return id
    }

    private suspend fun createReadyMasterImage(
        context: Context,
        conversationId: String,
        conversationOrder: Long = 1L
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = null,
                    conversationOrder = conversationOrder,
                    masterRelativePath = null,
                    thumbnailRelativePath = null,
                    mimeType = null,
                    width = null,
                    height = null,
                    byteSize = null,
                    sha256 = null,
                    source = MediaSource.PHOTO_PICKER.name,
                    lifecycleState = MediaLifecycleState.STAGED.name,
                    failureCode = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            )
        )
        val masterFile = File(context.filesDir, "media/master/$id.jpg")
        masterFile.parentFile?.mkdirs()
        val thumbnailFile = File(context.filesDir, "media/thumbnail/$id.jpg")
        thumbnailFile.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(80, 120, 160))
        masterFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        thumbnailFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        database.mediaAssetDao().markReady(
            id = id,
            masterRelativePath = "media/master/$id.jpg",
            thumbnailRelativePath = "media/thumbnail/$id.jpg",
            mimeType = "image/jpeg",
            width = 120,
            height = 120,
            byteSize = masterFile.length(),
            sha256 = "sha256-$id",
            updatedAt = now
        )
        return id
    }

    private suspend fun commitImageUserMessage(
        chatTransactionRepository: RoomChatMediaTransactionRepository,
        conversationId: String,
        mediaId: String
    ): String = commitImageUserMessage(
        chatTransactionRepository = chatTransactionRepository,
        conversationId = conversationId,
        mediaIds = listOf(mediaId),
        text = "午餐"
    )

    private suspend fun commitImageUserMessage(
        chatTransactionRepository: RoomChatMediaTransactionRepository,
        conversationId: String,
        mediaIds: List<String>,
        text: String
    ): String {
        val userMessageId = UUID.randomUUID().toString()
        val result = chatTransactionRepository.sendUserMessageWithMedia(
            SendUserMessageWithMediaRequest(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = text,
                orderedMediaIds = mediaIds,
                createdAt = System.currentTimeMillis()
            )
        )
        assertTrue(
            "user message must commit, was $result",
            result is SendUserMessageWithMediaResult.Committed
        )
        return userMessageId
    }

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
                sourceMediaIds = null // deployed edge does not assign; client must fill it in
            )
            return AiAssistantTurn(
                id = "turn-confirm",
                intent = AiIntent.GeneralChat,
                replyText = "这是我为你生成的午餐记录草稿。",
                cards = listOf(
                    ShowConfirmCardPayload(
                        id = "confirm-1",
                        confirmType = "food_record",
                        title = "确认记录",
                        message = "帮你识别到以下食物",
                        originalText = "午餐",
                        mealType = null,
                        items = emptyList(),
                        meals = listOf(meal),
                        buttons = listOf(
                            ConfirmCardOption("cancel", "取消"),
                            ConfirmCardOption("confirm", "确认")
                        )
                    )
                ),
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

    private class ThreeMealFallbackAssistantRepository(
        private val assignedMediaIds: List<String>? = null
    ) : AiAssistantRepository {
        var streamAttachmentIds: List<String> = emptyList()
            private set
        var fallbackAttachmentIds: List<String> = emptyList()
            private set
        var streamEffectiveText: String = ""
            private set
        var fallbackEffectiveText: String = ""
            private set

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            streamAttachmentIds = request.attachments.orEmpty().map { it.mediaId }
            streamEffectiveText = request.userText
            onDelta("正在识别三餐")
            val turn = sendMessage(request)
            fallbackAttachmentIds = emptyList()
            fallbackEffectiveText = ""
            return turn
        }

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            fallbackAttachmentIds = request.attachments.orEmpty().map { it.mediaId }
            fallbackEffectiveText = request.userText
            val labels = listOf("早餐", "午餐", "晚餐")
            val types = listOf("breakfast", "lunch", "dinner")
            val meals = types.mapIndexed { index, type ->
                ConfirmCardMeal(
                    mealType = type,
                    mealLabel = labels[index],
                    subtotalCalories = 100 + index,
                    items = listOf(
                        ConfirmCardItem(
                            id = "item-$index",
                            name = "food-$index",
                            amountText = "1份",
                            calories = 100 + index,
                            calorieConfidence = "estimated"
                        )
                    ),
                    sourceMediaIds = assignedMediaIds?.let { listOf(it[index]) }
                )
            }
            return AiAssistantTurn(
                id = "turn-three-meals",
                intent = AiIntent.GeneralChat,
                replyText = "这是三餐记录草稿。",
                cards = listOf(
                    ShowConfirmCardPayload(
                        id = "confirm-three",
                        confirmType = "food_record",
                        title = "确认记录",
                        message = "请确认三餐。",
                        originalText = "第一张早餐，第二张午餐，第三张晚餐",
                        mealType = null,
                        items = emptyList(),
                        meals = meals,
                        buttons = listOf(
                            ConfirmCardOption("confirm", "确认"),
                            ConfirmCardOption("cancel", "取消")
                        )
                    ),
                    AskMissingInfoCardPayload(
                        id = "ask-conflict",
                        title = "补充餐次",
                        message = "记录到哪一餐？",
                        field = "mealType",
                        originalText = "第一张早餐，第二张午餐，第三张晚餐",
                        options = listOf(AskMissingInfoOption("breakfast", "早餐"))
                    )
                ),
                suggestedReplies = emptyList()
            )
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
        override fun observeRecords(): Flow<List<DailyRecord>> = records
        override suspend fun upsertRecord(record: DailyRecord) = Unit
        override suspend fun deleteRecordById(recordId: String) = Unit
        override suspend fun getRecordById(recordId: String): DailyRecord? = null
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? = null
        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) = Unit
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) = Unit
        override suspend fun clearAllRecords() = Unit
    }

    private class FixedCurrentDateProvider(private val date: LocalDate) : CurrentDateProvider {
        override fun currentDate(): LocalDate = date
    }

    private class StaticIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity = AppIdentity(
            localOwnerId = "owner-1",
            remoteUserId = null,
            authProvider = "local",
            canRemoteSync = false
        )
    }

    private class FakeAiImageDerivativeProcessor(
        private val derivativeByteSize: Long = 1024L
    ) : AiImageDerivativeProcessor {
        override fun createAiDerivative(sourceFile: File, destFile: File): ProcessedImageMetadata {
            val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            val payload = ByteArray(derivativeByteSize.toInt()) { 0x00 }
            destFile.outputStream().use { out ->
                out.write(header)
                out.write(payload)
            }
            return ProcessedImageMetadata(
                width = 100,
                height = 100,
                mimeType = com.example.domain.model.ai.assistant.PreparedVisionRequest.MIME_TYPE_JPEG
            )
        }
    }

    private class ThrowingAiDraftApiService : com.example.data.remote.api.AiDraftApiService {
        override suspend fun generateDraft(
            request: com.example.data.remote.dto.AiDraftRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun generateDailySummary(
            request: com.example.data.remote.dto.AiSummaryRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun sendAssistantTurnV2WithResponse(
            request: com.example.data.remote.dto.assistant.AiAssistantRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
        override suspend fun classifyUserIntent(
            request: com.example.data.remote.dto.IntentClassifierRequestDto
        ) = throw UnsupportedOperationException("not used in this test")
    }
}
