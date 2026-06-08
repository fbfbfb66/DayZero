package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class IntentClassifierRequestDto(
    @Json(name = "userText") val userText: String,
    @Json(name = "date") val date: String,
    @Json(name = "todayRecordSummary") val todayRecordSummary: String,
    @Json(name = "recentMessages") val recentMessages: List<RecentMessageDto>
)

@JsonClass(generateAdapter = true)
data class RecentMessageDto(
    @Json(name = "role") val role: String,
    @Json(name = "text") val text: String
)

@JsonClass(generateAdapter = true)
data class IntentClassificationResultDto(
    @Json(name = "primaryIntent") val primaryIntent: String,
    @Json(name = "speechAct") val speechAct: String,
    @Json(name = "consumptionStatus") val consumptionStatus: String,
    @Json(name = "containsFood") val containsFood: Boolean,
    @Json(name = "foodText") val foodText: String?,
    @Json(name = "containsEmotion") val containsEmotion: Boolean,
    @Json(name = "mealTimeMentioned") val mealTimeMentioned: Boolean,
    @Json(name = "weightMentioned") val weightMentioned: Boolean,
    @Json(name = "shouldComfortFirst") val shouldComfortFirst: Boolean,
    @Json(name = "shouldCreateDraft") val shouldCreateDraft: Boolean,
    @Json(name = "shouldAskMealTime") val shouldAskMealTime: Boolean,
    @Json(name = "shouldWriteData") val shouldWriteData: Boolean,
    @Json(name = "confidence") val confidence: Double,
    @Json(name = "reason") val reason: String?
)
