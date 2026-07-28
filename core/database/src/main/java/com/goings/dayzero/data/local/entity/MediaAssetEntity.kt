package com.goings.dayzero.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["conversationId", "conversationOrder"], unique = true),
        Index(value = ["conversationId", "deletedAt", "conversationOrder"]),
        Index(value = ["sourceMessageId"]),
        Index(value = ["lifecycleState", "updatedAt"]),
        Index(value = ["ownerLocalId"]),
        Index(value = ["remoteSyncState", "updatedAt"])
    ]
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
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
    val source: String,
    val lifecycleState: String,
    val failureCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    /**
     * Cross-device sync dimension, kept separate from [lifecycleState] so local
     * capture/attach/vision logic is untouched. See [MediaRemoteSyncState].
     */
    @ColumnInfo(defaultValue = "LOCAL_ONLY")
    val remoteSyncState: String = "LOCAL_ONLY",
    /** Supabase Storage object key for the master image (device-independent). */
    val remoteMasterPath: String? = null,
    /** Supabase Storage object key for the thumbnail image (device-independent). */
    val remoteThumbnailPath: String? = null
)
