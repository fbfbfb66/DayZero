package com.example.data.local.mapper

import com.example.data.local.entity.MediaAssetEntity
import com.example.domain.model.media.MediaAsset
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import com.example.domain.model.media.NewMediaAssetRequest

object MediaAssetMapper {
    fun toDomain(entity: MediaAssetEntity): MediaAsset {
        return MediaAsset(
            id = entity.id,
            ownerLocalId = entity.ownerLocalId,
            conversationId = entity.conversationId,
            sourceMessageId = entity.sourceMessageId,
            conversationOrder = entity.conversationOrder,
            masterRelativePath = entity.masterRelativePath,
            thumbnailRelativePath = entity.thumbnailRelativePath,
            mimeType = entity.mimeType,
            width = entity.width,
            height = entity.height,
            byteSize = entity.byteSize,
            sha256 = entity.sha256,
            source = parseSource(entity.source),
            lifecycleState = parseLifecycleState(entity.lifecycleState),
            failureCode = entity.failureCode,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt
        )
    }

    fun stagedEntity(
        request: NewMediaAssetRequest,
        conversationOrder: Long,
        now: Long
    ): MediaAssetEntity {
        return MediaAssetEntity(
            id = request.id,
            ownerLocalId = request.ownerLocalId,
            conversationId = request.conversationId,
            sourceMessageId = null,
            conversationOrder = conversationOrder,
            masterRelativePath = null,
            thumbnailRelativePath = null,
            mimeType = null,
            width = null,
            height = null,
            byteSize = null,
            sha256 = null,
            source = request.source.name,
            lifecycleState = MediaLifecycleState.STAGED.name,
            failureCode = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
    }

    private fun parseSource(value: String): MediaSource {
        return MediaSource.entries.firstOrNull { it.name == value }
            ?: error("Unknown media source: $value")
    }

    private fun parseLifecycleState(value: String): MediaLifecycleState {
        return MediaLifecycleState.entries.firstOrNull { it.name == value }
            ?: error("Unknown media lifecycle state: $value")
    }
}
