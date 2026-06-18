package com.example.data.sync

import android.content.Context

class BackfillStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_backfill",
        Context.MODE_PRIVATE
    )

    fun getInitialBackfillVersion(): Int {
        return preferences.getInt(KEY_INITIAL_BACKFILL_VERSION, 0)
    }

    fun markInitialBackfillEnqueued(version: Int, enqueuedAt: Long) {
        preferences.edit()
            .putInt(KEY_INITIAL_BACKFILL_VERSION, version)
            .putLong(KEY_INITIAL_BACKFILL_ENQUEUED_AT, enqueuedAt)
            .apply()
    }

    fun markInitialBackfillCompleted(completedAt: Long) {
        preferences.edit()
            .putLong(KEY_INITIAL_BACKFILL_COMPLETED_AT, completedAt)
            .remove(KEY_LAST_BACKFILL_ERROR)
            .apply()
    }

    fun recordError(error: String) {
        preferences.edit()
            .putString(KEY_LAST_BACKFILL_ERROR, error)
            .apply()
    }

    companion object {
        const val CURRENT_INITIAL_BACKFILL_VERSION = 1

        private const val KEY_INITIAL_BACKFILL_VERSION = "initial_backfill_version"
        private const val KEY_INITIAL_BACKFILL_ENQUEUED_AT = "initial_backfill_enqueued_at"
        private const val KEY_INITIAL_BACKFILL_COMPLETED_AT = "initial_backfill_completed_at"
        private const val KEY_LAST_BACKFILL_ERROR = "last_backfill_error"
    }
}
