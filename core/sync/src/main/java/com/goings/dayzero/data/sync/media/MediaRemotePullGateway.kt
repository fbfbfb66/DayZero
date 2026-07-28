package com.goings.dayzero.data.sync.media

import com.goings.dayzero.domain.identity.AppIdentity

/** Composite keyset cursor `(server_updated_at, id)` for incremental media pull. */
data class MediaSyncServerCursor(
    val serverUpdatedAt: String,
    val id: String
)

/** Remote `media_assets` row projection. Object paths are Storage keys, not local paths. */
data class MediaRemoteSnapshot(
    val id: String,
    val conversationId: String,
    val sourceMessageId: String?,
    val conversationOrder: Long,
    val masterObjectPath: String?,
    val thumbnailObjectPath: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val byteSize: Long?,
    val sha256: String?,
    val source: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long?,
    val schemaVersion: Int
)

data class MediaRemotePage(
    val items: List<MediaRemoteSnapshot>,
    val nextCursor: MediaSyncServerCursor?,
    val hasMore: Boolean
)

interface MediaRemotePullGateway {
    suspend fun fetchMediaPage(
        identity: AppIdentity,
        cursor: MediaSyncServerCursor?,
        limit: Int
    ): MediaRemotePullResult<MediaRemotePage>
}

sealed class MediaRemotePullResult<out T> {
    data class Success<T>(val data: T) : MediaRemotePullResult<T>()
    data class RetryableFailure(val message: String) : MediaRemotePullResult<Nothing>()
    data class FatalFailure(val message: String) : MediaRemotePullResult<Nothing>()
    data class Skipped(val reason: String) : MediaRemotePullResult<Nothing>()
}
