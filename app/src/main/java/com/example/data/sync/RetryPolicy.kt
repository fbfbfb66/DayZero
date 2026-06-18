package com.example.data.sync

import com.example.data.local.entity.SyncQueueEntity

enum class RetryFailureType {
    NETWORK,
    SERVER_5XX,
    RATE_LIMIT,
    AUTH_WAITING,
    AUTH_FATAL,
    RLS_OR_PERMISSION,
    PAYLOAD_INVALID,
    UNKNOWN_RETRYABLE,
    UNKNOWN_FATAL
}

object RetryPolicy {
    const val MAX_RETRY_COUNT = 10

    fun canAttempt(now: Long, task: SyncQueueEntity): Boolean {
        return task.nextAttemptAt <= 0L || task.nextAttemptAt <= now
    }

    fun nextAttemptAt(now: Long, retryCount: Int, failureType: RetryFailureType = RetryFailureType.UNKNOWN_RETRYABLE): Long {
        val baseDelayMs = when {
            failureType == RetryFailureType.AUTH_WAITING -> DayZeroSyncConstants.WAITING_FOR_AUTH_RETRY_DELAY_MS
            failureType == RetryFailureType.RATE_LIMIT -> 10 * 60 * 1000L
            retryCount <= 0 -> 0L
            retryCount == 1 -> 30 * 1000L
            retryCount == 2 -> 2 * 60 * 1000L
            retryCount == 3 -> 10 * 60 * 1000L
            retryCount == 4 -> 30 * 60 * 1000L
            else -> 2 * 60 * 60 * 1000L
        }
        return now + baseDelayMs
    }

    fun classifyFailure(error: String?): RetryFailureType {
        val normalized = error.orEmpty().lowercase()
        return when {
            normalized.contains("waiting_for_auth") -> RetryFailureType.AUTH_WAITING
            normalized.contains("supabase_not_configured") ||
                normalized.contains("noop_gateway") ||
                normalized.contains("remote_disabled") -> RetryFailureType.AUTH_WAITING
            normalized.contains("payload") ||
                normalized.contains("missing") ||
                normalized.contains("unsupported_operation") -> RetryFailureType.PAYLOAD_INVALID
            normalized.contains("http_429") -> RetryFailureType.RATE_LIMIT
            normalized.contains("http_500") ||
                normalized.contains("http_502") ||
                normalized.contains("http_503") ||
                normalized.contains("http_504") -> RetryFailureType.SERVER_5XX
            normalized.contains("http_401") -> RetryFailureType.AUTH_FATAL
            normalized.contains("http_403") -> RetryFailureType.RLS_OR_PERMISSION
            normalized.contains("timeout") ||
                normalized.contains("unknownhost") ||
                normalized.contains("connect") ||
                normalized.contains("socket") ||
                normalized.contains("network") -> RetryFailureType.NETWORK
            normalized.contains("fatal") -> RetryFailureType.UNKNOWN_FATAL
            else -> RetryFailureType.UNKNOWN_RETRYABLE
        }
    }

    fun shouldBecomeFatal(retryCountAfterFailure: Int, failureType: RetryFailureType): Boolean {
        return failureType == RetryFailureType.PAYLOAD_INVALID ||
            failureType == RetryFailureType.AUTH_FATAL ||
            failureType == RetryFailureType.RLS_OR_PERMISSION ||
            failureType == RetryFailureType.UNKNOWN_FATAL ||
            retryCountAfterFailure >= MAX_RETRY_COUNT
    }
}
