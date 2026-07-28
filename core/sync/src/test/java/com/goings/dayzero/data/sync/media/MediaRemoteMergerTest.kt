package com.goings.dayzero.data.sync.media

import com.goings.dayzero.data.local.dao.MediaAssetDao
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.ConversationEntity
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.model.media.MediaRemoteSyncState
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

@RunWith(RobolectricTestRunner::class)
class MediaRemoteMergerTest {

    private lateinit var database: DayZeroDatabase
    private lateinit var mediaDao: MediaAssetDao
    private lateinit var syncQueueDao: SyncQueueDao
    private lateinit var merger: MediaRemoteMerger

    private val identity = AppIdentity("local_1", "remote_1", "supabase", true)

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database = androidx.room.Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaDao = database.mediaAssetDao()
        syncQueueDao = database.syncQueueDao()
        merger = MediaRemoteMerger(database, mediaDao, syncQueueDao, MediaSyncQueueWriter(syncQueueDao))

        // Parent conversation required by the media FK.
        runTest {
            database.conversationDao().insertConversation(
                ConversationEntity(
                    id = "conv-1",
                    conversationDate = "2026-07-11",
                    title = "t",
                    lastMessagePreview = "p",
                    createdAt = 1L,
                    updatedAt = 1L,
                    lastActivityAt = 1L,
                    deletedAt = null
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun remote(
        id: String = "m1",
        order: Long = 1,
        deletedAtMillis: Long? = null,
        updatedAtMillis: Long = 2000L
    ) = MediaRemoteSnapshot(
        id = id,
        conversationId = "conv-1",
        sourceMessageId = "msg-1",
        conversationOrder = order,
        masterObjectPath = "remote_1/$id/master.jpg",
        thumbnailObjectPath = "remote_1/$id/thumb.jpg",
        mimeType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 1234,
        sha256 = "h",
        source = "CAMERA",
        createdAtMillis = 1000L,
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
        schemaVersion = 1
    )

    private fun localRow(id: String = "m1", order: Long = 1, deletedAt: Long? = null) = MediaAssetEntity(
        id = id,
        ownerLocalId = "local_1",
        conversationId = "conv-1",
        sourceMessageId = "msg-1",
        conversationOrder = order,
        masterRelativePath = "media/master/$id.jpg",
        thumbnailRelativePath = "media/thumbnail/$id.jpg",
        mimeType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 1234,
        sha256 = "h",
        source = "CAMERA",
        lifecycleState = "READY",
        failureCode = null,
        createdAt = 1000L,
        updatedAt = 1000L,
        deletedAt = deletedAt,
        remoteSyncState = MediaRemoteSyncState.UPLOADED.name
    )

    @Test
    fun remoteActiveLocalNone_insertsRemotePendingAndEnqueuesDownload() = runTest {
        val stats = merger.mergeMediaPage(identity, listOf(remote()))

        assertEquals(1, stats.insertedCount)
        val local = mediaDao.getById("m1")
        assertNotNull(local)
        assertEquals(MediaRemoteSyncState.REMOTE_PENDING.name, local?.remoteSyncState)
        assertNull(local?.masterRelativePath)
        assertEquals("remote_1/m1/master.jpg", local?.remoteMasterPath)
        // A DOWNLOAD task was enqueued.
        val downloadTasks = syncQueueDao.countActiveTasksForEntityAndOperation(
            "local_1",
            MediaSyncQueueContract.ENTITY_MEDIA_ASSET,
            "m1",
            MediaSyncQueueContract.OP_DOWNLOAD_MEDIA_ASSET
        )
        assertEquals(1, downloadTasks)
    }

    @Test
    fun remoteTombstoneLocalNone_skipsWithoutInsert() = runTest {
        val stats = merger.mergeMediaPage(identity, listOf(remote(deletedAtMillis = 3000L)))

        assertEquals(1, stats.skippedCount)
        assertNull(mediaDao.getById("m1"))
    }

    @Test
    fun localDirtyWithPendingUpload_defers() = runTest {
        mediaDao.insertAll(listOf(localRow()))
        // Simulate a pending local upload for this asset.
        syncQueueDao.insert(
            SyncQueueEntity(
                entityType = MediaSyncQueueContract.ENTITY_MEDIA_ASSET,
                entityLocalId = "m1",
                operation = MediaSyncQueueContract.OP_UPSERT_MEDIA_ASSET,
                payloadJson = "{\"clientId\":\"m1\"}",
                status = "PENDING",
                createdAt = 1L,
                updatedAt = 1L,
                ownerLocalId = "local_1"
            )
        )

        val stats = merger.mergeMediaPage(identity, listOf(remote(deletedAtMillis = 5000L)))

        assertEquals(1, stats.deferredCount)
        // Tombstone was NOT applied because local is dirty.
        assertNull(mediaDao.getById("m1")?.deletedAt)
    }

    @Test
    fun remoteTombstoneLocalActiveClean_appliesTombstone() = runTest {
        mediaDao.insertAll(listOf(localRow()))

        val stats = merger.mergeMediaPage(identity, listOf(remote(deletedAtMillis = 5000L, updatedAtMillis = 5000L)))

        assertEquals(1, stats.deletedCount)
        assertNotNull(mediaDao.getById("m1")?.deletedAt)
    }

    @Test
    fun remoteActiveLocalActiveClean_skipsAsImmutable() = runTest {
        mediaDao.insertAll(listOf(localRow()))

        val stats = merger.mergeMediaPage(identity, listOf(remote(updatedAtMillis = 9999L)))

        assertEquals(1, stats.skippedCount)
        // Local download/upload state untouched.
        assertEquals(MediaRemoteSyncState.UPLOADED.name, mediaDao.getById("m1")?.remoteSyncState)
    }
}
