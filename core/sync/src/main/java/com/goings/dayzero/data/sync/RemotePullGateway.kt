package com.goings.dayzero.data.sync

import com.goings.dayzero.data.sync.remote.DailyRecordRemoteDto
import com.goings.dayzero.data.sync.remote.FoodEntryRemoteDto
import com.goings.dayzero.data.sync.remote.MealRemoteDto
import com.goings.dayzero.data.sync.remote.WeightRecordRemoteDto
import com.goings.dayzero.domain.identity.AppIdentity

interface RemotePullGateway {
    suspend fun canPull(identity: AppIdentity): Boolean
    suspend fun pullDailyRecords(sinceUpdatedAt: Long?, limit: Int): RemotePullResult<DailyRecordRemoteDto>
    suspend fun pullMeals(sinceUpdatedAt: Long?, limit: Int): RemotePullResult<MealRemoteDto>
    suspend fun pullFoodEntries(sinceUpdatedAt: Long?, limit: Int): RemotePullResult<FoodEntryRemoteDto>
    suspend fun pullWeightRecords(sinceUpdatedAt: Long?, limit: Int): RemotePullResult<WeightRecordRemoteDto>
}

sealed class RemotePullResult<out T> {
    data class Success<T>(val items: List<T>, val hasMore: Boolean) : RemotePullResult<T>()
    data class RetryableFailure(val message: String) : RemotePullResult<Nothing>()
    data class FatalFailure(val message: String) : RemotePullResult<Nothing>()
    data class Skipped(val reason: String) : RemotePullResult<Nothing>()
}

class NoopRemotePullGateway : RemotePullGateway {
    override suspend fun canPull(identity: AppIdentity): Boolean = identity.canRemoteSync

    override suspend fun pullDailyRecords(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<DailyRecordRemoteDto> = RemotePullResult.Skipped("remote_disabled")

    override suspend fun pullMeals(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<MealRemoteDto> = RemotePullResult.Skipped("remote_disabled")

    override suspend fun pullFoodEntries(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<FoodEntryRemoteDto> = RemotePullResult.Skipped("remote_disabled")

    override suspend fun pullWeightRecords(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<WeightRecordRemoteDto> = RemotePullResult.Skipped("remote_disabled")
}
