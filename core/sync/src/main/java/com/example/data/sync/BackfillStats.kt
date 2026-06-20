package com.example.data.sync

data class BackfillStats(
    val scannedDailyRecordCount: Int = 0,
    val scannedMealCount: Int = 0,
    val scannedFoodEntryCount: Int = 0,
    val scannedWeightRecordCount: Int = 0,
    val enqueuedCount: Int = 0,
    val skippedAlreadyQueuedCount: Int = 0,
    val skippedAlreadySyncedCount: Int = 0,
    val errorCount: Int = 0
)
