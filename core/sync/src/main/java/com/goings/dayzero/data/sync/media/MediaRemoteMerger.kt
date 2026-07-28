package com.goings.dayzero.data.sync.media

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.goings.dayzero.data.local.dao.MediaAssetDao
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.domain.identity.AppIdentity
import com.goings.dayzero.domain.model.media.MediaRemoteSyncState

/**
 * Merges a page of remote `media_assets` rows into local Room, mirroring
 * [com.goings.dayzero.data.sync.chat.ChatConversationRemoteMerger].
 *
 * Media metadata is effectively immutable after upload, so the only mutation applied to an
 * existing local row is a soft-delete tombstone. New remote rows are inserted as
 * [MediaRemoteSyncState.REMOTE_PENDING] with no local files, and a DOWNLOAD task is enqueued
 * so the bytes are fetched from Storage.
 */
class MediaRemoteMerger(
    private val database: DayZeroDatabase,
    private val mediaAssetDao: MediaAssetDao,
    private val syncQueueDao: SyncQueueDao,
    private val mediaSyncQueueWriter: MediaSyncQueueWriter
) {

    suspend fun mergeMediaPage(
        identity: AppIdentity,
        remoteSnapshots: List<MediaRemoteSnapshot>
    ): MediaMergeStats {
        if (remoteSnapshots.isEmpty()) return MediaMergeStats()
        var stats = MediaMergeStats()
        database.withTransaction {
            for (remote in remoteSnapshots) {
                stats += applyRemoteMedia(identity, remote)
            }
        }
        return stats
    }

    private suspend fun applyRemoteMedia(
        identity: AppIdentity,
        remote: MediaRemoteSnapshot
    ): MediaMergeStats {
        val local = mediaAssetDao.getById(remote.id)

        if (local == null) {
            if (remote.deletedAtMillis != null) {
                // Never had it locally and it's a tombstone -> don't clutter local DB.
                return MediaMergeStats(skippedCount = 1)
            }
            val entity = MediaAssetEntity(
                id = remote.id,
                ownerLocalId = identity.localOwnerId,
                conversationId = remote.conversationId,
                sourceMessageId = remote.sourceMessageId,
                conversationOrder = remote.conversationOrder,
                masterRelativePath = null,
                thumbnailRelativePath = null,
                mimeType = remote.mimeType,
                width = remote.width,
                height = remote.height,
                byteSize = remote.byteSize,
                sha256 = remote.sha256,
                source = remote.source,
                lifecycleState = "STAGED",
                failureCode = null,
                createdAt = remote.createdAtMillis,
                updatedAt = remote.updatedAtMillis,
                deletedAt = null,
                remoteSyncState = MediaRemoteSyncState.REMOTE_PENDING.name,
                remoteMasterPath = remote.masterObjectPath,
                remoteThumbnailPath = remote.thumbnailObjectPath
            )
            return try {
                mediaAssetDao.insertAll(listOf(entity))
                mediaSyncQueueWriter.enqueueMediaDownload(entity, identity)
                Log.d("DayZeroMediaPull", "inserted remote-pending id=${remote.id.take(8)}")
                MediaMergeStats(insertedCount = 1)
            } catch (e: SQLiteConstraintException) {
                // (conversationId, conversationOrder) uniqueness collided with a local row.
                Log.w("DayZeroMediaPull", "skipped colliding remote media id=${remote.id.take(8)}: ${e.message}")
                MediaMergeStats(skippedCount = 1, conflictCount = 1)
            }
        }

        // Local exists. Defer if a local upload is still pending for this asset.
        if (isLocalDirty(identity.localOwnerId, remote.id)) {
            return MediaMergeStats(deferredCount = 1)
        }

        // Only tombstones mutate an existing row; metadata is immutable post-upload.
        val remoteDeletedAt = remote.deletedAtMillis
        if (remoteDeletedAt != null && local.deletedAt == null) {
            return if (remote.updatedAtMillis >= local.updatedAt) {
                mediaAssetDao.applyRemoteTombstone(remote.id, remoteDeletedAt, remote.updatedAtMillis)
                Log.d("DayZeroMediaPull", "applied remote tombstone id=${remote.id.take(8)}")
                MediaMergeStats(deletedCount = 1)
            } else {
                MediaMergeStats(skippedCount = 1)
            }
        }
        return MediaMergeStats(skippedCount = 1)
    }

    private suspend fun isLocalDirty(identityLocalId: String, mediaId: String): Boolean {
        val active = syncQueueDao.countActiveTasksForEntityAndOperation(
            ownerLocalId = identityLocalId,
            entityType = MediaSyncQueueContract.ENTITY_MEDIA_ASSET,
            entityLocalId = mediaId,
            operation = MediaSyncQueueContract.OP_UPSERT_MEDIA_ASSET
        )
        return active > 0
    }
}

data class MediaMergeStats(
    val insertedCount: Int = 0,
    val deletedCount: Int = 0,
    val deferredCount: Int = 0,
    val skippedCount: Int = 0,
    val conflictCount: Int = 0
) {
    operator fun plus(other: MediaMergeStats): MediaMergeStats {
        return MediaMergeStats(
            insertedCount = insertedCount + other.insertedCount,
            deletedCount = deletedCount + other.deletedCount,
            deferredCount = deferredCount + other.deferredCount,
            skippedCount = skippedCount + other.skippedCount,
            conflictCount = conflictCount + other.conflictCount
        )
    }
}
