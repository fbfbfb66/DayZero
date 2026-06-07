package com.example.domain.model

import com.example.domain.model.ai.AiChatMessage
import java.time.LocalDate

data class AppState(
    val currentDate: LocalDate = LocalDate.now(),
    val records: List<DailyRecord> = emptyList(),
    val chatMessages: List<AiChatMessage> = emptyList(),
    val isAnalyzing: Boolean = false,
    val conflictState: ConflictState? = null
)

data class ConflictState(
    val draftId: String,
    val existingMealTypes: List<MealType>,
    val weightKg: Float?
)
