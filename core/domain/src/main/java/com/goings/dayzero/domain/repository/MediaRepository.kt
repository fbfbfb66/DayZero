package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.NewMediaAssetRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface MediaRepository {
    fun observeConversationMedia(conversationId: String): Flow<List<MediaAsset>>

    suspend fun getConversationMedia(conversationId: String): List<MediaAsset>

    suspend fun getMediaByIds(ids: List<String>): List<MediaAsset>

    /** Default keeps legacy test doubles source-compatible; production repositories override reactively. */
    fun observeMediaByIds(ids: List<String>): Flow<List<MediaAsset>> = flowOf(emptyList())

    suspend fun createStagedMedia(
        requests: List<NewMediaAssetRequest>,
        now: Long
    ): List<MediaAsset>

    suspend fun attachMediaToMessage(
        mediaIds: List<String>,
        conversationId: String,
        messageId: String,
        now: Long
    )

    suspend fun markMediaReady(
        id: String,
        conversationId: String,
        masterRelativePath: String,
        thumbnailRelativePath: String,
        mimeType: String,
        width: Int,
        height: Int,
        byteSize: Long,
        sha256: String,
        now: Long
    ): MediaAsset

    suspend fun markMediaFailed(
        id: String,
        conversationId: String,
        failureCode: String?,
        now: Long
    ): MediaAsset

    suspend fun softDeleteMedia(
        id: String,
        conversationId: String,
        now: Long
    )

    suspend fun findStaleStagedMedia(updatedBefore: Long): List<MediaAsset>
}
