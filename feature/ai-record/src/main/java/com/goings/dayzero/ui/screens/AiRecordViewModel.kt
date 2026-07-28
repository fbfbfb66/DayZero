package com.goings.dayzero.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.ai.Conversation
import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.model.media.ImportLocalMediaRequest
import com.goings.dayzero.domain.model.media.LocalMediaInput
import com.goings.dayzero.domain.model.media.MediaSource
import com.goings.dayzero.domain.usecase.CreateConversationWithFirstMessageUseCase
import com.goings.dayzero.domain.usecase.ObserveConversationMediaUseCase
import com.goings.dayzero.domain.usecase.ImportLocalMediaUseCase
import com.goings.dayzero.domain.usecase.RetryLocalMediaImportUseCase
import com.goings.dayzero.domain.usecase.DiscardStagedMediaUseCase
import com.goings.dayzero.domain.usecase.SendUserMessageWithMediaUseCase
import com.goings.dayzero.domain.identity.CurrentIdentityProvider
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaRequest
import com.goings.dayzero.domain.network.NetworkAvailabilityProvider
import com.goings.dayzero.domain.model.ai.SendUserMessageWithMediaResult
import com.goings.dayzero.domain.repository.AiDraftRepository
import com.goings.dayzero.domain.repository.ConversationRepository
import com.goings.dayzero.domain.repository.UpdateFoodCardPhotoAssignmentsResult
import com.goings.dayzero.domain.usecase.UpdateFoodCardPhotoAssignmentsUseCase
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentDraft
import com.goings.dayzero.ui.screens.photoeditor.PhotoAssignmentEditorUiState
import com.goings.dayzero.ui.screens.photoeditor.findEditorCard
import com.goings.dayzero.ui.screens.photoeditor.isCardPhotoEditable
import com.goings.dayzero.ui.screens.photoeditor.mealDisplayLabel
import com.goings.dayzero.ui.screens.photoeditor.resolveOriginMediaIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AttachmentImportFailure(
    val mediaId: String,
    val errorCode: String
)

data class ConversationAttachmentDraftState(
    val conversationId: String,
    val attachmentIds: List<String> = emptyList(),
    val assets: List<MediaAsset> = emptyList(),
    val importingCount: Int = 0,
    val failures: List<AttachmentImportFailure> = emptyList(),
    val isPickerOpen: Boolean = false,
    val isCameraOpening: Boolean = false
)

data class AiConversationHistoryState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val homeInputText: String = "",
    val lastCreatedConversationId: String? = null,
    val errorMessage: String? = null
)

data class AiConversationDetailState(
    val currentConversation: Conversation? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val messagesWithMedia: List<MessageWithMedia> = emptyList(),
    val mediaAssets: List<MediaAsset> = emptyList(),
    val isSending: Boolean = false,
    val isStreaming: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val draftState: ConversationAttachmentDraftState? = null
)

data class AiRecordConversationUiState(
    val history: AiConversationHistoryState = AiConversationHistoryState(),
    val detail: AiConversationDetailState = AiConversationDetailState()
)

sealed interface AiRecordConversationEvent {
    data class ConversationCreated(
        val conversationId: String,
        val firstMessageText: String
    ) : AiRecordConversationEvent

