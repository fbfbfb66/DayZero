package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.sync.ChatRemotePullGateway
import com.example.data.sync.ChatRemotePullResult
import com.example.data.sync.InProcessSyncScheduler
import com.example.data.sync.PullCoordinator
import com.example.data.sync.PullStateStore
import com.example.data.sync.RemotePullGateway
import com.example.data.sync.RemotePullResult
import com.example.data.sync.SyncHealthReporter
import com.example.data.sync.SyncTriggerReason
import com.example.data.sync.chat.ChatConversationPullCoordinator
import com.example.data.sync.chat.ChatConversationPullStateStore
import com.example.data.sync.chat.ChatConversationRemoteMerger
import com.example.data.sync.chat.ChatMessagePullCoordinator
import com.example.data.sync.chat.ChatMessagePullStateStore
import com.example.data.sync.chat.ChatMessageRemoteMerger
import com.example.data.sync.chat.ChatPullCoordinator
import com.example.data.sync.chat.ChatPullHealthStateStore
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import com.example.domain.model.sync.ChatSyncMessageSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DayZeroChatSyncPullIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dayzero_pull", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dayzero_chat_pull_health", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dayzero_chat_conv_pull_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dayzero_chat_msg_pull_state", Context.MODE_PRIVATE).edit().clear().commit()

        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class StaticIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "local-owner",
                remoteUserId = "remote-user",
                authProvider = "supabase",
                canRemoteSync = true
            )
        }
    }

    private class FakeChatRemotePullGateway : ChatRemotePullGateway {
        var remoteConversations = mutableListOf<ChatSyncConversationSnapshot>()
        var remoteMessages = mutableListOf<ChatSyncMessageSnapshot>()

        override suspend fun fetchConversationPage(
            identity: AppIdentity,
            cursor: com.example.domain.model.sync.ChatSyncServerCursor?,
            limit: Int
        ): ChatRemotePullResult<com.example.domain.model.sync.ChatRemoteConversationPage> {
            val hasMore = remoteConversations.size > limit
            val items = remoteConversations.take(limit)
            remoteConversations = remoteConversations.drop(limit).toMutableList()
            val nextCursor = items.lastOrNull()?.let {
                com.example.domain.model.sync.ChatSyncServerCursor(it.updatedAtMillis.toString(), it.id)
            }
            return ChatRemotePullResult.Success(
                com.example.domain.model.sync.ChatRemoteConversationPage(items, nextCursor, hasMore)
            )
        }

        override suspend fun fetchMessagePage(
            identity: AppIdentity,
            cursor: com.example.domain.model.sync.ChatSyncServerCursor?,
            limit: Int
        ): ChatRemotePullResult<com.example.domain.model.sync.ChatRemoteMessagePage> {
            val hasMore = remoteMessages.size > limit
            val items = remoteMessages.take(limit)
            remoteMessages = remoteMessages.drop(limit).toMutableList()
            val nextCursor = items.lastOrNull()?.let {
                com.example.domain.model.sync.ChatSyncServerCursor(it.updatedAtMillis.toString(), it.id)
            }
            return ChatRemotePullResult.Success(
                com.example.domain.model.sync.ChatRemoteMessagePage(items, nextCursor, hasMore)
            )
        }
    }



    @Test
    fun `Full Pipeline Chat Sync Pull Integration`() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val identityProvider = StaticIdentityProvider()
        val syncQueueDao = database.syncQueueDao()

        val chatGateway = FakeChatRemotePullGateway()

        chatGateway.remoteConversations.add(
            ChatSyncConversationSnapshot(
                id = "conv-1",
                createdAtMillis = Instant.now().toEpochMilli(),
                updatedAtMillis = Instant.now().toEpochMilli(),
                lastActivityAtMillis = Instant.now().toEpochMilli(),
                title = "Test Conv",
                lastMessagePreview = "Hello",
                deletedAtMillis = null,
                conversationDate = java.time.LocalDate.now()
            )
        )

        chatGateway.remoteMessages.add(
            ChatSyncMessageSnapshot(
                id = "msg-1",
                conversationId = "conv-1",
                role = "User",
                text = "Hello",
                createdAtMillis = Instant.now().toEpochMilli(),
                messageType = "Text",
                deletedAtMillis = null,
                updatedAtMillis = Instant.now().toEpochMilli()
            )
        )

        val convMerger = ChatConversationRemoteMerger(database, database.conversationDao(), syncQueueDao)
        val msgMerger = ChatMessageRemoteMerger(database, database.aiChatMessageDao(), database.conversationDao(), syncQueueDao)

        val convCoordinator = ChatConversationPullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatGateway,
            remoteMerger = convMerger,
            stateStore = ChatConversationPullStateStore(context)
        )

        val msgCoordinator = ChatMessagePullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatGateway,
            remoteMerger = msgMerger,
            stateStore = ChatMessagePullStateStore(context)
        )

        val chatPullCoordinator = ChatPullCoordinator(convCoordinator, msgCoordinator)
        val chatPullHealthStore = ChatPullHealthStateStore(context)

        val pullCoordinator = PullCoordinator(
            database = database,
            remotePullGateway = com.example.data.sync.NoopRemotePullGateway(),
            identityProvider = identityProvider,
            stateStore = PullStateStore(context)
        )

        val healthReporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = com.example.data.sync.BackfillStateStore(context),
            pullStateStore = PullStateStore(context),
            chatPullHealthStateStore = chatPullHealthStore,
            dailyRecordDao = database.dailyRecordDao(),
            remoteSyncEnabledProvider = { true }
        )

        val scheduler = InProcessSyncScheduler(
            scope = testScope,
            syncCoordinator = null,
            backfillCoordinator = null,
            chatBackfillCoordinator = null,
            pullCoordinator = pullCoordinator,
            chatPullCoordinator = chatPullCoordinator,
            chatPullHealthStateStore = chatPullHealthStore,
            syncHealthReporter = healthReporter,
            debounceMs = 0L
        )

        val job = scheduler.requestPull(SyncTriggerReason.APP_START)
        job?.join()

        // Assert Conversions
        val convs = database.conversationDao().observeConversations().first()
        assertEquals(1, convs.size)
        assertEquals("conv-1", convs[0].id)

        // Assert Messages
        val msgs = database.aiChatMessageDao().getMessagesByConversationId("conv-1")
        assertEquals(1, msgs.size)
        assertEquals("msg-1", msgs[0].id)

        // Health should be success
        val snapshot = healthReporter.snapshot()
        assertEquals(com.example.data.sync.PullStatus.COMPLETED, snapshot.chatPullStatus)
    }

    @Test
    fun `Missing Parent Rolls Back Page and Does Not Advance Cursor`() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val identityProvider = StaticIdentityProvider()
        val syncQueueDao = database.syncQueueDao()

        val chatGateway = FakeChatRemotePullGateway()

        // Return a message for conv-2, which doesn't exist locally or in this pull payload
        chatGateway.remoteMessages.add(
            ChatSyncMessageSnapshot(
                id = "msg-orphan",
                conversationId = "conv-2",
                role = "User",
                text = "Orphan message",
                createdAtMillis = Instant.now().toEpochMilli(),
                messageType = "Text",
                deletedAtMillis = null,
                updatedAtMillis = Instant.now().toEpochMilli()
            )
        )

        val convMerger = ChatConversationRemoteMerger(database, database.conversationDao(), syncQueueDao)
        val msgMerger = ChatMessageRemoteMerger(database, database.aiChatMessageDao(), database.conversationDao(), syncQueueDao)

        val convCoordinator = ChatConversationPullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatGateway,
            remoteMerger = convMerger,
            stateStore = ChatConversationPullStateStore(context)
        )

        val msgStateStore = ChatMessagePullStateStore(context)
        val msgCoordinator = ChatMessagePullCoordinator(
            identityProvider = identityProvider,
            remotePullGateway = chatGateway,
            remoteMerger = msgMerger,
            stateStore = msgStateStore
        )

        val chatPullCoordinator = ChatPullCoordinator(convCoordinator, msgCoordinator)
        val chatPullHealthStore = ChatPullHealthStateStore(context)

        val pullCoordinator = PullCoordinator(
            database = database,
            remotePullGateway = com.example.data.sync.NoopRemotePullGateway(),
            identityProvider = identityProvider,
            stateStore = PullStateStore(context)
        )

        val healthReporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao,
            identityProvider = identityProvider,
            backfillStateStore = com.example.data.sync.BackfillStateStore(context),
            pullStateStore = PullStateStore(context),
            chatPullHealthStateStore = chatPullHealthStore,
            dailyRecordDao = database.dailyRecordDao(),
            remoteSyncEnabledProvider = { true }
        )

        val scheduler = InProcessSyncScheduler(
            scope = testScope,
            syncCoordinator = null,
            backfillCoordinator = null,
            chatBackfillCoordinator = null,
            pullCoordinator = pullCoordinator,
            chatPullCoordinator = chatPullCoordinator,
            chatPullHealthStateStore = chatPullHealthStore,
            syncHealthReporter = healthReporter,
            debounceMs = 0L
        )

        val job = scheduler.requestPull(SyncTriggerReason.APP_START)
        job?.join()

        // Message should NOT be inserted because parent is missing
        val msgs = database.aiChatMessageDao().getMessagesByConversationId("conv-2")
        assertEquals(0, msgs.size)

        // Health should be retryable
        val snapshot = healthReporter.snapshot()
        assertEquals(com.example.data.sync.PullStatus.FAILED_RETRYABLE, snapshot.chatPullStatus)
        assertEquals("deferred_missing_parent:conv-2", snapshot.chatPullLastError)

        // Cursor should not advance
        assertNotNull(snapshot.chatPullLastError)
        assertEquals(null, msgStateStore.getCursor("remote-user"))
    }

    @Test
    fun `Idempotency and Cursor Advancement - Multiple Runs Do Not Duplicate Data`() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val identityProvider = StaticIdentityProvider()
        val syncQueueDao = database.syncQueueDao()
        val chatGateway = FakeChatRemotePullGateway()

        chatGateway.remoteConversations.add(
            ChatSyncConversationSnapshot(
                id = "conv-idemp",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L,
                lastActivityAtMillis = 1000L,
                title = "Idempotent Conv",
                lastMessagePreview = "Idempotent Msg",
                deletedAtMillis = null,
                conversationDate = java.time.LocalDate.now()
            )
        )
        chatGateway.remoteMessages.add(
            ChatSyncMessageSnapshot(
                id = "msg-idemp",
                conversationId = "conv-idemp",
                role = "User",
                text = "Idempotent Msg",
                createdAtMillis = 1000L,
                messageType = "Text",
                deletedAtMillis = null,
                updatedAtMillis = 1000L
            )
        )

        val convStateStore = ChatConversationPullStateStore(context)
        val msgStateStore = ChatMessagePullStateStore(context)
        val chatPullHealthStore = ChatPullHealthStateStore(context)

        val convCoordinator = ChatConversationPullCoordinator(identityProvider, chatGateway, ChatConversationRemoteMerger(database, database.conversationDao(), syncQueueDao), convStateStore)
        val msgCoordinator = ChatMessagePullCoordinator(identityProvider, chatGateway, ChatMessageRemoteMerger(database, database.aiChatMessageDao(), database.conversationDao(), syncQueueDao), msgStateStore)
        val chatPullCoordinator = ChatPullCoordinator(convCoordinator, msgCoordinator)

        val healthReporter = SyncHealthReporter(
            syncQueueDao = syncQueueDao, identityProvider = identityProvider,
            backfillStateStore = com.example.data.sync.BackfillStateStore(context),
            pullStateStore = PullStateStore(context), chatPullHealthStateStore = chatPullHealthStore,
            dailyRecordDao = database.dailyRecordDao(), remoteSyncEnabledProvider = { true }
        )
        val pullCoordinator = PullCoordinator(database, com.example.data.sync.NoopRemotePullGateway(), identityProvider, PullStateStore(context))

        val scheduler = InProcessSyncScheduler(
            scope = testScope, syncCoordinator = null, backfillCoordinator = null, chatBackfillCoordinator = null,
            pullCoordinator = pullCoordinator, chatPullCoordinator = chatPullCoordinator,
            chatPullHealthStateStore = chatPullHealthStore, syncHealthReporter = healthReporter, debounceMs = 0L
        )

        // First Run
        scheduler.requestPull(SyncTriggerReason.APP_START)?.join()

        // Assert first run inserted and cursors advanced
        assertEquals(1, database.conversationDao().observeConversations().first().size)
        assertEquals(1, database.aiChatMessageDao().getMessagesByConversationId("conv-idemp").size)
        assertNotNull(convStateStore.getCursor("remote-user"))
        assertNotNull(msgStateStore.getCursor("remote-user"))

        // Reset gateway with same data to simulate same page returned
        chatGateway.remoteConversations.add(
            ChatSyncConversationSnapshot(
                id = "conv-idemp", createdAtMillis = 1000L, updatedAtMillis = 1000L, lastActivityAtMillis = 1000L, title = "Idempotent Conv", lastMessagePreview = "Idempotent Msg", deletedAtMillis = null, conversationDate = java.time.LocalDate.now()
            )
        )
        chatGateway.remoteMessages.add(
            ChatSyncMessageSnapshot(
                id = "msg-idemp", conversationId = "conv-idemp", role = "User", text = "Idempotent Msg", createdAtMillis = 1000L, messageType = "Text", deletedAtMillis = null, updatedAtMillis = 1000L
            )
        )

        // Second Run
        scheduler.requestPull(SyncTriggerReason.APP_START)?.join()

        // Assert no duplicates
        assertEquals(1, database.conversationDao().observeConversations().first().size)
        assertEquals(1, database.aiChatMessageDao().getMessagesByConversationId("conv-idemp").size)
    }

    @Test
    fun `Confirmed Card Pull Does Not Write to DailyRecord`() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val identityProvider = StaticIdentityProvider()
        val syncQueueDao = database.syncQueueDao()
        val chatGateway = FakeChatRemotePullGateway()

        chatGateway.remoteConversations.add(
            ChatSyncConversationSnapshot(
                id = "conv-cards",
                conversationDate = java.time.LocalDate.now(),
                title = "Cards",
                lastMessagePreview = "Cards",
                createdAtMillis = 1000L,
                updatedAtMillis = 1000L,
                lastActivityAtMillis = 1000L,
                deletedAtMillis = null
            )
        )
        // Add confirmed card msg
        chatGateway.remoteMessages.add(
            ChatSyncMessageSnapshot(
                id = "msg-card",
                conversationId = "conv-cards",
                role = "Assistant",
                text = "",
                createdAtMillis = 1000L,
                messageType = "Text",
                deletedAtMillis = null,
                updatedAtMillis = 1000L,
                assistantCardsJson = """[{"type": "show_confirm_card", "state": "confirmed", "id": "card-1"}]"""
            )
        )

        val chatPullCoordinator = ChatPullCoordinator(
            ChatConversationPullCoordinator(identityProvider, chatGateway, ChatConversationRemoteMerger(database, database.conversationDao(), syncQueueDao), ChatConversationPullStateStore(context)),
            ChatMessagePullCoordinator(identityProvider, chatGateway, ChatMessageRemoteMerger(database, database.aiChatMessageDao(), database.conversationDao(), syncQueueDao), ChatMessagePullStateStore(context))
        )

        val healthReporter = SyncHealthReporter(syncQueueDao, identityProvider, com.example.data.sync.BackfillStateStore(context), PullStateStore(context), ChatPullHealthStateStore(context), database.dailyRecordDao(), { true })
        val pullCoordinator = PullCoordinator(database, com.example.data.sync.NoopRemotePullGateway(), identityProvider, PullStateStore(context))
        val scheduler = InProcessSyncScheduler(testScope, null, null, null, pullCoordinator, chatPullCoordinator, ChatPullHealthStateStore(context), healthReporter, 0L)

        scheduler.requestPull(SyncTriggerReason.APP_START)?.join()

        // Message is pulled with card
        val msg = database.aiChatMessageDao().getMessagesByConversationId("conv-cards")[0]
        assertEquals(true, msg.assistantCardsJson?.contains("confirmed"))

        // BUT DailyRecord is NOT written (sync avoids business logic hooks)
        val records = database.dailyRecordDao().observeAllRecords().first()
        assertEquals(0, records.size)
    }

    @Test
    fun `Tombstones Do Not Appear in Active DAO Queries`() = runTest {
        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val identityProvider = StaticIdentityProvider()
        val syncQueueDao = database.syncQueueDao()

        // Pre-insert a local active conversation so the tombstone applies
        database.conversationDao().insertConversation(
            com.example.data.local.entity.ConversationEntity(
                id = "conv-tomb", conversationDate = java.time.LocalDate.now().toString(), createdAt = 500L, updatedAt = 500L, lastActivityAt = 500L, title = "Tomb", lastMessagePreview = "Tomb", deletedAt = null
            )
        )

        val chatGateway = FakeChatRemotePullGateway()
        chatGateway.remoteConversations.add(
            ChatSyncConversationSnapshot(
                id = "conv-tomb",
                conversationDate = java.time.LocalDate.now(),
                title = "Tomb",
                lastMessagePreview = "Tomb",
                createdAtMillis = 500L,
                updatedAtMillis = 1000L,
                lastActivityAtMillis = 1000L,
                deletedAtMillis = 1000L // deletedAt != null
            )
        )

        val chatPullCoordinator = ChatPullCoordinator(
            ChatConversationPullCoordinator(identityProvider, chatGateway, ChatConversationRemoteMerger(database, database.conversationDao(), syncQueueDao), ChatConversationPullStateStore(context)),
            ChatMessagePullCoordinator(identityProvider, chatGateway, ChatMessageRemoteMerger(database, database.aiChatMessageDao(), database.conversationDao(), syncQueueDao), ChatMessagePullStateStore(context))
        )

        val healthReporter = SyncHealthReporter(syncQueueDao, identityProvider, com.example.data.sync.BackfillStateStore(context), PullStateStore(context), ChatPullHealthStateStore(context), database.dailyRecordDao(), { true })
        val pullCoordinator = PullCoordinator(database, com.example.data.sync.NoopRemotePullGateway(), identityProvider, PullStateStore(context))
        val scheduler = InProcessSyncScheduler(testScope, null, null, null, pullCoordinator, chatPullCoordinator, ChatPullHealthStateStore(context), healthReporter, 0L)

        scheduler.requestPull(SyncTriggerReason.APP_START)?.join()

        // observeConversations() only returns active (deletedAt IS NULL)
        val activeConvs = database.conversationDao().observeConversations().first()
        assertEquals(0, activeConvs.size)

        // Direct query bypass shows it exists and was soft deleted
        val rawConv = database.conversationDao().getConversationById("conv-tomb")
        assertNotNull(rawConv)
        assertNotNull(rawConv?.deletedAt)
    }
}
