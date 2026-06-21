package com.example.data.sync.chat

import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.ChatRemotePullResult
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.sync.ChatRemoteConversationPage
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import com.example.domain.model.sync.ChatSyncServerCursor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatConversationPullCoordinatorTest {

    private lateinit var identityProvider: CurrentIdentityProvider
    private lateinit var remotePullGateway: ChatRemotePullGateway
    private lateinit var remoteMerger: ChatConversationRemoteMerger
    private lateinit var stateStore: ChatConversationPullStateStore
    private lateinit var coordinator: ChatConversationPullCoordinator

    private val identity = AppIdentity(
        remoteUserId = "user_123",
        localOwnerId = "local_456",
        canRemoteSync = true,
        authProvider = "test"
    )

    @Before
    fun setup() {
        identityProvider = mockk()
        remotePullGateway = mockk()
        remoteMerger = mockk()
        stateStore = mockk(relaxed = true)

        coEvery { identityProvider.currentIdentity() } returns identity

        coordinator = ChatConversationPullCoordinator(
            identityProvider,
            remotePullGateway,
            remoteMerger,
            stateStore
        )
    }

    @Test
    fun pullConversations_skipsIfSyncDisabled() = runTest {
        coEvery { identityProvider.currentIdentity() } returns identity.copy(canRemoteSync = false)

        val result = coordinator.pullConversations()

        assertTrue(result is ChatConversationPullResult.Skipped)
        assertEquals("remote_sync_disabled", (result as ChatConversationPullResult.Skipped).reason)
    }

    @Test
    fun pullConversations_successSavesCursorAndMerges() = runTest {
        every { stateStore.getCursor("user_123") } returns null

        val remoteSnapshot = ChatSyncConversationSnapshot(
            id = "conv-1",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "Title",
            lastMessagePreview = "Preview",
            createdAtMillis = 100L,
            updatedAtMillis = 100L,
            lastActivityAtMillis = 100L
        )
        val nextCursor = ChatSyncServerCursor("2026-06-21T00:00:00Z", "conv-1")

        coEvery { remotePullGateway.fetchConversationPage(identity, null, any()) } returns
                ChatRemotePullResult.Success(
                    ChatRemoteConversationPage(
                        items = listOf(remoteSnapshot),
                        nextCursor = nextCursor,
                        hasMore = false
                    )
                )

        coEvery { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) } returns
                ChatConversationMergeStats(insertedCount = 1)

        val result = coordinator.pullConversations()

        assertTrue(result is ChatConversationPullResult.Success)
        val success = result as ChatConversationPullResult.Success
        assertEquals(1, success.stats.insertedCount)
        assertEquals(1, success.pagesFetched)

        coVerify { stateStore.saveCursor("user_123", "2026-06-21T00:00:00Z", "conv-1") }
    }

    @Test
    fun pullConversations_readsCursorByRemoteUserIdAndMergesByLocalOwnerId() = runTest {
        every { stateStore.getCursor("user_123") } returns Pair("2026-06-21T00:00:00Z", "cursor-conv")

        val remoteSnapshot = ChatSyncConversationSnapshot(
            id = "conv-1",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "Title",
            lastMessagePreview = "Preview",
            createdAtMillis = 100L,
            updatedAtMillis = 100L,
            lastActivityAtMillis = 100L
        )
        val expectedCursor = ChatSyncServerCursor("2026-06-21T00:00:00Z", "cursor-conv")
        coEvery {
            remotePullGateway.fetchConversationPage(identity, expectedCursor, any())
        } returns ChatRemotePullResult.Success(
            ChatRemoteConversationPage(
                items = listOf(remoteSnapshot),
                nextCursor = ChatSyncServerCursor("2026-06-21T00:01:00Z", "conv-1"),
                hasMore = false
            )
        )
        coEvery { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) } returns
            ChatConversationMergeStats(updatedCount = 1)

        val result = coordinator.pullConversations()

        assertTrue(result is ChatConversationPullResult.Success)
        coVerify { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) }
        coVerify { stateStore.saveCursor("user_123", "2026-06-21T00:01:00Z", "conv-1") }
    }

    @Test
    fun pullConversations_cursorSaveFailureReturnsRetryableAfterMergeSoPageCanReplay() = runTest {
        every { stateStore.getCursor("user_123") } returns null
        every { stateStore.saveCursor(any(), any(), any()) } throws IllegalStateException("cursor disk full")

        val remoteSnapshot = ChatSyncConversationSnapshot(
            id = "conv-1",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "Title",
            lastMessagePreview = "Preview",
            createdAtMillis = 100L,
            updatedAtMillis = 100L,
            lastActivityAtMillis = 100L
        )
        coEvery { remotePullGateway.fetchConversationPage(identity, null, any()) } returns
            ChatRemotePullResult.Success(
                ChatRemoteConversationPage(
                    items = listOf(remoteSnapshot),
                    nextCursor = ChatSyncServerCursor("2026-06-21T00:00:00Z", "conv-1"),
                    hasMore = false
                )
            )
        coEvery { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) } returns
            ChatConversationMergeStats(updatedCount = 1)

        val result = coordinator.pullConversations()

        assertTrue(result is ChatConversationPullResult.RetryableFailure)
        assertTrue((result as ChatConversationPullResult.RetryableFailure).reason.contains("cursor disk full"))
        coVerify { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) }
    }

    @Test
    fun pullConversations_immutableConflictHaltsPull() = runTest {
        every { stateStore.getCursor("user_123") } returns null

        val remoteSnapshot = mockk<ChatSyncConversationSnapshot>(relaxed = true)

        coEvery { remotePullGateway.fetchConversationPage(identity, null, any()) } returns
                ChatRemotePullResult.Success(
                    ChatRemoteConversationPage(
                        items = listOf(remoteSnapshot),
                        nextCursor = ChatSyncServerCursor("time", "id"),
                        hasMore = true
                    )
                )

        coEvery { remoteMerger.mergeConversationPage("local_456", listOf(remoteSnapshot)) } throws
                ImmutableConflictException("conversationDate", "local", "remote")

        val result = coordinator.pullConversations()

        assertTrue(result is ChatConversationPullResult.FatalFailure)
        assertEquals("immutable_conflict_detected", (result as ChatConversationPullResult.FatalFailure).reason)

        // Cursor should NOT be saved if there's an immutable conflict aborting the process
        coVerify(exactly = 0) { stateStore.saveCursor(any(), any(), any()) }
    }

    @Test
    fun pullStateStoreScopesCursorBySupabaseRemoteUserId() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dayzero_chat_conversation_pull", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val store = ChatConversationPullStateStore(context)

        store.saveCursor("remote-user-a", "2026-06-21T00:00:00.000001Z", "conv-a")
        store.saveCursor("remote-user-b", "2026-06-21T00:00:00.000002Z", "conv-b")

        assertEquals(Pair("2026-06-21T00:00:00.000001Z", "conv-a"), store.getCursor("remote-user-a"))
        assertEquals(Pair("2026-06-21T00:00:00.000002Z", "conv-b"), store.getCursor("remote-user-b"))
        assertNull(store.getCursor("local_456"))
    }
}
