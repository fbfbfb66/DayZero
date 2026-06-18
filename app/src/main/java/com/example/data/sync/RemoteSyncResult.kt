package com.example.data.sync

sealed class RemoteSyncResult {
    data object Success : RemoteSyncResult()
    data class RetryableFailure(val message: String) : RemoteSyncResult()
    data class FatalFailure(val message: String) : RemoteSyncResult()
    data class Skipped(val reason: String) : RemoteSyncResult()
}
