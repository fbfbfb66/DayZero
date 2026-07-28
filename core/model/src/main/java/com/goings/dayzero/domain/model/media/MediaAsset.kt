package com.goings.dayzero.domain.model.media

data class MediaAsset(
    val id: String,
    val ownerLocalId: String,
    val conversationId: String,
    val sourceMessageId: String?,
    val conversationOrder: Long,
    val masterRelativePath: String?,
    val thumbnailRelativePath: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val byteSize: Long?,
    val sha256: String?,
    val source: MediaSource,
    val lifecycleState: MediaLifecycleState,
    val failureCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val remoteSyncState: MediaRemoteSyncState = MediaRemoteSyncState.LOCAL_ONLY,
    val remoteMasterPath: String? = null,
    val remoteThumbnailPath: String? = null
)

data class NewMediaAssetRequest(
    val id: String,
    val ownerLocalId: String,
    val conversationId: String,
    val source: MediaSource
)

enum class MediaSource {
    CAMERA,
    PHOTO_PICKER,
    EDITOR_IMPORT
}

enum class MediaLifecycleState {
    STAGED,
    READY,
    FAILED
}

/**
 * Cross-device sync state, orthogonal to [MediaLifecycleState].
 *
 * Device A (origin): LOCAL_ONLY -> UPLOADED once bytes + metadata reach Supabase.
 * Device B (recipient): REMOTE_PENDING (metadata pulled, bytes absent) -> DOWNLOADED
 * once bytes are fetched from Storage and written to local files.
 */
enum class MediaRemoteSyncState {
    LOCAL_ONLY,
    UPLOADED,
    REMOTE_PENDING,
    DOWNLOADED;

    companion object {
        fun fromRaw(value: String?): MediaRemoteSyncState =
            entries.firstOrNull { it.name == value } ?: LOCAL_ONLY
    }
}
