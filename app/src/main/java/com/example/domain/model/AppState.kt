package com.example.domain.model

import java.time.LocalDate

data class AppState(
    val currentDate: LocalDate = LocalDate.of(2026, 6, 7),
    val records: List<DailyRecord> = emptyList(),
    val isAnalyzing: Boolean = false,
    val aiMessage: String? = null
)
