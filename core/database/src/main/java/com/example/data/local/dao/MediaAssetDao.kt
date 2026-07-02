package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.local.entity.MediaAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAssetDao {
    @Insert
    suspend fun insertAll(entities: List<MediaAssetEntity>)

    @Query("SELECT MAX(conversationOrder) FROM media_assets WHERE conversationId = :conversationId")
    suspend fun getMaxConversationOrder(conversationId: String): Long?

    @Query(
        """
        SELECT * FROM media_assets
        WHERE conversationId = :conversationId
          AND deletedAt IS NULL
        ORDER BY conversationOrder ASC, id ASC
        """
    )
    fun observeActiveByConversation(conversationId: String): Flow<List<MediaAssetEntity>>

    @Query(
        """
        SELECT * FROM media_assets
        WHERE conversationId = :conversationId
          AND deletedAt IS NULL
        ORDER BY conversationOrder ASC, id ASC
        """
    )
    suspend fun getActiveByConversation(conversationId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MediaAssetEntity>

    @Query(
        """
        UPDATE media_assets
        SET sourceMessageId = :sourceMessageId,
            updatedAt = :updatedAt
        WHERE id = :id
          AND deletedAt IS NULL
        """
    )
    suspend fun attachToMessage(
        id: String,
        sourceMessageId: String,
        updatedAt: Long
    ): Int

    /**
     * Atomic compare-and-swap binding of READY, unbound media assets to a message.
     * Returns the number of rows actually updated. The caller must verify that
     * affectedRows == orderedMediaIds.size for transaction integrity.
     */
    @Query(
        """
        UPDATE media_assets
        SET sourceMessageId = :sourceMessageId,
            updatedAt = :updatedAt
        WHERE id IN (:orderedMediaIds)
          AND conversationId = :conversationId
          AND lifecycleState = 'READY'
          AND deletedAt IS NULL
          AND sourceMessageId IS NULL
        """
    )
    suspend fun attachReadyMediaToMessage(
        orderedMediaIds: List<String>,
        conversationId: String,
        sourceMessageId: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE media_assets
        SET masterRelativePath = :masterRelativePath,
            thumbnailRelativePath = :thumbnailRelativePath,
            mimeType = :mimeType,
            width = :width,
            height = :height,
            byteSize = :byteSize,
            sha256 = :sha256,
            lifecycleState = 'READY',
            failureCode = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
          AND deletedAt IS NULL
          AND lifecycleState IN ('STAGED', 'FAILED')
        """
    )
    suspend fun markReady(
        id: String,
        masterRelativePath: String,
        thumbnailRelativePath: String,
        mimeType: String,
        width: Int,
        height: Int,
        byteSize: Long,
        sha256: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE media_assets
        SET lifecycleState = 'FAILED',
            failureCode = :failureCode,
            updatedAt = :updatedAt
        WHERE id = :id
          AND deletedAt IS NULL
        """
    )
    suspend fun markFailed(
        id: String,
        failureCode: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE media_assets
        SET deletedAt = COALESCE(deletedAt, :deletedAt),
            updatedAt = CASE WHEN deletedAt IS NULL THEN :updatedAt ELSE updatedAt END
        WHERE id = :id
        """
    )
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        SELECT * FROM media_assets
        WHERE lifecycleState IN ('STAGED', 'FAILED')
          AND deletedAt IS NULL
          AND sourceMessageId IS NULL
          AND updatedAt < :updatedBefore
        ORDER BY updatedAt ASC, conversationOrder ASC, id ASC
        """
    )
    suspend fun findStaleStaged(updatedBefore: Long): List<MediaAssetEntity>
}
