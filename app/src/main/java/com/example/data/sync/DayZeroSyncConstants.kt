package com.example.data.sync

object DayZeroSyncConstants {
    const val LOG_PREFIX = "DayZeroSync"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_PROCESSING = "PROCESSING"
    const val STATUS_DONE = "DONE"
    const val STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val STATUS_FAILED_FATAL = "FAILED_FATAL"
    const val STATUS_WAITING_FOR_AUTH = "WAITING_FOR_AUTH"

    const val OP_UPSERT_DAILY_RECORD = "UPSERT_DAILY_RECORD"
    const val OP_UPSERT_MEAL = "UPSERT_MEAL"
    const val OP_UPSERT_FOOD_ENTRY = "UPSERT_FOOD_ENTRY"
    const val OP_UPSERT_WEIGHT_RECORD = "UPSERT_WEIGHT_RECORD"
    const val OP_SOFT_DELETE_RECORD = "SOFT_DELETE_RECORD"

    const val WAITING_FOR_AUTH_RETRY_DELAY_MS = 15 * 60 * 1000L
}
