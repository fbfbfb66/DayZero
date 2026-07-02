package com.example.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.AppState
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.CheckinDraft
import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.SendUserMessageWithMediaRequest
import com.example.domain.model.ai.SendUserMessageWithMediaResult
import com.example.domain.model.media.*
import com.example.domain.repository.ChatMediaTransactionRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.LocalMediaImportRepository
import com.example.domain.repository.MediaRepository
import com.example.domain.usecase.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiRecordPhase2ATest {

    @get:Rule
    val mainDispatcherRule = FeatureMainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = mainDispatcherRule.dispatcher

    private class FakeMediaRepository : MediaRepository {
        val mediaList = MutableStateFlow<List<MediaAsset>>(emptyList())
        override fun observeConversationMedia(conversationId: String): Flow<List<MediaAsset>> = mediaList
        override suspend fun getConversationMedia(conversationId: String): List<MediaAsset> = mediaList.value
        override suspend fun getMediaByIds(ids: List<String>): List<MediaAsset> = mediaList.value.filter { it.id in ids }
        override suspend fun createStagedMedia(requests: List<NewMediaAssetRequest>, now: Long): List<MediaAsset> {
            val newAssets = requests.map { req ->
                MediaAsset(
                    id = req.id, ownerLocalId = req.ownerLocalId, conversationId = req.conversationId,
                    sourceMessageId = null, conversationOrder = 0, masterRelativePath = null,
                    thumbnailRelativePath = null, mimeType = null, width = null, height = null,
                    byteSize = null, sha256 = null, source = req.source, lifecycleState = MediaLifecycleState.STAGED,
                    failureCode = null, createdAt = now, updatedAt = now, deletedAt = null
                )
            }
            mediaList.value = mediaList.value + newAssets
            return newAssets
        }
        override suspend fun attachMediaToMessage(mediaIds: List<String>, conversationId: String, messageId: String, now: Long) {}
        override suspend fun markMediaReady(id: String, conversationId: String, masterRelativePath: String, thumbnailRelativePath: String, mimeType: String, width: Int, height: Int, byteSize: Long, sha256: String, now: Long): MediaAsset {
            var updated: MediaAsset? = null
            mediaList.value = mediaList.value.map { asset ->
                if (asset.id == id) {
                    asset.copy(
                        lifecycleState = MediaLifecycleState.READY,
                        thumbnailRelativePath = thumbnailRelativePath,
                        updatedAt = now
                    ).also { updated = it }
                } else asset
            }
            return updated ?: throw IllegalArgumentException("Not found")
        }
        override suspend fun markMediaFailed(id: String, conversationId: String, failureCode: String?, now: Long): MediaAsset {
            var updated: MediaAsset? = null
            mediaList.value = mediaList.value.map { asset ->
                if (asset.id == id) {
                    asset.copy(
                        lifecycleState = MediaLifecycleState.FAILED,
                        failureCode = failureCode,
                        updatedAt = now
                    ).also { updated = it }
                } else asset
            }
            return updated ?: throw IllegalArgumentException("Not found")
        }
        override suspend fun softDeleteMedia(id: String, conversationId: String, now: Long) {
            mediaList.value = mediaList.value.map { asset ->
                if (asset.id == id) {
                    asset.copy(deletedAt = now)
                } else asset
            }
        }
        override suspend fun findStaleStagedMedia(updatedBefore: Long): List<MediaAsset> = emptyList()
    }

    private class FakeLocalMediaImportRepository : LocalMediaImportRepository {
        var simulateFailure = false
        override suspend fun importStagedMedia(mediaId: String, request: ImportLocalMediaRequest): LocalMediaImportItemResult {
            return if (simulateFailure) {
                LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.DECODE_FAILED)
            } else {
                val asset = MediaAsset(
                    id = mediaId, ownerLocalId = request.ownerLocalId, conversationId = request.conversationId,
                    sourceMessageId = null, conversationOrder = 0, masterRelativePath = "master",
                    thumbnailRelativePath = "media/thumbnail/$mediaId.jpg", mimeType = "image/jpeg",
                    width = 100, height = 100, byteSize = 1000, sha256 = "hash", source = request.source,
                    lifecycleState = MediaLifecycleState.READY, failureCode = null, createdAt = 0L, updatedAt = 0L, deletedAt = null
                )
                LocalMediaImportItemResult.Ready(mediaId, asset)
            }
        }
        override suspend fun retryImport(mediaId: String): LocalMediaImportItemResult = LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.DECODE_FAILED)
        override suspend fun discardStagedMedia(mediaId: String): Boolean = true
        override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> = emptyList()
    }

    @Test
    fun testDraftIsolation() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val importRepository = FakeLocalMediaImportRepository()
        val viewModel = createViewModel(mediaRepository, importRepository)

        // Add photo to conv A
        viewModel.importPhotos("conv-A", listOf("uri-1", "uri-2"))
        advanceUntilIdle()

        // Draft for A should have 2 assets
        val draftA = viewModel.getDraftStateFlow("conv-A").first()
        assertEquals(2, draftA.attachmentIds.size)

        // Draft for B should be empty
        val draftB = viewModel.getDraftStateFlow("conv-B").first()
        assertTrue(draftB.attachmentIds.isEmpty())
    }

    @Test
    fun testCapacityConstraint() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val importRepository = FakeLocalMediaImportRepository()
        val viewModel = createViewModel(mediaRepository, importRepository)

        // Add 5 photos to conv A
        viewModel.importPhotos("conv-A", listOf("u1", "u2", "u3", "u4", "u5"))
        advanceUntilIdle()

        // Try adding 3 more (should only accept 1, and ignore other 2)
        viewModel.importPhotos("conv-A", listOf("u6", "u7", "u8"))
        advanceUntilIdle()

        val draft = viewModel.getDraftStateFlow("conv-A").first()
        assertEquals(6, draft.attachmentIds.size)
    }

    @Test
    fun testPickerCancelClearsOpenState() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val importRepository = FakeLocalMediaImportRepository()
        val viewModel = createViewModel(mediaRepository, importRepository)

        viewModel.setPickerOpen("conv-A", true)
        var draft = viewModel.getDraftStateFlow("conv-A").first()
        assertTrue(draft.isPickerOpen)

        viewModel.setPickerOpen("conv-A", false)
        draft = viewModel.getDraftStateFlow("conv-A").first()
        assertFalse(draft.isPickerOpen)
    }

    @Test
    fun testPruningSavedStateHandle() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val importRepository = FakeLocalMediaImportRepository()

        // Seed mediaRepository
        val validAsset = MediaAsset(
            id = "valid-A", ownerLocalId = "owner-1", conversationId = "conv-A",
            sourceMessageId = null, conversationOrder = 0, masterRelativePath = "path",
            thumbnailRelativePath = "thumb", mimeType = "image/jpeg", width = 100, height = 100,
            byteSize = 1000, sha256 = "hash", source = MediaSource.PHOTO_PICKER,
            lifecycleState = MediaLifecycleState.READY, failureCode = null, createdAt = 0L, updatedAt = 0L, deletedAt = null
        )
        val crossAsset = MediaAsset(
            id = "cross-B", ownerLocalId = "owner-1", conversationId = "conv-B",
            sourceMessageId = null, conversationOrder = 0, masterRelativePath = "path",
            thumbnailRelativePath = "thumb", mimeType = "image/jpeg", width = 100, height = 100,
            byteSize = 1000, sha256 = "hash", source = MediaSource.PHOTO_PICKER,
            lifecycleState = MediaLifecycleState.READY, failureCode = null, createdAt = 0L, updatedAt = 0L, deletedAt = null
        )
        val deletedAsset = MediaAsset(
            id = "deleted-A", ownerLocalId = "owner-1", conversationId = "conv-A",
            sourceMessageId = null, conversationOrder = 0, masterRelativePath = "path",
            thumbnailRelativePath = "thumb", mimeType = "image/jpeg", width = 100, height = 100,
            byteSize = 1000, sha256 = "hash", source = MediaSource.PHOTO_PICKER,
            lifecycleState = MediaLifecycleState.READY, failureCode = null, createdAt = 0L, updatedAt = 0L, deletedAt = 12345L
        )

        mediaRepository.mediaList.value = listOf(validAsset, crossAsset, deletedAsset)

        val savedStateHandle = SavedStateHandle()
        // Save valid, cross, deleted, and invalid IDs in SavedStateHandle for conv-A
        savedStateHandle["draft_ids_conv-A"] = listOf("valid-A", "cross-B", "deleted-A", "invalid-id")

        val viewModel = createViewModel(mediaRepository, importRepository, savedStateHandle)

        // Observe draft state for conv-A (triggers pruning)
        val draft = viewModel.getDraftStateFlow("conv-A").first()

        // Only valid-A should remain
        assertEquals(listOf("valid-A"), draft.attachmentIds)
        assertEquals(listOf(validAsset), draft.assets)

        // SavedStateHandle should now be pruned to only contain valid-A
        val handleIds = savedStateHandle.get<List<String>>("draft_ids_conv-A")
        assertEquals(listOf("valid-A"), handleIds)
    }

    @Test
    fun testAsyncImportTargetIsolation() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val importRepository = FakeLocalMediaImportRepository()
        val viewModel = createViewModel(mediaRepository, importRepository)

        // Start import for conv-A
        viewModel.importPhotos("conv-A", listOf("uri-1"))
        // Switch context or start import for conv-B
        viewModel.importPhotos("conv-B", listOf("uri-2"))
        advanceUntilIdle()

        val draftA = viewModel.getDraftStateFlow("conv-A").first()
        val draftB = viewModel.getDraftStateFlow("conv-B").first()

        assertEquals(1, draftA.attachmentIds.size)
        assertEquals(1, draftB.attachmentIds.size)
    }

    @Test
    fun testImportingCountLimitsConcurrency() = runTest(testDispatcher) {
        val mediaRepository = FakeMediaRepository()
        val hangSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val importRepository = object : LocalMediaImportRepository {
            override suspend fun importStagedMedia(mediaId: String, request: ImportLocalMediaRequest): LocalMediaImportItemResult {
                hangSignal.await()
                return LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.UNKNOWN)
            }
            override suspend fun retryImport(mediaId: String): LocalMediaImportItemResult = LocalMediaImportItemResult.Failed(mediaId, MediaImportFailureCode.UNKNOWN)
            override suspend fun discardStagedMedia(mediaId: String): Boolean = true
            override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> = emptyList()
        }
        val viewModel = createViewModel(mediaRepository, importRepository)

        // Try importing 4 items on A. It will hang.
        val job1 = launch {
            viewModel.importPhotos("conv-A", listOf("u1", "u2", "u3", "u4"))
        }
        runCurrent()

        // At this point, importingCount should be 4
        var draft = viewModel.getDraftStateFlow("conv-A").first()
        assertEquals(4, draft.importingCount)

        // Now try importing another 4 items. Since 4 are already importing, only 2 should be allowed (6 - 4 = 2).
        val job2 = launch {
            viewModel.importPhotos("conv-A", listOf("u5", "u6", "u7", "u8"))
        }
        runCurrent()

        // Total importing count should be 4 + 2 = 6.
        draft = viewModel.getDraftStateFlow("conv-A").first()
        assertEquals(6, draft.importingCount)

        // Unhang everything
        hangSignal.complete(Unit)
        job1.join()
        job2.join()
        advanceUntilIdle()

        draft = viewModel.getDraftStateFlow("conv-A").first()
        assertEquals(0, draft.importingCount)
    }

    private fun createViewModel(
        mediaRepository: MediaRepository,
        importRepository: LocalMediaImportRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): AiRecordViewModel {
        val idGenerator = MediaIdGenerator { UUID.randomUUID().toString() }
        val currentIdentityProvider = object : CurrentIdentityProvider {
            override suspend fun currentIdentity() = AppIdentity("owner-1", null, "local", false)
        }
        val sendUserMessageWithMediaUseCase = SendUserMessageWithMediaUseCase(
            object : ChatMediaTransactionRepository {
                override suspend fun sendUserMessageWithMedia(request: SendUserMessageWithMediaRequest): SendUserMessageWithMediaResult {
                    return SendUserMessageWithMediaResult.Committed(
                        userMessageId = request.userMessageId,
                        assistantPlaceholderId = "placeholder-${request.userMessageId}"
                    )
                }
            }
        )
        return AiRecordViewModel(
            conversationRepository = InMemoryConversationRepository(),
            aiDraftRepository = FakeAiDraftRepository(),
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(FakeAiDraftRepository()),
            observeConversationMediaUseCase = ObserveConversationMediaUseCase(mediaRepository),
            importLocalMediaUseCase = ImportLocalMediaUseCase(mediaRepository, importRepository, idGenerator),
            retryLocalMediaImportUseCase = RetryLocalMediaImportUseCase(mediaRepository, importRepository),
            discardStagedMediaUseCase = DiscardStagedMediaUseCase(mediaRepository, importRepository),
            sendUserMessageWithMediaUseCase = sendUserMessageWithMediaUseCase,
            currentIdentityProvider = currentIdentityProvider,
            networkAvailabilityProvider = com.example.domain.network.NetworkAvailabilityProvider { true },
            savedStateHandle = savedStateHandle
        )
    }

    private class InMemoryConversationRepository : ConversationRepository {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override fun observeConversationsByLastActivity(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun getConversationById(id: String): Conversation? = null
        override suspend fun insertConversation(conversation: Conversation) {}
        override suspend fun updateConversationSummary(id: String, title: String, lastMessagePreview: String, lastActivityAt: Long, updatedAt: Long) {}
        override suspend fun softDeleteConversation(id: String, deletedAt: Long) {}
    }

    private class FakeAiDraftRepository : com.example.domain.repository.AiDraftRepository {
        override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft {
            return CheckinDraft(
                id = "draft-1",
                date = request.date,
                meals = emptyList(),
                totalCalories = 0,
                weightKg = null,
                aiSummary = "",
                sourceText = null
            )
        }
        override fun observeChatMessages(): Flow<List<AiChatMessage>> = flowOf(emptyList())
        override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> = flowOf(emptyList())
        override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? = null
        override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> = emptyList()
        override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? = null
        override suspend fun getChatMessageById(messageId: String): AiChatMessage? = null
        override suspend fun insertChatMessage(message: AiChatMessage) {}
        override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {}
        override suspend fun updateChatMessage(message: AiChatMessage) {}
        override suspend fun clearChatMessages() {}
        override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {}
        override fun clearStreamingState(conversationId: String) {}
    }
}
