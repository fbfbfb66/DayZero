package com.example.data.sync.chat

import android.content.Context
import com.example.data.sync.BackfillStatus

class ChatBackfillStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_chat_backfill",
        Context.MODE_PRIVATE
    )

    fun snapshot(): ChatBackfillStateSnapshot {
        return ChatBackfillStateSnapshot(
            status = BackfillStatus.fromValue(preferences.getString(KEY_STATUS, null)),
            phase = ChatBackfillPhase.fromValue(preferences.getString(KEY_PHASE, null)),
            conversationCursorCreatedAt = preferences.getLong(KEY_CONVERSATION_CURSOR_CREATED_AT, 0L),
            conversationCursorId = preferences.getString(KEY_CONVERSATION_CURSOR_ID, "") ?: "",
            messageCursorCreatedAt = preferences.getLong(KEY_MESSAGE_CURSOR_CREATED_AT, 0L),
            messageCursorId = preferences.getString(KEY_MESSAGE_CURSOR_ID, "") ?: "",
            startedAt = preferences.getLongOrNull(KEY_STARTED_AT),
            completedAt = preferences.getLongOrNull(KEY_COMPLETED_AT),
            lastAttemptAt = preferences.getLongOrNull(KEY_LAST_ATTEMPT_AT),
            lastSuccessAt = preferences.getLongOrNull(KEY_LAST_SUCCESS_AT),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
            scannedConversationCount = preferences.getInt(KEY_SCANNED_CONVERSATION_COUNT, 0),
            scannedMessageCount = preferences.getInt(KEY_SCANNED_MESSAGE_COUNT, 0),
            enqueuedConversationCount = preferences.getInt(KEY_ENQUEUED_CONVERSATION_COUNT, 0),
            enqueuedMessageCount = preferences.getInt(KEY_ENQUEUED_MESSAGE_COUNT, 0),
            skippedPlaceholderCount = preferences.getInt(KEY_SKIPPED_PLACEHOLDER_COUNT, 0),
            skippedDuplicateCount = preferences.getInt(KEY_SKIPPED_DUPLICATE_COUNT, 0),
            schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
        )
    }

    fun markRunning(startedAt: Long) {
        preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.RUNNING.value)
            .putLong(KEY_STARTED_AT, snapshot().startedAt ?: startedAt)
            .putLong(KEY_LAST_ATTEMPT_AT, startedAt)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun saveProgress(phase: ChatBackfillPhase, cursorCreatedAt: Long, cursorId: String, stats: ChatBackfillStats) {
        val editor = preferences.edit()
            .putString(KEY_PHASE, phase.value)
            .putString(KEY_STATUS, BackfillStatus.RUNNING.value)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
        when (phase) {
            ChatBackfillPhase.CONVERSATIONS -> {
                editor.putLong(KEY_CONVERSATION_CURSOR_CREATED_AT, cursorCreatedAt)
                    .putString(KEY_CONVERSATION_CURSOR_ID, cursorId)
            }
            ChatBackfillPhase.MESSAGES -> {
                editor.putLong(KEY_MESSAGE_CURSOR_CREATED_AT, cursorCreatedAt)
                    .putString(KEY_MESSAGE_CURSOR_ID, cursorId)
            }
        }
        putStats(editor, stats).apply()
    }

    fun moveToMessages(stats: ChatBackfillStats) {
        putStats(
            preferences.edit()
                .putString(KEY_PHASE, ChatBackfillPhase.MESSAGES.value)
                .putString(KEY_STATUS, BackfillStatus.RUNNING.value)
                .putLong(KEY_MESSAGE_CURSOR_CREATED_AT, 0L)
                .putString(KEY_MESSAGE_CURSOR_ID, "")
                .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION),
            stats
        ).apply()
    }

    fun markCompleted(completedAt: Long, stats: ChatBackfillStats) {
        putStats(
            preferences.edit()
                .putString(KEY_STATUS, BackfillStatus.COMPLETED.value)
                .putString(KEY_PHASE, ChatBackfillPhase.MESSAGES.value)
                .putLong(KEY_COMPLETED_AT, completedAt)
                .putLong(KEY_LAST_SUCCESS_AT, completedAt)
                .putLong(KEY_LAST_ATTEMPT_AT, completedAt)
                .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
                .remove(KEY_LAST_ERROR),
            stats
        ).apply()
    }

    fun markRetryableFailure(error: String, stats: ChatBackfillStats? = null, failedAt: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.FAILED_RETRYABLE.value)
            .putLong(KEY_LAST_ATTEMPT_AT, failedAt)
            .putString(KEY_LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
        stats?.let { putStats(editor, it) }
        editor.apply()
    }

    fun markFatalFailure(error: String, stats: ChatBackfillStats? = null, failedAt: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.FAILED_FATAL.value)
            .putLong(KEY_LAST_ATTEMPT_AT, failedAt)
            .putString(KEY_LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_CHAT_BACKFILL_VERSION)
        stats?.let { putStats(editor, it) }
        editor.apply()
    }

    fun resetForRemoteUserChange() {
        preferences.edit().clear().commit()
    }

    private fun putStats(editor: android.content.SharedPreferences.Editor, stats: ChatBackfillStats): android.content.SharedPreferences.Editor {
        return editor
            .putInt(KEY_SCANNED_CONVERSATION_COUNT, stats.scannedConversationCount)
            .putInt(KEY_SCANNED_MESSAGE_COUNT, stats.scannedMessageCount)
            .putInt(KEY_ENQUEUED_CONVERSATION_COUNT, stats.enqueuedConversationCount)
            .putInt(KEY_ENQUEUED_MESSAGE_COUNT, stats.enqueuedMessageCount)
            .putInt(KEY_SKIPPED_PLACEHOLDER_COUNT, stats.skippedPlaceholderCount)
            .putInt(KEY_SKIPPED_DUPLICATE_COUNT, stats.skippedDuplicateCount)
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? {
        if (!contains(key)) return null
        return getLong(key, 0L).takeIf { it > 0L }
    }

    companion object {
        const val CURRENT_CHAT_BACKFILL_VERSION = 1

        private const val MAX_ERROR_LENGTH = 180
        private const val KEY_STATUS = "status"
        private const val KEY_PHASE = "phase"
        private const val KEY_CONVERSATION_CURSOR_CREATED_AT = "conversation_cursor_created_at"
        private const val KEY_CONVERSATION_CURSOR_ID = "conversation_cursor_id"
        private const val KEY_MESSAGE_CURSOR_CREATED_AT = "message_cursor_created_at"
        private const val KEY_MESSAGE_CURSOR_ID = "message_cursor_id"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SCANNED_CONVERSATION_COUNT = "scanned_conversation_count"
        private const val KEY_SCANNED_MESSAGE_COUNT = "scanned_message_count"
        private const val KEY_ENQUEUED_CONVERSATION_COUNT = "enqueued_conversation_count"
        private const val KEY_ENQUEUED_MESSAGE_COUNT = "enqueued_message_count"
        private const val KEY_SKIPPED_PLACEHOLDER_COUNT = "skipped_placeholder_count"
        private const val KEY_SKIPPED_DUPLICATE_COUNT = "skipped_duplicate_count"
        private const val KEY_SCHEMA_VERSION = "schema_version"
    }
}

data class ChatBackfillStateSnapshot(
    val status: BackfillStatus,
    val phase: ChatBackfillPhase,
    val conversationCursorCreatedAt: Long,
    val conversationCursorId: String,
    val messageCursorCreatedAt: Long,
    val messageCursorId: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val scannedConversationCount: Int,
    val scannedMessageCount: Int,
    val enqueuedConversationCount: Int,
    val enqueuedMessageCount: Int,
    val skippedPlaceholderCount: Int,
    val skippedDuplicateCount: Int,
    val schemaVersion: Int
)

enum class ChatBackfillPhase(val value: String) {
    CONVERSATIONS("CONVERSATIONS"),
    MESSAGES("MESSAGES");

    companion object {
        fun fromValue(value: String?): ChatBackfillPhase {
            return entries.firstOrNull { it.value == value } ?: CONVERSATIONS
        }
    }
}
