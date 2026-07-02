package com.example.domain.usecase

import com.example.domain.model.ai.assistant.PrepareVisionAttachmentsRequest
import com.example.domain.model.ai.assistant.PreparedVisionAttachment
import com.example.domain.model.ai.assistant.PreparedVisionRequest
import com.example.domain.repository.VisionAttachmentPreparationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareVisionAttachmentsForMessageUseCaseTest {

    @Test
    fun invoke_delegatesToRepository() = runBlocking {
        val fakeRepository = FakeVisionAttachmentPreparationRepository()
        val useCase = PrepareVisionAttachmentsForMessageUseCase(fakeRepository)
        val request = PrepareVisionAttachmentsRequest(
            requestId = "req-1",
            conversationId = "conv-1",
            userMessageId = "msg-1"
        )

        val result = useCase(request)

        assertTrue(result.isSuccess)
        assertEquals(1, fakeRepository.prepareCallCount)
        assertEquals(request, fakeRepository.lastPrepareRequest)
    }

    @Test
    fun invoke_overload_buildsRequest() = runBlocking {
        val fakeRepository = FakeVisionAttachmentPreparationRepository()
        val useCase = PrepareVisionAttachmentsForMessageUseCase(fakeRepository)

        useCase("req-2", "conv-2", "msg-2")

        assertEquals(1, fakeRepository.prepareCallCount)
        assertEquals("req-2", fakeRepository.lastPrepareRequest?.requestId)
        assertEquals("conv-2", fakeRepository.lastPrepareRequest?.conversationId)
        assertEquals("msg-2", fakeRepository.lastPrepareRequest?.userMessageId)
    }

    @Test
    fun preparedResult_canBeReusedForStreamingAndFallback() = runBlocking {
        val fakeRepository = FakeVisionAttachmentPreparationRepository()
        val useCase = PrepareVisionAttachmentsForMessageUseCase(fakeRepository)
        val request = PrepareVisionAttachmentsRequest(
            requestId = "req-3",
            conversationId = "conv-3",
            userMessageId = "msg-3"
        )

        val prepared = useCase(request).getOrThrow()

        // Simulate streaming and fallback both using the same prepared result.
        val streamingText = prepared.effectiveAiText
        val fallbackText = prepared.effectiveAiText
        val streamingAttachments = prepared.attachments
        val fallbackAttachments = prepared.attachments

        assertSame(streamingAttachments, fallbackAttachments)
        assertEquals(streamingText, fallbackText)
        assertEquals(1, fakeRepository.prepareCallCount)
    }

    @Test
    fun release_delegatesToRepository() = runBlocking {
        val fakeRepository = FakeVisionAttachmentPreparationRepository()
        val useCase = ReleasePreparedVisionAttachmentsUseCase(fakeRepository)

        val result = useCase("req-1")

        assertTrue(result.isSuccess)
        assertEquals(1, fakeRepository.releaseCallCount)
        assertEquals("req-1", fakeRepository.lastReleaseRequestId)
    }

    private class FakeVisionAttachmentPreparationRepository : VisionAttachmentPreparationRepository {
        var prepareCallCount = 0
        var lastPrepareRequest: PrepareVisionAttachmentsRequest? = null
        var releaseCallCount = 0
        var lastReleaseRequestId: String? = null

        override suspend fun prepare(
            request: PrepareVisionAttachmentsRequest
        ): Result<PreparedVisionRequest> {
            prepareCallCount++
            lastPrepareRequest = request
            return Result.success(
                PreparedVisionRequest(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    userMessageId = request.userMessageId,
                    effectiveAiText = "test",
                    attachments = listOf(
                        PreparedVisionAttachment(
                            mediaId = "media-1",
                            mimeType = PreparedVisionRequest.MIME_TYPE_JPEG,
                            base64 = "dGVzdA==",
                            byteSize = 1234
                        )
                    )
                )
            )
        }

        override suspend fun release(requestId: String): Result<Unit> {
            releaseCallCount++
            lastReleaseRequestId = requestId
            return Result.success(Unit)
        }
    }
}
