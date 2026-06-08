package com.example.domain.model.ai.assistant

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AssistantTurnResponse(
    val reply: String,
    val actions: List<AssistantAction>
)
