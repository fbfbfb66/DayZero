package com.goings.dayzero.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goings.dayzero.data.identity.StaticLocalIdentityProvider
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.AiChatMessageEntity
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.local.entity.DailyRecordEntity
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRecordRepositoryTest {
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RoomRecordRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRecordRepository(
            database = database,
            dao = database.dailyRecordDao(),
            syncQueueDao = database.syncQueueDao(),
            identityProvider = StaticLocalIdentityProvider()
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun clearAllRecordsAtomicallyClearsBusinessDataAndPreservesUnrelatedData() = runBlocking {
        seedPreservedData()
        seedBusinessRecord()
        seedQueues()

        repository.clearAllRecords()

        assertEquals(0, count("daily_records"))
        assertEquals(0, count("sync_queue", "entityType IN ('daily_record','meal','food_entry','weight_record')"))
        assertEquals(1, count("conversations"))
        assertEquals(1, count("ai_chat_messages"))
        assertEquals(1, count("media_assets"))
        assertEquals(1, count("sync_queue", "entityType = 'ai_conversation'"))
        assertEquals(1, count("sync_queue", "entityType = 'ai_chat_message'"))
        assertEquals(1, count("sync_queue", "entityType = 'media_asset'"))
        assertEquals(1, count("sync_queue", "entityType = 'future_entity'"))
    }

    @Test
    fun queueDeleteFailureRollsBackBusinessRecordDelete() = runBlocking {
        seedBusinessRecord()
        seedQueues()
        val beforeQueues = count("sync_queue")
        exec("CREATE TRIGGER fail_business_queue_delete BEFORE DELETE ON sync_queue WHEN OLD.entityType = 'daily_record' BEGIN SELECT RAISE(ABORT, 'queue delete failure'); END")

        expectFailure { repository.clearAllRecords() }

        assertEquals(1, count("daily_records"))
        assertEquals(beforeQueues, count("sync_queue"))
    }

    @Test
    fun recordDeleteFailureLeavesQueueUnchanged() = runBlocking {
        seedBusinessRecord()
        seedQueues()
        val beforeQueues = count("sync_queue")
        exec("CREATE TRIGGER fail_record_delete BEFORE DELETE ON daily_records BEGIN SELECT RAISE(ABORT, 'record delete failure'); END")

        expectFailure { repository.clearAllRecords() }

        assertEquals(1, count("daily_records"))
        assertEquals(beforeQueues, count("sync_queue"))
    }

    @Test
    fun clearAllRecordsIsIdempotent() = runBlocking {
        seedPreservedData()
        seedBusinessRecord()
        seedQueues()

        repository.clearAllRecords()
        repository.clearAllRecords()

        assertEquals(0, count("daily_records"))
        assertEquals(4, count("sync_queue"))
        assertEquals(1, count("conversations"))
        assertEquals(1, count("ai_chat_messages"))
        assertEquals(1, count("media_assets"))
    }

    private suspend fun seedBusinessRecord() {
        database.dailyRecordDao().upsertRecord(
            DailyRecordEntity("record-1", "2026-07-03", "Confirmed", "[]", 70f, null, 1, 1)
        )
    }

    private suspend fun seedQueues() {
        val businessTypes = listOf("daily_record", "meal", "food_entry", "weight_record", "daily_record", "meal")
        val statuses = listOf("PENDING", "PROCESSING", "FAILED_RETRYABLE", "WAITING_FOR_AUTH", "DONE", "FAILED_FATAL")
        businessTypes.zip(statuses).forEachIndexed { index, (type, status) ->
            database.syncQueueDao().insert(queue("business-$index", type, status))
        }
        database.syncQueueDao().insert(queue("conversation-task", "ai_conversation", "DONE"))
        database.syncQueueDao().insert(queue("message-task", "ai_chat_message", "FAILED_FATAL"))
        database.syncQueueDao().insert(queue("media-task", "media_asset", "PENDING"))
        database.syncQueueDao().insert(queue("future-task", "future_entity", "PROCESSING"))
    }

    private suspend fun seedPreservedData() {
        database.conversationDao().insertConversation(ConversationEntity("conversation-1", "2026-07-03", "Title", "Preview", 1, 1, 1))
        database.aiChatMessageDao().insertMessage(AiChatMessageEntity("message-1", "conversation-1", "User", "hello", 1, null, "Text"))
        database.mediaAssetDao().insertAll(listOf(MediaAssetEntity("media-1", "owner-1", "conversation-1", "message-1", 0, "master.jpg", "thumb.jpg", "image/jpeg", 1, 1, 1, "sha", "CAMERA", "READY", null, 1, 1, null)))
    }

    private fun queue(id: String, type: String, status: String) = SyncQueueEntity(
        id = id,
        entityType = type,
        entityLocalId = "$type-entity",
        operation = "TEST_OPERATION",
        payloadJson = "{}",
        status = status,
        createdAt = 1,
        updatedAt = 1
    )

    private fun count(table: String, where: String? = null): Int {
        val sql = "SELECT COUNT(*) FROM $table" + if (where == null) "" else " WHERE $where"
        return database.openHelper.readableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun exec(sql: String) = database.openHelper.writableDatabase.execSQL(sql)

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected transaction failure")
        } catch (expected: Exception) {
            // The exception must escape the repository; Room rolls the transaction back.
        }
    }
}
