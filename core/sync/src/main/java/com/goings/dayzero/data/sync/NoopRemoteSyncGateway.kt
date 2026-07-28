package com.goings.dayzero.data.sync

import com.goings.dayzero.domain.identity.AppIdentity

class NoopRemoteSyncGateway : RemoteSyncGateway {
    private var currentIdentity: AppIdentity? = null

    override suspend fun canSync(identity: AppIdentity): Boolean {
        currentIdentity = identity
        return identity.canRemoteSync
    }

    override suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertChatConversation(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertChatMessage(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun upsertMediaAsset(payload: SyncPayload): RemoteSyncResult = noopResult()

    override suspend fun downloadMediaAsset(payload: SyncPayload): RemoteSyncResult = noopResult()

    private fun noopResult(): RemoteSyncResult {
        val identity = currentIdentity
        return if (identity?.canRemoteSync == true) {
            RemoteSyncResult.Skipped("noop_gateway")
        } else {
            RemoteSyncResult.Skipped("waiting_for_auth")
        }
    }
}
