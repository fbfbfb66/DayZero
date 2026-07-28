package com.goings.dayzero.data.remote.stream

import com.goings.dayzero.data.remote.SupabaseConfig
import com.goings.dayzero.data.remote.dto.assistant.AiAssistantRequestDto
import com.goings.dayzero.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import com.goings.dayzero.domain.model.ai.assistant.ProtocolException
import com.goings.dayzero.domain.model.ai.assistant.AiAssistantRemoteException
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AssistantTurnStreamClient(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val requestAdapter = moshi.adapter(AiAssistantRequestDto::class.java)
    private val finalAdapter = moshi.adapter(AssistantTurnV2ResponseDto::class.java)
    private val deltaAdapter = moshi.adapter(ReplyDeltaEventDto::class.java)
    private val timingAdapter = moshi.adapter(StreamDebugTimingEventDto::class.java)
    private val errorAdapter = moshi.adapter(StreamErrorEventDto::class.java)
    private val errorResponseAdapter = moshi.adapter(StreamErrorResponseDto::class.java)

    suspend fun stream(
        requestDto: AiAssistantRequestDto,
        onDelta: suspend (String) -> Unit,
        onTiming: suspend (StreamDebugTimingEventDto) -> Unit,
        onFinalReceived: suspend () -> Unit = {}
    ): AssistantTurnV2ResponseDto = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured()) {
            throw ProtocolException("Supabase AI runtime config is missing")
        }
        val requestJson = requestAdapter.toJson(requestDto)
        val request = Request.Builder()
            .url(SupabaseConfig.edgeFunctionUrl("assistant-turn-v2-stream"))
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                val parsed = runCatching { errorResponseAdapter.fromJson(errorBody) }.getOrNull()
                val errorCode = parsed?.errorCode?.takeIf { it.isNotBlank() }
                    ?: parsed?.code?.takeIf { it.isNotBlank() }
                    ?: "HTTP_${response.code}"
                throw AiAssistantRemoteException(
                    errorCode = errorCode,
                    retryable = parsed?.retryable ?: (response.code == 408 || response.code == 429 || response.code >= 500),
                    stage = parsed?.stage,
                    message = parsed?.message?.takeIf { it.isNotBlank() }
                        ?: "AI service request failed"
                )
            }

            val source = response.body?.source() ?: throw ProtocolException("协议错误")
            var eventName = "message"
            val dataLines = mutableListOf<String>()
            var finalResponse: AssistantTurnV2ResponseDto? = null

            suspend fun dispatchEvent() {
                if (dataLines.isEmpty()) return
                val data = dataLines.joinToString("\n")
                when (eventName) {
                    "reply_delta" -> {
                        val delta = deltaAdapter.fromJson(data)?.text.orEmpty()
                        if (delta.isNotEmpty()) onDelta(delta)
                    }
                    "debug_timing" -> {
                        timingAdapter.fromJson(data)?.let { onTiming(it) }
                    }
                    "actions", "final" -> {
                        onFinalReceived()
                        finalResponse = finalAdapter.fromJson(data)
                    }
                    "done" -> Unit
                    "error" -> {
                        val error = errorAdapter.fromJson(data)
                        val message = error?.message ?: "assistant-turn-v2-stream failed"
                        val code = error?.code?.trim().orEmpty()
                        throw AiAssistantRemoteException(
                            errorCode = code.ifEmpty { "STREAM_ERROR" },
                            retryable = error?.retryable ?: true,
                            stage = error?.stage,
                            message = message
                        )
                    }
                }
                eventName = "message"
                dataLines.clear()
            }

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    dispatchEvent()
                    continue
                }
                when {
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trimStart())
                }
            }
            dispatchEvent()

            return@withContext finalResponse ?: throw ProtocolException("协议错误")
        }
    }
}

@JsonClass(generateAdapter = true)
data class ReplyDeltaEventDto(
    val text: String = ""
)

@JsonClass(generateAdapter = true)
data class StreamDebugTimingEventDto(
    val traceId: String? = null,
    val totalMs: Double? = null,
    val requestParseMs: Double? = null,
    val promptBuildMs: Double? = null,
    val kimiTimeToFirstTokenMs: Double? = null,
    val kimiStreamMs: Double? = null,
    val kimiJsonParseMs: Double? = null,
    val protocolValidationMs: Double? = null,
    val promptChars: Int? = null,
    val outputJsonChars: Int? = null,
    val compactJsonUsed: Boolean? = null,
    val promptCacheKeyUsed: Boolean? = null,
    val lastReplyContentAvailableMs: Double? = null,
    val actionsReadyMs: Double? = null,
    val edgeFinalEmittedMs: Double? = null,
    val lastReplyToActionsReadyMs: Double? = null,
    val actionsReadyToEdgeFinalMs: Double? = null
)

@JsonClass(generateAdapter = true)
data class StreamErrorEventDto(
    val message: String? = null,
    val code: String? = null,
    val retryable: Boolean? = null,
    val stage: String? = null
)

@JsonClass(generateAdapter = true)
data class StreamErrorResponseDto(
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
    val stage: String? = null
)
