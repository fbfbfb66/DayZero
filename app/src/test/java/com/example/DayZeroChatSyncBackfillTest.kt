package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.sync.chat.ChatBackfillCoordinator
import com.example.data.sync.chat.ChatBackfillStateStore
import com.example.data.sync.chat.ChatSyncQueueWriter
import com.example.data.sync.chat.ChatSyncQueueContract
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DayZeroChatSyncBackfillTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var coordinator: ChatBackfillCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dayzero_chat_backfill", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
            
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        coordinator = ChatBackfillCoordinator(
            conversationDao = database.conversationDao(),
            messageDao = database.aiChatMessageDao(),
            identityProvider = StaticIdentityProvider(canRemoteSync = true),
            stateStore = ChatBackfillStateStore(context),
            queueWriter = ChatSyncQueueWriter(database.syncQueueDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class StaticIdentityProvider(
        private val canRemoteSync: Boolean
    ) : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = "local-owner-id",
                remoteUserId = if (canRemoteSync) "remote-user-id" else null,
                authProvider = if (canRemoteSync) "supabase" else "local",
                canRemoteSync = canRemoteSync
            )
        }
    }

    private suspend fun setupConversation(id: String = "conv_1") {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-21",
                title = "Backfill Conv",
                lastMessagePreview = "preview",
                createdAt = 100L,
                updatedAt = 100L,
                lastActivityAt = 100L,
                deletedAt = null
            )
        )
    }

    @Test
    fun testBackfillUsesPersistedUpdatedAt() = runBlocking {
        setupConversation("conv_1")
        
        val message = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "Hello persisted updatedAt test",
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 500L,
            deletedAt = null
        )
        database.aiChatMessageDao().insertMessage(message)

        // Run backfill
        coordinator.runOnce()

        // Verify task exists
        val tasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val msgTask = tasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNotNull("Should enqueue message backfill task", msgTask)

        // Assert updatedAt = 500L (ISO string), deletedAt = null
        val payload = JSONObject(msgTask!!.payloadJson)
        assertEquals("1970-01-01T00:00:00.500Z", payload.getString("updatedAt"))
        assertTrue("deletedAt must be null", payload.isNull("deletedAt"))
    }

    @Test
    fun testTombstoneBackfill() = runBlocking {
        setupConversation("conv_1")

        val message = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "Tombstoned message",
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 500L,
            deletedAt = 450L
        )
        database.aiChatMessageDao().insertMessage(message)

        // Run backfill
        coordinator.runOnce()

        // Verify task exists
        val tasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val msgTask = tasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNotNull("Should enqueue tombstoned message backfill task", msgTask)

        // Assert updatedAt = 500L, deletedAt = 450L (both ISO strings)
        val payload = JSONObject(msgTask!!.payloadJson)
        assertEquals("1970-01-01T00:00:00.500Z", payload.getString("updatedAt"))
        assertEquals("1970-01-01T00:00:00.450Z", payload.getString("deletedAt"))
    }

    @Test
    fun testPlaceholderSkip() = runBlocking {
        setupConversation("conv_1")

        // Empty assistant placeholder
        val placeholder = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "",
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 100L,
            deletedAt = null
        )
        database.aiChatMessageDao().insertMessage(placeholder)

        // Run backfill
        coordinator.runOnce()

        // Verify no message queue tasks generated for msg_1
        val tasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val msgTask = tasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNull("Placeholder assistant messages must not be enqueued during backfill", msgTask)
    }

    @Test
    fun testCardOnlyFinal() = runBlocking {
        setupConversation("conv_1")

        // Text empty but assistantCardsJson has card data
        val cardOnlyMessage = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "",
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "ChoiceCard",
            contentJson = null,
            assistantCardsJson = "[{\"id\":\"card_1\"}]",
            suggestedRepliesJson = null,
            updatedAt = 500L,
            deletedAt = null
        )
        database.aiChatMessageDao().insertMessage(cardOnlyMessage)

        // Run backfill
        coordinator.runOnce()

        // Verify task exists
        val tasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val msgTask = tasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNotNull("Card-only final messages must be enqueued during backfill", msgTask)
    }

    @Test
    fun testDuplicateBackfillIdempotency() = runBlocking {
        setupConversation("conv_1")

        val message = AiChatMessageEntity(
            id = "msg_1",
            conversationId = "conv_1",
            role = "Assistant",
            text = "Idempotency test text",
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = null,
            assistantCardsJson = null,
            suggestedRepliesJson = null,
            updatedAt = 500L,
            deletedAt = 450L
        )
        database.aiChatMessageDao().insertMessage(message)

        // Run backfill first time
        coordinator.runOnce()

        // Verify enqueued
        val firstTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val firstMsgTask = firstTasks.find { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        assertNotNull(firstMsgTask)
        val originalPayload = firstMsgTask!!.payloadJson

        // Reset backfill progress state store so it scans again
        context.getSharedPreferences("dayzero_chat_backfill", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        // Run backfill second time
        coordinator.runOnce()

        // Verify idempotency
        val secondTasks = database.syncQueueDao().getTasksByStatus("PENDING")
        val msgTasks = secondTasks.filter { it.entityLocalId == "msg_1" && it.entityType == "ai_chat_message" }
        
        // 1. Must not produce duplicate tasks (exactly 1 pending task)
        assertEquals("Should not insert duplicate tasks for the same message", 1, msgTasks.size)

        // 2. The task payload remains unchanged
        assertEquals("Payload must remain stable during duplicate backfill scans", originalPayload, msgTasks.single().payloadJson)
    }
}
