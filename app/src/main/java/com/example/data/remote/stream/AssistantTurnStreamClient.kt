package com.example.data.remote.stream

import com.example.data.remote.SupabaseConfig
import com.example.data.remote.dto.assistant.AiAssistantRequestDto
import com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import com.example.domain.model.ai.assistant.ProtocolException
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

    suspend fun stream(
        requestDto: AiAssistantRequestDto,
        onDelta: suspend (String) -> Unit,
        onTiming: suspend (StreamDebugTimingEventDto) -> Unit
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
                throw ProtocolException("assistant-turn-v2-stream HTTP ${response.code}: ${errorBody.take(120)}")
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
                        finalResponse = finalAdapter.fromJson(data)
                    }
                    "done" -> Unit
                    "error" -> {
                        val message = errorAdapter.fromJson(data)?.message ?: "assistant-turn-v2-stream failed"
                        throw ProtocolException(message)
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
    val promptCacheKeyUsed: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class StreamErrorEventDto(
    val message: String? = null
)
