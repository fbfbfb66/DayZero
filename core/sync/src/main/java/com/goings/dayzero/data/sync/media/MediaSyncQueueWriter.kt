package com.goings.dayzero.data.sync.media

import android.util.Log
import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.data.sync.DayZeroSyncConstants
import com.goings.dayzero.domain.identity.AppIdentity

/**
 * Enqueues media-asset sync tasks into the shared [sync_queue] table, mirroring
 * [com.goings.dayzero.data.sync.chat.ChatSyncQueueWriter]. Enqueue must happen inside the
 * same Room transaction as the business write so a media asset is never lost.
 */
class MediaSyncQueueWriter(
    private val syncQueueDao: SyncQueueDao,
    private val payloadBuilder: MediaSyncPayloadBuilder = MediaSyncPayloadBuilder()
) {
    /** Origin device: upload bytes + upsert metadata for a media asset just bound to a message. */
    suspend fun enqueueMediaUpsert(media: MediaAssetEntity, identity: AppIdentity): Boolean {
        val now = System.currentTimeMillis()
        return enqueueLatest(
            item = SyncQueueEntity(
                entityType = MediaSyncQueueContract.ENTITY_MEDIA_ASSET,
                entityLocalId = media.id,
                operation = MediaSyncQueueContract.OP_UPSERT_MEDIA_ASSET,
                payloadJson = payloadBuilder.upsertPayload(media, identity).toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            ),
            reason = "media_upsert_latest"
        )
    }

    /** Recipient device: download bytes for a media asset whose metadata was just pulled. */
    suspend fun enqueueMediaDownload(media: MediaAssetEntity, identity: AppIdentity): Boolean {
        val now = System.currentTimeMillis()
        return enqueueLatest(
            item = SyncQueueEntity(
                entityType = MediaSyncQueueContract.ENTITY_MEDIA_ASSET,
                entityLocalId = media.id,
                operation = MediaSyncQueueContract.OP_DOWNLOAD_MEDIA_ASSET,
                payloadJson = payloadBuilder.downloadPayload(media, identity).toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            ),
            reason = "media_download_latest"
        )
    }

    private suspend fun enqueueLatest(item: SyncQueueEntity, reason: String): Boolean {
        val coalesced = syncQueueDao.coalescePendingTask(
            ownerLocalId = item.ownerLocalId,
            entityType = item.entityType,
            entityLocalId = item.entityLocalId,
            operation = item.operation,
            payloadJson = item.payloadJson,
            updatedAt = item.updatedAt,
            reason = reason
        )
        if (coalesced > 0) {
            Log.d(LOG_PREFIX, "enqueue coalesced op=${item.operation} clientId=${item.entityLocalId}")
            return false
        }

        syncQueueDao.insert(item)
        Log.d(LOG_PREFIX, "enqueue success op=${item.operation} clientId=${item.entityLocalId}")
        return true
    }

    private companion object {
        private const val LOG_PREFIX = "DayZeroSync"
    }
}
