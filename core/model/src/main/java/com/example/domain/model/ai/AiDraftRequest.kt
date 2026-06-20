package com.example.domain.model.ai

import java.time.LocalDate

data class AiDraftRequest(
    val date: LocalDate,
    val text: String,
    val weightKg: Float? = null,
    val context: String? = null
)
