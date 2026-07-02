package com.example.domain.model.media

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
    val deletedAt: Long?
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
