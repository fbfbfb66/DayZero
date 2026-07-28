package com.goings.dayzero.domain.model.ai.assistant

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AssistantAction(
    val type: String? = null
)
