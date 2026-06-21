package com.example.data.sync.chat

import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.ChatRemotePullResult
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.sync.ChatRemoteMessagePage
import com.example.domain.model.sync.ChatSyncMessageSnapshot
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatMessagePullCoordinatorTest {

    private lateinit var identityProvider: CurrentIdentityProvider
    private lateinit var remotePullGateway: ChatRemotePullGateway
    private lateinit var remoteMerger: ChatMessageRemoteMerger
    private lateinit var stateStore: ChatMessagePullStateStore
    private lateinit var coordinator: ChatMessagePullCoordinator

    private val identity = AppIdentity(
        localOwnerId = "local_1",
        remoteUserId = "remote-user-a",
        authProvider = "test",
        canRemoteSync = true
    )

    @Before
    fun setup() {
        identityProvider = mockk()
        remotePullGateway = mockk()
        remoteMerger = mockk()
        stateStore = mockk(relaxed = true)
        coEvery { identityProvider.currentIdentity() } returns identity
        coordinator = ChatMessagePullCoordinator(identityProvider, remotePullGateway, remoteMerger, stateStore)
    }

    @Test
    fun pullMessagesUsesRemoteUserCursorAndLocalOwnerForDirtyScope() = runTest {
        every { stateStore.getCursor("remote-user-a") } returns Pair("2026-06-21T00:00:00.000001Z", "cursor-msg")
        val item = remoteMessage("m1")
        val next = ChatSyncServerCursor("2026-06-21T00:00:00.000002Z", "m1")
        coEvery {
            remotePullGateway.fetchMessagePage(identity, ChatSyncServerCursor("2026-06-21T00:00:00.000001Z", "cursor-msg"), any())
        } returns ChatRemotePullResult.Success(ChatRemoteMessagePage(listOf(item), next, hasMore = false))
        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item)) } returns ChatMessageMergeStats(insertedCount = 1)

        val result = coordinator.pullMessages()

        assertTrue(result is ChatMessagePullResult.Success)
        coVerify { remoteMerger.mergeMessagePage("local_1", listOf(item)) }
        coVerify { stateStore.saveCursor("remote-user-a", "2026-06-21T00:00:00.000002Z", "m1") }
    }

    @Test
    fun cursorSaveFailureReturnsRetryableAfterMergeSoPageCanReplay() = runTest {
        every { stateStore.getCursor("remote-user-a") } returns null
        every { stateStore.saveCursor(any(), any(), any()) } throws IllegalStateException("disk full")
        val item = remoteMessage("m1")
        coEvery { remotePullGateway.fetchMessagePage(identity, null, any()) } returns
            ChatRemotePullResult.Success(ChatRemoteMessagePage(listOf(item), ChatSyncServerCursor("time", "m1"), hasMore = false))
        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item)) } returns ChatMessageMergeStats(updatedCount = 1)

        val result = coordinator.pullMessages()

        assertTrue(result is ChatMessagePullResult.RetryableFailure)
        assertTrue((result as ChatMessagePullResult.RetryableFailure).reason.contains("cursor_save_failed"))
        coVerify { remoteMerger.mergeMessagePage("local_1", listOf(item)) }
    }

    @Test
    fun missingParentAndFatalConflictsDoNotSaveCursor() = runTest {
        every { stateStore.getCursor("remote-user-a") } returns null
        val item = remoteMessage("m1")
        coEvery { remotePullGateway.fetchMessagePage(identity, null, any()) } returns
            ChatRemotePullResult.Success(ChatRemoteMessagePage(listOf(item), ChatSyncServerCursor("time", "m1"), hasMore = false))
        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item)) } throws
            MissingParentConversationException("m1", "missing-parent")

        val missingParent = coordinator.pullMessages()

        assertTrue(missingParent is ChatMessagePullResult.DeferredMissingParent)
        coVerify(exactly = 0) { stateStore.saveCursor(any(), any(), any()) }

        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item)) } throws
            ImmutableMessageConflictException("m1", "role", "User", "Assistant")
        val fatal = coordinator.pullMessages()
        assertTrue(fatal is ChatMessagePullResult.ImmutableConflict)
    }

    @Test
    fun multiPageStopsAtEmptyOrHasMoreFalseAndDoesNotAdvancePastFailedMiddlePage() = runTest {
        every { stateStore.getCursor("remote-user-a") } returns null
        val item1 = remoteMessage("m1")
        val item2 = remoteMessage("m2")
        val cursor1 = ChatSyncServerCursor("t1", "m1")
        val cursor2 = ChatSyncServerCursor("t2", "m2")
        coEvery { remotePullGateway.fetchMessagePage(identity, null, any()) } returns
            ChatRemotePullResult.Success(ChatRemoteMessagePage(listOf(item1), cursor1, hasMore = true))
        coEvery { remotePullGateway.fetchMessagePage(identity, cursor1, any()) } returns
            ChatRemotePullResult.Success(ChatRemoteMessagePage(listOf(item2), cursor2, hasMore = true))
        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item1)) } returns ChatMessageMergeStats(insertedCount = 1)
        coEvery { remoteMerger.mergeMessagePage("local_1", listOf(item2)) } throws
            InvalidRemoteMessageException("m2", "bad_schema")

        val result = coordinator.pullMessages()

        assertTrue(result is ChatMessagePullResult.InvalidRemoteMessage)
        coVerify { stateStore.saveCursor("remote-user-a", "t1", "m1") }
        coVerify(exactly = 0) { stateStore.saveCursor("remote-user-a", "t2", "m2") }
    }

    @Test
    fun emptyPageSucceedsWithoutSavingCursorAndBlockedIdentityIsExplicit() = runTest {
        every { stateStore.getCursor("remote-user-a") } returns null
        coEvery { remotePullGateway.fetchMessagePage(identity, null, any()) } returns
            ChatRemotePullResult.Success(ChatRemoteMessagePage(emptyList(), null, hasMore = false))

        val empty = coordinator.pullMessages()

        assertTrue(empty is ChatMessagePullResult.Success)
        assertEquals(0, (empty as ChatMessagePullResult.Success).pagesFetched)
        coVerify(exactly = 0) { stateStore.saveCursor(any(), any(), any()) }

        coEvery { identityProvider.currentIdentity() } returns identity.copy(canRemoteSync = false)
        val blocked = coordinator.pullMessages()
        assertTrue(blocked is ChatMessagePullResult.BlockedIdentity)
    }

    @Test
    fun messageStateStoreScopesByRemoteUserAndIsIndependentFromConversationCursor() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dayzero_chat_message_pull", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dayzero_chat_conversation_pull", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val messageStore = ChatMessagePullStateStore(context)
        val conversationStore = ChatConversationPullStateStore(context)

        messageStore.saveCursor("remote-a", "2026-06-21T00:00:00.000001Z", "msg-a")
        messageStore.saveCursor("remote-b", "2026-06-21T00:00:00.000002Z", "msg-b")
        conversationStore.saveCursor("remote-a", "2026-06-21T00:00:00.000003Z", "conv-a")

        assertEquals(Pair("2026-06-21T00:00:00.000001Z", "msg-a"), messageStore.getCursor("remote-a"))
        assertEquals(Pair("2026-06-21T00:00:00.000002Z", "msg-b"), messageStore.getCursor("remote-b"))
        assertEquals(Pair("2026-06-21T00:00:00.000003Z", "conv-a"), conversationStore.getCursor("remote-a"))
        assertNull(messageStore.getCursor("local_1"))
    }

    private fun remoteMessage(id: String): ChatSyncMessageSnapshot {
        return ChatSyncMessageSnapshot(
            id = id,
            conversationId = "conv-1",
            role = "User",
            text = "hello",
            createdAtMillis = 100L,
            updatedAtMillis = 1000L
        )
    }
}
