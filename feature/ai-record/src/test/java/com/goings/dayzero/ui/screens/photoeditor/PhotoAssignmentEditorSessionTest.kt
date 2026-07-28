package com.goings.dayzero.ui.screens.photoeditor

import androidx.lifecycle.SavedStateHandle
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.AiDraftRequest
import com.goings.dayzero.domain.model.ai.ChatRole
import com.goings.dayzero.domain.model.ai.CheckinDraft
import com.goings.dayzero.domain.model.ai.Conversation
import com.goings.dayzero.domain.model.ai.assistant.AiChatCard
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardItem
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardMeal
import com.goings.dayzero.domain.model.ai.assistant.ConfirmCardOption
import com.goings.dayzero.domain.model.ai.assistant.DateMismatchGuardCardPayload
import com.goings.dayzero.domain.model.ai.assistant.ShowConfirmCardPayload
import com.goings.dayzero.domain.model.ai.assistant.assistantPlaceholderId
import com.goings.dayzero.domain.repository.AiDraftRepository
import com.goings.dayzero.domain.repository.ConversationRepository
import com.goings.dayzero.domain.repository.FoodCardPhotoAssignmentRepository
import com.goings.dayzero.domain.repository.MealPhotoAssignment
import com.goings.dayzero.domain.repository.UpdateFoodCardPhotoAssignmentsResult
import com.goings.dayzero.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.goings.dayzero.domain.usecase.DiscardStagedMediaUseCase
import com.goings.dayzero.domain.usecase.ImportLocalMediaUseCase
import com.goings.dayzero.domain.usecase.ObserveConversationMediaUseCase
import com.goings.dayzero.domain.usecase.RetryLocalMediaImportUseCase
import com.goings.dayzero.domain.usecase.SendUserMessageWithMediaUseCase
import com.goings.dayzero.domain.usecase.UpdateFoodCardPhotoAssignmentsUseCase
import com.goings.dayzero.ui.screens.AiRecordViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoAssignmentEditorSessionTest {

    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var conversationRepository: SessionConversationRepository
    private lateinit var draftRepository: SessionDraftRepository
    private lateinit var assignmentRepository: RecordingAssignmentRepository

    private val userId = "user-1"
    private val assistantId = assistantPlaceholderId(userId)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        conversationRepository = SessionConversationRepository()
        draftRepository = SessionDraftRepository()
        assignmentRepository = RecordingAssignmentRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun confirmCard(
        state: String = "pending",
        meals: List<ConfirmCardMeal> = listOf(
            meal("breakfast", "早餐", listOf("m1")),
            meal("lunch", "午餐", null)
        )
    ) = ShowConfirmCardPayload(
        id = "card-1",
        confirmType = "food_record",
        title = "t",
        message = "m",
        originalText = null,
        mealType = null,
        items = emptyList(),
        meals = meals,
        buttons = listOf(ConfirmCardOption("confirm", "确认")),
        state = state
    )

    private fun meal(type: String, label: String?, ids: List<String>?) = ConfirmCardMeal(
        mealType = type,
        mealLabel = label,
        subtotalCalories = 100,
        items = listOf(ConfirmCardItem(name = "rice", amountText = null, calories = 100, calorieConfidence = "medium")),
        sourceMediaIds = ids
    )

    private suspend fun seedConversation(card: AiChatCard, originIds: List<String> = listOf("m1", "m2", "m3")) {
        conversationRepository.insertConversation(
            Conversation(
                id = "conv-1", conversationDate = LocalDate.of(2026, 7, 9), title = "T",
                lastMessagePreview = "p", createdAt = 1L, updatedAt = 1L, lastActivityAt = 1L
            )
        )
        draftRepository.insertChatMessage(
            AiChatMessage(id = userId, conversationId = "conv-1", role = ChatRole.User, text = "", sourceMediaIds = originIds)
        )
        draftRepository.insertChatMessage(
            AiChatMessage(id = assistantId, conversationId = "conv-1", role = ChatRole.Assistant, text = "", assistantCards = listOf(card))
        )
    }

    private fun createViewModel(): AiRecordViewModel {
        val mediaRepository = NoOpMediaRepository()
        val importRepository = NoOpImportRepository()
        return AiRecordViewModel(
            conversationRepository = conversationRepository,
            aiDraftRepository = draftRepository,
            createConversationWithFirstMessageUseCase = CreateConversationWithFirstMessageUseCase(draftRepository),
            observeConversationMediaUseCase = ObserveConversationMediaUseCase(mediaRepository),
            importLocalMediaUseCase = ImportLocalMediaUseCase(
                mediaRepository,
                importRepository,
                com.goings.dayzero.domain.usecase.MediaIdGenerator { "id" }
            ),
            retryLocalMediaImportUseCase = RetryLocalMediaImportUseCase(mediaRepository, importRepository),
            discardStagedMediaUseCase = DiscardStagedMediaUseCase(mediaRepository, importRepository),
            sendUserMessageWithMediaUseCase = SendUserMessageWithMediaUseCase(
                object : com.goings.dayzero.domain.repository.ChatMediaTransactionRepository {
                    override suspend fun sendUserMessageWithMedia(
                        request: com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
                    ): com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult =
                        com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult.Committed(request.userMessageId, "p")
                }
            ),
            currentIdentityProvider = object : com.goings.dayzero.domain.identity.CurrentIdentityProvider {
                override suspend fun currentIdentity() = com.goings.dayzero.domain.identity.AppIdentity("owner", null, "local", false)
            },
            networkAvailabilityProvider = com.goings.dayzero.domain.network.NetworkAvailabilityProvider { true },
            updateFoodCardPhotoAssignmentsUseCase = UpdateFoodCardPhotoAssignmentsUseCase(assignmentRepository),
            savedStateHandle = SavedStateHandle()
        )
    }

    @Test
    fun openBuildsSnapshotFromCardMealsAndOriginMessage() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()

        viewModel.openPhotoAssignmentEditor("card-1", initialMealIndex = 1)
        val session = viewModel.photoEditor.value
        assertNotNull(session)
        assertEquals(listOf("早餐", "午餐"), session!!.mealLabels)
        assertEquals(1, session.selectedMealIndex)
        assertEquals(mapOf(0 to listOf("m1"), 1 to emptyList<String>()), session.draft.assignments)
        assertEquals(listOf("m2", "m3"), session.draft.unassignedIds)
        assertFalse(session.isDirty)
    }

    @Test
    fun openRefusedForTerminalCardsPendingOrCancelledGuardsAndNoOriginPhotos() = runTest(dispatcher) {
        seedConversation(confirmCard(state = "confirmed"))
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNull(viewModel.photoEditor.value)

        draftRepository.clearChatMessages()
        seedConversation(guard("pending"))
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNull(viewModel.photoEditor.value)

        draftRepository.clearChatMessages()
        seedConversation(guard("cancelled"))
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNull(viewModel.photoEditor.value)

        // Text-only turn: no origin image message → editor never opens.
        draftRepository.clearChatMessages()
        seedConversation(confirmCard(), originIds = emptyList())
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNull(viewModel.photoEditor.value)
    }

    @Test
    fun openAllowedForApprovedGuardOriginalCard() = runTest(dispatcher) {
        seedConversation(guard("approved"))
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNotNull(viewModel.photoEditor.value)
    }

    private fun guard(state: String) = DateMismatchGuardCardPayload(
        id = "guard-1",
        conversationId = "conv-1",
        conversationDate = LocalDate.of(2026, 7, 8),
        detectedCurrentDate = LocalDate.of(2026, 7, 9),
        state = state,
        pendingOriginalCard = confirmCard()
    )

    @Test
    fun assignRemoveMoveMutateOnlyLocalDraft() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")

        viewModel.photoEditorSelectMeal(1)
        viewModel.photoEditorAssignToSelectedMeal("m2")
        assertEquals(listOf("m2"), viewModel.photoEditor.value!!.draft.assignedTo(1))
        assertTrue(viewModel.photoEditor.value!!.isDirty)

        viewModel.photoEditorMove("m1", 1)
        assertEquals(listOf("m2", "m1"), viewModel.photoEditor.value!!.draft.assignedTo(1))
        assertEquals(emptyList<String>(), viewModel.photoEditor.value!!.draft.assignedTo(0))

        viewModel.photoEditorRemove("m2")
        assertTrue("m2" in viewModel.photoEditor.value!!.draft.unassignedIds)

        advanceUntilIdle()
        // Nothing was written to the card or the assignment repository during editing.
        assertEquals(0, assignmentRepository.calls.size)
        val storedCard = draftRepository.currentMessages().first { it.id == assistantId }.assistantCards.first()
        assertEquals(listOf("m1"), (storedCard as ShowConfirmCardPayload).meals!![0].sourceMediaIds)
    }

    @Test
    fun cleanCloseExitsDirectlyDirtyCloseAsksAndDiscardKeepsCardUntouched() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()

        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.requestClosePhotoEditor()
        assertNull(viewModel.photoEditor.value)

        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.photoEditorAssignToSelectedMeal("m2")
        viewModel.requestClosePhotoEditor()
        assertTrue(viewModel.photoEditor.value!!.showDiscardDialog)

        viewModel.dismissPhotoEditorDiscardDialog()
        assertFalse(viewModel.photoEditor.value!!.showDiscardDialog)

        viewModel.requestClosePhotoEditor()
        viewModel.discardPhotoEditor()
        assertNull(viewModel.photoEditor.value)
        advanceUntilIdle()
        assertEquals(0, assignmentRepository.calls.size)
    }

    @Test
    fun saveCallsUseCaseOnceWithFullAssignmentsAndClosesOnSuccess() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.photoEditorSelectMeal(1)
        viewModel.photoEditorAssignToSelectedMeal("m2")

        viewModel.savePhotoAssignments()
        viewModel.savePhotoAssignments() // double-tap must be debounced
        advanceUntilIdle()

        assertEquals(1, assignmentRepository.calls.size)
        val (cardId, assignments) = assignmentRepository.calls.single()
        assertEquals("card-1", cardId)
        assertEquals(
            listOf(
                MealPhotoAssignment(0, listOf("m1")),
                MealPhotoAssignment(1, listOf("m2"))
            ),
            assignments
        )
        assertNull(viewModel.photoEditor.value)
    }

    @Test
    fun saveFailureKeepsEditStateAndAllowsRetry() = runTest(dispatcher) {
        seedConversation(confirmCard())
        assignmentRepository.error = RuntimeException("disk full")
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.photoEditorAssignToSelectedMeal("m2")

        viewModel.savePhotoAssignments()
        advanceUntilIdle()

        val failed = viewModel.photoEditor.value
        assertNotNull(failed)
        assertEquals("保存失败，请重试", failed!!.saveError)
        assertFalse(failed.isSaving)
        assertFalse(failed.cardNoLongerEditable)
        assertEquals(listOf("m1", "m2"), failed.draft.assignedTo(0))

        assignmentRepository.error = null
        viewModel.savePhotoAssignments()
        advanceUntilIdle()
        assertEquals(2, assignmentRepository.calls.size)
        assertNull(viewModel.photoEditor.value)
    }

    @Test
    fun saveOnTerminalCardFailsSafelyWithoutWriting() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.photoEditorAssignToSelectedMeal("m2")

        // The card is confirmed (e.g. synced from another device) while editing.
        draftRepository.replaceCard(assistantId, confirmCard(state = "confirmed"))
        advanceUntilIdle()

        viewModel.savePhotoAssignments()
        advanceUntilIdle()

        assertEquals(0, assignmentRepository.calls.size)
        val session = viewModel.photoEditor.value
        assertNotNull(session)
        assertTrue(session!!.cardNoLongerEditable)
        assertNotNull(session.saveError)
    }

    @Test
    fun notEditableRepositoryResultMarksTerminal() = runTest(dispatcher) {
        seedConversation(confirmCard())
        assignmentRepository.result = UpdateFoodCardPhotoAssignmentsResult.NotEditable
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        viewModel.photoEditorAssignToSelectedMeal("m2")

        viewModel.savePhotoAssignments()
        advanceUntilIdle()

        assertEquals(1, assignmentRepository.calls.size)
        assertTrue(viewModel.photoEditor.value!!.cardNoLongerEditable)
    }

    @Test
    fun switchingConversationClosesEditorSession() = runTest(dispatcher) {
        seedConversation(confirmCard())
        val viewModel = createViewModel()
        viewModel.openConversation("conv-1")
        advanceUntilIdle()
        viewModel.openPhotoAssignmentEditor("card-1")
        assertNotNull(viewModel.photoEditor.value)

        viewModel.openConversation("conv-2")
        assertNull(viewModel.photoEditor.value)
    }
}

