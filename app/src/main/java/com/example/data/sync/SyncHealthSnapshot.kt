package com.example.data.sync

data class SyncHealthSnapshot(
    val pendingCount: Int,
    val processingCount: Int,
    val doneCount: Int,
    val retryableFailureCount: Int,
    val fatalFailureCount: Int,
    val waitingForAuthCount: Int,
    val lastSuccessfulSyncAt: Long?,
    val lastSyncError: String?
)
