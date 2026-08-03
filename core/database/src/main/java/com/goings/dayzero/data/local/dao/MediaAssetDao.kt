package com.goings.dayzero.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.goings.dayzero.data.local.entity.MediaAssetEntity
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

    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND deletedAt IS NULL")
    fun observeActiveByIds(ids: List<String>): Flow<List<MediaAssetEntity>>

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

    // ---- Cross-device sync (Storage) ----

    /**
     * Origin-device reconciliation scan: READY assets bound to a sent message that
     * have not yet been uploaded. Primary enqueue path is transactional at bind
     * time; this is a safety net for rows whose enqueue was lost.
     */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE remoteSyncState = 'LOCAL_ONLY'
          AND lifecycleState = 'READY'
          AND sourceMessageId IS NOT NULL
          AND deletedAt IS NULL
        ORDER BY updatedAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun findAssetsNeedingUpload(limit: Int): List<MediaAssetEntity>

    /**
     * Recipient-device scan: rows pulled from remote whose bytes are not yet on disk.
     */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE remoteSyncState = 'REMOTE_PENDING'
          AND deletedAt IS NULL
        ORDER BY updatedAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun findAssetsNeedingDownload(limit: Int): List<MediaAssetEntity>

    @Query(
        """
        UPDATE media_assets
        SET remoteSyncState = 'UPLOADED',
            remoteMasterPath = :remoteMasterPath,
            remoteThumbnailPath = :remoteThumbnailPath,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun markUploaded(
        id: String,
        remoteMasterPath: String,
        remoteThumbnailPath: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE media_assets
        SET remoteSyncState = 'DOWNLOADED',
            lifecycleState = 'READY',
            masterRelativePath = :masterRelativePath,
            thumbnailRelativePath = :thumbnailRelativePath,
            mimeType = COALESCE(:mimeType, mimeType),
            width = COALESCE(:width, width),
            height = COALESCE(:height, height),
            byteSize = COALESCE(:byteSize, byteSize),
            failureCode = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
          AND remoteSyncState = 'REMOTE_PENDING'
        """
    )
    suspend fun markDownloaded(
        id: String,
        masterRelativePath: String,
        thumbnailRelativePath: String?,
        mimeType: String?,
        width: Int?,
        height: Int?,
        byteSize: Long?,
        updatedAt: Long
    ): Int

    /**
     * Applies a remote soft-delete tombstone. Clears local file paths so the UI
     * stops resolving now-orphaned files; physical files are removed by the caller.
     */
    @Query(
        """
        UPDATE media_assets
        SET deletedAt = COALESCE(deletedAt, :deletedAt),
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun applyRemoteTombstone(
        id: String,
        deletedAt: Long,
        updatedAt: Long
    ): Int
}
