package com.goings.dayzero.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.goings.dayzero.data.local.database.DayZeroDatabase
import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.data.local.mapper.MediaAssetMapper
import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.model.media.MediaLifecycleState
import com.goings.dayzero.domain.model.media.NewMediaAssetRequest
import com.goings.dayzero.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class RoomMediaRepository(
    private val database: DayZeroDatabase
) : MediaRepository {
    private val mediaDao = database.mediaAssetDao()

    override fun observeConversationMedia(conversationId: String): Flow<List<MediaAsset>> {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        return mediaDao.observeActiveByConversation(conversationId)
            .map { entities -> entities.map(MediaAssetMapper::toDomain) }
    }

    override suspend fun getConversationMedia(conversationId: String): List<MediaAsset> {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        return mediaDao.getActiveByConversation(conversationId).map(MediaAssetMapper::toDomain)
    }

    override suspend fun getMediaByIds(ids: List<String>): List<MediaAsset> {
        if (ids.isEmpty()) return emptyList()
        require(ids.all { it.isNotBlank() }) { "Media id must not be blank" }
        return mediaDao.getByIds(ids).map(MediaAssetMapper::toDomain)
    }

    override fun observeMediaByIds(ids: List<String>): Flow<List<MediaAsset>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        require(ids.all { it.isNotBlank() }) { "Media id must not be blank" }
        return mediaDao.observeActiveByIds(ids.distinct())
            .map { entities -> entities.map(MediaAssetMapper::toDomain) }
    }

    override suspend fun createStagedMedia(
        requests: List<NewMediaAssetRequest>,
        now: Long
    ): List<MediaAsset> {
        validateCreateRequests(requests)
        val conversationId = requests.first().conversationId
        var lastFailure: SQLiteConstraintException? = null
        repeat(MAX_ORDER_ALLOCATION_ATTEMPTS) {
            try {
                return database.withTransaction {
                    val maxOrder = mediaDao.getMaxConversationOrder(conversationId) ?: 0L
                    val entities = requests.mapIndexed { index, request ->
                        MediaAssetMapper.stagedEntity(
                            request = request,
                            conversationOrder = maxOrder + index + 1L,
                            now = now
                        )
                    }
                    mediaDao.insertAll(entities)
                    entities.map(MediaAssetMapper::toDomain)
                }
            } catch (e: SQLiteConstraintException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IllegalStateException("Failed to allocate media conversation order")
    }

    override suspend fun attachMediaToMessage(
        mediaIds: List<String>,
        conversationId: String,
        messageId: String,
        now: Long
    ) {
        if (mediaIds.isEmpty()) return
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        require(messageId.isNotBlank()) { "Message id must not be blank" }
        val distinctIds = mediaIds.distinct()
        require(distinctIds.size == mediaIds.size) { "Media ids must be unique within one attach request" }
        require(distinctIds.all { it.isNotBlank() }) { "Media id must not be blank" }
        database.withTransaction {
            val entities = requireExistingMedia(distinctIds)
            requireAllBelongToConversation(entities, conversationId)
            entities.forEach { entity ->
                check(entity.deletedAt == null) { "Cannot attach a soft-deleted media asset: ${entity.id}" }
                val rows = mediaDao.attachToMessage(
                    id = entity.id,
                    sourceMessageId = messageId,
                    updatedAt = now
                )
                check(rows == 1) { "Failed to attach media to message: ${entity.id}" }
            }
        }
    }

    override suspend fun markMediaReady(
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
    ): MediaAsset {
        validateReadyMetadata(
            masterRelativePath = masterRelativePath,
            thumbnailRelativePath = thumbnailRelativePath,
            mimeType = mimeType,
            width = width,
            height = height,
            byteSize = byteSize,
            sha256 = sha256
        )
        return database.withTransaction {
            val entity = requireMedia(id, conversationId)
            check(entity.deletedAt == null) { "Cannot mark a soft-deleted media asset READY: $id" }
            if (entity.lifecycleState == MediaLifecycleState.READY.name) {
                check(entity.hasSameReadyMetadata(masterRelativePath, thumbnailRelativePath, mimeType, width, height, byteSize, sha256)) {
                    "Media asset is already READY with different metadata: $id"
                }
                return@withTransaction MediaAssetMapper.toDomain(entity)
            }
            val rows = mediaDao.markReady(
                id = id,
                masterRelativePath = masterRelativePath,
                thumbnailRelativePath = thumbnailRelativePath,
                mimeType = mimeType,
                width = width,
                height = height,
                byteSize = byteSize,
                sha256 = sha256,
                updatedAt = now
            )
            check(rows == 1) { "Failed to mark media READY: $id" }
            MediaAssetMapper.toDomain(mediaDao.getById(id) ?: error("Media disappeared after READY update: $id"))
        }
    }

    override suspend fun markMediaFailed(
        id: String,
        conversationId: String,
        failureCode: String?,
        now: Long
    ): MediaAsset {
        return database.withTransaction {
            val entity = requireMedia(id, conversationId)
            check(entity.deletedAt == null) { "Cannot mark a soft-deleted media asset FAILED: $id" }
            val rows = mediaDao.markFailed(id, failureCode, now)
            check(rows == 1) { "Failed to mark media FAILED: $id" }
            MediaAssetMapper.toDomain(mediaDao.getById(id) ?: error("Media disappeared after FAILED update: $id"))
        }
    }

    override suspend fun softDeleteMedia(
        id: String,
        conversationId: String,
        now: Long
    ) {
        database.withTransaction {
            requireMedia(id, conversationId)
            val rows = mediaDao.softDelete(id, deletedAt = now, updatedAt = now)
            check(rows == 1) { "Failed to soft-delete media: $id" }
        }
    }

    override suspend fun findStaleStagedMedia(updatedBefore: Long): List<MediaAsset> {
        return mediaDao.findStaleStaged(updatedBefore).map(MediaAssetMapper::toDomain)
    }

    private fun validateCreateRequests(requests: List<NewMediaAssetRequest>) {
        require(requests.isNotEmpty()) { "Media request list must not be empty" }
        val ids = requests.map { it.id }
        require(ids.all { it.isNotBlank() }) { "Media id must not be blank" }
        require(ids.toSet().size == ids.size) { "Media ids must be unique within one batch" }
        require(requests.all { it.ownerLocalId.isNotBlank() }) { "Owner local id must not be blank" }
        require(requests.all { it.conversationId.isNotBlank() }) { "Conversation id must not be blank" }
        val conversationIds = requests.map { it.conversationId }.toSet()
        require(conversationIds.size == 1) { "All media requests must belong to the same conversation" }
    }

    private fun validateReadyMetadata(
        masterRelativePath: String,
        thumbnailRelativePath: String,
        mimeType: String,
        width: Int,
        height: Int,
        byteSize: Long,
        sha256: String
    ) {
        require(masterRelativePath.isNotBlank()) { "READY media requires a master relative path" }
        require(thumbnailRelativePath.isNotBlank()) { "READY media requires a thumbnail relative path" }
        require(mimeType.isNotBlank()) { "READY media requires a MIME type" }
        require(width > 0) { "READY media width must be greater than zero" }
        require(height > 0) { "READY media height must be greater than zero" }
        require(byteSize > 0L) { "READY media byte size must be greater than zero" }
        require(sha256.isNotBlank()) { "READY media requires a SHA-256 hash" }
    }

    private suspend fun requireExistingMedia(ids: List<String>): List<MediaAssetEntity> {
        val entities = mediaDao.getByIds(ids)
        val foundIds = entities.map { it.id }.toSet()
        val missing = ids.filterNot { it in foundIds }
        check(missing.isEmpty()) { "Media asset not found: ${missing.joinToString()}" }
        return ids.map { id -> entities.first { it.id == id } }
    }

    private suspend fun requireMedia(id: String, conversationId: String): MediaAssetEntity {
        require(id.isNotBlank()) { "Media id must not be blank" }
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        val entity = mediaDao.getById(id) ?: error("Media asset not found: $id")
        requireAllBelongToConversation(listOf(entity), conversationId)
        return entity
    }

    private fun requireAllBelongToConversation(
        entities: List<MediaAssetEntity>,
        conversationId: String
    ) {
        val wrongConversationIds = entities.filter { it.conversationId != conversationId }.map { it.id }
        check(wrongConversationIds.isEmpty()) {
            "Media assets do not belong to conversation $conversationId: ${wrongConversationIds.joinToString()}"
        }
    }

    private fun MediaAssetEntity.hasSameReadyMetadata(
        masterRelativePath: String,
        thumbnailRelativePath: String,
        mimeType: String,
        width: Int,
        height: Int,
        byteSize: Long,
        sha256: String
    ): Boolean {
        return this.masterRelativePath == masterRelativePath &&
            this.thumbnailRelativePath == thumbnailRelativePath &&
            this.mimeType == mimeType &&
            this.width == width &&
            this.height == height &&
            this.byteSize == byteSize &&
            this.sha256 == sha256
    }

    private companion object {
        private const val MAX_ORDER_ALLOCATION_ATTEMPTS = 2
    }
}
