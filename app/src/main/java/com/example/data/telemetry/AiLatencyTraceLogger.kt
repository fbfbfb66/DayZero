package com.example.data.telemetry

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AiLatencyTraceLogger(context: Context) {
    private val appContext = context.applicationContext
    private val outputFile: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "dayzero_ai_latency_traces.jsonl"
    )
    private val traces = ConcurrentHashMap<String, Trace>()
    private val assistantMessageToTrace = ConcurrentHashMap<String, String>()
    private val assistantMessageToConversationType = ConcurrentHashMap<String, String>()

    init {
        outputFile.parentFile?.mkdirs()
        Log.d(TAG, "latency trace file: ${outputFile.absolutePath}")
    }

    fun start(
        turnType: String,
        userText: String,
        actionType: String? = null,
        selectedOptionId: String? = null
    ): String {
        val traceId = UUID.randomUUID().toString()
        val trace = Trace(
            traceId = traceId,
            startedWallTimeMs = System.currentTimeMillis(),
            turnType = turnType,
            userTextPreview = userText.take(80),
            triggerActionType = actionType,
            selectedOptionId = selectedOptionId
        )
        traces[traceId] = trace
        mark(traceId, "trace_started")
        return traceId
    }

    fun mark(traceId: String?, name: String, metadata: Map<String, Any?> = emptyMap()) {
        if (traceId.isNullOrBlank()) return
        val trace = traces[traceId] ?: return
        synchronized(trace) {
            trace.events.add(
                TraceEvent(
                    name = name,
                    elapsedMs = SystemClock.elapsedRealtime() - trace.startedElapsedMs,
                    metadata = metadata.filterValues { it != null }
                )
            )
        }
        Log.d(TAG, "trace=$traceId event=$name metadata=$metadata")
    }

    fun bindAssistantMessage(traceId: String?, messageId: String, conversationType: String) {
        if (traceId.isNullOrBlank()) return
        assistantMessageToTrace[messageId] = traceId
        assistantMessageToConversationType[messageId] = conversationType
        mark(
            traceId,
            "assistant_message_bound",
            mapOf("messageId" to messageId, "conversationType" to conversationType)
        )
    }

    fun completeByRenderedMessage(messageId: String, fallbackConversationType: String) {
        val traceId = assistantMessageToTrace.remove(messageId) ?: return
        val conversationType = assistantMessageToConversationType.remove(messageId) ?: fallbackConversationType
        mark(traceId, "compose_assistant_message_rendered", mapOf("messageId" to messageId))
        complete(traceId, status = "rendered", conversationType = conversationType)
    }

    fun complete(traceId: String?, status: String, conversationType: String, metadata: Map<String, Any?> = emptyMap()) {
        if (traceId.isNullOrBlank()) return
        val trace = traces.remove(traceId) ?: return
        synchronized(trace) {
            if (trace.completed) return
            trace.completed = true
            trace.events.add(
                TraceEvent(
                    name = "trace_completed",
                    elapsedMs = SystemClock.elapsedRealtime() - trace.startedElapsedMs,
                    metadata = (metadata + mapOf("status" to status, "conversationType" to conversationType)).filterValues { it != null }
                )
            )
            appendTrace(trace, status, conversationType)
        }
    }

    fun fail(traceId: String?, error: Throwable) {
        complete(
            traceId = traceId,
            status = "error",
            conversationType = "error",
            metadata = mapOf("error" to (error.message ?: error::class.java.simpleName))
        )
    }

    private fun appendTrace(trace: Trace, status: String, conversationType: String) {
        val totalMs = trace.events.lastOrNull()?.elapsedMs ?: 0L
        val segments = trace.events.zipWithNext().map { (from, to) ->
            val duration = (to.elapsedMs - from.elapsedMs).coerceAtLeast(0L)
            mapOf(
                "from" to from.name,
                "to" to to.name,
                "durationMs" to duration,
                "percent" to percent(duration, totalMs)
            )
        }

        val body = mapOf(
            "traceId" to trace.traceId,
            "startedWallTimeMs" to trace.startedWallTimeMs,
            "status" to status,
            "turnType" to trace.turnType,
            "conversationType" to conversationType,
            "triggerActionType" to trace.triggerActionType,
            "selectedOptionId" to trace.selectedOptionId,
            "userTextPreview" to trace.userTextPreview,
            "totalMs" to totalMs,
            "events" to trace.events.map {
                mapOf(
                    "name" to it.name,
                    "elapsedMs" to it.elapsedMs,
                    "metadata" to it.metadata
                )
            },
            "segments" to segments
        )

        outputFile.appendText(toJson(body) + "\n")
        Log.d(TAG, "trace=${trace.traceId} completed totalMs=$totalMs file=${outputFile.absolutePath}")
    }

    private fun percent(durationMs: Long, totalMs: Long): Double {
        if (totalMs <= 0L) return 0.0
        return String.format(Locale.US, "%.2f", durationMs * 100.0 / totalMs).toDouble()
    }

    private fun toJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escape(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                "\"${escape(entry.key.toString())}\":${toJson(entry.value)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
            else -> "\"${escape(value.toString())}\""
        }
    }

    private fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private data class Trace(
        val traceId: String,
        val startedWallTimeMs: Long,
        val startedElapsedMs: Long = SystemClock.elapsedRealtime(),
        val turnType: String,
        val userTextPreview: String,
        val triggerActionType: String?,
        val selectedOptionId: String?,
        val events: MutableList<TraceEvent> = mutableListOf(),
        var completed: Boolean = false
    )

    private data class TraceEvent(
        val name: String,
        val elapsedMs: Long,
        val metadata: Map<String, Any?>
    )

    companion object {
        private const val TAG = "DayZeroLatencyTrace"
    }
}
