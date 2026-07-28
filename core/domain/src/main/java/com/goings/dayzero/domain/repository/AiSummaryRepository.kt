package com.goings.dayzero.domain.repository

import com.goings.dayzero.domain.model.DailyRecord

interface AiSummaryRepository {
    suspend fun generateDailySummary(record: DailyRecord): String
}
