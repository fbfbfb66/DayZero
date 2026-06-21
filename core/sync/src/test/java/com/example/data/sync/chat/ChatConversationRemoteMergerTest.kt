package com.example.data.sync.chat


import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.SyncQueueEntity
import com.example.data.sync.DayZeroSyncConstants
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ChatConversationRemoteMergerTest {

    private lateinit var database: DayZeroDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var merger: ChatConversationRemoteMerger

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            DayZeroDatabase::class.java
        ).allowMainThreadQueries().build()
        conversationDao = database.conversationDao()
        syncQueueDao = database.syncQueueDao()
        merger = ChatConversationRemoteMerger(database, conversationDao, syncQueueDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun merge_remoteActiveLocalNone_insertsNewAndReturnsStats() = runTest {
        val remote = createRemoteSnapshot(
            id = "conv-1",
            conversationDate = LocalDate.parse("2026-06-21"),
            title = "New Remote",
            updatedAtMillis = 1000L
        )

        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.insertedCount)
        val local = conversationDao.getConversationById("conv-1")
        assertNotNull(local)
        assertEquals("New Remote", local?.title)
        assertEquals("2026-06-21", local?.conversationDate)
        assertEquals(1000L, local?.updatedAt)
        assertNull(local?.deletedAt)

        // Ensure no sync queue is created
        val count = syncQueueDao.getPendingCount()
        assertEquals(0, count)
    }

    @Test
    fun merge_remoteTombstoneLocalNone_skips() = runTest {
        val remote = createRemoteSnapshot(
            id = "conv-1",
            deletedAtMillis = 1500L
        )

        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.skippedCount)
        assertEquals(0, stats.insertedCount)
        assertNull(conversationDao.getConversationById("conv-1"))
    }

    @Test
    fun merge_remoteNewerActiveLocalActive_updatesLocal() = runTest {
        insertLocal(id = "conv-1", title = "Local Title", updatedAt = 1000L)

        val remote = createRemoteSnapshot(id = "conv-1", title = "Remote Title", updatedAtMillis = 2000L)
        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.updatedCount)
        val local = conversationDao.getConversationById("conv-1")
        assertEquals("Remote Title", local?.title)
        assertEquals(2000L, local?.updatedAt)
    }

    @Test
    fun merge_remoteOlderActiveLocalActive_retainsLocal() = runTest {
        insertLocal(id = "conv-1", title = "Local Title", updatedAt = 2000L)

        val remote = createRemoteSnapshot(id = "conv-1", title = "Remote Title", updatedAtMillis = 1000L)
        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.skippedCount)
        val local = conversationDao.getConversationById("conv-1")
        assertEquals("Local Title", local?.title)
        assertEquals(2000L, local?.updatedAt)
    }

    @Test
    fun merge_remoteTombstoneLocalActiveNewerRemote_softDeletesLocal() = runTest {
        insertLocal(id = "conv-1", updatedAt = 1000L)

        val remote = createRemoteSnapshot(id = "conv-1", updatedAtMillis = 2000L, deletedAtMillis = 2000L)
        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.deletedCount)
        val local = conversationDao.getConversationById("conv-1")
        assertNotNull(local)
        assertEquals(2000L, local?.deletedAt)
    }

    @Test
    fun merge_immutableConflict_rejectsMerge() = runTest {
        insertLocal(id = "conv-1", createdAt = 1000L)
        val remote = createRemoteSnapshot(id = "conv-1", createdAtMillis = 2000L)

        var thrown = false
        try {
            merger.mergeConversationPage("local_1", listOf(remote))
        } catch (e: ImmutableConflictException) {
            thrown = true
        }

        assertEquals(true, thrown)
        val local = conversationDao.getConversationById("conv-1")
        assertEquals(1000L, local?.createdAt)
    }

    @Test
    fun merge_localDirty_defersMerge_onlyIfUpsertConversation() = runTest {
        insertLocal(id = "conv-1", title = "Local Dirty", updatedAt = 1000L)
        // Task with wrong operation should not make it dirty
        syncQueueDao.insert(
            SyncQueueEntity(
                id = "task-other",
                ownerLocalId = "local_1",
                entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
                entityLocalId = "conv-1",
                operation = "OTHER_OPERATION",
                payloadJson = "{}",
                status = "PENDING",
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val remote1 = createRemoteSnapshot(id = "conv-1", title = "Remote 1", updatedAtMillis = 5000L)
        val stats1 = merger.mergeConversationPage("local_1", listOf(remote1))

        assertEquals(1, stats1.updatedCount) // Should update because OTHER_OPERATION doesn't make it dirty
        assertEquals("Remote 1", conversationDao.getConversationById("conv-1")?.title)

        // Now add the correct operation
        syncQueueDao.insert(
            SyncQueueEntity(
                id = "task-upsert",
                ownerLocalId = "local_1",
                entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
                entityLocalId = "conv-1",
                operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
                payloadJson = "{}",
                status = "PENDING",
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val remote2 = createRemoteSnapshot(id = "conv-1", title = "Remote 2", updatedAtMillis = 6000L)
        val stats2 = merger.mergeConversationPage("local_1", listOf(remote2))

        assertEquals(1, stats2.deferredCount) // Now it defers
        assertEquals("Remote 1", conversationDao.getConversationById("conv-1")?.title) // Remains unchanged
    }

    @Test
    fun merge_remoteNewerActiveLocalTombstone_refusesResurrection() = runTest {
        insertLocal(id = "conv-1", updatedAt = 1000L)
        conversationDao.softDeleteConversation("conv-1", 1000L)

        val remote = createRemoteSnapshot(id = "conv-1", title = "Remote Title", updatedAtMillis = 2000L)
        val stats = merger.mergeConversationPage("local_1", listOf(remote))

        assertEquals(1, stats.skippedCount)
        val local = conversationDao.getConversationById("conv-1")
        assertNotNull(local?.deletedAt)
    }

    @Test
    fun merge_updatesLocalWithoutCascadingMessages() = runTest {
        insertLocal(id = "conv-1", title = "Local Title", updatedAt = 1000L)
        insertMessages("conv-1")
        val before = messagesFor("conv-1")

        val remote = createRemoteSnapshot(id = "conv-1", title = "Remote Title", updatedAtMillis = 2000L)
        merger.mergeConversationPage("local_1", listOf(remote))

        val local = conversationDao.getConversationById("conv-1")
        assertEquals("Remote Title", local?.title)

        assertMessagesPreserved("conv-1", before)
    }

    @Test
    fun merge_replayTieOlderAndSoftDelete_preservesMessages() = runTest {
        insertLocal(id = "conv-1", title = "Local Title", updatedAt = 1000L)
        insertMessages("conv-1")
        val before = messagesFor("conv-1")

        val newer = createRemoteSnapshot(id = "conv-1", title = "Remote Newer", updatedAtMillis = 2000L)
        merger.mergeConversationPage("local_1", listOf(newer))
        assertMessagesPreserved("conv-1", before)

        merger.mergeConversationPage("local_1", listOf(newer))
        assertMessagesPreserved("conv-1", before)

        val tie = createRemoteSnapshot(id = "conv-1", title = "Remote Tie", updatedAtMillis = 2000L)
        val tieStats = merger.mergeConversationPage("local_1", listOf(tie))
        assertEquals(1, tieStats.updatedCount)
        assertEquals(1, tieStats.conflictCount)
        assertMessagesPreserved("conv-1", before)

        val older = createRemoteSnapshot(id = "conv-1", title = "Remote Older", updatedAtMillis = 1500L)
        val olderStats = merger.mergeConversationPage("local_1", listOf(older))
        assertEquals(1, olderStats.skippedCount)
        assertEquals("Remote Tie", conversationDao.getConversationById("conv-1")?.title)
        assertMessagesPreserved("conv-1", before)

        val tombstone = createRemoteSnapshot(id = "conv-1", updatedAtMillis = 3000L, deletedAtMillis = 3000L)
        val deleteStats = merger.mergeConversationPage("local_1", listOf(tombstone))
        assertEquals(1, deleteStats.deletedCount)
        assertEquals(3000L, conversationDao.getConversationById("conv-1")?.deletedAt)
        assertMessagesPreserved("conv-1", before)
    }

    @Test
    fun merge_tombstoneRulesAreMonotonicAndDirtyLocalDefers() = runTest {
        insertLocal(id = "local-deleted", updatedAt = 1000L)
        conversationDao.softDeleteConversation("local-deleted", 1000L)

        val activeSameTimestamp = createRemoteSnapshot(id = "local-deleted", title = "Remote Active", updatedAtMillis = 1000L)
        val activeNewer = createRemoteSnapshot(id = "local-deleted", title = "Remote Active Newer", updatedAtMillis = 5000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(activeSameTimestamp)).skippedCount)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(activeNewer)).skippedCount)
        assertEquals(1000L, conversationDao.getConversationById("local-deleted")?.deletedAt)

        insertLocal(id = "active-newer", title = "Local Active", updatedAt = 5000L)
        val oldTombstone = createRemoteSnapshot(id = "active-newer", updatedAtMillis = 1000L, deletedAtMillis = 1000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(oldTombstone)).skippedCount)
        assertNull(conversationDao.getConversationById("active-newer")?.deletedAt)

        insertLocal(id = "active-tie", title = "Local Active", updatedAt = 2000L)
        val tieTombstone = createRemoteSnapshot(id = "active-tie", updatedAtMillis = 2000L, deletedAtMillis = 2000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(tieTombstone)).deletedCount)
        assertEquals(2000L, conversationDao.getConversationById("active-tie")?.deletedAt)

        insertLocal(id = "dirty-local", title = "Local Dirty", updatedAt = 1000L)
        syncQueueDao.insert(
            SyncQueueEntity(
                id = "dirty-task",
                ownerLocalId = "local_1",
                entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
                entityLocalId = "dirty-local",
                operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
                payloadJson = "{}",
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val dirtyActive = createRemoteSnapshot(id = "dirty-local", title = "Remote Active", updatedAtMillis = 5000L)
        val dirtyTombstone = createRemoteSnapshot(id = "dirty-local", updatedAtMillis = 6000L, deletedAtMillis = 6000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(dirtyActive)).deferredCount)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(dirtyTombstone)).deferredCount)
        val dirtyAfter = conversationDao.getConversationById("dirty-local")
        assertEquals("Local Dirty", dirtyAfter?.title)
        assertNull(dirtyAfter?.deletedAt)
    }

    @Test
    fun conversationDirtyQueryFiltersOwnerEntityOperationAndActiveStatus() = runTest {
        insertLocal(id = "conv-1", title = "Local Clean", updatedAt = 1000L)
        val baseTask = SyncQueueEntity(
            id = "task-done",
            ownerLocalId = "local_1",
            entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
            entityLocalId = "conv-1",
            operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
            payloadJson = "{}",
            status = DayZeroSyncConstants.STATUS_DONE,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        syncQueueDao.insert(baseTask)
        syncQueueDao.insert(baseTask.copy(id = "task-other-op", operation = ChatSyncQueueContract.OP_UPSERT_MESSAGE, status = DayZeroSyncConstants.STATUS_PENDING))
        syncQueueDao.insert(baseTask.copy(id = "task-other-owner", ownerLocalId = "other-local", status = DayZeroSyncConstants.STATUS_PENDING))
        syncQueueDao.insert(baseTask.copy(id = "task-other-entity", entityLocalId = "conv-2", status = DayZeroSyncConstants.STATUS_PENDING))

        val cleanRemote = createRemoteSnapshot(id = "conv-1", title = "Remote Clean", updatedAtMillis = 2000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(cleanRemote)).updatedCount)
        assertEquals("Remote Clean", conversationDao.getConversationById("conv-1")?.title)

        syncQueueDao.insert(baseTask.copy(id = "task-uninitialized-owner", ownerLocalId = "local_uninitialized", status = DayZeroSyncConstants.STATUS_PENDING))
        val dirtyRemote = createRemoteSnapshot(id = "conv-1", title = "Remote Dirty", updatedAtMillis = 3000L)
        assertEquals(1, merger.mergeConversationPage("local_1", listOf(dirtyRemote)).deferredCount)
        assertEquals("Remote Clean", conversationDao.getConversationById("conv-1")?.title)
    }

    @Test
    fun merge_immutableConflict_throwsExceptionAndRollsBack() = runTest {
        insertLocal(id = "conv-1", title = "Conv 1", updatedAt = 1000L)
        insertMessages("conv-1")
        val messagesBefore = messagesFor("conv-1")
        insertLocal(id = "conv-3", title = "Conv 3", createdAt = 1000L, updatedAt = 1000L)
        syncQueueDao.insert(
            SyncQueueEntity(
                id = "task-before",
                ownerLocalId = "local_1",
                entityType = ChatSyncQueueContract.ENTITY_CONVERSATION,
                entityLocalId = "conv-1",
                operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION,
                payloadJson = "{}",
                status = DayZeroSyncConstants.STATUS_DONE,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val remote1 = createRemoteSnapshot(id = "conv-1", title = "Remote 1", updatedAtMillis = 2000L)
        val remote2 = createRemoteSnapshot(id = "conv-2", title = "Remote 2", updatedAtMillis = 2000L)
        val remote3 = createRemoteSnapshot(id = "conv-3", title = "Remote 3", createdAtMillis = 2000L) // Conflict!

        var thrown = false
        try {
            merger.mergeConversationPage("local_1", listOf(remote1, remote2, remote3))
        } catch (e: ImmutableConflictException) {
            thrown = true
        }

        assertEquals(true, thrown)

        // Verify that conv-1 and conv-2 were ROLLED BACK
        assertEquals("Conv 1", conversationDao.getConversationById("conv-1")?.title)
        assertNull(conversationDao.getConversationById("conv-2"))
        assertEquals("Conv 3", conversationDao.getConversationById("conv-3")?.title)
        assertEquals(1, syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_DONE))
        assertEquals(0, syncQueueDao.countByStatus(DayZeroSyncConstants.STATUS_PENDING))
        assertMessagesPreserved("conv-1", messagesBefore)
    }

    @Test
    fun genericDirtyQueryStillCountsDailyRecordOperations() = runTest {
        syncQueueDao.insert(
            SyncQueueEntity(
                id = "daily-task",
                ownerLocalId = "local_1",
                entityType = "daily_record",
                entityLocalId = "record-1",
                operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
                payloadJson = "{}",
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        assertEquals(
            1,
            syncQueueDao.countActiveTasksForEntity(
                ownerLocalId = "local_1",
                entityType = "daily_record",
                entityLocalId = "record-1"
            )
        )
        assertEquals(
            0,
            syncQueueDao.countActiveTasksForEntityAndOperation(
                ownerLocalId = "local_1",
                entityType = "daily_record",
                entityLocalId = "record-1",
                operation = ChatSyncQueueContract.OP_UPSERT_CONVERSATION
            )
        )
    }

    private suspend fun insertLocal(
        id: String,
        title: String = "Test",
        createdAt: Long = 100L,
        updatedAt: Long = 100L
    ) {
        conversationDao.insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-21",
                title = title,
                lastMessagePreview = "Preview",
                createdAt = createdAt,
                updatedAt = updatedAt,
                lastActivityAt = updatedAt
            )
        )
    }

    private suspend fun insertMessages(conversationId: String) {
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                id = "$conversationId-msg-1",
                conversationId = conversationId,
                role = "user",
                text = "Hello",
                createdAt = 100L,
                relatedDraftId = null,
                messageType = "text",
                contentJson = """{"kind":"plain"}""",
                assistantCardsJson = null,
                suggestedRepliesJson = """["ok"]"""
            )
        )
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                id = "$conversationId-msg-2",
                conversationId = conversationId,
                role = "assistant",
                text = "Hi",
                createdAt = 200L,
                relatedDraftId = null,
                messageType = "text",
                contentJson = null,
                assistantCardsJson = """[{"type":"show_confirm_card","state":"pending"}]""",
                suggestedRepliesJson = null
            )
        )
    }

    private suspend fun messagesFor(conversationId: String): List<AiChatMessageEntity> {
        return database.aiChatMessageDao().getMessagesByConversationId(conversationId)
    }

    private suspend fun assertMessagesPreserved(
        conversationId: String,
        expected: List<AiChatMessageEntity>
    ) {
        val actual = messagesFor(conversationId)
        assertEquals(expected.size, actual.size)
        assertTrue(expected.isNotEmpty())
        expected.zip(actual).forEach { (before, after) ->
            assertEquals(before.id, after.id)
            assertEquals(conversationId, after.conversationId)
            assertEquals(before.conversationId, after.conversationId)
            assertEquals(before.text, after.text)
            assertEquals(before.contentJson, after.contentJson)
            assertEquals(before.assistantCardsJson, after.assistantCardsJson)
            assertEquals(before.suggestedRepliesJson, after.suggestedRepliesJson)
        }
    }

    private fun createRemoteSnapshot(
        id: String,
        conversationDate: LocalDate = LocalDate.parse("2026-06-21"),
        title: String = "Remote",
        createdAtMillis: Long = 100L,
        updatedAtMillis: Long = 100L,
        deletedAtMillis: Long? = null
    ): ChatSyncConversationSnapshot {
        return ChatSyncConversationSnapshot(
            id = id,
            conversationDate = conversationDate,
            title = title,
            lastMessagePreview = "Preview",
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            lastActivityAtMillis = updatedAtMillis,
            deletedAtMillis = deletedAtMillis
        )
    }
}