    /**
     * Emitted after the local media transaction successfully commits.
     * Listeners (AppNavigation) should start the Vision assistant turn for the
     * persisted user message and must not re-send the user message.
     */
    data class MediaMessageCommitted(
        val conversationId: String,
        val userMessageId: String
    ) : AiRecordConversationEvent
}


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiRecordViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val aiDraftRepository: AiDraftRepository,
    private val createConversationWithFirstMessageUseCase: CreateConversationWithFirstMessageUseCase,
    private val observeConversationMediaUseCase: ObserveConversationMediaUseCase,
    private val importLocalMediaUseCase: ImportLocalMediaUseCase,
    private val retryLocalMediaImportUseCase: RetryLocalMediaImportUseCase,
    private val discardStagedMediaUseCase: DiscardStagedMediaUseCase,
    private val sendUserMessageWithMediaUseCase: SendUserMessageWithMediaUseCase,
    private val currentIdentityProvider: CurrentIdentityProvider,
    private val networkAvailabilityProvider: NetworkAvailabilityProvider,
    private val updateFoodCardPhotoAssignmentsUseCase: UpdateFoodCardPhotoAssignmentsUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val selectedConversationId = MutableStateFlow<String?>(savedStateHandle[KEY_CONVERSATION_ID] as? String)
    private val historyTransient = MutableStateFlow(HistoryTransientState())
    private val detailTransient = MutableStateFlow(DetailTransientState())
    private val _events = MutableSharedFlow<AiRecordConversationEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private val importingCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val pickerOpenStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val cameraOpeningStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val historyState: Flow<AiConversationHistoryState> = combine(
        observeHistory(),
        historyTransient
    ) { history: AiConversationHistoryState, overlay: HistoryTransientState ->
        history.copy(
            isCreating = overlay.isCreating,
            homeInputText = overlay.homeInputText,
            lastCreatedConversationId = overlay.lastCreatedConversationId,
            errorMessage = overlay.errorMessage ?: history.errorMessage
        )
    }

    private val detailState: Flow<AiConversationDetailState> = combine(
        observeDetail(),
        detailTransient
    ) { detail: AiConversationDetailState, overlay: DetailTransientState ->
        detail.copy(
            isSending = overlay.isSending,
            isStreaming = overlay.isStreaming,
            isSubmitting = overlay.isSubmitting,
            errorMessage = overlay.errorMessage ?: detail.errorMessage
        )
    }

    val uiState: StateFlow<AiRecordConversationUiState> = combine(
        historyState,
        detailState
    ) { history: AiConversationHistoryState, detail: AiConversationDetailState ->
        AiRecordConversationUiState(history = history, detail = detail)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AiRecordConversationUiState()
    )

    fun openConversation(conversationId: String) {
        savedStateHandle[KEY_CONVERSATION_ID] = conversationId
        if (selectedConversationId.value != conversationId) {
            _photoEditor.value = null
        }
        selectedConversationId.value = conversationId
        detailTransient.value = DetailTransientState()
    }

    fun updateHomeInput(text: String) {
        historyTransient.update { it.copy(homeInputText = text, errorMessage = null) }
    }

    fun submitHomeInput() {
        createConversationWithFirstMessage(historyTransient.value.homeInputText)
    }

    fun createConversationWithFirstMessage(text: String) {
        if (historyTransient.value.isCreating) return
        val networkAvailable = networkAvailabilityProvider.hasValidatedInternet()
        android.util.Log.i(
            NETWORK_GATE_TAG,
            "path=home_first_message available=$networkAvailable gatePosition=before_local_transaction"
        )
        if (!networkAvailable) {
            historyTransient.update { it.copy(errorMessage = "当前无网络，连接后再发送") }
            return
        }
        historyTransient.update { it.copy(isCreating = true, errorMessage = null) }

        viewModelScope.launch {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                historyTransient.update { it.copy(isCreating = false, errorMessage = "Message cannot be blank") }
                return@launch
            }

            runCatching { createConversationWithFirstMessageUseCase(trimmed) }
                .onSuccess { conversationId ->
                    if (conversationId == null) {
                        historyTransient.update { it.copy(isCreating = false, errorMessage = "Message cannot be blank") }
                    } else {
                        savedStateHandle[KEY_CONVERSATION_ID] = conversationId
                        selectedConversationId.value = conversationId
                        historyTransient.value = HistoryTransientState(
                            isCreating = false,
                            homeInputText = "",
                            lastCreatedConversationId = conversationId
                        )
                        _events.tryEmit(
                            AiRecordConversationEvent.ConversationCreated(
                                conversationId = conversationId,
                                firstMessageText = trimmed
                            )
                        )
                    }
                }
                .onFailure { error ->
                    historyTransient.update {
                        it.copy(isCreating = false, errorMessage = error.message ?: "Failed to create conversation")
                    }
                }
        }
    }

    fun setSendingState(isSending: Boolean, isStreaming: Boolean = false) {
        detailTransient.update { it.copy(isSending = isSending, isStreaming = isStreaming) }
    }

    fun setDetailError(message: String?) {
        detailTransient.update { it.copy(errorMessage = message) }
    }

    fun getDraftStateFlow(conversationId: String): Flow<ConversationAttachmentDraftState> {
        val savedIdsFlow = savedStateHandle.getStateFlow<List<String>>("draft_ids_$conversationId", emptyList())
        return combine(
            savedIdsFlow,
            observeConversationMediaUseCase(conversationId),
            importingCounts,
            pickerOpenStates,
            cameraOpeningStates
        ) { savedIds, dbAssets, impCounts, pickerOpens, cameraOpens ->
            val activeDbAssetsMap = dbAssets
                .filter { it.deletedAt == null && it.conversationId == conversationId }
                .associateBy { it.id }

            val validIds = savedIds.filter { id -> activeDbAssetsMap.containsKey(id) }

            if (validIds != savedIds) {
                savedStateHandle["draft_ids_$conversationId"] = validIds
            }

            val draftAssets = validIds.mapNotNull { activeDbAssetsMap[it] }

            val failures = draftAssets.filter { it.lifecycleState == MediaLifecycleState.FAILED }
                .map { AttachmentImportFailure(it.id, it.failureCode ?: "unknown") }

            ConversationAttachmentDraftState(
                conversationId = conversationId,
                attachmentIds = validIds,
                assets = draftAssets,
                importingCount = impCounts[conversationId] ?: 0,
                failures = failures,
                isPickerOpen = pickerOpens[conversationId] ?: false,
                isCameraOpening = cameraOpens[conversationId] ?: false
            )
        }
    }

    fun setPickerOpen(conversationId: String, isOpen: Boolean) {
        pickerOpenStates.update { it + (conversationId to isOpen) }
    }

    fun setCameraOpening(conversationId: String, isOpen: Boolean) {
        cameraOpeningStates.update { it + (conversationId to isOpen) }
    }

    fun importPhotos(conversationId: String, uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val currentIds = savedStateHandle.get<List<String>>("draft_ids_$conversationId") ?: emptyList()
            val currentImpCount = importingCounts.value[conversationId] ?: 0
            val remaining = (6 - (currentIds.size + currentImpCount)).coerceAtLeast(0)
            if (remaining <= 0) {
                return@launch
            }
            val toImport = uris.take(remaining)
            if (toImport.isEmpty()) return@launch

            importingCounts.update { it + (conversationId to (currentImpCount + toImport.size)) }

            try {
                val identity = currentIdentityProvider.currentIdentity()
                val ownerId = identity.localOwnerId
                val requests = toImport.map { uri ->
                    ImportLocalMediaRequest(
                        conversationId = conversationId,
                        ownerLocalId = ownerId,
                        source = MediaSource.PHOTO_PICKER,
                        input = LocalMediaInput.ContentReference(uri)
                    )
                }
                val results = importLocalMediaUseCase(requests, System.currentTimeMillis())
                val newIds = results.map { it.mediaId }
                val updatedIds = currentIds + newIds
                savedStateHandle["draft_ids_$conversationId"] = updatedIds
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Failures logged
            } finally {
                importingCounts.update {
                    val count = it[conversationId] ?: 0
                    it + (conversationId to (count - toImport.size).coerceAtLeast(0))
                }
            }
        }
    }

    fun importCameraCapture(
        conversationId: String,
        relativePath: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val currentIds = savedStateHandle.get<List<String>>("draft_ids_$conversationId") ?: emptyList()
            val currentImpCount = importingCounts.value[conversationId] ?: 0
            val remaining = (6 - (currentIds.size + currentImpCount)).coerceAtLeast(0)
            if (remaining <= 0) {
                onComplete(false, null)
                return@launch
            }

            importingCounts.update { it + (conversationId to (currentImpCount + 1)) }

            var success = false
            var finalMediaId: String? = null
            try {
                val identity = currentIdentityProvider.currentIdentity()
                val ownerId = identity.localOwnerId
                val requests = listOf(
                    ImportLocalMediaRequest(
                        conversationId = conversationId,
                        ownerLocalId = ownerId,
                        source = MediaSource.CAMERA,
                        input = LocalMediaInput.AppCacheFile(relativePath)
                    )
                )
                val results = importLocalMediaUseCase(requests, System.currentTimeMillis())
                val firstResult = results.firstOrNull()
                if (firstResult != null) {
                    val updatedIds = currentIds + firstResult.mediaId
                    savedStateHandle["draft_ids_$conversationId"] = updatedIds
                    success = true
                    finalMediaId = firstResult.mediaId
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Exceptions caught
            } finally {
                importingCounts.update {
                    val count = it[conversationId] ?: 0
                    it + (conversationId to (count - 1).coerceAtLeast(0))
                }
                onComplete(success, finalMediaId)
            }
        }
    }

    fun removeDraftAttachment(conversationId: String, mediaId: String) {
        viewModelScope.launch {
            val currentList = savedStateHandle.get<List<String>>("draft_ids_$conversationId") ?: emptyList()
            savedStateHandle["draft_ids_$conversationId"] = currentList - mediaId
            try {
                discardStagedMediaUseCase(mediaId, System.currentTimeMillis())
            } catch (e: IllegalArgumentException) {
                // Ignore if READY
            } catch (e: Exception) {
                // Log exception
            }
        }
    }

    fun retryDraftAttachment(conversationId: String, mediaId: String) {
        viewModelScope.launch {
            try {
                retryLocalMediaImportUseCase(mediaId)
            } catch (e: Exception) {
                // Log exception
            }
        }
    }

    /**
     * Submits a user message that may contain both text and local media attachments.
     *
     * This method captures immutable input, runs the local Room transaction once,
     * and on success clears only the submitted draft IDs and emits
     * [AiRecordConversationEvent.MediaMessageCommitted] so that the Vision assistant
     * turn can be started for the persisted user message.
     *
     * On any local failure, the input text and attachment drafts are preserved so
     * the user can retry or edit.
     */
    fun submitMediaMessage(
        conversationId: String,
        text: String,
        orderedAttachmentIds: List<String>
    ) {
        if (conversationId.isBlank()) return
        if (detailTransient.value.isSubmitting) return
        val networkAvailable = networkAvailabilityProvider.hasValidatedInternet()
        android.util.Log.i(
            NETWORK_GATE_TAG,
            "path=media_message available=$networkAvailable gatePosition=before_media_transaction"
        )
        if (!networkAvailable) {
            setDetailError("当前无网络，连接后再发送")
            return
        }

        val trimmedText = text.trim()
        val attachments = orderedAttachmentIds.distinct()
        if (attachments.isEmpty()) {
            if (trimmedText.isBlank()) {
                setDetailError("Message cannot be blank")
            }
            return
        }
        if (attachments.size > MAX_ATTACHMENT_COUNT) {
            setDetailError("最多只能发送 6 张图片")
            return
        }

        detailTransient.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val userMessageId = UUID.randomUUID().toString()
                val request = SendUserMessageWithMediaRequest(
                    conversationId = conversationId,
                    userMessageId = userMessageId,
                    text = trimmedText,
                    orderedMediaIds = attachments,
                    createdAt = System.currentTimeMillis()
                )

                when (val result = sendUserMessageWithMediaUseCase(request)) {
                    is SendUserMessageWithMediaResult.Committed,
                    is SendUserMessageWithMediaResult.AlreadyCommitted -> {
                        val persistedUserMessageId = if (result is SendUserMessageWithMediaResult.Committed) {
                            result.userMessageId
                        } else {
                            userMessageId
                        }
                        removeSubmittedDraftIds(conversationId, attachments)
                        _events.tryEmit(
                            AiRecordConversationEvent.MediaMessageCommitted(
                                conversationId = conversationId,
                                userMessageId = persistedUserMessageId
                            )
                        )
                    }

                    is SendUserMessageWithMediaResult.InvalidConversation -> {
                        setDetailError("无法发送：${result.reason}")
                    }

                    is SendUserMessageWithMediaResult.InvalidMedia -> {
                        setDetailError("图片无效：${result.reason}")
                    }

                    is SendUserMessageWithMediaResult.MediaAlreadyAttached -> {
                        setDetailError("图片已经发送过")
                    }

                    is SendUserMessageWithMediaResult.Conflict -> {
                        setDetailError("发送冲突，请刷新后重试")
                    }

                    is SendUserMessageWithMediaResult.Failed -> {
                        setDetailError("发送失败：${result.error.message ?: "未知错误"}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setDetailError("发送失败：${e.message ?: "未知错误"}")
            } finally {
                detailTransient.update { it.copy(isSubmitting = false) }
            }
        }
    }

    // region Photo assignment editor session

    private val _photoEditor = MutableStateFlow<PhotoAssignmentEditorUiState?>(null)
    val photoEditor: StateFlow<PhotoAssignmentEditorUiState?> = _photoEditor

    /**
     * Opens the photo assignment editor for one confirmation card. The session
     * is a local snapshot: no Room write happens until [savePhotoAssignments].
     * Refuses to open for terminal/guard-pending cards or cards without a legal
     * origin photo set.
     */
    fun openPhotoAssignmentEditor(cardId: String, initialMealIndex: Int = 0) {
        val conversationId = selectedConversationId.value ?: return
        val messages = uiState.value.detail.messages
        val lookup = findEditorCard(messages, cardId) ?: return
        if (!isCardPhotoEditable(lookup)) return
        val meals = lookup.card.meals.orEmpty()
        if (meals.isEmpty()) return
        val originIds = resolveOriginMediaIds(messages, lookup.assistantMessageId)
        if (!PhotoAssignmentDraft.isLegalOriginSet(originIds)) return

        val draft = PhotoAssignmentDraft.fromMeals(cardId, meals, originIds)
        _photoEditor.value = PhotoAssignmentEditorUiState(
            conversationId = conversationId,
            cardId = cardId,
            mealLabels = meals.map(::mealDisplayLabel),
            selectedMealIndex = initialMealIndex.coerceIn(0, meals.size - 1),
            initialDraft = draft,
            draft = draft
        )
    }

    fun photoEditorSelectMeal(mealIndex: Int) {
        _photoEditor.update { session ->
            session?.copy(selectedMealIndex = mealIndex.coerceIn(0, (session.mealLabels.size - 1).coerceAtLeast(0)))
        }
    }

    /** Assigns a photo to the currently selected meal (moving it out of any other meal). */
    fun photoEditorAssignToSelectedMeal(mediaId: String) {
        _photoEditor.update { session ->
            session?.copy(draft = session.draft.assignToMeal(mediaId, session.selectedMealIndex))
        }
    }

    fun photoEditorRemove(mediaId: String) {
        _photoEditor.update { session -> session?.copy(draft = session.draft.removeFromMeal(mediaId)) }
    }

    fun photoEditorMove(mediaId: String, targetMealIndex: Int) {
        _photoEditor.update { session ->
            session?.copy(draft = session.draft.assignToMeal(mediaId, targetMealIndex))
        }
    }

    /** Back/cancel: exits directly when clean, otherwise raises the discard confirmation. */
    fun requestClosePhotoEditor() {
        val session = _photoEditor.value ?: return
        if (session.isSaving) return
        if (session.isDirty && !session.cardNoLongerEditable) {
            _photoEditor.value = session.copy(showDiscardDialog = true)
        } else {
            _photoEditor.value = null
        }
    }

    fun dismissPhotoEditorDiscardDialog() {
        _photoEditor.update { it?.copy(showDiscardDialog = false) }
    }

    /** Discards the session; the real card is left completely untouched. */
    fun discardPhotoEditor() {
        _photoEditor.value = null
    }

    /**
     * Saves the whole card's meal assignments once through the existing
     * UpdateFoodCardPhotoAssignmentsUseCase. Debounced by [PhotoAssignmentEditorUiState.isSaving];
     * on failure the edit state is kept and a retryable error is surfaced; when
     * the card reached a terminal state the save fails safely and only exit remains.
     */
    fun savePhotoAssignments() {
        val session = _photoEditor.value ?: return
        if (session.isSaving) return

        // Re-validate against the live Room-backed card before writing so a stale
        // editor session can never overwrite a card that was confirmed/cancelled
        // or synced away while editing.
        val lookup = findEditorCard(uiState.value.detail.messages, session.cardId)
        if (lookup == null || !isCardPhotoEditable(lookup)) {
            _photoEditor.update {
                it?.copy(saveError = "这条记录已进入最终状态，照片无法再调整", cardNoLongerEditable = true)
            }
            return
        }
        if (!session.draft.isValid()) {
            _photoEditor.update { it?.copy(saveError = "照片分配无效，请调整后重试") }
            return
        }

        _photoEditor.update { it?.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val result = updateFoodCardPhotoAssignmentsUseCase(
                    cardId = session.cardId,
                    assignments = session.draft.toMealPhotoAssignments()
                )
                when (result) {
                    UpdateFoodCardPhotoAssignmentsResult.Updated,
                    UpdateFoodCardPhotoAssignmentsResult.Unchanged -> {
                        _photoEditor.value = null
                    }

                    UpdateFoodCardPhotoAssignmentsResult.NotEditable,
                    UpdateFoodCardPhotoAssignmentsResult.CardNotFound -> {
                        _photoEditor.update {
                            it?.copy(
                                isSaving = false,
                                saveError = "这条记录已进入最终状态，照片无法再调整",
                                cardNoLongerEditable = true
                            )
                        }
                    }

                    UpdateFoodCardPhotoAssignmentsResult.InvalidAssignments -> {
                        _photoEditor.update {
                            it?.copy(isSaving = false, saveError = "照片分配无效，请调整后重试")
                        }
                    }
                }
            } catch (c: CancellationException) {
                _photoEditor.update { it?.copy(isSaving = false) }
                throw c
            } catch (e: Exception) {
                _photoEditor.update {
                    it?.copy(isSaving = false, saveError = "保存失败，请重试")
                }
            }
        }
    }

    // endregion

    private fun removeSubmittedDraftIds(conversationId: String, submittedIds: List<String>) {
        val currentList = savedStateHandle.get<List<String>>("draft_ids_$conversationId") ?: emptyList()
        savedStateHandle["draft_ids_$conversationId"] = currentList - submittedIds.toSet()
    }

    private fun observeHistory(): Flow<AiConversationHistoryState> {
        return conversationRepository.observeConversationsByLastActivity()
            .map { conversations ->
                AiConversationHistoryState(conversations = conversations, isLoading = false)
            }
            .onStart { emit(AiConversationHistoryState(isLoading = true)) }
            .catch { error ->
                emit(
                    AiConversationHistoryState(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to load conversations"
                    )
                )
            }
    }

    private fun observeDetail(): Flow<AiConversationDetailState> {
        return selectedConversationId
            .flatMapLatest { conversationId: String? ->
                if (conversationId.isNullOrBlank()) {
                    emptyFlow<AiConversationDetailState>()
                } else {
                    combine(
                        conversationRepository.observeConversationsByLastActivity()
                            .map { conversations -> conversations.firstOrNull { it.id == conversationId } },
                        aiDraftRepository.observeChatMessages(conversationId),
                        observeConversationMediaUseCase(conversationId),
                        getDraftStateFlow(conversationId)
                    ) { conversation, messages, conversationMedia, draft ->
                        val mediaMap = conversationMedia
                            .filter { it.deletedAt == null }
                            .associateBy { it.id }
                        val messagesWithMedia = messages.map { it.toMessageWithMedia(mediaMap) }
                        AiConversationDetailState(
                            currentConversation = conversation,
                            messages = messages,
                            messagesWithMedia = messagesWithMedia,
                            mediaAssets = conversationMedia,
                            draftState = draft
                        )
                    }
                }
            }
            .onStart { emit(AiConversationDetailState()) }
            .catch { error ->
                emit(AiConversationDetailState(errorMessage = error.message ?: "Failed to load conversation"))
            }
    }

    private data class HistoryTransientState(
        val isCreating: Boolean = false,
        val homeInputText: String = "",
        val lastCreatedConversationId: String? = null,
        val errorMessage: String? = null
    )

    private data class DetailTransientState(
        val isSending: Boolean = false,
        val isStreaming: Boolean = false,
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null
    )

    private companion object {
        private const val KEY_CONVERSATION_ID = "conversationId"
        private const val MAX_ATTACHMENT_COUNT = 6
        private const val NETWORK_GATE_TAG = "DayZeroNetworkGate"
    }
}
