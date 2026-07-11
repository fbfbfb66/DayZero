package com.example.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.AppState
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult
import com.example.domain.model.media.MediaAsset
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ChatMediaTransactionRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.MediaRepository
import com.example.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.example.domain.usecase.DiscardStagedMediaUseCase
import com.example.domain.usecase.ImportLocalMediaUseCase
import com.example.domain.usecase.ObserveConversationMediaUseCase
import com.example.domain.usecase.RetryLocalMediaImportUseCase
import com.example.domain.usecase.SendUserMessageWithMediaUseCase
import com.example.domain.network.NetworkAvailabilityProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiRecordMediaSendTest {

    @get:Rule
    val mainDispatcherRule = FeatureMainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun submitMediaMessage_withNoAttachmentsAndBlankText_doesNothing() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val viewModel = createViewModel(transactionRepository = fakeRepo)

        viewModel.submitMediaMessage("conv-1", "   ", emptyList())
        advanceUntilIdle()

        assertEquals(0, fakeRepo.callCount)
        assertEquals("Message cannot be blank", viewModel.uiState.value.detail.errorMessage)
    }

    @Test
    fun submitMediaMessage_withTextAndAttachments_callsUseCaseWithCorrectOrder() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a", "media-b")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(
                readyAsset("media-a", "conv-1"),
                readyAsset("media-b", "conv-1")
            )
        )

        viewModel.submitMediaMessage("conv-1", "  breakfast  ", listOf("media-a", "media-b"))
        advanceUntilIdle()

        assertEquals(1, fakeRepo.callCount)
        val request = fakeRepo.requests.single()
        assertEquals("conv-1", request.conversationId)
        assertEquals("breakfast", request.text)
        assertEquals(listOf("media-a", "media-b"), request.orderedMediaIds)
        assertEquals(2, request.orderedMediaIds.size)
    }

    @Test
    fun submitMediaMessage_success_clearsSubmittedDraftsAndEmitsEvent() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a", "media-b")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(
                readyAsset("media-a", "conv-1"),
                readyAsset("media-b", "conv-1")
            )
        )

        val eventDeferred = async { viewModel.events.first() }
        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a", "media-b"))
        advanceUntilIdle()

        val event = eventDeferred.await()
        assertTrue(event is AiRecordConversationEvent.MediaMessageCommitted)
        val committed = event as AiRecordConversationEvent.MediaMessageCommitted
        assertEquals("conv-1", committed.conversationId)
        assertEquals(fakeRepo.lastCommittedUserMessageId, committed.userMessageId)

        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertTrue(draftIds.isNullOrEmpty())
        assertNull(viewModel.uiState.value.detail.errorMessage)
    }

    @Test
    fun submitMediaMessage_failure_preservesDraftsAndText() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        fakeRepo.nextResult = SendUserMessageWithMediaResult.Failed(RuntimeException("disk full"))
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(readyAsset("media-a", "conv-1"))
        )

        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        advanceUntilIdle()

        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertEquals(listOf("media-a"), draftIds)
        assertNotNull(viewModel.uiState.value.detail.errorMessage)
    }

    @Test
    fun submitMediaMessage_doubleClick_onlyExecutesOnce() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(readyAsset("media-a", "conv-1"))
        )

        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        advanceUntilIdle()

        assertEquals(1, fakeRepo.callCount)
    }

    @Test
    fun submitMediaMessage_alreadyCommitted_emitsEventWithSameUserMessageId() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        fakeRepo.nextResult = SendUserMessageWithMediaResult.AlreadyCommitted
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(readyAsset("media-a", "conv-1"))
        )

        val eventDeferred = async { viewModel.events.first() }
        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        advanceUntilIdle()

        val event = eventDeferred.await() as AiRecordConversationEvent.MediaMessageCommitted
        assertEquals(fakeRepo.lastRequest?.userMessageId, event.userMessageId)
        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertTrue(draftIds.isNullOrEmpty())
    }

    @Test
    fun submitMediaMessage_conflict_doesNotEmitEventAndKeepsDrafts() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        fakeRepo.nextResult = SendUserMessageWithMediaResult.Conflict("inconsistent")
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(readyAsset("media-a", "conv-1"))
        )

        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        advanceUntilIdle()

        val event = withTimeoutOrNull(100) {
            viewModel.events.first()
        }
        assertNull(event)
        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertEquals(listOf("media-a"), draftIds)
    }

    @Test
    fun submitMediaMessage_newDraftsAfterCaptureAreNotCleared() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(
                readyAsset("media-a", "conv-1"),
                readyAsset("media-b", "conv-1")
            )
        )

        // Simulate a new draft arriving after the submit captured its immutable list.
        val currentIds = savedStateHandle.get<List<String>>("draft_ids_conv-1") ?: emptyList()
        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        savedStateHandle["draft_ids_conv-1"] = currentIds + "media-b"
        advanceUntilIdle()

        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertEquals(listOf("media-b"), draftIds)
    }

    @Test
    fun conversationScreen_noAttachment_callsSendAiMessage() {
        val sent = mutableListOf<Pair<String, String>>()
        val submittedMedia = mutableListOf<Triple<String, String, List<String>>>()
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(currentConversation = conversation("a", "A", 1L)),
                    appState = AppState(activeConversationId = "a"),
                    actionHandler = object : AiRecordActionHandler by NoOpActionHandler {
                        override fun sendAiMessage(conversationId: String, text: String): Boolean {
                            sent += conversationId to text
                            return true
                        }
                    },
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { c, t, ids -> submittedMedia += Triple(c, t, ids) }
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).performTextInput("hello")
        composeRule.onNodeWithTag(AiRecordTestTags.ConversationSend).performClick()

        assertEquals(listOf("a" to "hello"), sent)
        assertTrue(submittedMedia.isEmpty())
    }

    @Test
    fun conversationScreen_rejectedTextSend_preservesInputDraft() {
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(currentConversation = conversation("a", "A", 1L)),
                    appState = AppState(activeConversationId = "a"),
                    actionHandler = object : AiRecordActionHandler by NoOpActionHandler {
                        override fun sendAiMessage(conversationId: String, text: String): Boolean = false
                    },
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).performTextInput("keep me")
        composeRule.onNodeWithTag(AiRecordTestTags.ConversationSend).performClick()

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).assertTextEquals("keep me")
    }

    @Test
    fun conversationScreen_withAttachments_callsSubmitMediaMessage() {
        val sent = mutableListOf<Pair<String, String>>()
        val submittedMedia = mutableListOf<Triple<String, String, List<String>>>()
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(
                        currentConversation = conversation("a", "A", 1L),
                        draftState = ConversationAttachmentDraftState(
                            conversationId = "a",
                            attachmentIds = listOf("media-1", "media-2"),
                            assets = listOf(
                                readyAsset("media-1", "a"),
                                readyAsset("media-2", "a")
                            )
                        )
                    ),
                    appState = AppState(activeConversationId = "a"),
                    actionHandler = object : AiRecordActionHandler by NoOpActionHandler {
                        override fun sendAiMessage(conversationId: String, text: String): Boolean {
                            sent += conversationId to text
                            return true
                        }
                    },
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { c, t, ids -> submittedMedia += Triple(c, t, ids) }
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).performTextInput("breakfast")
        composeRule.onNodeWithTag(AiRecordTestTags.ConversationSend).performClick()

        assertTrue(sent.isEmpty())
        assertEquals(1, submittedMedia.size)
        val (c, t, ids) = submittedMedia.single()
        assertEquals("a", c)
        assertEquals("breakfast", t)
        assertEquals(listOf("media-1", "media-2"), ids)
    }

    @Test
    fun conversationScreen_importingBlocksSend() {
        var submittedMedia = 0
        var toasts = 0
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(
                        currentConversation = conversation("a", "A", 1L),
                        draftState = ConversationAttachmentDraftState(
                            conversationId = "a",
                            attachmentIds = listOf("media-1"),
                            assets = listOf(readyAsset("media-1", "a")),
                            importingCount = 1
                        )
                    ),
                    appState = AppState(activeConversationId = "a"),
                    actionHandler = NoOpActionHandler,
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> submittedMedia += 1 }
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationSend).performClick()
        assertEquals(0, submittedMedia)
    }

    @Test
    fun conversationScreen_analyzingDisablesInput() {
        composeRule.setContent {
            MyApplicationTheme {
                AiConversationScreen(
                    conversationId = "a",
                    detailState = AiConversationDetailState(currentConversation = conversation("a", "A", 1L)),
                    appState = AppState(activeConversationId = "a", isAnalyzing = true),
                    actionHandler = NoOpActionHandler,
                    events = MutableSharedFlow(),
                    onBack = {},
                    onImportPhotos = { _, _ -> },
                    onRemoveAttachment = { _, _ -> },
                    onRetryAttachment = { _, _ -> },
                    onNavigateToCamera = {},
                    onSetPickerOpen = { _, _ -> },
                    onSubmitMediaMessage = { _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag(AiRecordTestTags.ConversationInput).assertIsNotEnabled()
    }

    @Test
    fun submitMediaMessage_noNetwork_doesNotCallUseCaseAndPreservesDraft() = runTest(testDispatcher) {
        val fakeRepo = FakeChatMediaTransactionRepository()
        val networkProvider = FakeNetworkAvailabilityProvider(hasInternet = false)
        val savedStateHandle = SavedStateHandle()
        savedStateHandle["draft_ids_conv-1"] = listOf("media-a")
        val viewModel = createViewModel(
            transactionRepository = fakeRepo,
            savedStateHandle = savedStateHandle,
            mediaAssets = listOf(readyAsset("media-a", "conv-1")),
            networkAvailabilityProvider = networkProvider
        )

        viewModel.submitMediaMessage("conv-1", "breakfast", listOf("media-a"))
        advanceUntilIdle()

        assertEquals(0, fakeRepo.callCount)
        val draftIds = savedStateHandle.get<List<String>>("draft_ids_conv-1")
        assertEquals(listOf("media-a"), draftIds)
        assertEquals("当前无网络，连接后再发送", viewModel.uiState.value.detail.errorMessage)
    }

    @Test
    fun createConversationWithFirstMessage_noNetwork_doesNotCreateConversation() = runTest(testDispatcher) {
        val networkProvider = FakeNetworkAvailabilityProvider(hasInternet = false)
        val viewModel = createViewModel(
            transactionRepository = FakeChatMediaTransactionRepository(),
            networkAvailabilityProvider = networkProvider
        )

        viewModel.createConversationWithFirstMessage("hello")
        advanceUntilIdle()

        assertEquals("当前无网络，连接后再发送", viewModel.uiState.value.history.errorMessage)
        assertFalse(viewModel.uiState.value.history.isCreating)
    }

    private fun createViewModel(
        transactionRepository: FakeChatMediaTransactionRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        mediaAssets: List<MediaAsset> = emptyList(),
        networkAvailabilityProvider: NetworkAvailabilityProvider = FakeNetworkAvailabilityProvider(hasInternet = true)
    ): AiRecordViewModel {
        val mediaRepository = FakeMediaRepository(mediaAssets)
        val importRepository = object : com.example.domain.repository.LocalMediaImportRepository {
            override suspend fun importStagedMedia(mediaId: String, request: com.example.domain.model.media.ImportLocalMediaRequest) =
                com.example.domain.model.media.LocalMediaImportItemResult.Failed(mediaId, com.example.domain.model.media.MediaImportFailureCode.UNKNOWN)
            override suspend fun retryImport(mediaId: String) =
                com.example.domain.model.media.LocalMediaImportItemResult.Failed(mediaId, com.example.domain.model.media.MediaImportFailureCode.UNKNOWN)
            override suspend fun discardStagedMedia(mediaId: String) = true
            override suspend fun cleanupStaleMedia(updatedBefore: Long) = emptyList<String>()
        }
        val idGenerator = com.example.domain.usecase.MediaIdGenerator { UUID.randomUUID().toString() }
        val currentIdentityProvider = object : CurrentIdentityProvider {
            override suspend fun currentIdentity() = AppIdentity("owner-1", null, "local", false)
        }
        val aiDraftRepository = object : AiDraftRepository {
            override fun observeChatMessages(): Flow<List<AiChatMessage>> = emptyFlow()
            override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> = emptyFlow()
            override suspend fun generateDraft(request: com.example.domain.model.ai.AiDraftRequest) =
                error("unused")
            override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? = null
            override suspend fun getRecentChatMessages(conversationId: String, limit: Int) = emptyList<AiChatMessage>()
            override suspend fun findMessageByAssistantCardId(cardId: String) = null
            override suspend fun getChatMessageById(messageId: String) = null
            override suspend fun insertChatMessage(message: AiChatMessage) {}
            override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {}
            override suspend fun updateChatMessage(message: AiChatMessage) {}
            override suspend fun clearChatMessages() {}
            override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {}
            override fun clearStreamingState(conversationId: String) {}
        }

        return AiRecordViewModel(
            conversationRepository = InMemoryConversationRepository(),
            aiDraftRepository = aiDraftRepository,
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(aiDraftRepository),
            observeConversationMediaUseCase = ObserveConversationMediaUseCase(mediaRepository),
            importLocalMediaUseCase = ImportLocalMediaUseCase(mediaRepository, importRepository, idGenerator),
            retryLocalMediaImportUseCase = RetryLocalMediaImportUseCase(mediaRepository, importRepository),
            discardStagedMediaUseCase = DiscardStagedMediaUseCase(mediaRepository, importRepository),
            sendUserMessageWithMediaUseCase = SendUserMessageWithMediaUseCase(transactionRepository),
            currentIdentityProvider = currentIdentityProvider,
            networkAvailabilityProvider = networkAvailabilityProvider,
            updateFoodCardPhotoAssignmentsUseCase = com.example.domain.usecase.UpdateFoodCardPhotoAssignmentsUseCase(
                object : com.example.domain.repository.FoodCardPhotoAssignmentRepository {
                    override suspend fun updatePhotoAssignments(
                        cardId: String,
                        assignments: List<com.example.domain.repository.MealPhotoAssignment>
                    ) = com.example.domain.repository.UpdateFoodCardPhotoAssignmentsResult.Unchanged
                }
            ),
            savedStateHandle = savedStateHandle
        )
    }

    private object NoOpActionHandler : AiRecordActionHandler {
        override fun sendAiMessage(text: String) = true
        override fun sendAiMessage(conversationId: String, text: String) = true
        override fun startAssistantTurnForExistingUserMessage(conversationId: String, text: String) = Unit
        override fun startVisionAssistantTurnForExistingUserMessage(conversationId: String, userMessageId: String) = Unit
        override fun setActiveConversationId(conversationId: String?) = Unit
        override fun sendInteractionResult(
            interactionId: String,
            actionType: String,
            optionId: String,
            optionLabel: String,
            field: String?,
            originalText: String?,
            confirmType: String?,
            payloadSummary: com.example.domain.model.ai.assistant.PayloadSummary?
        ) = Unit
        override fun handleDateMismatchGuardResult(guardId: String, approved: Boolean) = Unit
        override fun updateFoodDraftCard(interactionId: String, weightKg: Double?, meals: List<com.example.domain.model.ai.assistant.ConfirmCardMeal>) = Unit
        override fun clearChatMessages() = Unit
        override fun clearLocalRecords() = Unit
        override fun clearAllData() = Unit
        override fun clearCloudBackupForDebug() = Unit
        override fun markAssistantMessageRendered(message: AiChatMessage) = Unit
    }

    private fun readyAsset(id: String, conversationId: String) = MediaAsset(
        id = id,
        ownerLocalId = "owner-1",
        conversationId = conversationId,
        sourceMessageId = null,
        conversationOrder = 0,
        masterRelativePath = "master/$id.jpg",
        thumbnailRelativePath = "thumb/$id.jpg",
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        byteSize = 1000,
        sha256 = "hash-$id",
        source = MediaSource.PHOTO_PICKER,
        lifecycleState = MediaLifecycleState.READY,
        failureCode = null,
        createdAt = 0L,
        updatedAt = 0L,
        deletedAt = null
    )

    private fun conversation(id: String, title: String, activity: Long) = Conversation(
        id = id,
        conversationDate = LocalDate.of(2026, 6, 20),
        title = title,
        lastMessagePreview = title,
        createdAt = activity,
        updatedAt = activity,
        lastActivityAt = activity
    )

    private class FakeChatMediaTransactionRepository : ChatMediaTransactionRepository {
        var nextResult: SendUserMessageWithMediaResult? = null
        val requests = mutableListOf<SendUserMessageWithMediaRequest>()
        var callCount = 0
        var lastCommittedUserMessageId: String? = null
        var lastRequest: SendUserMessageWithMediaRequest? = null

        override suspend fun sendUserMessageWithMedia(request: SendUserMessageWithMediaRequest): SendUserMessageWithMediaResult {
            callCount++
            requests += request
            lastRequest = request
            return when (val result = nextResult ?: SendUserMessageWithMediaResult.Committed(
                userMessageId = request.userMessageId,
                assistantPlaceholderId = "placeholder-${request.userMessageId}"
            )) {
                is SendUserMessageWithMediaResult.Committed -> {
                    lastCommittedUserMessageId = result.userMessageId
                    result
                }
                else -> result
            }
        }
    }

    private class FakeMediaRepository(private val assets: List<MediaAsset>) : MediaRepository {
        private val flow = MutableStateFlow(assets)
        override fun observeConversationMedia(conversationId: String): Flow<List<MediaAsset>> = flow.map { list ->
            list.filter { it.conversationId == conversationId && it.deletedAt == null }
        }
        override suspend fun getConversationMedia(conversationId: String) = assets.filter { it.conversationId == conversationId }
        override suspend fun getMediaByIds(ids: List<String>) = assets.filter { it.id in ids }
        override suspend fun createStagedMedia(requests: List<com.example.domain.model.media.NewMediaAssetRequest>, now: Long) = emptyList<MediaAsset>()
        override suspend fun attachMediaToMessage(mediaIds: List<String>, conversationId: String, messageId: String, now: Long) {}
        override suspend fun markMediaReady(id: String, conversationId: String, masterRelativePath: String, thumbnailRelativePath: String, mimeType: String, width: Int, height: Int, byteSize: Long, sha256: String, now: Long) =
            error("unused")
        override suspend fun markMediaFailed(id: String, conversationId: String, failureCode: String?, now: Long) =
            error("unused")
        override suspend fun softDeleteMedia(id: String, conversationId: String, now: Long) {}
        override suspend fun findStaleStagedMedia(updatedBefore: Long) = emptyList<MediaAsset>()
    }

    private class InMemoryConversationRepository : ConversationRepository {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun getConversationById(id: String): Conversation? = null
        override suspend fun insertConversation(conversation: Conversation) {}
        override suspend fun updateConversationSummary(id: String, title: String, lastMessagePreview: String, lastActivityAt: Long, updatedAt: Long) {}
        override suspend fun softDeleteConversation(id: String, deletedAt: Long) {}
    }

    private class FakeNetworkAvailabilityProvider(
        private val hasInternet: Boolean
    ) : NetworkAvailabilityProvider {
        override fun hasValidatedInternet(): Boolean = hasInternet
    }
}
