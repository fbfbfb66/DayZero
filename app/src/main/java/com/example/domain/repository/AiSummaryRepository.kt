package com.example.domain.repository

import com.example.domain.model.DailyRecord

interface AiSummaryRepository {
    suspend fun generateDailySummary(record: DailyRecord): String
}
