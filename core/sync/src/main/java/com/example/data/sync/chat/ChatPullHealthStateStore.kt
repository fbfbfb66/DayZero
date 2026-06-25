package com.example.data.sync.chat

import android.content.Context
import com.example.data.sync.PullStatus

class ChatPullHealthStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dayzero_chat_pull_health", Context.MODE_PRIVATE)

    fun snapshot(): ChatPullHealthState {
        return ChatPullHealthState(
            status = PullStatus.fromValue(preferences.getString("status", PullStatus.NOT_STARTED.value)),
            lastAttemptAt = preferences.readLongOrNull("lastAttemptAt"),
            lastSuccessAt = preferences.readLongOrNull("lastSuccessAt"),
            lastFailureAt = preferences.readLongOrNull("lastFailureAt"),
            lastError = preferences.getString("lastError", null)
        )
    }

    fun markRunning(startedAt: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString("status", PullStatus.RUNNING.value)
            .putLong("lastAttemptAt", startedAt)
            .apply()
    }

    fun markCompleted(completedAt: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString("status", PullStatus.COMPLETED.value)
            .putLong("lastSuccessAt", completedAt)
            .remove("lastError")
            .apply()
    }

    fun markRetryableFailure(error: String, failedAt: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString("status", PullStatus.FAILED_RETRYABLE.value)
            .putLong("lastFailureAt", failedAt)
            .putString("lastError", error)
            .apply()
    }

    fun markFatalFailure(error: String, failedAt: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString("status", PullStatus.FAILED_FATAL.value)
            .putLong("lastFailureAt", failedAt)
            .putString("lastError", error)
            .apply()
    }

    fun resetForRemoteUserChange() {
        preferences.edit().clear().commit()
    }

    private fun android.content.SharedPreferences.readLongOrNull(key: String): Long? {
        return if (contains(key)) getLong(key, 0L) else null
    }
}

data class ChatPullHealthState(
    val status: PullStatus,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?
)
