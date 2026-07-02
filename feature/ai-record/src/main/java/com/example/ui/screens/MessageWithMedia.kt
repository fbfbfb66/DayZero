package com.example.ui.screens

import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.media.MediaAsset

sealed class MessageMediaReference {
    data class LocalReady(val mediaAsset: MediaAsset) : MessageMediaReference()
    data object MissingLocalAsset : MessageMediaReference()
    data object MissingLocalFile : MessageMediaReference()
    data object InvalidReference : MessageMediaReference()
}

data class MessageWithMedia(
    val message: AiChatMessage,
    val media: List<MessageMediaReference>
)

fun AiChatMessage.toMessageWithMedia(mediaMap: Map<String, MediaAsset>): MessageWithMedia {
    if (sourceMediaIds.isEmpty()) {
        return MessageWithMedia(message = this, media = emptyList())
    }

    val seen = mutableSetOf<String>()
    val references = sourceMediaIds.mapNotNull { rawId ->
        val id = rawId.trim()
        if (id.isBlank()) {
            return@mapNotNull MessageMediaReference.InvalidReference
        }
        if (!seen.add(id)) {
            return@mapNotNull MessageMediaReference.InvalidReference
        }
        val asset = mediaMap[id]
        when {
            asset == null -> MessageMediaReference.MissingLocalAsset
            asset.deletedAt != null -> MessageMediaReference.MissingLocalAsset
            asset.thumbnailRelativePath.isNullOrBlank() -> MessageMediaReference.MissingLocalFile
            else -> MessageMediaReference.LocalReady(asset)
        }
    }
    return MessageWithMedia(message = this, media = references)
}
