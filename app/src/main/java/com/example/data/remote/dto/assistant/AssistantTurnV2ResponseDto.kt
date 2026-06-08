package com.example.data.remote.dto.assistant

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AssistantTurnV2ResponseDto(
    val reply: String?,
    val actions: List<AssistantActionDto>?
)
