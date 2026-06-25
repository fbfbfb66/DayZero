package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.sync.chat.ChatBackfillStateStore
import com.example.data.sync.chat.ChatConversationPullStateStore
import com.example.data.sync.chat.ChatMessagePullStateStore
import com.example.data.sync.chat.ChatPullHealthStateStore
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteIdentityBindingCoordinatorTest {
    private lateinit var context: Context
    private lateinit var identityProvider: MutableIdentityProvider
    private lateinit var bindingStore: RemoteIdentityBindingStateStore
    private lateinit var backfillStore: BackfillStateStore
    private lateinit var pullStore: PullStateStore
    private lateinit var chatBackfillStore: ChatBackfillStateStore
    private lateinit var conversationCursorStore: ChatConversationPullStateStore
    private lateinit var messageCursorStore: ChatMessagePullStateStore
    private lateinit var chatHealthStore: ChatPullHealthStateStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "dayzero_remote_identity_binding",
            "dayzero_backfill",
            "dayzero_pull",
            "dayzero_chat_backfill",
            "dayzero_chat_conversation_pull",
            "dayzero_chat_message_pull",
            "dayzero_chat_pull_health"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }

        identityProvider = MutableIdentityProvider("anonymous-user")
        bindingStore = RemoteIdentityBindingStateStore(context)
        backfillStore = BackfillStateStore(context)
        pullStore = PullStateStore(context)
        chatBackfillStore = ChatBackfillStateStore(context)
        conversationCursorStore = ChatConversationPullStateStore(context)
        messageCursorStore = ChatMessagePullStateStore(context)
        chatHealthStore = ChatPullHealthStateStore(context)
    }

    @Test
    fun remoteUserChangeResetsGlobalBackfillPullAndChatBackfillStateWithoutClearingOtherUserCursors() = runTest {
        seedCompletedState()
        conversationCursorStore.saveCursor("anonymous-user", "2026-06-20T00:00:00Z", "old-conv")
        messageCursorStore.saveCursor("anonymous-user", "2026-06-20T00:00:00Z", "old-msg")
        bindingStore.saveBoundRemoteUserId("anonymous-user")
        identityProvider.remoteUserId = "fixed-user"

        val changed = coordinator().ensureRemoteUserBound()

        assertTrue(changed)
        assertEquals("fixed-user", bindingStore.lastBoundRemoteUserId())
        assertEquals(BackfillStatus.NOT_STARTED, backfillStore.snapshot().status)
        assertEquals(BackfillStatus.NOT_STARTED, chatBackfillStore.snapshot().status)
        assertEquals(PullStatus.NOT_STARTED, pullStore.snapshot().status)
        assertEquals(PullStatus.NOT_STARTED, chatHealthStore.snapshot().status)
        assertNull(pullStore.snapshot().dailyRecordsCursorUpdatedAt)
        assertEquals(Pair("2026-06-20T00:00:00Z", "old-conv"), conversationCursorStore.getCursor("anonymous-user"))
        assertEquals(Pair("2026-06-20T00:00:00Z", "old-msg"), messageCursorStore.getCursor("anonymous-user"))
        assertNull(conversationCursorStore.getCursor("fixed-user"))
        assertNull(messageCursorStore.getCursor("fixed-user"))
    }

    @Test
    fun repeatedIdentityResolutionDoesNotResetBackfillAgain() = runTest {
        identityProvider.remoteUserId = "fixed-user"
        assertTrue(coordinator().ensureRemoteUserBound())
        backfillStore.markCompleted(completedAt = 2000L, stats = BackfillStats(scannedDailyRecordCount = 1))

        val changedAgain = coordinator().ensureRemoteUserBound()

        assertFalse(changedAgain)
        assertEquals(BackfillStatus.COMPLETED, backfillStore.snapshot().status)
        assertEquals(1, backfillStore.snapshot().scannedDailyRecordCount)
    }

    @Test
    fun waitingForAuthDoesNotBindOrReset() = runTest {
        seedCompletedState()
        identityProvider.canRemoteSync = false
        identityProvider.remoteUserId = null

        val changed = coordinator().ensureRemoteUserBound()

        assertFalse(changed)
        assertNull(bindingStore.lastBoundRemoteUserId())
        assertEquals(BackfillStatus.COMPLETED, backfillStore.snapshot().status)
    }

    private fun seedCompletedState() {
        backfillStore.markCompleted(completedAt = 1000L, stats = BackfillStats(scannedDailyRecordCount = 3))
        pullStore.updateCursors(
            dailyRecordsCursorUpdatedAt = 100L,
            mealsCursorUpdatedAt = 200L,
            foodEntriesCursorUpdatedAt = 300L,
            weightRecordsCursorUpdatedAt = 400L
        )
        pullStore.markCompleted(stats = PullStats(appliedCount = 4))
        chatBackfillStore.markCompleted(completedAt = 1000L, stats = com.example.data.sync.chat.ChatBackfillStats(scannedConversationCount = 2))
        chatHealthStore.markCompleted()
    }

    private fun coordinator(): RemoteIdentityBindingCoordinator {
        return RemoteIdentityBindingCoordinator(
            identityProvider = identityProvider,
            bindingStateStore = bindingStore,
            backfillStateStore = backfillStore,
            pullStateStore = pullStore,
            chatBackfillStateStore = chatBackfillStore,
            chatConversationPullStateStore = conversationCursorStore,
            chatMessagePullStateStore = messageCursorStore,
            chatPullHealthStateStore = chatHealthStore
        )
    }

    private class MutableIdentityProvider(
        var remoteUserId: String?,
        var canRemoteSync: Boolean = true
    ) : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "local-owner",
                remoteUserId = remoteUserId,
                authProvider = if (canRemoteSync) "supabase_fixed_password" else "local",
                canRemoteSync = canRemoteSync
            )
        }
    }
}
