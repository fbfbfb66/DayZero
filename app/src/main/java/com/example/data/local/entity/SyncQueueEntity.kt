package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sync_queue",
    indices = [Index(value = ["status", "createdAt"])]
)
data class SyncQueueEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entityType: String,
    val entityLocalId: String,
    val operation: String,
    val payloadJson: String,
    val status: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'local_uninitialized'")
    val ownerLocalId: String = "local_uninitialized",
    @ColumnInfo(defaultValue = "0")
    val nextAttemptAt: Long = 0L
)
