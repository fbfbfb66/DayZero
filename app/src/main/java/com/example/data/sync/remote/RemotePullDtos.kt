package com.example.data.sync.remote

data class DailyRecordRemoteDto(
    val userId: String,
    val clientId: String,
    val recordDate: String,
    val status: String,
    val totalCalories: Int,
    val weightKg: Float?,
    val aiSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class MealRemoteDto(
    val userId: String,
    val clientId: String,
    val dailyRecordClientId: String,
    val mealType: String,
    val hasPhoto: Boolean,
    val subtotalCalories: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class FoodEntryRemoteDto(
    val userId: String,
    val clientId: String,
    val mealClientId: String,
    val dailyRecordClientId: String,
    val name: String,
    val quantity: String,
    val estimatedCalories: Int,
    val confidence: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class WeightRecordRemoteDto(
    val userId: String,
    val clientId: String,
    val dailyRecordClientId: String?,
    val measuredDate: String,
    val weightKg: Float,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)
