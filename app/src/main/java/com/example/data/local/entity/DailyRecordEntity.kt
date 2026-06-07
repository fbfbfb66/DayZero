package com.example.data.local.entity

import androidx.room.Entity
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
    val updatedAt: Long
)
