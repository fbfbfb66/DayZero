package com.example.data.sync.chat

import androidx.room.Room
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.DailyRecordEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.DayZeroSyncConstants
import com.example.domain.model.sync.ChatSyncMessageSnapshot
import kotlinx.coroutines.test.runTest
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
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatMessageRemoteMergerTest {

    private lateinit var database: DayZeroDatabase
    private lateinit var messageDao: AiChatMessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var dailyRecordDao: DailyRecordDao
    private lateinit var merger: ChatMessageRemoteMerger

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DayZeroDatabase::class.java
        ).allowMainThreadQueries().build()
        messageDao = database.aiChatMessageDao()
        conversationDao = database.conversationDao()
        syncQueueDao = database.syncQueueDao()
        dailyRecordDao = database.dailyRecordDao()
        merger = ChatMessageRemoteMerger(database, messageDao, conversationDao, syncQueueDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertsFinalUserAssistantCardOnlyAndReplaysIdempotentlyWithoutQueue() = runTest {
        insertConversation("conv-1")
        val user = remoteMessage("user-1", role = "User", text = "hello")
        val assistant = remoteMessage("assistant-1", role = "Assistant", text = "hi")
        val cardOnly = remoteMessage(
            "card-1",
            role = "Assistant",
            text = "",
            assistantCardsJson = """[{"type":"show_confirm_card","id":"confirm-1","state":"pending","future":{"keep":true}}]"""
        )
        val emptyCards = remoteMessage("empty-cards", role = "Assistant", text = "empty", assistantCardsJson = "[]")
        val nullCards = remoteMessage("null-cards", role = "Assistant", text = "null", assistantCardsJson = null)

        val stats = merger.mergeMessagePage("local_1", listOf(user, assistant, cardOnly, emptyCards, nullCards))
        val replay = merger.mergeMessagePage("local_1", listOf(user, assistant, cardOnly, emptyCards, nullCards))

        assertEquals(5, stats.insertedCount)
        assertEquals(5, replay.skippedCount)
        assertEquals(0, syncQueueDao.getPendingCount())
        assertEquals("[]", messageDao.getMessageById("empty-cards")?.assistantCardsJson)
        assertNull(messageDao.getMessageById("null-cards")?.assistantCardsJson)
        val card = JSONObject(messageDao.getMessageById("card-1")!!.assistantCardsJson!!.removeSurrounding("[", "]"))
        assertTrue(card.getJSONObject("future").getBoolean("keep"))
    }

    @Test
    fun parentCanBeActiveOrTombstoneButMissingParentRollsBackAndCreatesNoFakeParent() = runTest {
        insertConversation("active-parent")
        insertConversation("deleted-parent", deletedAt = 2000L)

        merger.mergeMessagePage(
            "local_1",
            listOf(
                remoteMessage("active-msg", conversationId = "active-parent"),
                remoteMessage("deleted-parent-msg", conversationId = "deleted-parent")
            )
        )
        assertNotNull(messageDao.getMessageById("active-msg"))
        assertNotNull(messageDao.getMessageById("deleted-parent-msg"))

        val before = messageDao.getMessagesByConversationId("active-parent")
        try {
            merger.mergeMessagePage(
                "local_1",
                listOf(
                    remoteMessage("will-rollback", conversationId = "active-parent"),
                    remoteMessage("orphan", conversationId = "missing-parent")
                )
            )
            throw AssertionError("Expected missing parent")
        } catch (_: MissingParentConversationException) {
        }
        assertEquals(before, messageDao.getMessagesByConversationId("active-parent"))
        assertNull(messageDao.getMessageById("will-rollback"))
        assertNull(conversationDao.getConversationById("missing-parent"))
    }

    @Test
    fun immutableAndFinalTextConflictsThrowAndRollBackWholePage() = runTest {
        insertConversation("conv-1")
        insertMessage(localMessage("m1", text = "one"))
        insertMessage(localMessage("m2", text = "two"))
        insertMessage(localMessage("m3", text = "three"))
        val before = messageDao.getMessagesByConversationId("conv-1")

        try {
            merger.mergeMessagePage(
                "local_1",
                listOf(
                    remoteMessage("m1", text = "one", contentJson = """{"new":true}"""),
                    remoteMessage("new-m2", text = "inserted"),
                    remoteMessage("m3", text = "different")
                )
            )
            throw AssertionError("Expected content conflict")
        } catch (_: ImmutableMessageContentConflictException) {
        }

        assertEquals(before, messageDao.getMessagesByConversationId("conv-1"))
        assertNull(messageDao.getMessageById("new-m2"))
    }

    @Test
    fun immutableIdentityFieldsAreRejected() = runTest {
        insertConversation("conv-1")
        insertConversation("conv-2")
        insertMessage(localMessage("message-1"))

        assertThrowsImmutable { remoteMessage("message-1", conversationId = "conv-2") }
        assertThrowsImmutable { remoteMessage("message-1", role = "Assistant") }
        assertThrowsImmutable { remoteMessage("message-1", messageType = "ChoiceCard") }
        assertThrowsImmutable { remoteMessage("message-1", createdAtMillis = 999L) }
    }

    @Test
    fun assistantPlaceholderCanBecomeFinalButFinalCannotRollbackToPlaceholderOrDifferentFinal() = runTest {
        insertConversation("conv-1")
        insertMessage(localMessage("placeholder", role = "Assistant", text = ""))

        val stats = merger.mergeMessagePage(
            "local_1",
            listOf(remoteMessage("placeholder", role = "Assistant", text = "final", assistantCardsJson = "[]"))
        )

        assertEquals(1, stats.updatedCount)
        assertEquals("final", messageDao.getMessageById("placeholder")?.text)
        assertEquals("[]", messageDao.getMessageById("placeholder")?.assistantCardsJson)

        try {
            merger.mergeMessagePage("local_1", listOf(remoteMessage("placeholder", role = "Assistant", text = "")))
            throw AssertionError("Expected invalid placeholder")
        } catch (_: InvalidRemoteMessageException) {
        }

        try {
            merger.mergeMessagePage("local_1", listOf(remoteMessage("placeholder", role = "Assistant", text = "other final")))
            throw AssertionError("Expected immutable assistant final text")
        } catch (_: ImmutableMessageContentConflictException) {
        }
    }

    @Test
    fun contentAndSuggestedRepliesUseCleanRemoteSnapshotWithNullDistinctFromEmpty() = runTest {
        insertConversation("conv-1")
        insertMessage(
            localMessage(
                "json-fields",
                text = "same",
                contentJson = """{"local":true}""",
                suggestedRepliesJson = """["old"]"""
            )
        )

        val stats = merger.mergeMessagePage(
            "local_1",
            listOf(
                remoteMessage(
                    "json-fields",
                    text = "same",
                    updatedAtMillis = 100L,
                    contentJson = null,
                    suggestedRepliesJson = "[]"
                )
            )
        )

        assertEquals(1, stats.updatedCount)
        assertNull(messageDao.getMessageById("json-fields")?.contentJson)
        assertEquals("[]", messageDao.getMessageById("json-fields")?.suggestedRepliesJson)
    }

    @Test
    fun dirtyQueryFiltersOperationEntityOwnerAndActiveStatusWithoutChangingQueue() = runTest {
        insertConversation("conv-1")
        insertMessage(localMessage("dirty", text = "local"))

        insertQueue("done", status = DayZeroSyncConstants.STATUS_DONE, operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE)
        insertQueue("fatal", status = DayZeroSyncConstants.STATUS_FAILED_FATAL, operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE)
        insertQueue("other-op", operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION)
        insertQueue("other-owner", owner = "other-local", operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE)

        val cleanStats = merger.mergeMessagePage("local_1", listOf(remoteMessage("dirty", text = "local", contentJson = """{"remote":true}""")))
        assertEquals(1, cleanStats.updatedCount)
        assertEquals("""{"remote":true}""", messageDao.getMessageById("dirty")?.contentJson)

        listOf(
            DayZeroSyncConstants.STATUS_PENDING,
            DayZeroSyncConstants.STATUS_PROCESSING,
            DayZeroSyncConstants.STATUS_FAILED_RETRYABLE,
            DayZeroSyncConstants.STATUS_WAITING_FOR_AUTH
        ).forEachIndexed { index, status ->
            val id = "dirty-$index"
            insertMessage(localMessage(id, text = "local"))
            insertQueue("queue-$index", messageId = id, status = status, operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE)
            val stats = merger.mergeMessagePage("local_1", listOf(remoteMessage(id, text = "remote", contentJson = """{"remote":true}""")))
            assertEquals(1, stats.deferredLocalDirtyCount)
            assertEquals("local", messageDao.getMessageById(id)?.text)
        }

        assertEquals(
            1,
            syncQueueDao.countActiveTasksForEntityAndOperation(
                ownerLocalId = "local_1",
                entityType = ChatSyncQueueContract.ENTITY_MESSAGE,
                entityLocalId = "dirty-0",
                operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE
            )
        )

        insertMessage(localMessage("legacy-owner", text = "legacy local"))
        insertQueue(
            "legacy-owner-queue",
            messageId = "legacy-owner",
            owner = "local_uninitialized",
            operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE
        )
        val legacyStats = merger.mergeMessagePage("local_1", listOf(remoteMessage("legacy-owner", text = "legacy remote")))
        assertEquals(1, legacyStats.deferredLocalDirtyCount)
        assertEquals("legacy local", messageDao.getMessageById("legacy-owner")?.text)
    }

    @Test
    fun unsupportedSchemaVersionFailsBeforeAnyWrite() = runTest {
        insertConversation("conv-1")

        try {
            merger.mergeMessagePage("local_1", listOf(remoteMessage("future-schema", schemaVersion = 99)))
            throw AssertionError("Expected invalid remote message")
        } catch (_: InvalidRemoteMessageException) {
        }

        assertNull(messageDao.getMessageById("future-schema"))
    }

    @Test
    fun tombstoneRulesAreMonotonicAndNeverHardDelete() = runTest {
        insertConversation("conv-1")
        insertMessage(localMessage("newer-active", createdAt = 5000L, text = "active"))
        insertMessage(localMessage("to-delete", createdAt = 1000L, text = "keep text", assistantCardsJson = "[]"))

        val oldTombstone = merger.mergeMessagePage(
            "local_1",
            listOf(remoteMessage("newer-active", text = "active", createdAtMillis = 5000L, updatedAtMillis = 1000L, deletedAtMillis = 1000L))
        )
        assertEquals(1, oldTombstone.skippedCount)
        assertNull(messageDao.getMessageById("newer-active")!!.contentJson)

        val deleteStats = merger.mergeMessagePage(
            "local_1",
            listOf(remoteMessage("to-delete", text = "keep text", createdAtMillis = 1000L, updatedAtMillis = 3000L, deletedAtMillis = 3000L))
        )
        assertEquals(1, deleteStats.tombstoneCount)
        assertEquals(3000L, messageDao.getMessageByIdIncludingDeleted("to-delete")?.deletedAt)
        assertEquals("keep text", messageDao.getMessageByIdIncludingDeleted("to-delete")?.text)
        assertEquals("[]", messageDao.getMessageByIdIncludingDeleted("to-delete")?.assistantCardsJson)

        val resurrect = merger.mergeMessagePage("local_1", listOf(remoteMessage("to-delete", text = "remote active", createdAtMillis = 1000L, updatedAtMillis = 5000L)))
        assertEquals(1, resurrect.skippedCount)
        assertEquals("keep text", messageDao.getMessageByIdIncludingDeleted("to-delete")?.text)

        val missingTombstone = merger.mergeMessagePage(
            "local_1",
            listOf(remoteMessage("missing-delete", text = "remote old text", deletedAtMillis = 6000L, updatedAtMillis = 6000L))
        )
        assertEquals(1, missingTombstone.insertedCount)
        assertEquals(1, missingTombstone.tombstoneCount)
        assertEquals(1, messageDao.getMessagesByConversationId("conv-1").size)
    }

    @Test
    fun confirmedCardPullHasNoBusinessSideEffectsAndDoesNotUpdateConversationSummary() = runTest {
        insertConversation("conv-1", title = "Before", lastMessagePreview = "Preview")
        dailyRecordDao.upsertRecord(
            DailyRecordEntity(
                id = "record-1",
                date = "2026-06-21",
                status = "Confirmed",
                mealsJson = "[]",
                weightKg = null,
                aiSummary = null,
                createdAt = 1L,
                updatedAt = 1L,
                syncStatus = DayZeroSyncConstants.STATUS_SYNCED
            )
        )

        val card = """
            [{"type":"show_confirm_card","id":"confirm-1","state":"confirmed","weightKg":71.2,
              "meals":[{"mealType":"Dinner","items":[{"name":"rice","calories":300}]}]}]
        """.trimIndent()
        merger.mergeMessagePage("local_1", listOf(remoteMessage("confirmed-card", role = "Assistant", text = "", assistantCardsJson = card)))

        assertEquals(1, dailyRecordDao.countBusinessRecordsIncludingDeleted())
        assertEquals(0, syncQueueDao.getPendingCount())
        val conversation = conversationDao.getConversationById("conv-1")
        assertEquals("Before", conversation?.title)
        assertEquals("Preview", conversation?.lastMessagePreview)
    }

    private suspend fun assertThrowsImmutable(snapshot: () -> ChatSyncMessageSnapshot) {
        try {
            merger.mergeMessagePage("local_1", listOf(snapshot()))
            throw AssertionError("Expected immutable conflict")
        } catch (_: ImmutableMessageConflictException) {
        }
    }

    private suspend fun insertConversation(
        id: String,
        deletedAt: Long? = null,
        title: String = "Conversation",
        lastMessagePreview: String = "Preview"
    ) {
        conversationDao.insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-21",
                title = title,
                lastMessagePreview = lastMessagePreview,
                createdAt = 100L,
                updatedAt = 100L,
                lastActivityAt = 100L,
                deletedAt = deletedAt
            )
        )
    }

    private suspend fun insertMessage(message: AiChatMessageEntity) {
        messageDao.insertMessage(message)
    }

    private fun localMessage(
        id: String,
        conversationId: String = "conv-1",
        role: String = "User",
        text: String = "hello",
        createdAt: Long = 100L,
        messageType: String = "Text",
        contentJson: String? = null,
        assistantCardsJson: String? = null,
        suggestedRepliesJson: String? = null,
        updatedAt: Long = createdAt,
        deletedAt: Long? = null
    ): AiChatMessageEntity {
        return AiChatMessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            text = text,
            createdAt = createdAt,
            relatedDraftId = null,
            messageType = messageType,
            contentJson = contentJson,
            assistantCardsJson = assistantCardsJson,
            suggestedRepliesJson = suggestedRepliesJson,
            updatedAt = updatedAt,
            deletedAt = deletedAt
        )
    }

    private fun remoteMessage(
        id: String,
        conversationId: String = "conv-1",
        role: String = "User",
        text: String = "hello",
        createdAtMillis: Long = 100L,
        updatedAtMillis: Long = 1000L,
        deletedAtMillis: Long? = null,
        messageType: String = "Text",
        contentJson: String? = null,
        assistantCardsJson: String? = null,
        suggestedRepliesJson: String? = null,
        schemaVersion: Int = 1
    ): ChatSyncMessageSnapshot {
        return ChatSyncMessageSnapshot(
            id = id,
            conversationId = conversationId,
            role = role,
            text = text,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            deletedAtMillis = deletedAtMillis,
            messageType = messageType,
            contentJson = contentJson,
            assistantCardsJson = assistantCardsJson,
            suggestedRepliesJson = suggestedRepliesJson,
            schemaVersion = schemaVersion
        )
    }

    private suspend fun insertQueue(
        id: String,
        messageId: String = "dirty",
        owner: String = "local_1",
        status: String = DayZeroSyncConstants.STATUS_PENDING,
        operation: String = ChatSyncQueueContract.OP_UPSERT_MESSAGE
    ) {
        syncQueueDao.insert(
            SyncQueueEntity(
                id = id,
                ownerLocalId = owner,
                entityType = ChatSyncQueueContract.ENTITY_MESSAGE,
                entityLocalId = messageId,
                operation = operation,
                payloadJson = "{}",
                status = status,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
    }
}
