package com.example.data.sync.chat

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPullCoordinatorTest {

    private lateinit var conversationCoordinator: ChatConversationPullCoordinator
    private lateinit var messageCoordinator: ChatMessagePullCoordinator
    private lateinit var coordinator: ChatPullCoordinator

    private val convStats = ChatConversationMergeStats(1, 0, 0)
    private val msgStats = ChatMessageMergeStats(1, 0, 0, 0, 0)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        conversationCoordinator = mockk()
        messageCoordinator = mockk()
        coordinator = ChatPullCoordinator(conversationCoordinator, messageCoordinator)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `pullAll success - calls conversation then message`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.Success(msgStats, 1)

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.Success)
        val success = result as ChatPullResult.Success
        assertEquals(convStats, success.conversationStats)
        assertEquals(msgStats, success.messageStats)

        coVerifyOrder {
            conversationCoordinator.pullConversations(100)
            messageCoordinator.pullMessages(100)
        }
    }

    @Test
    fun `conversation pull retryable failure - skips message pull`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.RetryableFailure("network_error")

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.ConversationRetryableFailure)
        assertEquals("network_error", (result as ChatPullResult.ConversationRetryableFailure).reason)

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 0) { messageCoordinator.pullMessages(any()) }
    }

    @Test
    fun `conversation pull fatal failure - skips message pull`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.FatalFailure("auth_error")

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.ConversationFatalFailure)
        assertEquals("auth_error", (result as ChatPullResult.ConversationFatalFailure).reason)

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 0) { messageCoordinator.pullMessages(any()) }
    }

    @Test
    fun `message pull retryable failure - retains conversation success stats`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.RetryableFailure("timeout")

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.MessageRetryableFailure)
        val retryable = result as ChatPullResult.MessageRetryableFailure
        assertEquals("timeout", retryable.reason)
        assertEquals(convStats, retryable.conversationStats)

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 1) { messageCoordinator.pullMessages(100) }
    }

    @Test
    fun `message pull fatal failure - retains conversation success stats`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.FatalFailure("db_error")

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.MessageFatalFailure)
        val fatal = result as ChatPullResult.MessageFatalFailure
        assertEquals("db_error", fatal.reason)
        assertEquals(convStats, fatal.conversationStats)

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 1) { messageCoordinator.pullMessages(100) }
    }

    @Test
    fun `message pull missing parent - mapped to message retryable failure`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.DeferredMissingParent("missing_id")

        val result = coordinator.pullAll()

        assertTrue(result is ChatPullResult.MessageRetryableFailure)
        val retryable = result as ChatPullResult.MessageRetryableFailure
        assertEquals("deferred_missing_parent:missing_id", retryable.reason)
        assertEquals(convStats, retryable.conversationStats)

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 1) { messageCoordinator.pullMessages(100) }
    }

    @Test
    fun `single flight - concurrent calls block or skip`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } coAnswers {
            delay(200) // simulate delay
            ChatConversationPullResult.Success(convStats, 1)
        }
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.Success(msgStats, 1)

        // Launch two concurrent pulls
        var result1: ChatPullResult? = null
        var result2: ChatPullResult? = null

        val job1 = launch {
            result1 = coordinator.pullAll()
        }
        val job2 = launch {
            delay(50)
            result2 = coordinator.pullAll()
        }

        job1.join()
        job2.join()

        // One should succeed, one should skip
        assertTrue(
            (result1 is ChatPullResult.Success && result2 is ChatPullResult.SkippedAlreadyRunning) ||
            (result2 is ChatPullResult.Success && result1 is ChatPullResult.SkippedAlreadyRunning)
        )

        coVerify(exactly = 1) { conversationCoordinator.pullConversations(100) }
        coVerify(exactly = 1) { messageCoordinator.pullMessages(100) }
    }

    @Test
    fun `mutex released after success - subsequent call succeeds`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.Success(msgStats, 1)

        val result1 = coordinator.pullAll()
        val result2 = coordinator.pullAll()

        assertTrue(result1 is ChatPullResult.Success)
        assertTrue(result2 is ChatPullResult.Success)
        coVerify(exactly = 2) { conversationCoordinator.pullConversations(100) }
    }

    @Test
    fun `mutex released after conversation throws exception`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } throws RuntimeException("Unexpected crash")

        try {
            coordinator.pullAll()
        } catch (e: Exception) {
            // expected
        }

        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.Success(msgStats, 1)

        val result2 = coordinator.pullAll()
        assertTrue(result2 is ChatPullResult.Success)
    }

    @Test
    fun `mutex released after message throws exception`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } returns ChatConversationPullResult.Success(convStats, 1)
        coEvery { messageCoordinator.pullMessages(any()) } throws RuntimeException("Unexpected crash")

        try {
            coordinator.pullAll()
        } catch (e: Exception) {
            // expected
        }

        coEvery { messageCoordinator.pullMessages(any()) } returns ChatMessagePullResult.Success(msgStats, 1)

        val result2 = coordinator.pullAll()
        assertTrue(result2 is ChatPullResult.Success)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `cancellation exception propagates up`() = runTest {
        coEvery { conversationCoordinator.pullConversations(any()) } throws kotlinx.coroutines.CancellationException("Cancelled")

        // This should throw CancellationException directly
        coordinator.pullAll()
    }
}
