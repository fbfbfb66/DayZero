package com.goings.dayzero.data.sync.media

import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.domain.identity.AppIdentity
import org.json.JSONObject
import java.time.Instant

/**
 * Builds sync-queue payloads for media assets.
 *
 * The upsert payload carries the local file paths (so the push worker can locate the
 * JPEG bytes to upload) plus all metadata mirrored into the remote `media_assets` table.
 * The download payload only needs the client id — the worker reloads the row to obtain
 * the remote object keys.
 */
class MediaSyncPayloadBuilder {
    fun upsertPayload(media: MediaAssetEntity, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", media.id)
            .put("remoteUserId", identity.remoteUserId ?: JSONObject.NULL)
            .put("conversationId", media.conversationId)
            .putNullableString("sourceMessageId", media.sourceMessageId)
            .put("conversationOrder", media.conversationOrder)
            .putNullableString("masterRelativePath", media.masterRelativePath)
            .putNullableString("thumbnailRelativePath", media.thumbnailRelativePath)
            .putNullableString("mimeType", media.mimeType)
            .putNullableInt("width", media.width)
            .putNullableInt("height", media.height)
            .putNullableLong("byteSize", media.byteSize)
            .putNullableString("sha256", media.sha256)
            .put("source", media.source)
            .put("createdAt", media.createdAt.toIsoInstant())
            .put("updatedAt", media.updatedAt.toIsoInstant())
            .putNullableInstant("deletedAt", media.deletedAt)
            .put("schemaVersion", MediaSyncQueueContract.MEDIA_SYNC_SCHEMA_VERSION)
    }

    fun downloadPayload(media: MediaAssetEntity, identity: AppIdentity): JSONObject {
        return JSONObject()
            .put("clientId", media.id)
            .put("remoteUserId", identity.remoteUserId ?: JSONObject.NULL)
            .put("schemaVersion", MediaSyncQueueContract.MEDIA_SYNC_SCHEMA_VERSION)
    }

    private fun JSONObject.putNullableString(name: String, value: String?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableInt(name: String, value: Int?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableLong(name: String, value: Long?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullableInstant(name: String, value: Long?): JSONObject {
        return put(name, value?.toIsoInstant() ?: JSONObject.NULL)
    }

    private fun Long.toIsoInstant(): String = Instant.ofEpochMilli(this).toString()
}
