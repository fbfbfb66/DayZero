package com.example.data.repository

import android.util.Log
import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.mapper.AiAssistantRemoteMapper
import com.example.data.remote.mapper.AssistantTurnV2ResponseMapper
import com.example.data.remote.stream.AssistantTurnStreamClient
import com.example.data.telemetry.AiLatencyTraceLogger
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.AiAssistantTurn
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.repository.AiAssistantRepository

class RemoteAiAssistantRepository(
    private val apiService: AiDraftApiService,
    private val latencyLogger: AiLatencyTraceLogger? = null,
    private val streamClient: AssistantTurnStreamClient? = null,
    private val promptCacheKeyProvider: (() -> String)? = null
) : AiAssistantRepository {

    private val mapper = AiAssistantRemoteMapper()
    private val v2ResponseMapper = AssistantTurnV2ResponseMapper()

    override suspend fun streamMessage(
        request: AiAssistantRequest,
        onDelta: suspend (String) -> Unit
    ): AiAssistantTurn {
        val client = streamClient ?: return super.streamMessage(request, onDelta)
        val promptCacheKey = promptCacheKeyProvider?.invoke()
        latencyLogger?.mark(
            request.traceId,
            "repository_stream_dto_map_start",
            mapOf("promptCacheKeyUsed" to (!promptCacheKey.isNullOrBlank()))
        )
        val requestDto = mapper.toRequestDto(request, promptCacheKey)
        latencyLogger?.mark(request.traceId, "repository_stream_dto_map_complete")

        latencyLogger?.mark(request.traceId, "http_assistant_turn_v2_stream_start")
        val responseDto = client.stream(
            requestDto = requestDto,
            onDelta = { delta ->
                latencyLogger?.mark(
                    request.traceId,
                    "stream_reply_delta_received",
                    mapOf("deltaLength" to delta.length)
                )
                onDelta(delta)
            },
            onTiming = { timing ->
                latencyLogger?.mark(
                    request.traceId,
                    "server_stream_debug_timing_received",
                    mapOf(
                        "serverTraceId" to timing.traceId,
                        "serverTotalMs" to timing.totalMs,
                        "requestParseMs" to timing.requestParseMs,
                        "promptBuildMs" to timing.promptBuildMs,
                        "kimiTimeToFirstTokenMs" to timing.kimiTimeToFirstTokenMs,
                        "kimiStreamMs" to timing.kimiStreamMs,
                        "kimiJsonParseMs" to timing.kimiJsonParseMs,
                        "protocolValidationMs" to timing.protocolValidationMs,
                        "promptChars" to timing.promptChars,
                        "outputJsonChars" to timing.outputJsonChars,
                        "compactJsonUsed" to timing.compactJsonUsed,
                        "promptCacheKeyUsed" to timing.promptCacheKeyUsed
                    )
                )
            }
        )
        latencyLogger?.mark(
            request.traceId,
            "http_assistant_turn_v2_stream_complete",
            mapOf(
                "actionsCount" to (responseDto.actions?.size ?: 0),
                "replyLength" to (responseDto.reply?.length ?: 0)
            )
        )
        return v2ResponseMapper.toDomain(responseDto)
    }

    override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
        Log.d(
            "AssistantTurnV2",
            "sendMessage start: userText='${request.userText.take(50)}', date=${request.date}, recentMessagesCount=${request.recentMessages.size}"
        )

        latencyLogger?.mark(request.traceId, "repository_dto_map_start")
        val requestDto = mapper.toRequestDto(request)
        latencyLogger?.mark(request.traceId, "repository_dto_map_complete")

        return try {
            latencyLogger?.mark(request.traceId, "http_assistant_turn_v2_start")
            val response = apiService.sendAssistantTurnV2WithResponse(requestDto)
            val statusCode = response.code()
            latencyLogger?.mark(
                request.traceId,
                "http_assistant_turn_v2_response_received",
                mapOf("statusCode" to statusCode)
            )
            Log.d("AssistantTurnV2", "assistant-turn-v2 response received: status=$statusCode")

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string() ?: "Unknown error"
                Log.e("AssistantTurnV2", "assistant-turn-v2 failed: status=$statusCode, error=$errorBody")
                val message = when (statusCode) {
                    401, 403 -> "Supabase API key invalid or expired"
                    else -> "assistant-turn-v2 HTTP $statusCode"
                }
                throw ProtocolException(message)
            }

            val responseBody = response.body() ?: throw ProtocolException("协议错误")
            responseBody.debugTiming?.let { timing ->
                latencyLogger?.mark(
                    request.traceId,
                    "server_debug_timing_received",
                    mapOf(
                        "serverTraceId" to timing.traceId,
                        "serverTotalMs" to timing.totalMs,
                        "requestParseMs" to timing.requestParseMs,
                        "promptBuildMs" to timing.promptBuildMs,
                        "kimiRequestMs" to timing.kimiRequestMs,
                        "kimiJsonParseMs" to timing.kimiJsonParseMs,
                        "protocolValidationMs" to timing.protocolValidationMs
                    )
                )
            }

            latencyLogger?.mark(request.traceId, "protocol_validate_start")
            val turn = v2ResponseMapper.toDomain(responseBody)
            latencyLogger?.mark(
                request.traceId,
                "protocol_validate_complete",
                mapOf("replyLength" to turn.replyText.length, "actionsCount" to turn.cards.size)
            )
            latencyLogger?.mark(
                request.traceId,
                "action_parse_success",
                mapOf(
                    "actionsCount" to turn.cards.size,
                    "cardTypes" to turn.cards.joinToString(",") { it.type.name }
                )
            )
            turn
        } catch (e: Exception) {
            if (e is ProtocolException) throw e
            Log.e("DayZeroAiV2", "parse AssistantTurnResponse error", e)
            throw ProtocolException("协议错误", e)
        }
    }
}
