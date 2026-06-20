package com.example.data.sync

interface SyncCoordinator {
    suspend fun runOnce()

    suspend fun syncPending()
}
