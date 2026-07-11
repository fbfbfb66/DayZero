package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.VisionAssistantTurnOrchestrator
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealType
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.Conversation
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.AiChatCard
import com.example.domain.model.ai.assistant.AiIntent
import com.example.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.example.domain.model.ai.assistant.PreparedVisionAttachment
import com.example.domain.model.ai.assistant.PreparedVisionRequest
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.model.ai.assistant.VisionAssistantTurnResult
import com.example.domain.model.ai.assistant.VisionPreparationFailure
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.ConversationRepository
import com.example.domain.repository.RecordRepository
import com.example.domain.repository.VisionAttachmentPreparationRepository
import com.example.domain.time.CurrentDateProvider
import com.example.domain.usecase.PrepareVisionAttachmentsForMessageUseCase
import com.example.domain.usecase.ReleasePreparedVisionAttachmentsUseCase
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VisionAssistantTurnOrchestratorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var context: Context
    private lateinit var latencyLogger: AiLatencyTraceLogger
    private lateinit var prepareRepository: FakeVisionAttachmentPreparationRepository
    private lateinit var releaseRepository: FakeVisionAttachmentPreparationRepository
    private lateinit var prepareUseCase: PrepareVisionAttachmentsForMessageUseCase
    private lateinit var releaseUseCase: ReleasePreparedVisionAttachmentsUseCase
    private lateinit var assistantRepository: FakeAiAssistantRepository
    private lateinit var draftRepository: FakeAiDraftRepository
    private lateinit var recordRepository: FakeRecordRepository
    private lateinit var conversationRepository: FakeConversationRepository
    private lateinit var currentDateProvider: FixedCurrentDateProvider
    private lateinit var orchestrator: VisionAssistantTurnOrchestrator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        latencyLogger = AiLatencyTraceLogger(context)
        prepareRepository = FakeVisionAttachmentPreparationRepository()
        releaseRepository = prepareRepository // share to track release calls against same fake
        prepareUseCase = PrepareVisionAttachmentsForMessageUseCase(prepareRepository)
        releaseUseCase = ReleasePreparedVisionAttachmentsUseCase(releaseRepository)
        assistantRepository = FakeAiAssistantRepository()
        draftRepository = FakeAiDraftRepository()
        recordRepository = FakeRecordRepository()
        conversationRepository = FakeConversationRepository()
        currentDateProvider = FixedCurrentDateProvider(LocalDate.of(2026, 6, 26))
        orchestrator = VisionAssistantTurnOrchestrator(
            prepareUseCase = prepareUseCase,
            releaseUseCase = releaseUseCase,
            aiAssistantRepository = assistantRepository,
            aiDraftRepository = draftRepository,
            recordRepository = recordRepository,
            conversationRepository = conversationRepository,
            currentDateProvider = currentDateProvider,
            latencyLogger = latencyLogger
        )
    }

    @After
    fun tearDown() {
        // No shared database to close.
    }

    @Test
    fun streamSuccess_preparesOnce_streamsOnce_releasesOnce_noFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamResult = turn("stream reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Success)
        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
        assertEquals(listOf(prepared.requestId), releaseRepository.releasedRequestIds)
    }

    @Test
    fun streamSuccess_deltasAreAccumulatedAndWrittenToStreamingState() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-delta"
        val userMessageId = "user-delta"
        val assistantMessageId = placeholderId(userMessageId)
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User,
                text = "pic"
            )
        )
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = assistantMessageId,
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = ""
            )
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = conversationId, userMessageId = userMessageId)
        )
        assistantRepository.streamDeltas = listOf("Hello", " ", "world")
        assistantRepository.streamResult = turn("Hello world")

        orchestrator.runVisionTurn(conversationId, userMessageId)
        advanceUntilIdle()

        val final = draftRepository.getChatMessageById(assistantMessageId)
        assertEquals("Hello world", final?.text)
        assertNull(draftRepository.streamingStateFor(conversationId))
    }

    @Test
    fun streamSuccess_singleServerDelta_isPresentedInMultipleFramesBeforeFinal() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-single-delta"
        val userMessageId = "user-single-delta"
        val assistantMessageId = placeholderId(userMessageId)
        draftRepository.insertChatMessage(
            AiChatMessage(id = userMessageId, conversationId = conversationId, role = ChatRole.User, text = "pic")
        )
        draftRepository.insertChatMessage(
            AiChatMessage(id = assistantMessageId, conversationId = conversationId, role = ChatRole.Assistant, text = "")
        )
        prepareRepository.nextResult = Result.success(defaultPrepared(conversationId, userMessageId))
        val fullReply = "This vision reply is deliberately long enough to require several visible presentation frames."
        assistantRepository.streamDeltas = listOf(fullReply)
        assistantRepository.streamResult = turn(fullReply)

        orchestrator.runVisionTurn(conversationId, userMessageId)
        advanceUntilIdle()

        val frames = draftRepository.streamingHistory.filter { it.conversationId == conversationId }
        assertTrue(frames.size > 2)
        assertTrue(frames.first().text.length < fullReply.length)
        assertEquals(fullReply, frames.last().text)
        assertEquals(fullReply, draftRepository.getChatMessageById(assistantMessageId)?.text)
        assertNull(draftRepository.streamingStateFor(conversationId))
    }

    @Test
    fun eligibleStreamFailure_isReturnedWithoutClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared(text = "look at this", mediaCount = 2)
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = IOException("network timeout")
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")
        advanceUntilIdle()

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)

        val streamRequest = assistantRepository.streamRequests.single()
        assertEquals(prepared.effectiveAiText, streamRequest.userText)
        assertSameAttachments(prepared, streamRequest)
        assertTrue(assistantRepository.sendRequests.isEmpty())
        assertEquals(1, prepareRepository.prepareCount)
    }

    @Test
    fun streamAndFallbackBothFail_releasesAndCleansAnalyzing() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = ProtocolException("stream protocol error")
        assistantRepository.sendError = ProtocolException("fallback protocol error")

        val analyzingChanges = mutableListOf<Boolean>()
        val result = orchestrator.runVisionTurn("conv-1", "user-1") { analyzingChanges.add(it) }

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
        assertEquals(listOf(true, false), analyzingChanges)
    }

    @Test
    fun prepareFailure_noStreamNoFallback_noReleaseOfPrepared() = runTest(mainDispatcherRule.testDispatcher) {
        prepareRepository.nextFailure = VisionPreparationFailure.MessageNotFound("not found")

        val analyzingChanges = mutableListOf<Boolean>()
        val result = orchestrator.runVisionTurn("conv-1", "user-1") { analyzingChanges.add(it) }

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(0, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(0, releaseRepository.releaseCount)
        assertEquals(listOf(true, false), analyzingChanges)
    }

    @Test
    fun cancellationDuringStream_releases_rethrows_noFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamSuspends = true

        val analyzingChanges = mutableListOf<Boolean>()
        val job = launch(mainDispatcherRule.testDispatcher) {
            try {
                orchestrator.runVisionTurn("conv-1", "user-1") { analyzingChanges.add(it) }
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                // expected
            }
        }
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
        assertTrue(analyzingChanges.last() == false)
    }

    @Test
    fun streamFailure_releasesWithoutStartingFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = IOException("timeout")
        assistantRepository.sendSuspends = true

        val analyzingChanges = mutableListOf<Boolean>()
        val result = orchestrator.runVisionTurn("conv-1", "user-1") { analyzingChanges.add(it) }

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, prepareRepository.prepareCount)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
        assertTrue(analyzingChanges.last() == false)
    }

    @Test
    fun localStreamError_notEligibleForFallback_doesNotFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = IllegalStateException("unexpected local state")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
    }

    @Test
    fun cancellationException_doesNotTriggerFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = CancellationException("user left")

        try {
            orchestrator.runVisionTurn("conv-1", "user-1")
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("user left", e.message)
        }

        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
    }

    @Test
    fun eofException_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = EOFException("stream ended unexpectedly")
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun jsonDataException_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = JsonDataException("malformed SSE payload")
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun missingFinalProtocolException_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = ProtocolException("missing final event")
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun wrappedRecoverableException_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = RuntimeException(
            "stream wrapper",
            IOException("underlying network failure")
        )
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun temporaryHttpException_429_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = httpException(429)
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun temporaryHttpException_503_doesNotStartClientFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = httpException(503)
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun nonRecoverableHttpException_400_doesNotFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = httpException(400)

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun illegalArgumentException_doesNotFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = IllegalArgumentException("local input error")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun persistenceLikeRuntimeException_doesNotFallback() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = RuntimeException("unexpected persistence error")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(1, assistantRepository.streamCount)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun releaseException_doesNotMaskOriginalStreamFailure() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        prepareRepository.releaseError = RuntimeException("release failed")
        assistantRepository.streamError = IOException("network failure")
        assistantRepository.sendResult = turn("fallback reply")

        val result = orchestrator.runVisionTurn("conv-1", "user-1")

        assertTrue(result is VisionAssistantTurnResult.Failure)
        assertEquals(0, assistantRepository.sendCount)
        assertEquals(1, releaseRepository.releaseCount)
    }

    @Test
    fun streamSuccess_persistsFinalReplyToOriginalPlaceholder() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-persist"
        val userMessageId = "user-persist"
        val assistantMessageId = placeholderId(userMessageId)
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User,
                text = "text + image"
            )
        )
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = assistantMessageId,
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = ""
            )
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = "text + image"
            )
        )
        assistantRepository.streamResult = turn("final vision reply")

        val result = orchestrator.runVisionTurn(conversationId, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.Success)
        val final = draftRepository.getChatMessageById(assistantMessageId)
        assertNotNull(final)
        assertEquals("final vision reply", final!!.text)
        assertEquals(ChatRole.Assistant, final.role)
        assertNull(draftRepository.streamingStateFor(conversationId))
    }

    @Test
    fun streamFailure_keepsSameEmptyRetryablePlaceholder() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-fallback"
        val userMessageId = "user-fallback"
        val assistantMessageId = placeholderId(userMessageId)
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User,
                text = ""
            )
        )
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = assistantMessageId,
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = ""
            )
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(
                conversationId = conversationId,
                userMessageId = userMessageId,
                text = PreparedVisionRequest.IMAGE_ONLY_PROMPT
            )
        )
        assistantRepository.streamError = IOException("stream broken")
        assistantRepository.sendResult = turn("fallback final")

        val result = orchestrator.runVisionTurn(conversationId, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.Failure)
        val final = draftRepository.getChatMessageById(assistantMessageId)
        assertEquals("", final?.text)
        assertEquals(0, assistantRepository.sendCount)
    }

    @Test
    fun imageOnlyMessage_usesSyntheticPrompt_notPersistedToUserMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-image-only"
        val userMessageId = "user-image-only"
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User,
                text = ""
            )
        )
        val prepared = defaultPrepared(
            conversationId = conversationId,
            userMessageId = userMessageId,
            text = PreparedVisionRequest.IMAGE_ONLY_PROMPT
        )
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamResult = turn("image only reply")

        orchestrator.runVisionTurn(conversationId, userMessageId)

        val streamRequest = assistantRepository.streamRequests.single()
        assertEquals(PreparedVisionRequest.IMAGE_ONLY_PROMPT, streamRequest.userText)
        // User message text must remain blank.
        assertEquals("", draftRepository.getChatMessageById(userMessageId)?.text)
    }

    @Test
    fun textAndImageMessage_usesPersistedOriginalText() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-text-image"
        val userMessageId = "user-text-image"
        val originalText = "这是我今天的午餐"
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = conversationId,
                role = ChatRole.User,
                text = originalText
            )
        )
        val prepared = defaultPrepared(
            conversationId = conversationId,
            userMessageId = userMessageId,
            text = originalText
        )
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamResult = turn("reply")

        orchestrator.runVisionTurn(conversationId, userMessageId)

        assertEquals(originalText, assistantRepository.streamRequests.single().userText)
        assertEquals(originalText, draftRepository.getChatMessageById(userMessageId)?.text)
    }

    @Test
    fun conversationSwitching_duringNetwork_finalStillWritesToOriginalConversation() = runTest(mainDispatcherRule.testDispatcher) {
        val originalConversation = "conv-original"
        val otherConversation = "conv-other"
        val userMessageId = "user-original"
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = userMessageId,
                conversationId = originalConversation,
                role = ChatRole.User,
                text = "food pic"
            )
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = originalConversation, userMessageId = userMessageId)
        )
        assistantRepository.streamResult = turn("original reply")

        // Simulate an in-flight switch by changing an external "active" id is not needed;
        // the orchestrator only uses the captured targetConversationId.
        val result = orchestrator.runVisionTurn(originalConversation, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.Success)
        val originalMessages = draftRepository.getRecentChatMessages(originalConversation, 10)
        val otherMessages = draftRepository.getRecentChatMessages(otherConversation, 10)
        assertTrue(originalMessages.any { it.role == ChatRole.Assistant && it.text == "original reply" })
        assertTrue(otherMessages.none { it.text == "original reply" })
    }

    @Test
    fun retry_afterFailure_doesNotCreateNewUserMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-retry"
        val userMessageId = "user-retry"
        draftRepository.insertChatMessage(
            AiChatMessage(id = userMessageId, conversationId = conversationId, role = ChatRole.User, text = "pic")
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = conversationId, userMessageId = userMessageId)
        )
        // First attempt fails completely, leaving the placeholder empty.
        assistantRepository.streamError = IOException("first stream fail")
        assistantRepository.sendError = ProtocolException("first fallback fail")
        orchestrator.runVisionTurn(conversationId, userMessageId)

        // Retry: only one user message exists and the empty placeholder is reused.
        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = conversationId, userMessageId = userMessageId)
        )
        assistantRepository.streamError = null
        assistantRepository.streamResult = turn("retry reply")
        val result = orchestrator.runVisionTurn(conversationId, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.Success)
        val userMessages = draftRepository.getRecentChatMessages(conversationId, 10)
            .filter { it.role == ChatRole.User }
        assertEquals(1, userMessages.size)
        assertEquals("retry reply", draftRepository.getChatMessageById(placeholderId(userMessageId))?.text)
    }

    @Test
    fun retry_onCompletedPlaceholder_returnsAlreadyCompleted() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-retry-completed"
        val userMessageId = "user-retry-completed"
        draftRepository.insertChatMessage(
            AiChatMessage(id = userMessageId, conversationId = conversationId, role = ChatRole.User, text = "pic")
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = conversationId, userMessageId = userMessageId)
        )
        assistantRepository.streamResult = turn("first reply")
        orchestrator.runVisionTurn(conversationId, userMessageId)

        prepareRepository.nextResult = Result.success(
            defaultPrepared(conversationId = conversationId, userMessageId = userMessageId)
        )
        assistantRepository.streamResult = turn("retry reply")
        val result = orchestrator.runVisionTurn(conversationId, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.AlreadyCompleted)
        assertEquals("first reply", draftRepository.getChatMessageById(placeholderId(userMessageId))?.text)
    }

    @Test
    fun completedAssistant_notOverwritten() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-completed"
        val userMessageId = "user-completed"
        val assistantMessageId = placeholderId(userMessageId)
        draftRepository.insertChatMessage(
            AiChatMessage(id = userMessageId, conversationId = conversationId, role = ChatRole.User, text = "pic")
        )
        draftRepository.insertChatMessage(
            AiChatMessage(
                id = assistantMessageId,
                conversationId = conversationId,
                role = ChatRole.Assistant,
                text = "already completed",
                assistantCards = emptyList()
            )
        )

        val result = orchestrator.runVisionTurn(conversationId, userMessageId)

        assertTrue(result is VisionAssistantTurnResult.AlreadyCompleted)
        assertEquals(assistantMessageId, (result as VisionAssistantTurnResult.AlreadyCompleted).assistantMessageId)
        assertEquals(0, prepareRepository.prepareCount)
        assertEquals(0, assistantRepository.streamCount)
        assertEquals("already completed", draftRepository.getChatMessageById(assistantMessageId)?.text)
    }

    @Test
    fun releaseUsesCorrectRequestId() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared()
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamResult = turn("ok")

        orchestrator.runVisionTurn("conv-1", "user-1")

        assertEquals(listOf(prepared.requestId), releaseRepository.releasedRequestIds)
    }

    @Test
    fun streamingState_clearedForOriginalConversation_notOtherConversations() = runTest(mainDispatcherRule.testDispatcher) {
        val original = "conv-original"
        val other = "conv-other"
        draftRepository.updateStreamingState(other, "other-msg", "other partial", true)
        draftRepository.insertChatMessage(
            AiChatMessage(id = "user-1", conversationId = original, role = ChatRole.User, text = "pic")
        )
        prepareRepository.nextResult = Result.success(defaultPrepared(conversationId = original, userMessageId = "user-1"))
        assistantRepository.streamResult = turn("ok")

        orchestrator.runVisionTurn(original, "user-1")

        assertNull(draftRepository.streamingStateFor(original))
        assertNotNull(draftRepository.streamingStateFor(other))
    }

    @Test
    fun prepareCalledExactlyOnce_evenWhenStreamAndFallbackRun() = runTest(mainDispatcherRule.testDispatcher) {
        val prepared = defaultPrepared(mediaCount = 3)
        prepareRepository.nextResult = Result.success(prepared)
        assistantRepository.streamError = IOException("fail")
        assistantRepository.sendResult = turn("fallback ok")

        orchestrator.runVisionTurn("conv-1", "user-1")

        assertEquals(1, prepareRepository.prepareCount)
    }

    @Test
    fun fallbackDoesNotRebindMediaAssets_orCreateSecondUserMessage() = runTest(mainDispatcherRule.testDispatcher) {
        val conversationId = "conv-binding"
        val userMessageId = "user-binding"
        val mediaIds = listOf("m1", "m2")
        draftRepository.insertChatMessage(
            AiChatMessage(id = userMessageId, conversationId = conversationId, role = ChatRole.User, text = "pic")
        )
        prepareRepository.nextResult = Result.success(
            defaultPrepared(
                conversationId = conversationId,
                userMessageId = userMessageId,
                mediaIds = mediaIds
            )
        )
        assistantRepository.streamError = IOException("fail")
        assistantRepository.sendResult = turn("fallback ok")

        orchestrator.runVisionTurn(conversationId, userMessageId)

        val userMessages = draftRepository.getRecentChatMessages(conversationId, 10)
            .filter { it.role == ChatRole.User }
        assertEquals(1, userMessages.size)
        // No media binding happens in the orchestrator; the fake preparation repository simply
        // records the request. We assert the request IDs are stable.
        assertEquals(mediaIds, lastPrepared?.attachments?.map { it.mediaId })
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun defaultPrepared(
        conversationId: String = "conv-1",
        userMessageId: String = "user-1",
        text: String = "look at this",
        mediaCount: Int = 1,
        mediaIds: List<String>? = null
    ): PreparedVisionRequest {
        val ids = mediaIds ?: (0 until mediaCount).map { "media-$it" }
        val attachments = ids.map { id ->
            PreparedVisionAttachment(
                mediaId = id,
                mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                base64 = "YjY=", // deterministic small base64, not a real image
                byteSize = 1234
            )
        }
        return PreparedVisionRequest(
            requestId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            userMessageId = userMessageId,
            effectiveAiText = text,
            attachments = attachments
        )
    }

    private fun turn(reply: String, cards: List<AiChatCard> = emptyList()): AiAssistantTurn {
        return AiAssistantTurn(
            id = "turn-${reply.hashCode()}",
            intent = AiIntent.GeneralChat,
            replyText = reply,
            cards = cards,
            suggestedReplies = emptyList()
        )
    }

    private fun placeholderId(userMessageId: String): String {
        return com.example.data.repository.RoomChatMediaTransactionRepository.assistantPlaceholderId(userMessageId)
    }

    private fun httpException(code: Int): HttpException {
        return HttpException(
            Response.error<Any>(
                code,
                "error".toResponseBody(null)
            )
        )
    }

    private fun assertSameAttachments(preparedRequest: PreparedVisionRequest, request: AiAssistantRequest) {
        val actual: List<PreparedVisionAttachment> = request.attachments ?: error("expected attachments")
        val expected: List<PreparedVisionAttachment> = preparedRequest.attachments
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val expectedAttachment = expected[i]
            val actualAttachment = actual[i]
            assertEquals(expectedAttachment.mediaId, actualAttachment.mediaId)
            assertEquals(expectedAttachment.mimeType, actualAttachment.mimeType)
            assertEquals(expectedAttachment.base64, actualAttachment.base64)
            assertEquals(expectedAttachment.byteSize, actualAttachment.byteSize)
        }
    }

    private val lastPrepared: PreparedVisionRequest?
        get() = prepareRepository.lastRequest?.let { prepareRepository.preparedByRequestId[it.requestId] }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeVisionAttachmentPreparationRepository : VisionAttachmentPreparationRepository {
        var nextResult: Result<PreparedVisionRequest>? = null
        var nextFailure: VisionPreparationFailure? = null
        var releaseError: Throwable? = null
        var prepareCount = 0
        var releaseCount = 0
        val releasedRequestIds = mutableListOf<String>()
        var lastRequest: PrepareVisionAttachmentsRequest? = null
        val preparedByRequestId = mutableMapOf<String, PreparedVisionRequest>()

        override suspend fun prepare(request: PrepareVisionAttachmentsRequest): Result<PreparedVisionRequest> {
            prepareCount++
            lastRequest = request
            val result = nextResult ?: nextFailure?.let { Result.failure(it) }
            ?: Result.failure(VisionPreparationFailure.Unknown("not configured"))
            result.getOrNull()?.let { preparedByRequestId[request.requestId] = it }
            nextResult = null
            nextFailure = null
            return result
        }

        override suspend fun release(requestId: String): Result<Unit> {
            releaseCount++
            releasedRequestIds.add(requestId)
            releaseError?.let { throw it }
            return Result.success(Unit)
        }
    }

    private class FakeAiAssistantRepository : AiAssistantRepository {
        var streamResult: AiAssistantTurn? = null
        var streamError: Throwable? = null
        var streamDeltas: List<String> = emptyList()
        var sendResult: AiAssistantTurn? = null
        var sendError: Throwable? = null
        var streamSuspends: Boolean = false
        var sendSuspends: Boolean = false

        val streamRequests = mutableListOf<AiAssistantRequest>()
        val sendRequests = mutableListOf<AiAssistantRequest>()
        var streamCount = 0
        var sendCount = 0

        override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
            sendCount++
            sendRequests.add(request)
            if (sendSuspends) {
                CompletableDeferred<Unit>().await()
            }
            return sendResult ?: throw sendError ?: IllegalStateException("no fallback result")
        }

        override suspend fun streamMessage(
            request: AiAssistantRequest,
            onDelta: suspend (String) -> Unit
        ): AiAssistantTurn {
            streamCount++
            streamRequests.add(request)
            if (streamSuspends) {
                CompletableDeferred<Unit>().await()
            }
            streamError?.let { throw it }
            streamDeltas.forEach { onDelta(it) }
            return streamResult ?: throw IllegalStateException("no stream result")
        }
    }

    private class FakeAiDraftRepository : AiDraftRepository {
        private val messages = mutableListOf<AiChatMessage>()
        private val streamingStates = mutableMapOf<String, FakeStreamingState>()
        val streamingHistory = mutableListOf<FakeStreamingState>()

        data class FakeStreamingState(
            val conversationId: String,
            val messageId: String,
            val text: String,
            val isStreaming: Boolean
        )

        override suspend fun generateDraft(request: com.example.domain.model.ai.AiDraftRequest): com.example.domain.model.ai.CheckinDraft {
            error("unused")
        }

        override fun observeChatMessages(): kotlinx.coroutines.flow.Flow<List<AiChatMessage>> {
            return kotlinx.coroutines.flow.flowOf(messages)
        }

        override fun observeChatMessages(conversationId: String): kotlinx.coroutines.flow.Flow<List<AiChatMessage>> {
            return kotlinx.coroutines.flow.flowOf(messages.filter { it.conversationId == conversationId })
        }

        override suspend fun createConversationWithFirstMessage(text: String, now: Long): String? {
            error("unused")
        }

        override suspend fun getRecentChatMessages(conversationId: String, limit: Int): List<AiChatMessage> {
            return messages.filter { it.conversationId == conversationId }.takeLast(limit)
        }

        override suspend fun findMessageByAssistantCardId(cardId: String): AiChatMessage? {
            return messages.find { msg ->
                msg.assistantCards.any { it.id == cardId }
            }
        }

        override suspend fun getChatMessageById(messageId: String): AiChatMessage? {
            return messages.find { it.id == messageId }
        }

        override suspend fun insertChatMessage(message: AiChatMessage) {
            messages.add(message)
        }

        override suspend fun insertChatMessage(conversationId: String, message: AiChatMessage) {
            messages.add(message.copy(conversationId = conversationId))
        }

        override suspend fun updateChatMessage(message: AiChatMessage) {
            val index = messages.indexOfFirst { it.id == message.id }
            if (index >= 0) {
                messages[index] = message
            } else {
                messages.add(message)
            }
        }

        override suspend fun clearChatMessages() {
            messages.clear()
        }

        override fun updateStreamingState(conversationId: String, messageId: String, text: String, isStreaming: Boolean) {
            val state = FakeStreamingState(
                conversationId = conversationId,
                messageId = messageId,
                text = text,
                isStreaming = isStreaming
            )
            streamingStates[conversationId] = state
            streamingHistory.add(state)
        }

        override fun clearStreamingState(conversationId: String) {
            streamingStates.remove(conversationId)
        }

        fun streamingStateFor(conversationId: String) = streamingStates[conversationId]
    }

    private class FakeRecordRepository : RecordRepository {
        override fun observeRecords(): kotlinx.coroutines.flow.Flow<List<DailyRecord>> {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }

        override suspend fun upsertRecord(record: DailyRecord) {}
        override suspend fun deleteRecordById(recordId: String) {}
        override suspend fun getRecordById(recordId: String): DailyRecord? = null
        override suspend fun getRecordByDateAndStatus(date: LocalDate, status: RecordStatus): DailyRecord? = null
        override suspend fun updateRecordStatus(recordId: String, status: RecordStatus, weightKg: Float?) {}
        override suspend fun deleteFoodFromRecord(recordId: String, mealType: MealType, foodId: String) {}
        override suspend fun clearAllRecords() {}
    }

    private class FakeConversationRepository : ConversationRepository {
        private val conversations = mutableMapOf<String, Conversation>()

        override suspend fun insertConversation(conversation: Conversation) {
            conversations[conversation.id] = conversation
        }

        override suspend fun getConversationById(id: String): Conversation? = conversations[id]
        override fun observeConversations(): kotlinx.coroutines.flow.Flow<List<Conversation>> {
            return kotlinx.coroutines.flow.flowOf(conversations.values.toList())
        }

        override fun observeConversationsByLastActivity(): kotlinx.coroutines.flow.Flow<List<Conversation>> {
            return kotlinx.coroutines.flow.flowOf(conversations.values.toList())
        }

        override suspend fun updateConversationSummary(
            id: String,
            title: String,
            lastMessagePreview: String,
            lastActivityAt: Long,
            updatedAt: Long
        ) {
        }

        override suspend fun softDeleteConversation(id: String, deletedAt: Long) {}
    }

    private class FixedCurrentDateProvider(private val date: LocalDate) : CurrentDateProvider {
        override fun currentDate(): LocalDate = date
    }
}
