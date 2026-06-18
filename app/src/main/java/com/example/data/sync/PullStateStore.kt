package com.example.data.sync

import android.content.Context
import android.util.Log

class PullStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): PullState {
        return PullState(
            status = PullStatus.fromValue(preferences.getString(KEY_STATUS, PullStatus.NOT_STARTED.value)),
            startedAt = preferences.readLongOrNull(KEY_STARTED_AT),
            completedAt = preferences.readLongOrNull(KEY_COMPLETED_AT),
            lastAttemptAt = preferences.readLongOrNull(KEY_LAST_ATTEMPT_AT),
            lastSuccessAt = preferences.readLongOrNull(KEY_LAST_SUCCESS_AT),
            lastFailureAt = preferences.readLongOrNull(KEY_LAST_FAILURE_AT),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
            dailyRecordsCursorUpdatedAt = preferences.readLongOrNull(KEY_DAILY_CURSOR),
            mealsCursorUpdatedAt = preferences.readLongOrNull(KEY_MEALS_CURSOR),
            foodEntriesCursorUpdatedAt = preferences.readLongOrNull(KEY_FOOD_CURSOR),
            weightRecordsCursorUpdatedAt = preferences.readLongOrNull(KEY_WEIGHT_CURSOR),
            pulledDailyRecordCount = preferences.getInt(KEY_PULLED_DAILY, 0),
            pulledMealCount = preferences.getInt(KEY_PULLED_MEALS, 0),
            pulledFoodEntryCount = preferences.getInt(KEY_PULLED_FOOD, 0),
            pulledWeightRecordCount = preferences.getInt(KEY_PULLED_WEIGHT, 0),
            appliedCount = preferences.getInt(KEY_APPLIED, 0),
            skippedCount = preferences.getInt(KEY_SKIPPED, 0),
            conflictCount = preferences.getInt(KEY_CONFLICT, 0),
            schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        )
    }

    fun markRunning(startedAt: Long = System.currentTimeMillis()) {
        Log.d("DayZeroPull", "state running")
        preferences.edit()
            .putString(KEY_STATUS, PullStatus.RUNNING.value)
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_LAST_ATTEMPT_AT, startedAt)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markCompleted(completedAt: Long = System.currentTimeMillis(), stats: PullStats) {
        Log.d("DayZeroPull", "state completed applied=${stats.appliedCount} conflict=${stats.conflictCount}")
        preferences.edit()
            .putString(KEY_STATUS, PullStatus.COMPLETED.value)
            .putLong(KEY_COMPLETED_AT, completedAt)
            .putLong(KEY_LAST_SUCCESS_AT, completedAt)
            .putInt(KEY_PULLED_DAILY, stats.pulledDailyRecordCount)
            .putInt(KEY_PULLED_MEALS, stats.pulledMealCount)
            .putInt(KEY_PULLED_FOOD, stats.pulledFoodEntryCount)
            .putInt(KEY_PULLED_WEIGHT, stats.pulledWeightRecordCount)
            .putInt(KEY_APPLIED, stats.appliedCount)
            .putInt(KEY_SKIPPED, stats.skippedCount)
            .putInt(KEY_CONFLICT, stats.conflictCount)
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markRetryableFailure(error: String, failedAt: Long = System.currentTimeMillis(), stats: PullStats? = null) {
        Log.e("DayZeroPull", "state retryable error reason=$error")
        preferences.edit()
            .putString(KEY_STATUS, PullStatus.FAILED_RETRYABLE.value)
            .putLong(KEY_LAST_FAILURE_AT, failedAt)
            .putString(KEY_LAST_ERROR, error)
            .applyStats(stats)
            .apply()
    }

    fun markFatalFailure(error: String, failedAt: Long = System.currentTimeMillis(), stats: PullStats? = null) {
        Log.e("DayZeroPull", "state fatal error reason=$error")
        preferences.edit()
            .putString(KEY_STATUS, PullStatus.FAILED_FATAL.value)
            .putLong(KEY_LAST_FAILURE_AT, failedAt)
            .putString(KEY_LAST_ERROR, error)
            .applyStats(stats)
            .apply()
    }

    fun updateCursors(
        dailyRecordsCursorUpdatedAt: Long?,
        mealsCursorUpdatedAt: Long?,
        foodEntriesCursorUpdatedAt: Long?,
        weightRecordsCursorUpdatedAt: Long?
    ) {
        preferences.edit()
            .putLongOrRemove(KEY_DAILY_CURSOR, dailyRecordsCursorUpdatedAt)
            .putLongOrRemove(KEY_MEALS_CURSOR, mealsCursorUpdatedAt)
            .putLongOrRemove(KEY_FOOD_CURSOR, foodEntriesCursorUpdatedAt)
            .putLongOrRemove(KEY_WEIGHT_CURSOR, weightRecordsCursorUpdatedAt)
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.applyStats(stats: PullStats?): android.content.SharedPreferences.Editor {
        if (stats == null) return this
        return putInt(KEY_PULLED_DAILY, stats.pulledDailyRecordCount)
            .putInt(KEY_PULLED_MEALS, stats.pulledMealCount)
            .putInt(KEY_PULLED_FOOD, stats.pulledFoodEntryCount)
            .putInt(KEY_PULLED_WEIGHT, stats.pulledWeightRecordCount)
            .putInt(KEY_APPLIED, stats.appliedCount)
            .putInt(KEY_SKIPPED, stats.skippedCount)
            .putInt(KEY_CONFLICT, stats.conflictCount)
    }

    private fun android.content.SharedPreferences.readLongOrNull(key: String): Long? {
        return if (contains(key)) getLong(key, 0L) else null
    }

    private fun android.content.SharedPreferences.Editor.putLongOrRemove(
        key: String,
        value: Long?
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putLong(key, value)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val PREFS_NAME = "dayzero_pull"
        private const val KEY_STATUS = "status"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_COMPLETED_AT = "completedAt"
        private const val KEY_LAST_ATTEMPT_AT = "lastAttemptAt"
        private const val KEY_LAST_SUCCESS_AT = "lastSuccessAt"
        private const val KEY_LAST_FAILURE_AT = "lastFailureAt"
        private const val KEY_LAST_ERROR = "lastError"
        private const val KEY_DAILY_CURSOR = "dailyRecordsCursorUpdatedAt"
        private const val KEY_MEALS_CURSOR = "mealsCursorUpdatedAt"
        private const val KEY_FOOD_CURSOR = "foodEntriesCursorUpdatedAt"
        private const val KEY_WEIGHT_CURSOR = "weightRecordsCursorUpdatedAt"
        private const val KEY_PULLED_DAILY = "pulledDailyRecordCount"
        private const val KEY_PULLED_MEALS = "pulledMealCount"
        private const val KEY_PULLED_FOOD = "pulledFoodEntryCount"
        private const val KEY_PULLED_WEIGHT = "pulledWeightRecordCount"
        private const val KEY_APPLIED = "appliedCount"
        private const val KEY_SKIPPED = "skippedCount"
        private const val KEY_CONFLICT = "conflictCount"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
    }
}

data class PullState(
    val status: PullStatus,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastError: String?,
    val dailyRecordsCursorUpdatedAt: Long?,
    val mealsCursorUpdatedAt: Long?,
    val foodEntriesCursorUpdatedAt: Long?,
    val weightRecordsCursorUpdatedAt: Long?,
    val pulledDailyRecordCount: Int,
    val pulledMealCount: Int,
    val pulledFoodEntryCount: Int,
    val pulledWeightRecordCount: Int,
    val appliedCount: Int,
    val skippedCount: Int,
    val conflictCount: Int,
    val schemaVersion: Int
)

data class PullStats(
    val pulledDailyRecordCount: Int = 0,
    val pulledMealCount: Int = 0,
    val pulledFoodEntryCount: Int = 0,
    val pulledWeightRecordCount: Int = 0,
    val appliedCount: Int = 0,
    val skippedCount: Int = 0,
    val conflictCount: Int = 0,
    val skippedMissingParentCount: Int = 0
)

enum class PullStatus(val value: String) {
    NOT_STARTED("NOT_STARTED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    FAILED_RETRYABLE("FAILED_RETRYABLE"),
    FAILED_FATAL("FAILED_FATAL");

    companion object {
        fun fromValue(value: String?): PullStatus {
            return entries.firstOrNull { it.value == value } ?: NOT_STARTED
        }
    }
}
