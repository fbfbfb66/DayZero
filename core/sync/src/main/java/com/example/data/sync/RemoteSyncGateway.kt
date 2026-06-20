package com.example.data.sync

import com.example.domain.identity.AppIdentity

interface RemoteSyncGateway {
    suspend fun canSync(identity: AppIdentity): Boolean

    suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertChatConversation(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertChatMessage(payload: SyncPayload): RemoteSyncResult
}
