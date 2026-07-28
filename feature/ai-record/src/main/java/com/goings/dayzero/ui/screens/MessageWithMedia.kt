package com.goings.dayzero.ui.screens

import com.goings.dayzero.domain.model.ai.AiChatMessage
import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.MediaRemoteSyncState

sealed class MessageMediaReference {
    data class LocalReady(val mediaAsset: MediaAsset) : MessageMediaReference()

    /** Metadata pulled from another device; bytes are still downloading from Storage. */
    data class RemotePending(val mediaAsset: MediaAsset) : MessageMediaReference()
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
            asset.thumbnailRelativePath.isNullOrBlank() ->
                if (asset.remoteSyncState == MediaRemoteSyncState.REMOTE_PENDING) {
                    MessageMediaReference.RemotePending(asset)
                } else {
                    MessageMediaReference.MissingLocalFile
                }
            else -> MessageMediaReference.LocalReady(asset)
        }
    }
    return MessageWithMedia(message = this, media = references)
}