private class RecordingAssignmentRepository : FoodCardPhotoAssignmentRepository {
    val calls = mutableListOf<Pair<String, List<MealPhotoAssignment>>>()
    var result: UpdateFoodCardPhotoAssignmentsResult = UpdateFoodCardPhotoAssignmentsResult.Updated
    var error: Throwable? = null

    override suspend fun updatePhotoAssignments(
        cardId: String,
        assignments: List<MealPhotoAssignment>
    ): UpdateFoodCardPhotoAssignmentsResult {
        calls += cardId to assignments
        error?.let { throw it }
        return result
    }
}

private class SessionConversationRepository : ConversationRepository {
    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override suspend fun insertConversation(conversation: Conversation) {
        conversations.update { current -> current.filterNot { it.id == conversation.id } + conversation }
    }

    override suspend fun getConversationById(id: String): Conversation? = conversations.value.find { it.id == id }
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

private class SessionDraftRepository : AiDraftRepository {
    private val messages = MutableStateFlow<List<AiChatMessage>>(emptyList())

    fun currentMessages(): List<AiChatMessage> = messages.value

    suspend fun replaceCard(messageId: String, card: AiChatCard) {
        messages.update { current ->
            current.map { if (it.id == messageId) it.copy(assistantCards = listOf(card)) else it }
        }
    }

