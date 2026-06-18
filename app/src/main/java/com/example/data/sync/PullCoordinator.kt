package com.example.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.DailyRecordEntity
import com.example.data.sync.remote.DailyRecordRemoteDto
import com.example.data.sync.remote.FoodEntryRemoteDto
import com.example.data.sync.remote.MealRemoteDto
import com.example.data.sync.remote.WeightRecordRemoteDto
import com.example.domain.identity.CurrentIdentityProvider
import com.example.domain.model.FoodEntry
import com.example.domain.model.MealEntry
import com.example.domain.model.MealSortPolicy
import com.example.domain.model.MealType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PullMode {
    INITIAL_RESTORE,
    INCREMENTAL,
    MANUAL_RESTORE_CHECK
}

class PullCoordinator(
    private val database: DayZeroDatabase,
    private val remotePullGateway: RemotePullGateway,
    private val identityProvider: CurrentIdentityProvider,
    private val stateStore: PullStateStore,
    private val batchLimit: Int = DEFAULT_BATCH_LIMIT
) {
    private val dailyRecordDao = database.dailyRecordDao()
    private val syncQueueDao = database.syncQueueDao()
    private val mutex = Mutex()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, MealEntry::class.java)
    private val mealsAdapter = moshi.adapter<List<MealEntry>>(listType)

    suspend fun runInitialRestoreIfLocalEmpty(): PullStats {
        if (dailyRecordDao.countBusinessRecordsIncludingDeleted() > 0) {
            Log.d("DayZeroPull", "initial restore skipped local not empty")
            return PullStats(skippedCount = 1)
        }
        return runOnce(PullMode.INITIAL_RESTORE)
    }

    suspend fun runIncrementalPull(): PullStats = runOnce(PullMode.INCREMENTAL)

    suspend fun runOnce(mode: PullMode): PullStats {
        if (mutex.isLocked) {
            Log.d("DayZeroPull", "run skipped already running mode=$mode")
            return PullStats(skippedCount = 1)
        }

        return mutex.withLock {
            runOnceLocked(mode)
        }
    }

    private suspend fun runOnceLocked(mode: PullMode): PullStats {
        Log.d("DayZeroPull", "run start mode=$mode")
        val identity = identityProvider.currentIdentity()
        if (!remotePullGateway.canPull(identity)) {
            Log.d("DayZeroPull", "run skipped waiting_for_auth")
            return PullStats(skippedCount = 1)
        }

        val localCount = dailyRecordDao.countBusinessRecordsIncludingDeleted()
        val effectiveMode = if (mode == PullMode.MANUAL_RESTORE_CHECK && localCount == 0) {
            PullMode.INITIAL_RESTORE
        } else {
            mode
        }
        if (effectiveMode == PullMode.INITIAL_RESTORE && localCount > 0) {
            Log.d("DayZeroPull", "initial restore skipped local not empty")
            return PullStats(skippedCount = 1)
        }

        val startedAt = System.currentTimeMillis()
        stateStore.markRunning(startedAt)
        val state = stateStore.snapshot()
        val sinceDaily = if (effectiveMode == PullMode.INITIAL_RESTORE) null else state.dailyRecordsCursorUpdatedAt
        val sinceMeals = if (effectiveMode == PullMode.INITIAL_RESTORE) null else state.mealsCursorUpdatedAt
        val sinceFood = if (effectiveMode == PullMode.INITIAL_RESTORE) null else state.foodEntriesCursorUpdatedAt
        val sinceWeight = if (effectiveMode == PullMode.INITIAL_RESTORE) null else state.weightRecordsCursorUpdatedAt

        return try {
            val dailyRecords = pullAll("daily_records", sinceDaily) { since, limit ->
                remotePullGateway.pullDailyRecords(since, limit)
            }
            val meals = pullAll("meals", sinceMeals) { since, limit ->
                remotePullGateway.pullMeals(since, limit)
            }
            val foods = pullAll("food_entries", sinceFood) { since, limit ->
                remotePullGateway.pullFoodEntries(since, limit)
            }
            val weights = pullAll("weight_records", sinceWeight) { since, limit ->
                remotePullGateway.pullWeightRecords(since, limit)
            }

            var stats = PullStats(
                pulledDailyRecordCount = dailyRecords.size,
                pulledMealCount = meals.size,
                pulledFoodEntryCount = foods.size,
                pulledWeightRecordCount = weights.size
            )
            val mealsByRecord = meals.filter { it.deletedAt == null }.groupBy { it.dailyRecordClientId }
            val foodsByMeal = foods.filter { it.deletedAt == null }.groupBy { it.mealClientId }
            val weightsByRecord = weights.filter { it.deletedAt == null && !it.dailyRecordClientId.isNullOrBlank() }
                .associateBy { it.dailyRecordClientId }

            database.withTransaction {
                dailyRecords.forEach { remote ->
                    stats = applyRemoteDailyRecord(
                        remote = remote,
                        meals = mealsByRecord[remote.clientId].orEmpty(),
                        foodsByMeal = foodsByMeal,
                        weight = weightsByRecord[remote.clientId],
                        ownerLocalId = identity.localOwnerId,
                        currentStats = stats
                    )
                }

                val knownDailyClientIds = dailyRecords.map { it.clientId }.toSet()
                val knownMealClientIds = meals.map { it.clientId }.toSet()
                val skippedChildren = meals.count { it.dailyRecordClientId !in knownDailyClientIds } +
                    foods.count { it.dailyRecordClientId !in knownDailyClientIds && it.mealClientId !in knownMealClientIds }
                if (skippedChildren > 0) {
                    Log.d("DayZeroPull", "skipped missing parent count=$skippedChildren")
                    stats = stats.copy(
                        skippedCount = stats.skippedCount + skippedChildren,
                        skippedMissingParentCount = stats.skippedMissingParentCount + skippedChildren
                    )
                }
            }

            stateStore.updateCursors(
                dailyRecordsCursorUpdatedAt = maxOfCursor(state.dailyRecordsCursorUpdatedAt, dailyRecords.maxOfOrNull { it.updatedAt }),
                mealsCursorUpdatedAt = maxOfCursor(state.mealsCursorUpdatedAt, meals.maxOfOrNull { it.updatedAt }),
                foodEntriesCursorUpdatedAt = maxOfCursor(state.foodEntriesCursorUpdatedAt, foods.maxOfOrNull { it.updatedAt }),
                weightRecordsCursorUpdatedAt = maxOfCursor(state.weightRecordsCursorUpdatedAt, weights.maxOfOrNull { it.updatedAt })
            )
            stateStore.markCompleted(System.currentTimeMillis(), stats)
            Log.d("DayZeroPull", "run success applied=${stats.appliedCount} skipped=${stats.skippedCount} conflict=${stats.conflictCount}")
            stats
        } catch (error: PullRunException) {
            if (error.fatal) {
                stateStore.markFatalFailure(error.message ?: error::class.java.simpleName)
            } else {
                stateStore.markRetryableFailure(error.message ?: error::class.java.simpleName)
            }
            PullStats(skippedCount = 1)
        } catch (error: Exception) {
            Log.e("DayZeroPull", "run retryable error reason=${error::class.java.simpleName}", error)
            stateStore.markRetryableFailure(error.message ?: error::class.java.simpleName)
            PullStats(skippedCount = 1)
        }
    }

    private suspend fun applyRemoteDailyRecord(
        remote: DailyRecordRemoteDto,
        meals: List<MealRemoteDto>,
        foodsByMeal: Map<String, List<FoodEntryRemoteDto>>,
        weight: WeightRecordRemoteDto?,
        ownerLocalId: String,
        currentStats: PullStats
    ): PullStats {
        val local = dailyRecordDao.getRecordByClientIdIncludingDeleted(remote.clientId)
        if (local != null && isLocalDirty(local, ownerLocalId)) {
            Log.d("DayZeroPull", "skip dirty local clientId=${remote.clientId}")
            return currentStats.copy(conflictCount = currentStats.conflictCount + 1)
        }

        if (remote.deletedAt != null) {
            if (local != null) {
                dailyRecordDao.markDeletedFromRemote(
                    clientId = remote.clientId,
                    deletedAt = remote.deletedAt,
                    updatedAt = remote.updatedAt,
                    lastSyncedAt = remote.updatedAt,
                    remoteId = remote.clientId
                )
                return currentStats.copy(appliedCount = currentStats.appliedCount + 1)
            }
            return currentStats.copy(skippedCount = currentStats.skippedCount + 1)
        }

        if (local != null && local.syncStatus == DayZeroSyncConstants.STATUS_SYNCED && local.updatedAt >= remote.updatedAt) {
            return currentStats.copy(skippedCount = currentStats.skippedCount + 1)
        }

        val entity = DailyRecordEntity(
            id = local?.id ?: remote.clientId,
            date = remote.recordDate,
            status = remote.status,
            mealsJson = mealsAdapter.toJson(buildMeals(meals, foodsByMeal)),
            weightKg = weight?.weightKg ?: remote.weightKg,
            aiSummary = remote.aiSummary,
            createdAt = local?.createdAt ?: remote.createdAt,
            updatedAt = remote.updatedAt,
            clientId = remote.clientId,
            remoteId = remote.clientId,
            syncStatus = DayZeroSyncConstants.STATUS_SYNCED,
            syncVersion = remote.updatedAt,
            deletedAt = null,
            lastSyncedAt = remote.updatedAt,
            ownerLocalId = ownerLocalId
        )
        dailyRecordDao.upsertRecord(entity)
        return currentStats.copy(appliedCount = currentStats.appliedCount + 1)
    }

    private suspend fun isLocalDirty(local: DailyRecordEntity, ownerLocalId: String): Boolean {
        if (local.syncStatus != DayZeroSyncConstants.STATUS_SYNCED) return true
        if (local.lastSyncedAt == null) return true
        if (local.updatedAt > local.lastSyncedAt) return true
        return syncQueueDao.countActiveTasksForEntity(
            ownerLocalId = ownerLocalId,
            entityType = "daily_record",
            entityLocalId = local.clientId
        ) > 0
    }

    private fun buildMeals(
        meals: List<MealRemoteDto>,
        foodsByMeal: Map<String, List<FoodEntryRemoteDto>>
    ): List<MealEntry> {
        return MealSortPolicy.sortMeals(
            meals.sortedBy { it.createdAt }.map { meal ->
                MealEntry(
                    id = meal.clientId,
                    mealType = parseMealType(meal.mealType),
                    hasPhoto = meal.hasPhoto,
                    foods = foodsByMeal[meal.clientId].orEmpty()
                        .sortedBy { it.createdAt }
                        .map { food ->
                            FoodEntry(
                                id = food.clientId,
                                name = food.name,
                                quantity = food.quantity,
                                estimatedCalories = food.estimatedCalories,
                                confidence = food.confidence
                            )
                        }
                )
            }
        )
    }

    private fun parseMealType(value: String): MealType {
        return runCatching { MealType.valueOf(value) }.getOrElse {
            when (value.lowercase()) {
                "breakfast" -> MealType.Breakfast
                "lunch" -> MealType.Lunch
                "dinner" -> MealType.Dinner
                else -> MealType.Snack
            }
        }
    }

    private suspend fun <T> pullAll(
        label: String,
        initialSince: Long?,
        pullPage: suspend (Long?, Int) -> RemotePullResult<T>
    ): List<T> {
        val items = mutableListOf<T>()
        var since = initialSince
        var page = 0
        while (page < MAX_PAGES_PER_RUN) {
            when (val result = pullPage(since, batchLimit)) {
                is RemotePullResult.Success -> {
                    items += result.items
                    if (!result.hasMore || result.items.isEmpty()) break
                    val nextCursor = result.items.maxRemoteUpdatedAt()
                    if (nextCursor == null || nextCursor == since) break
                    since = nextCursor
                    page += 1
                }

                is RemotePullResult.RetryableFailure -> throw PullRunException("pull_retryable:$label:${result.message}", fatal = false)
                is RemotePullResult.FatalFailure -> throw PullRunException("pull_fatal:$label:${result.message}", fatal = true)
                is RemotePullResult.Skipped -> throw PullRunException("pull_skipped:$label:${result.reason}", fatal = false)
            }
        }
        return items
    }

    private fun <T> List<T>.maxRemoteUpdatedAt(): Long? {
        return mapNotNull {
            when (it) {
                is DailyRecordRemoteDto -> it.updatedAt
                is MealRemoteDto -> it.updatedAt
                is FoodEntryRemoteDto -> it.updatedAt
                is WeightRecordRemoteDto -> it.updatedAt
                else -> null
            }
        }.maxOrNull()
    }

    private fun maxOfCursor(current: Long?, next: Long?): Long? {
        return listOfNotNull(current, next).maxOrNull()
    }

    private class PullRunException(message: String, val fatal: Boolean) : Exception(message)

    private companion object {
        private const val DEFAULT_BATCH_LIMIT = 100
        private const val MAX_PAGES_PER_RUN = 5
    }
}
