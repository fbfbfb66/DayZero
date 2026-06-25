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

        val dailyResult = pullAll("daily_records", sinceDaily) { since, limit ->
                remotePullGateway.pullDailyRecords(since, limit)
            }
        val mealsResult = pullAll("meals", sinceMeals) { since, limit ->
                remotePullGateway.pullMeals(since, limit)
            }
        val foodsResult = pullAll("food_entries", sinceFood) { since, limit ->
                remotePullGateway.pullFoodEntries(since, limit)
            }
        val weightsResult = pullAll("weight_records", sinceWeight) { since, limit ->
                remotePullGateway.pullWeightRecords(since, limit)
            }

        var stats = PullStats(
            pulledDailyRecordCount = dailyResult.items.size,
            pulledMealCount = mealsResult.items.size,
            pulledFoodEntryCount = foodsResult.items.size,
            pulledWeightRecordCount = weightsResult.items.size,
            partialFailureCount = listOf(dailyResult, mealsResult, foodsResult, weightsResult).count { !it.success }
        )
        val firstFailure = listOf(dailyResult, mealsResult, foodsResult, weightsResult)
            .firstOrNull { !it.success }
            ?.error
        val hasFatalFailure = listOf(dailyResult, mealsResult, foodsResult, weightsResult).any { it.fatal }
        val childTablesHealthy = mealsResult.success && foodsResult.success && weightsResult.success
        val dailyRecords = if (dailyResult.success && childTablesHealthy) dailyResult.items else emptyList()
        val meals = if (childTablesHealthy) mealsResult.items else emptyList()
        val foods = if (childTablesHealthy) foodsResult.items else emptyList()
        val weights = if (childTablesHealthy) weightsResult.items else emptyList()

        return try {
            val mealsByRecord = meals.filter { it.deletedAt == null }.groupBy { it.dailyRecordClientId }
            val foodsByMeal = foods.filter { it.deletedAt == null }.groupBy { it.mealClientId }
            val weightsByDate = weights.filter { it.deletedAt == null }
                .associateBy { it.localDate }

            database.withTransaction {
                dailyRecords.forEach { remote ->
                    stats = applyRemoteDailyRecord(
                        remote = remote,
                        meals = mealsByRecord[remote.clientId].orEmpty(),
                        foodsByMeal = foodsByMeal,
                        weight = weightsByDate[remote.localDate],
                        ownerLocalId = identity.localOwnerId,
                        currentStats = stats
                    )
                }

                val knownDailyClientIds = dailyRecords.map { it.clientId }.toSet()
                val knownMealClientIds = meals.map { it.clientId }.toSet()
                val skippedChildren = meals.count { it.dailyRecordClientId !in knownDailyClientIds } +
                    foods.count { it.mealClientId !in knownMealClientIds }
                if (skippedChildren > 0) {
                    Log.d("DayZeroPull", "skipped missing parent count=$skippedChildren")
                    stats = stats.copy(
                        skippedCount = stats.skippedCount + skippedChildren,
                        skippedMissingParentCount = stats.skippedMissingParentCount + skippedChildren
                    )
                }
            }

            stateStore.updateCursors(
                dailyRecordsCursorUpdatedAt = if (dailyResult.success && childTablesHealthy) maxOfCursor(state.dailyRecordsCursorUpdatedAt, dailyResult.cursorUpdatedAt) else state.dailyRecordsCursorUpdatedAt,
                mealsCursorUpdatedAt = if (mealsResult.success && childTablesHealthy) maxOfCursor(state.mealsCursorUpdatedAt, mealsResult.cursorUpdatedAt) else state.mealsCursorUpdatedAt,
                foodEntriesCursorUpdatedAt = if (foodsResult.success && childTablesHealthy) maxOfCursor(state.foodEntriesCursorUpdatedAt, foodsResult.cursorUpdatedAt) else state.foodEntriesCursorUpdatedAt,
                weightRecordsCursorUpdatedAt = if (weightsResult.success && childTablesHealthy) maxOfCursor(state.weightRecordsCursorUpdatedAt, weightsResult.cursorUpdatedAt) else state.weightRecordsCursorUpdatedAt
            )
            if (stats.partialFailureCount > 0) {
                if (hasFatalFailure) {
                    stateStore.markFatalFailure(firstFailure ?: "partial_pull_fatal", stats = stats)
                } else {
                    stateStore.markRetryableFailure(firstFailure ?: "partial_pull_failure", stats = stats)
                }
            } else {
                stateStore.markCompleted(System.currentTimeMillis(), stats)
            }
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
            return currentStats.copy(
                conflictCount = currentStats.conflictCount + 1,
                skippedDirtyLocalCount = currentStats.skippedDirtyLocalCount + 1
            )
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
            date = remote.localDate,
            status = "Confirmed",
            mealsJson = mealsAdapter.toJson(buildMeals(meals, foodsByMeal)),
            weightKg = weight?.weightKg,
            aiSummary = remote.note,
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
        val lastSyncedAt = local.lastSyncedAt ?: return true
        if (local.updatedAt > lastSyncedAt) return true
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
            meals.sortedWith(compareBy<MealRemoteDto> { it.displayOrder ?: Int.MAX_VALUE }.thenBy { it.loggedAt ?: it.createdAt }).map { meal ->
                MealEntry(
                    id = meal.clientId,
                    mealType = parseMealType(meal.mealType.orEmpty()),
                    hasPhoto = false,
                    foods = foodsByMeal[meal.clientId].orEmpty()
                        .sortedBy { it.createdAt }
                        .map { food ->
                            FoodEntry(
                                id = food.clientId,
                                name = food.name,
                                quantity = food.amountText ?: food.grams?.let { "${it}g" } ?: "1",
                                estimatedCalories = food.calories?.toInt() ?: 0,
                                confidence = food.confidence?.toString() ?: "unknown",
                                carbohydratesG = food.carbsG,
                                proteinG = food.proteinG,
                                fatG = food.fatG,
                                fiberG = food.fiberG
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
    ): TablePullResult<T> {
        val items = mutableListOf<T>()
        var since = initialSince
        var page = 0
        var cursor: Long? = null
        while (page < MAX_PAGES_PER_RUN) {
            when (val result = pullPage(since, batchLimit)) {
                is RemotePullResult.Success -> {
                    items += result.items
                    cursor = maxOfCursor(cursor, result.items.maxRemoteUpdatedAt())
                    if (!result.hasMore || result.items.isEmpty()) break
                    val nextCursor = result.items.maxRemoteUpdatedAt()
                    if (result.items.hasSingleUpdatedAt()) {
                        Log.e("DayZeroPull", "partial failure same updated_at cursor risk table=$label")
                        return TablePullResult(
                            items = items,
                            cursorUpdatedAt = initialSince,
                            success = false,
                            error = "same_updated_at_cursor_risk:$label"
                        )
                    }
                    if (nextCursor == null || nextCursor == since) {
                        Log.e("DayZeroPull", "partial failure same updated_at cursor risk table=$label")
                        return TablePullResult(
                            items = items,
                            cursorUpdatedAt = initialSince,
                            success = false,
                            error = "same_updated_at_cursor_risk:$label"
                        )
                    }
                    since = nextCursor
                    page += 1
                }

                is RemotePullResult.RetryableFailure -> return TablePullResult(items, cursor, success = false, error = "pull_retryable:$label:${result.message}")
                is RemotePullResult.FatalFailure -> return TablePullResult(items, cursor, success = false, fatal = true, error = "pull_fatal:$label:${result.message}")
                is RemotePullResult.Skipped -> return TablePullResult(items, cursor, success = false, error = "pull_skipped:$label:${result.reason}")
            }
        }
        return TablePullResult(items = items, cursorUpdatedAt = cursor, success = true)
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

    private fun <T> List<T>.hasSingleUpdatedAt(): Boolean {
        val values = mapNotNull {
            when (it) {
                is DailyRecordRemoteDto -> it.updatedAt
                is MealRemoteDto -> it.updatedAt
                is FoodEntryRemoteDto -> it.updatedAt
                is WeightRecordRemoteDto -> it.updatedAt
                else -> null
            }
        }.distinct()
        return size > 1 && values.size == 1
    }

    private fun maxOfCursor(current: Long?, next: Long?): Long? {
        return listOfNotNull(current, next).maxOrNull()
    }

    private class PullRunException(message: String, val fatal: Boolean) : Exception(message)

    private data class TablePullResult<T>(
        val items: List<T>,
        val cursorUpdatedAt: Long?,
        val success: Boolean,
        val fatal: Boolean = false,
        val error: String? = null
    )

    private companion object {
        private const val DEFAULT_BATCH_LIMIT = 100
        private const val MAX_PAGES_PER_RUN = 5
    }
}