    override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) = Unit
    override fun clearStreamingState(conversationId: String) = Unit
    override suspend fun generateDraft(request: AiDraftRequest): CheckinDraft = error("unused")
    override fun observeChatMessages(): Flow<List<AiChatMessage>> = messages.asStateFlow()
    override fun observeChatMessages(conversationId: String): Flow<List<AiChatMessage>> =
        kotlinx.coroutines.flow.combine(messages, flowOf(Unit)) { msgs, _ ->
            msgs.filter { it.conversationId == conversationId }
        }

    override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? = null
    override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> =
        messages.value.filter { it.conversationId == conversationId }.takeLast(limit)

    override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? =
        messages.value.find { message -> message.assistantCards.any { it.id == cardId } }

    override suspend fun getChatMessageById(messageId: String): AiChatMessage? = messages.value.find { it.id == messageId }
    override suspend fun insertChatMessage(message: AiChatMessage) {
        messages.update { it + message }
    }

    override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {
        messages.update { it + message.copy(conversationId = conversationId) }
    }

    override suspend fun updateChatMessage(message: AiChatMessage) {
        messages.update { current -> current.map { if (it.id == message.id) message else it } }
    }

    override suspend fun clearChatMessages() {
        messages.value = emptyList()
    }
}

private class NoOpMediaRepository : com.goings.dayzero.domain.repository.MediaRepository {
    override fun observeConversationMedia(conversationId: String): Flow<List<com.goings.dayzero.domain.model.media.MediaAsset>> =
        flowOf(emptyList())

