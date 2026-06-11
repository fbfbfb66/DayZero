package com.example.data.remote.dto.assistant

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AssistantTurnV2ResponseDto(
    val reply: String?,
    val actions: List<AssistantActionDto>?,
    val debugTiming: AssistantTurnDebugTimingDto? = null
)

@JsonClass(generateAdapter = true)
data class AssistantTurnDebugTimingDto(
    val traceId: String? = null,
    val totalMs: Double? = null,
    val requestParseMs: Double? = null,
    val promptBuildMs: Double? = null,
    val kimiRequestMs: Double? = null,
    val kimiJsonParseMs: Double? = null,
    val protocolValidationMs: Double? = null
)
