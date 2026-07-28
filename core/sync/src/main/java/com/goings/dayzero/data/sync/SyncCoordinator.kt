package com.goings.dayzero.data.sync

interface SyncCoordinator {
    suspend fun runOnce()

    suspend fun syncPending()
}