    override suspend fun getConversationMedia(conversationId: String): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
    override suspend fun getMediaByIds(ids: List<String>): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
    override suspend fun createStagedMedia(
        requests: List<com.goings.dayzero.domain.model.media.NewMediaAssetRequest>,
        now: Long
    ): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()

    override suspend fun attachMediaToMessage(mediaIds: List<String>, conversationId: String, messageId: String, now: Long) = Unit
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
    ): com.goings.dayzero.domain.model.media.MediaAsset = error("unused")

    override suspend fun markMediaFailed(
        id: String,
        conversationId: String,
        failureCode: String?,
        now: Long
    ): com.goings.dayzero.domain.model.media.MediaAsset = error("unused")

    override suspend fun softDeleteMedia(id: String, conversationId: String, now: Long) = Unit
    override suspend fun findStaleStagedMedia(updatedBefore: Long): List<com.goings.dayzero.domain.model.media.MediaAsset> = emptyList()
}

private class NoOpImportRepository : com.goings.dayzero.domain.repository.LocalMediaImportRepository {
    override suspend fun importStagedMedia(
        mediaId: String,
        request: com.goings.dayzero.domain.model.media.ImportLocalMediaRequest
    ): com.goings.dayzero.domain.model.media.LocalMediaImportItemResult =
        com.goings.dayzero.domain.model.media.LocalMediaImportItemResult.Failed(
            mediaId,
            com.goings.dayzero.domain.model.media.MediaImportFailureCode.UNKNOWN
        )

    override suspend fun retryImport(mediaId: String): com.goings.dayzero.domain.model.media.LocalMediaImportItemResult =
        com.goings.dayzero.domain.model.media.LocalMediaImportItemResult.Failed(
            mediaId,
            com.goings.dayzero.domain.model.media.MediaImportFailureCode.UNKNOWN
        )

    override suspend fun discardStagedMedia(mediaId: String): Boolean = true
    override suspend fun cleanupStaleMedia(updatedBefore: Long): List<String> = emptyList()
}
