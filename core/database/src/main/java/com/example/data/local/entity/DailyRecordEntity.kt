package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "daily_records")
data class DailyRecordEntity(
    @PrimaryKey val id: String,
    val date: String,
    val status: String,
    val mealsJson: String,
    val weightKg: Float?,
    val aiSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "")
    val clientId: String = id,
    val remoteId: String? = null,
    @ColumnInfo(defaultValue = "'PENDING'")
    val syncStatus: String = "PENDING",
    @ColumnInfo(defaultValue = "0")
    val syncVersion: Long = updatedAt,
    val deletedAt: Long? = null,
    val lastSyncedAt: Long? = null,
    @ColumnInfo(defaultValue = "'local_uninitialized'")
    val ownerLocalId: String = "local_uninitialized"
)
