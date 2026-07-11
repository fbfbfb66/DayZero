package com.example

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.identity.StaticLocalIdentityProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.repository.RemoteAiDraftRepository
import com.example.data.repository.RoomFoodCardPhotoAssignmentRepository
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.assistantPlaceholderId
import com.example.domain.repository.ConversationRepository
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.domain.usecase.DiscardStagedMediaUseCase
import com.example.domain.usecase.ImportLocalMediaUseCase
import com.example.domain.usecase.ObserveConversationMediaUseCase
import com.example.domain.usecase.RetryLocalMediaImportUseCase
import com.example.domain.usecase.SendUserMessageWithMediaUseCase
import com.example.domain.usecase.UpdateFoodCardPhotoAssignmentsUseCase
import com.example.ui.screens.AiRecordViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Phase 4B-1 requirement 36-44 (editor slice): the editor's save path must run
 * through the REAL UpdateFoodCardPhotoAssignmentsUseCase into the REAL Room
 * repository — raw card JSON updated in place, unknown fields preserved, one
 * chat-sync queue entry, idempotent re-save, and no business record write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiRecordPhotoEditorSavePersistenceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var database: DayZeroDatabase

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private val conversationId = "conv-photo-editor"
    private val userId = "user-image-1"
    private val assistantId = assistantPlaceholderId(userId)

    @Test
    fun editorSaveUpdatesRawCardJsonEnqueuesChatSyncAndStaysIdempotent() =
        runTest(mainDispatcherRule.testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            // Synchronous executors so Room.withTransaction invoked from viewModelScope
            // does not deadlock under the test dispatcher (see project memory).
            val directExecutor = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(directExecutor)
                .setTransactionExecutor(directExecutor)
                .build()

            seedRoom()
            val baselineQueueCount = database.syncQueueDao().getPendingCount()

            val aiDraftRepository = RemoteAiDraftRepository(
                apiService = ThrowingAiDraftApiService(),
                database = database
            )
            val viewModel = createViewModel(aiDraftRepository)
            viewModel.openConversation(conversationId)
            advanceUntilIdle()

            viewModel.openPhotoAssignmentEditor("card", initialMealIndex = 0)
            assertNotNull(viewModel.photoEditor.value)

            // Assign m2 to the first meal (m1 is already there from the seed).
            viewModel.photoEditorAssignToSelectedMeal("m2")
            viewModel.savePhotoAssignments()
            advanceUntilIdle()

            assertNull(viewModel.photoEditor.value)
            val storedCard = JSONArray(
                database.aiChatMessageDao().getMessageById(assistantId)!!.assistantCardsJson
            ).getJSONObject(0)
            // Unknown fields survive the raw JSON patch.
            assertEquals("card-unknown", storedCard.getString("unknownCard"))
            val mealIds = storedCard.getJSONArray("meals").getJSONObject(0).getJSONArray("sourceMediaIds")
            assertEquals(listOf("m1", "m2"), (0 until mealIds.length()).map { mealIds.getString(it) })
            assertEquals(baselineQueueCount + 1, database.syncQueueDao().getPendingCount())

            // No business record was written by the photo save.
            database.query("SELECT COUNT(*) FROM daily_records", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }

            // Idempotent re-save: same assignments → Unchanged → no new queue entry, editor closes.
            viewModel.openPhotoAssignmentEditor("card", initialMealIndex = 0)
            assertNotNull(viewModel.photoEditor.value)
            viewModel.savePhotoAssignments()
            advanceUntilIdle()
            assertNull(viewModel.photoEditor.value)
            assertEquals(baselineQueueCount + 1, database.syncQueueDao().getPendingCount())
        }

    private suspend fun seedRoom() {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = conversationId,
                conversationDate = LocalDate.of(2026, 7, 9).toString(),
                title = "photos",
                lastMessagePreview = "",
                lastActivityAt = 1000L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                userId, conversationId, "User", "", 1, null, "Text",
                """{"media":{"schemaVersion":1,"sourceMediaIds":["m1","m2"]}}""", null, null, 1
            )
        )
        val card =
            """{"type":"show_confirm_card","id":"card","confirmType":"food_record","state":"pending","unknownCard":"card-unknown","buttons":[{"id":"confirm","label":"确认"}],"meals":[{"mealType":"lunch","mealLabel":"午餐","sourceMediaIds":["m1"],"items":[{"name":"rice","calories":100,"calorieConfidence":"medium"}]}]}"""
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(assistantId, conversationId, "Assistant", "ok", 2, null, "Text", null, "[$card]", null, 2)
        )
    }

    private fun createViewModel(aiDraftRepository: RemoteAiDraftRepository): AiRecordViewModel {
        val assignmentRepository = RoomFoodCardPhotoAssignmentRepository(
            database = database,
            identityProvider = StaticLocalIdentityProvider("owner")
        )
        val mediaRepository = NoOpMediaRepository()
        val importRepository = NoOpImportRepository()
        return AiRecordViewModel(
            conversationRepository = InMemoryConversationRepository().apply {
                seedConversation(
                    Conversation(
                        id = conversationId,
                        conversationDate = LocalDate.of(2026, 7, 9),
                        title = "photos",
                        lastMessagePreview = "",
                        createdAt = 1000L,
                        updatedAt = 1000L,
                        lastActivityAt = 1000L
                    )
                )
            },
            aiDraftRepository = aiDraftRepository,
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            observeConversationMediaUseCase = ObserveConversationMediaUseCase(mediaRepository),
            importLocalMediaUseCase = ImportLocalMediaUseCase(
                mediaRepository,
                importRepository,
                com.example.domain.usecase.MediaIdGenerator { "id" }
            ),
            retryLocalMediaImportUseCase = RetryLocalMediaImportUseCase(mediaRepository, importRepository),
            discardStagedMediaUseCase = DiscardStagedMediaUseCase(mediaRepository, importRepository),
            sendUserMessageWithMediaUseCase = SendUserMessageWithMediaUseCase(
                object : com.example.domain.repository.ChatMediaTransactionRepository {
                    override suspend fun sendUserMessageWithMedia(
                        request: com.example.domain.model.ai.SendUserMessageWithMediaRequest
                    ): com.example.domain.model.ai.SendUserMessageWithMediaResult =
                        com.example.domain.model.ai.SendUserMessageWithMediaResult.Committed(request.userMessageId, "p")
                }
            ),
            currentIdentityProvider = StaticLocalIdentityProvider("owner"),
            networkAvailabilityProvider = com.example.domain.network.NetworkAvailabilityProvider { true },
            updateFoodCardPhotoAssignmentsUseCase = UpdateFoodCardPhotoAssignmentsUseCase(assignmentRepository),
            savedStateHandle = SavedStateHandle()
        )
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<Conversation>>(emptyList())

        fun seedConversation(conversation: Conversation) {
            conversations.update { it + conversation }
        }

        override suspend fun insertConversation(conversation: Conversation) {
            conversations.update { it + conversation }
        }

        override suspend fun getConversationById(id: String): Conversation? =
            conversations.value.find { it.id == id }

        override fun observeConversations(): Flow<List<Conversation>> = conversations.asStateFlow()
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = conversations.asStateFlow()
        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) = Unit

        override suspend fun softDeleteConversation(id: String, deletedAt: Long) = Unit
    }

    private class NoOpMediaRepository : com.example.domain.repository.MediaRepository {
        override fun observeConversationMedia(conversationId: String): Flow<List<com.example.domain.model.media.MediaAsset>> =
            flowOf(emptyList())

        override suspend fun getConversationMedia(conversationId: String): List<com.example.domain.model.media.MediaAsset> =
            emptyList()

        override suspend fun getMediaByIds(ids: List<String>): List<com.example.domain.model.media.MediaAsset> = emptyList()
        override suspend fun createStagedMedia(
            requests: List<com.example.domain.model.media.NewMediaAssetRequest>,
            now: Long
        ): List<com.example.domain.model.media.MediaAsset> = emptyList()

        override suspend fun attachMediaToMessage(
            mediaIds: List<String>,
            conversationId: String,
            messageId: String,
            now: Long
        ) = Unit

        override suspend fun markMediaReady(
            id: String,
            conversationId: String,
            masterRelativePath: String,
            thumbnailRelativePath: String,
            mimeType: String,
            width: Int,
            height: Int,
            byteSize: Long,
            sha256: String,
            now: Long
        ): com.example.domain.model.media.MediaAsset = error("unused")

        override suspend fun markMediaFailed(
            id: String,
            conversationId: String,
            failureCode: String?,
            now: Long
        ): com.example.domain.model.media.MediaAsset = error("unused")

        override suspend fun softDeleteMedia(id: String, conversationId: String, now: Long) = Unit
        override suspend fun findStaleStagedMedia(updatedBefore: Long): List<com.example.domain.model.media.MediaAsset> =
            emptyList()
    }

    private class NoOpImportRepository : com.example.domain.repository.LocalMediaImportRepository {
        override suspend fun importStagedMedia(
            mediaId: String,
            request: com.example.domain.model.media.ImportLocalMediaRequest
        ): com.example.domain.model.media.LocalMediaImportItemResult =
            com.example.domain.model.media.LocalMediaImportItemResult.Failed(
                mediaId,
                com.example.domain.model.media.MediaImportFailureCode.UNKNOWN
            )

        override suspend fun retryImport(mediaId: String): com.example.domain.model.media.LocalMediaImportItemResult =
            com.example.domain.model.media.LocalMediaImportItemResult.Failed(
                mediaId,
                com.example.domain.model.media.MediaImportFailureCode.UNKNOWN
            )

        override suspend fun discardStagedMedia(mediaId: String): Boolean = true
        override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> = emptyList()
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
