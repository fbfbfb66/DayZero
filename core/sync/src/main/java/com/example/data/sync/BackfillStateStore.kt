package com.example.data.sync

import android.content.Context

class BackfillStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_backfill",
        Context.MODE_PRIVATE
    )

    fun snapshot(): BackfillStateSnapshot {
        return BackfillStateSnapshot(
            status = BackfillStatus.fromValue(preferences.getString(KEY_STATUS, null)),
            startedAt = preferences.getLongOrNull(KEY_STARTED_AT),
            completedAt = preferences.getLongOrNull(KEY_COMPLETED_AT),
            lastAttemptAt = preferences.getLongOrNull(KEY_LAST_ATTEMPT_AT),
            lastSuccessAt = preferences.getLongOrNull(KEY_LAST_SUCCESS_AT),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
            scannedDailyRecordCount = preferences.getInt(KEY_SCANNED_DAILY_RECORD_COUNT, 0),
            scannedMealCount = preferences.getInt(KEY_SCANNED_MEAL_COUNT, 0),
            scannedFoodEntryCount = preferences.getInt(KEY_SCANNED_FOOD_ENTRY_COUNT, 0),
            scannedWeightRecordCount = preferences.getInt(KEY_SCANNED_WEIGHT_RECORD_COUNT, 0),
            enqueuedTaskCount = preferences.getInt(KEY_ENQUEUED_TASK_COUNT, 0),
            skippedDuplicateCount = preferences.getInt(KEY_SKIPPED_DUPLICATE_COUNT, 0),
            schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, CURRENT_INITIAL_BACKFILL_VERSION)
        )
    }

    fun markRunning(startedAt: Long) {
        preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.RUNNING.value)
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_LAST_ATTEMPT_AT, startedAt)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_INITIAL_BACKFILL_VERSION)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markCompleted(completedAt: Long, stats: BackfillStats) {
        preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.COMPLETED.value)
            .putLong(KEY_COMPLETED_AT, completedAt)
            .putLong(KEY_LAST_SUCCESS_AT, completedAt)
            .putLong(KEY_LAST_ATTEMPT_AT, completedAt)
            .putInt(KEY_SCANNED_DAILY_RECORD_COUNT, stats.scannedDailyRecordCount)
            .putInt(KEY_SCANNED_MEAL_COUNT, stats.scannedMealCount)
            .putInt(KEY_SCANNED_FOOD_ENTRY_COUNT, stats.scannedFoodEntryCount)
            .putInt(KEY_SCANNED_WEIGHT_RECORD_COUNT, stats.scannedWeightRecordCount)
            .putInt(KEY_ENQUEUED_TASK_COUNT, stats.enqueuedCount)
            .putInt(KEY_SKIPPED_DUPLICATE_COUNT, stats.skippedAlreadyQueuedCount)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_INITIAL_BACKFILL_VERSION)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markRetryableFailure(error: String, stats: BackfillStats? = null, failedAt: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.FAILED_RETRYABLE.value)
            .putLong(KEY_LAST_ATTEMPT_AT, failedAt)
            .putString(KEY_LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_INITIAL_BACKFILL_VERSION)
        stats?.let { putStats(editor, it) }
        editor.apply()
    }

    fun markFatalFailure(error: String, stats: BackfillStats? = null, failedAt: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
            .putString(KEY_STATUS, BackfillStatus.FAILED_FATAL.value)
            .putLong(KEY_LAST_ATTEMPT_AT, failedAt)
            .putString(KEY_LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .putInt(KEY_SCHEMA_VERSION, CURRENT_INITIAL_BACKFILL_VERSION)
        stats?.let { putStats(editor, it) }
        editor.apply()
    }

    fun recordError(error: String) {
        markRetryableFailure(error)
    }

    fun resetForRemoteUserChange() {
        preferences.edit().clear().commit()
    }

    private fun putStats(editor: android.content.SharedPreferences.Editor, stats: BackfillStats) {
        editor
            .putInt(KEY_SCANNED_DAILY_RECORD_COUNT, stats.scannedDailyRecordCount)
            .putInt(KEY_SCANNED_MEAL_COUNT, stats.scannedMealCount)
            .putInt(KEY_SCANNED_FOOD_ENTRY_COUNT, stats.scannedFoodEntryCount)
            .putInt(KEY_SCANNED_WEIGHT_RECORD_COUNT, stats.scannedWeightRecordCount)
            .putInt(KEY_ENQUEUED_TASK_COUNT, stats.enqueuedCount)
            .putInt(KEY_SKIPPED_DUPLICATE_COUNT, stats.skippedAlreadyQueuedCount)
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? {
        if (!contains(key)) return null
        return getLong(key, 0L).takeIf { it > 0L }
    }

    companion object {
        const val CURRENT_INITIAL_BACKFILL_VERSION = 1

        private const val MAX_ERROR_LENGTH = 180
        private const val KEY_STATUS = "status"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SCANNED_DAILY_RECORD_COUNT = "scanned_daily_record_count"
        private const val KEY_SCANNED_MEAL_COUNT = "scanned_meal_count"
        private const val KEY_SCANNED_FOOD_ENTRY_COUNT = "scanned_food_entry_count"
        private const val KEY_SCANNED_WEIGHT_RECORD_COUNT = "scanned_weight_record_count"
        private const val KEY_ENQUEUED_TASK_COUNT = "enqueued_task_count"
        private const val KEY_SKIPPED_DUPLICATE_COUNT = "skipped_duplicate_count"
        private const val KEY_SCHEMA_VERSION = "schema_version"
    }
}

data class BackfillStateSnapshot(
    val status: BackfillStatus,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val scannedDailyRecordCount: Int,
    val scannedMealCount: Int,
    val scannedFoodEntryCount: Int,
    val scannedWeightRecordCount: Int,
    val enqueuedTaskCount: Int,
    val skippedDuplicateCount: Int,
    val schemaVersion: Int
)

enum class BackfillStatus(val value: String) {
    NOT_STARTED("NOT_STARTED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    FAILED_RETRYABLE("FAILED_RETRYABLE"),
    FAILED_FATAL("FAILED_FATAL");

    companion object {
        fun fromValue(value: String?): BackfillStatus {
            return entries.firstOrNull { it.value == value } ?: NOT_STARTED
        }
    }
}
