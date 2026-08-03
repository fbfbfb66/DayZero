package com.goings.dayzero.data.sync

import com.goings.dayzero.domain.identity.AppIdentity

interface RemoteSyncGateway {
    suspend fun canSync(identity: AppIdentity): Boolean

    suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertChatConversation(payload: SyncPayload): RemoteSyncResult
    suspend fun upsertChatMessage(payload: SyncPayload): RemoteSyncResult
    suspend fun submitConversationTitleJob(payload: SyncPayload): RemoteSyncResult =
        RemoteSyncResult.Skipped("title_jobs_unsupported")

    /** Uploads master/thumbnail bytes to Storage then upserts the media_assets metadata row. */
    suspend fun upsertMediaAsset(payload: SyncPayload): RemoteSyncResult

    /** Downloads master/thumbnail bytes from Storage into local files for a pulled asset. */
    suspend fun downloadMediaAsset(payload: SyncPayload): RemoteSyncResult
}
