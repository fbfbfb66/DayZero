package com.example.data.sync

import android.util.Log
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.remote.SupabaseConfig
import com.example.domain.identity.AppIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class SupabaseRemoteSyncGateway(
    private val okHttpClient: OkHttpClient,
    private val sessionProvider: SupabaseAuthSessionProvider,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : RemoteSyncGateway {
    override suspend fun canSync(identity: AppIdentity): Boolean {
        return isConfigured && identity.canRemoteSync && !identity.remoteUserId.isNullOrBlank()
    }

    override suspend fun upsertDailyRecord(payload: SyncPayload): RemoteSyncResult {
        return upsert(
            entityType = payload.entityType,
            tableName = "daily_records",
            clientId = payload.clientId(),
            body = dailyRecordBody(payload)
        )
    }

    override suspend fun upsertMeal(payload: SyncPayload): RemoteSyncResult {
        return upsert(
            entityType = payload.entityType,
            tableName = "meals",
            clientId = payload.clientId(),
            body = mealBody(payload)
        )
    }

    override suspend fun upsertFoodEntry(payload: SyncPayload): RemoteSyncResult {
        return upsert(
            entityType = payload.entityType,
            tableName = "food_entries",
            clientId = payload.clientId(),
            body = foodEntryBody(payload)
        )
    }

    override suspend fun upsertWeightRecord(payload: SyncPayload): RemoteSyncResult {
        return upsert(
            entityType = payload.entityType,
            tableName = "weight_records",
            clientId = payload.clientId(),
            body = weightRecordBody(payload)
        )
    }

    override suspend fun softDeleteRecord(payload: SyncPayload): RemoteSyncResult {
        val clientId = payload.clientId()
        val session = sessionProvider.currentSessionOrNull()
            ?: return RemoteSyncResult.Skipped("waiting_for_auth")
        val body = JSONObject()
            .put("deleted_at", isoNow())
            .put("updated_at", isoNow())
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = "${restUrl()}daily_records?user_id=eq.${session.userId}&client_id=eq.$clientId"

        Log.d("DayZeroRemote", "upsert start entityType=${payload.entityType} clientId=$clientId")
        return executeRequest(
            request = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .patch(body)
                .build(),
            entityType = payload.entityType,
            clientId = clientId
        )
    }

    private suspend fun upsert(
        entityType: String,
        tableName: String,
        clientId: String,
        body: JSONObject
    ): RemoteSyncResult {
        if (!isConfigured) return RemoteSyncResult.Skipped("supabase_not_configured")
        val session = sessionProvider.currentSessionOrNull()
            ?: return RemoteSyncResult.Skipped("waiting_for_auth")
        val requestBody = body
            .put("user_id", session.userId)
            .put("updated_at", isoNow())
            .put("schema_version", body.optInt("schema_version", 1))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        Log.d("DayZeroRemote", "upsert start entityType=$entityType clientId=$clientId")
        return executeRequest(
            request = Request.Builder()
                .url("${restUrl()}$tableName?on_conflict=user_id,client_id")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(requestBody)
                .build(),
            entityType = entityType,
            clientId = clientId
        )
    }

    private fun executeRequest(
        request: Request,
        entityType: String,
        clientId: String
    ): RemoteSyncResult {
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        Log.d("DayZeroRemote", "upsert success entityType=$entityType clientId=$clientId")
                        RemoteSyncResult.Success
                    }

                    response.code in RETRYABLE_STATUS_CODES -> {
                        Log.e(
                            "DayZeroRemote",
                            "upsert retryable failure entityType=$entityType reason=http_${response.code}"
                        )
                        RemoteSyncResult.RetryableFailure("http_${response.code}")
                    }

                    response.code in FATAL_STATUS_CODES -> {
                        Log.e(
                            "DayZeroRemote",
                            "upsert fatal failure entityType=$entityType reason=http_${response.code}"
                        )
                        RemoteSyncResult.FatalFailure("http_${response.code}")
                    }

                    else -> {
                        Log.e(
                            "DayZeroRemote",
                            "upsert retryable failure entityType=$entityType reason=http_${response.code}"
                        )
                        RemoteSyncResult.RetryableFailure("http_${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DayZeroRemote", "upsert retryable failure entityType=$entityType reason=${e::class.java.simpleName}")
            RemoteSyncResult.RetryableFailure(e.message ?: e::class.java.simpleName)
        }
    }

    private fun dailyRecordBody(payload: SyncPayload): JSONObject {
        return baseBody(payload)
            .put("record_date", payload.body.optString("date").ifBlank { null })
            .put("status", payload.body.optString("status").ifBlank { "Confirmed" })
            .put("total_calories", payload.body.optInt("totalCalories", 0))
            .putNullable("weight_kg", payload.body.optNullableNumber("weightKg"))
            .putNullable("ai_summary", payload.body.optNullableString("aiSummary"))
    }

    private fun mealBody(payload: SyncPayload): JSONObject {
        val dailyRecordClientId = payload.body.optString("dailyRecordClientId")
        return baseBody(payload)
            .put("daily_record_id", stableUuid(dailyRecordClientId))
            .put("daily_record_client_id", dailyRecordClientId)
            .put("meal_type", payload.body.optString("mealType").ifBlank { "Snack" })
            .put("has_photo", payload.body.optBoolean("hasPhoto", false))
            .put("subtotal_calories", payload.body.optInt("subtotalCalories", 0))
    }

    private fun foodEntryBody(payload: SyncPayload): JSONObject {
        val dailyRecordClientId = payload.body.optString("dailyRecordClientId")
        val mealClientId = payload.body.optString("mealClientId")
        return baseBody(payload)
            .put("meal_id", stableUuid(mealClientId))
            .put("daily_record_id", stableUuid(dailyRecordClientId))
            .put("meal_client_id", mealClientId)
            .put("daily_record_client_id", dailyRecordClientId)
            .put("name", payload.body.optString("name").ifBlank { "unknown" })
            .put("quantity", payload.body.optString("quantity").ifBlank { "1" })
            .put("estimated_calories", payload.body.optInt("estimatedCalories", 0))
            .put("confidence", payload.body.optString("confidence").ifBlank { "unknown" })
            .put("raw_estimate_json", JSONObject(payload.body.toString()))
    }

    private fun weightRecordBody(payload: SyncPayload): JSONObject {
        val dailyRecordClientId = payload.body.optString("dailyRecordClientId")
        return baseBody(payload)
            .put("daily_record_id", stableUuid(dailyRecordClientId))
            .put("daily_record_client_id", dailyRecordClientId)
            .put("measured_date", payload.body.optString("measuredDate").ifBlank { null })
            .put("weight_kg", payload.body.optDouble("weightKg"))
            .put("source", payload.body.optString("source").ifBlank { "confirm_card" })
    }

    private fun baseBody(payload: SyncPayload): JSONObject {
        val clientId = payload.clientId()
        return JSONObject()
            .put("id", stableUuid(clientId))
            .put("client_id", clientId)
            .put("local_owner_id", payload.ownerLocalId)
            .put("created_at", isoNow())
            .put("deleted_at", JSONObject.NULL)
            .put("schema_version", payload.body.optInt("schemaVersion", 1))
    }

    private fun SyncPayload.clientId(): String {
        return body.optString("clientId").ifBlank { entityLocalId }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        val value = optString(name)
        return value.ifBlank { null }
    }

    private fun JSONObject.optNullableNumber(name: String): Any? {
        return if (has(name) && !isNull(name)) opt(name) else null
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun stableUuid(value: String): String {
        return runCatching { UUID.fromString(value).toString() }
            .getOrElse { UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString() }
    }

    private fun restUrl(): String = "${normalizedUrl()}rest/v1/"

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private fun isoNow(): String = Instant.now().toString()

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        private val FATAL_STATUS_CODES = setOf(400, 401, 403, 404, 422)
    }
}
