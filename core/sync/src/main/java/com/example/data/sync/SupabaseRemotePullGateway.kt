package com.example.data.sync

import android.util.Log
import com.example.data.identity.SupabaseAuthSessionStatus
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.remote.SupabaseConfig
import com.example.data.sync.remote.DailyRecordRemoteDto
import com.example.data.sync.remote.FoodEntryRemoteDto
import com.example.data.sync.remote.MealRemoteDto
import com.example.data.sync.remote.WeightRecordRemoteDto
import com.example.domain.identity.AppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class SupabaseRemotePullGateway(
    private val okHttpClient: OkHttpClient,
    private val sessionProvider: SupabaseAuthSessionProvider,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : RemotePullGateway {
    override suspend fun canPull(identity: AppIdentity): Boolean {
        return isConfigured && identity.canRemoteSync && !identity.remoteUserId.isNullOrBlank()
    }

    override suspend fun pullDailyRecords(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<DailyRecordRemoteDto> {
        return pullTable(
            tableName = "daily_records",
            sinceUpdatedAt = sinceUpdatedAt,
            limit = limit,
            mapper = ::dailyRecordFromJson
        )
    }

    override suspend fun pullMeals(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<MealRemoteDto> {
        return pullTable(
            tableName = "meals",
            sinceUpdatedAt = sinceUpdatedAt,
            limit = limit,
            mapper = ::mealFromJson
        )
    }

    override suspend fun pullFoodEntries(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<FoodEntryRemoteDto> {
        return pullTable(
            tableName = "food_entries",
            sinceUpdatedAt = sinceUpdatedAt,
            limit = limit,
            mapper = ::foodEntryFromJson
        )
    }

    override suspend fun pullWeightRecords(
        sinceUpdatedAt: Long?,
        limit: Int
    ): RemotePullResult<WeightRecordRemoteDto> {
        return pullTable(
            tableName = "weight_records",
            sinceUpdatedAt = sinceUpdatedAt,
            limit = limit,
            mapper = ::weightRecordFromJson
        )
    }

    private suspend fun <T> pullTable(
        tableName: String,
        sinceUpdatedAt: Long?,
        limit: Int,
        mapper: (JSONObject) -> T
    ): RemotePullResult<T> {
        if (!isConfigured) return RemotePullResult.Skipped("remote_disabled")
        val session = sessionProvider.currentSessionOrNull()
            ?: return sessionUnavailablePullResult()

        val urlBuilder = "${restUrl()}$tableName".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.${session.userId}")
            .addQueryParameter("order", "updated_at.asc")
            .addQueryParameter("limit", limit.toString())
        sinceUpdatedAt?.let {
            urlBuilder.addQueryParameter("updated_at", "gt.${Instant.ofEpochMilli(it)}")
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()

        Log.d("DayZeroRemote", "pull start table=$tableName limit=$limit since=${sinceUpdatedAt ?: "none"}")
        return withContext(Dispatchers.IO) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val array = JSONArray(body)
                            val items = buildList {
                                for (index in 0 until array.length()) {
                                    val item = array.getJSONObject(index)
                                    val clientId = item.optString("client_id")
                                    if (clientId.isBlank()) {
                                        Log.e("DayZeroRemote", "pull skipped row table=$tableName reason=missing_client_id")
                                        continue
                                    }
                                    runCatching {
                                        mapper(item)
                                    }.onSuccess { mapped ->
                                        add(mapped)
                                    }.onFailure { error ->
                                        Log.e(
                                            "DayZeroRemote",
                                            "pull skipped row table=$tableName clientId=$clientId reason=${error::class.java.simpleName}"
                                        )
                                    }
                                }
                            }
                            Log.d("DayZeroRemote", "pull success table=$tableName count=${items.size}")
                            RemotePullResult.Success(items = items, hasMore = items.size >= limit)
                        }

                        response.code in RETRYABLE_STATUS_CODES -> {
                            Log.e("DayZeroRemote", "pull retryable table=$tableName reason=http_${response.code}")
                            RemotePullResult.RetryableFailure("http_${response.code}")
                        }

                        response.code in FATAL_STATUS_CODES -> {
                            Log.e("DayZeroRemote", "pull fatal table=$tableName reason=http_${response.code}")
                            RemotePullResult.FatalFailure("http_${response.code}")
                        }

                        else -> {
                            Log.e("DayZeroRemote", "pull retryable table=$tableName reason=http_${response.code}")
                            RemotePullResult.RetryableFailure("http_${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DayZeroRemote", "pull retryable table=$tableName reason=${e::class.java.simpleName}", e)
                RemotePullResult.RetryableFailure(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun dailyRecordFromJson(json: JSONObject): DailyRecordRemoteDto {
        // Temporary legacy compatibility: read old record_date rows until real data is aligned.
        return DailyRecordRemoteDto(
            userId = json.optString("user_id"),
            clientId = json.getString("client_id"),
            localDate = json.optString("local_date").ifBlank { json.optString("record_date") },
            timezone = json.optNullableString("timezone"),
            note = json.optNullableString("note") ?: json.optNullableString("ai_summary"),
            createdAt = parseRemoteTime(json.optString("created_at")),
            updatedAt = parseRemoteTime(json.optString("updated_at")),
            deletedAt = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun mealFromJson(json: JSONObject): MealRemoteDto {
        return MealRemoteDto(
            userId = json.optString("user_id"),
            clientId = json.getString("client_id"),
            dailyRecordClientId = json.optString("daily_record_client_id"),
            mealType = json.optNullableString("meal_type"),
            loggedAt = parseNullableRemoteTime(json.optNullableString("logged_at")),
            displayOrder = json.optNullableInt("display_order"),
            createdAt = parseRemoteTime(json.optString("created_at")),
            updatedAt = parseRemoteTime(json.optString("updated_at")),
            deletedAt = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun foodEntryFromJson(json: JSONObject): FoodEntryRemoteDto {
        return FoodEntryRemoteDto(
            userId = json.optString("user_id"),
            clientId = json.getString("client_id"),
            mealClientId = json.optString("meal_client_id"),
            name = json.optString("name").ifBlank { "unknown" },
            amountText = json.optNullableString("amount_text") ?: json.optNullableString("quantity"),
            grams = json.optNullableDouble("grams")?.toFloat(),
            calories = (json.optNullableDouble("calories") ?: json.optNullableDouble("estimated_calories"))?.toFloat(),
            proteinG = json.optNullableDouble("protein_g")?.toFloat(),
            carbsG = json.optNullableDouble("carbs_g")?.toFloat(),
            fatG = json.optNullableDouble("fat_g")?.toFloat(),
            confidence = json.optNullableDouble("confidence")?.toFloat(),
            source = json.optNullableString("source"),
            createdAt = parseRemoteTime(json.optString("created_at")),
            updatedAt = parseRemoteTime(json.optString("updated_at")),
            deletedAt = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun weightRecordFromJson(json: JSONObject): WeightRecordRemoteDto {
        return WeightRecordRemoteDto(
            userId = json.optString("user_id"),
            clientId = json.getString("client_id"),
            localDate = json.optString("local_date").ifBlank { json.optString("measured_date") },
            measuredAt = parseNullableRemoteTime(json.optNullableString("measured_at")),
            weightKg = json.optDouble("weight_kg").toFloat(),
            source = json.optString("source").ifBlank { "remote_pull" },
            createdAt = parseRemoteTime(json.optString("created_at")),
            updatedAt = parseRemoteTime(json.optString("updated_at")),
            deletedAt = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name) else null
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun parseNullableRemoteTime(value: String?): Long? {
        return value?.takeIf { it.isNotBlank() }?.let(::parseRemoteTime)
    }

    internal fun parseRemoteTime(value: String): Long {
        if (value.isBlank()) {
            throw IllegalArgumentException("Remote time value is blank")
        }
        return try {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            java.time.Instant.parse(value).toEpochMilli()
        }
    }


    private fun restUrl(): String = "${normalizedUrl()}rest/v1/"

    private fun <T> sessionUnavailablePullResult(): RemotePullResult<T> {
        return when (val status = sessionProvider.currentSessionStatus()) {
            is SupabaseAuthSessionStatus.RefreshTemporaryFailure -> {
                RemotePullResult.RetryableFailure("identity_temporarily_unavailable:${status.reason}")
            }
            is SupabaseAuthSessionStatus.RefreshPermanentlyRejected -> {
                RemotePullResult.FatalFailure("identity_permanently_unavailable:${status.reason}")
            }
            else -> RemotePullResult.Skipped("waiting_for_auth")
        }
    }

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private companion object {
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        private val FATAL_STATUS_CODES = setOf(400, 401, 403, 404, 422)
    }
}
