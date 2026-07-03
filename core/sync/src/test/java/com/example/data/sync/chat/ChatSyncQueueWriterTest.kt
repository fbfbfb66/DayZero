package com.example.data.sync.chat

import com.example.data.local.entity.AiChatMessageEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSyncQueueWriterTest {

    private val writer = ChatSyncQueueWriter(syncQueueDao = FakeSyncQueueDao())

    @Test
    fun textUserMessageIsSyncable() {
        val message = messageEntity(role = "User", text = "hello")
        assertTrue(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun imageOnlyUserMessageWithValidMediaContentJsonIsSyncable() {
        val message = messageEntity(
            role = "User",
            text = "",
            contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":["a","b"]}}"""
        )
        assertTrue(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun imageOnlyUserMessageWithEmptyMediaArrayIsNotSyncable() {
        val message = messageEntity(
            role = "User",
            text = "",
            contentJson = """{"media":{"schemaVersion":1,"sourceMediaIds":[]}}"""
        )
        assertFalse(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun userMessageWithOnlyUnknownContentJsonIsNotSyncable() {
        val message = messageEntity(
            role = "User",
            text = "",
            contentJson = """{"future":true}"""
        )
        assertFalse(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun userMessageWithNullContentJsonAndEmptyTextIsNotSyncable() {
        val message = messageEntity(role = "User", text = "", contentJson = null)
        assertFalse(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun emptyAssistantPlaceholderIsNotSyncable() {
        val message = messageEntity(role = "Assistant", text = "")
        assertFalse(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun assistantWithEmptyTextButCardsIsSyncable() {
        val message = messageEntity(
            role = "Assistant",
            text = "",
            assistantCardsJson = """[{"type":"show_confirm_card","id":"c1"}]"""
        )
        assertTrue(writer.isSyncableFinalMessage(message))
    }

    @Test
    fun deletedMessageIsSyncableEvenIfEmpty() {
        val message = messageEntity(role = "User", text = "", deletedAt = 1000L)
        assertTrue(writer.isSyncableFinalMessage(message))
    }

    private class FakeSyncQueueDao : com.example.data.local.dao.SyncQueueDao {
        override suspend fun insert(item: com.example.data.local.entity.SyncQueueEntity) {}
        override suspend fun getRunnableTasks(now: Long, limit: Int): List<com.example.data.local.entity.SyncQueueEntity> = emptyList()
        override suspend fun getPending(now: Long, limit: Int): List<com.example.data.local.entity.SyncQueueEntity> = emptyList()
        override suspend fun markProcessing(id: String, now: Long, reason: String?): Int = 0
        override suspend fun markDone(id: String, updatedAt: Long) {}
        override suspend fun markRetryableFailure(id: String, error: String?, retryCount: Int, updatedAt: Long, nextAttemptAt: Long, reason: String?) {}
        override suspend fun markFatalFailure(id: String, error: String?, updatedAt: Long, reason: String?) {}
        override suspend fun markWaitingForAuth(id: String, reason: String?, updatedAt: Long, nextAttemptAt: Long) {}
        override fun observePendingCount(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(0)
        override suspend fun getPendingCount(): Int = 0
        override suspend fun countPending(): Int = 0
        override suspend fun countRetryable(): Int = 0
        override suspend fun countFatal(): Int = 0
        override suspend fun countWaitingForAuth(): Int = 0
        override suspend fun countBlockingDuplicate(ownerLocalId: String, entityType: String, entityLocalId: String, operation: String): Int = 0
        override suspend fun countActiveTasksForEntity(ownerLocalId: String, entityType: String, entityLocalId: String): Int = 0
        override suspend fun countActiveTasksForEntityAndOperation(ownerLocalId: String, entityType: String, entityLocalId: String, operation: String): Int = 0
        override suspend fun coalescePendingTask(ownerLocalId: String, entityType: String, entityLocalId: String, operation: String, payloadJson: String, updatedAt: Long, reason: String?): Int = 0
        override suspend fun countByStatus(status: String): Int = 0
        override suspend fun getTasksByStatus(status: String): List<com.example.data.local.entity.SyncQueueEntity> = emptyList()
        override suspend fun resetStuckProcessingTasks(beforeTimestamp: Long, now: Long): Int = 0
        override suspend fun deleteDoneOlderThan(beforeTimestamp: Long): Int = 0
        override suspend fun deleteBusinessRecordTasks(): Int = 0
        override suspend fun getLastSyncAttemptAt(): Long? = null
        override suspend fun getLastSuccessfulSyncAt(): Long? = null
        override suspend fun getLastSyncFailureAt(): Long? = null
        override suspend fun getOldestPendingAt(): Long? = null
        override suspend fun getLastSyncError(): String? = null
    }

    private fun messageEntity(
        role: String,
        text: String,
        contentJson: String? = null,
        assistantCardsJson: String? = null,
        deletedAt: Long? = null
    ): AiChatMessageEntity {
        return AiChatMessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = role,
            text = text,
            createdAt = 100L,
            relatedDraftId = null,
            messageType = "Text",
            contentJson = contentJson,
            assistantCardsJson = assistantCardsJson,
            suggestedRepliesJson = null,
            updatedAt = 100L,
            deletedAt = deletedAt
        )
    }
}
