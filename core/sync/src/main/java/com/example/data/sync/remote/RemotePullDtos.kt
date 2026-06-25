package com.example.data.sync.remote

data class DailyRecordRemoteDto(
    val userId: String,
    val clientId: String,
    val localDate: String,
    val timezone: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class MealRemoteDto(
    val userId: String,
    val clientId: String,
    val dailyRecordClientId: String,
    val mealType: String?,
    val loggedAt: Long?,
    val displayOrder: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class FoodEntryRemoteDto(
    val userId: String,
    val clientId: String,
    val mealClientId: String,
    val name: String,
    val amountText: String?,
    val grams: Float?,
    val calories: Float?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val fiberG: Float?,
    val confidence: Float?,
    val source: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)

data class WeightRecordRemoteDto(
    val userId: String,
    val clientId: String,
    val localDate: String,
    val measuredAt: Long?,
    val weightKg: Float,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val schemaVersion: Int
)
