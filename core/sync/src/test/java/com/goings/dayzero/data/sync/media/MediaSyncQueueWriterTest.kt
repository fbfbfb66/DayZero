package com.goings.dayzero.data.sync.media

import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.domain.identity.AppIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSyncQueueWriterTest {

    private val identity = AppIdentity("local_1", "remote_1", "supabase", true)

    private fun media(id: String = "m1") = MediaAssetEntity(
        id = id,
        ownerLocalId = "local_1",
        conversationId = "conv-1",
        sourceMessageId = "msg-1",
        conversationOrder = 1,
        masterRelativePath = "media/master/$id.jpg",
        thumbnailRelativePath = "media/thumbnail/$id.jpg",
        mimeType = "image/jpeg",
        width = 10,
        height = 10,
        byteSize = 100,
        sha256 = "h",
        source = "CAMERA",
        lifecycleState = "READY",
        failureCode = null,
        createdAt = 1L,
        updatedAt = 2L,
        deletedAt = null
    )

    @Test
    fun enqueueMediaUpsert_insertsUpsertOp() = runBlocking {
        val dao = CapturingSyncQueueDao()
        val writer = MediaSyncQueueWriter(dao)

        val inserted = writer.enqueueMediaUpsert(media(), identity)

        assertTrue(inserted)
        val item = dao.inserted.single()
        assertEquals(MediaSyncQueueContract.ENTITY_MEDIA_ASSET, item.entityType)
        assertEquals(MediaSyncQueueContract.OP_UPSERT_MEDIA_ASSET, item.operation)
        assertEquals("m1", item.entityLocalId)
        assertEquals("local_1", item.ownerLocalId)
        assertTrue(item.payloadJson.contains("\"clientId\":\"m1\""))
    }

    @Test
    fun enqueueMediaDownload_insertsDownloadOp() = runBlocking {
        val dao = CapturingSyncQueueDao()
        val writer = MediaSyncQueueWriter(dao)

        writer.enqueueMediaDownload(media("m2"), identity)

        val item = dao.inserted.single()
        assertEquals(MediaSyncQueueContract.OP_DOWNLOAD_MEDIA_ASSET, item.operation)
        assertEquals("m2", item.entityLocalId)
    }

    @Test
    fun enqueue_coalescesInsteadOfInsertingWhenPendingExists() = runBlocking {
        val dao = CapturingSyncQueueDao(coalesceResult = 1)
        val writer = MediaSyncQueueWriter(dao)

        val inserted = writer.enqueueMediaUpsert(media(), identity)

        assertTrue(!inserted)
        assertTrue(dao.inserted.isEmpty())
    }

    private class CapturingSyncQueueDao(
        private val coalesceResult: Int = 0
    ) : com.goings.dayzero.data.local.dao.SyncQueueDao {
        val inserted = mutableListOf<SyncQueueEntity>()
        override suspend fun insert(item: SyncQueueEntity) { inserted.add(item) }
        override suspend fun insertIgnore(item: SyncQueueEntity): Long {
            inserted.add(item)
            return 1L
        }
        override suspend fun getStatusById(id: String): String? = null
        override suspend fun countActiveTasksForOperation(operation: String): Int = 0
        override suspend fun coalescePendingTask(ownerLocalId: String, entityType: String, entityLocalId: String, operation: String, payloadJson: String, updatedAt: Long, reason: String?): Int = coalesceResult
        override suspend fun getRunnableTasks(now: Long, limit: Int): List<SyncQueueEntity> = emptyList()
        override suspend fun getPending(now: Long, limit: Int): List<SyncQueueEntity> = emptyList()
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
        override suspend fun countByStatus(status: String): Int = 0
        override suspend fun getTasksByStatus(status: String): List<SyncQueueEntity> = emptyList()
        override suspend fun resetStuckProcessingTasks(beforeTimestamp: Long, now: Long): Int = 0
        override suspend fun deleteDoneOlderThan(beforeTimestamp: Long): Int = 0
        override suspend fun deleteBusinessRecordTasks(): Int = 0
        override suspend fun getLastSyncAttemptAt(): Long? = null
        override suspend fun getLastSuccessfulSyncAt(): Long? = null
        override suspend fun getLastSyncFailureAt(): Long? = null
        override suspend fun getOldestPendingAt(): Long? = null
        override suspend fun getLastSyncError(): String? = null
    }
}
