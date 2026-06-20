package com.example.data.sync

import android.util.Log
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.remote.SupabaseConfig
import com.example.domain.identity.AppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
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
        if (!isConfigured) return RemoteSyncResult.Skipped("supabase_not_configured")
        val tableName = tableNameForEntity(payload.entityType)
            ?: return RemoteSyncResult.FatalFailure("unsupported_soft_delete_entity:${payload.entityType}")
        val clientId = payload.clientId()
        val session = sessionProvider.currentSessionOrNull()
            ?: return RemoteSyncResult.Skipped("waiting_for_auth")
        val deletedAt = deletedAtIso(payload)
        val body = JSONObject()
            .put("deleted_at", deletedAt)
            .put("updated_at", isoNow())
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = "${restUrl()}$tableName?user_id=eq.${session.userId}&client_id=eq.${filterValue(clientId)}"

        Log.d("DayZeroRemote", "soft delete start entityType=${payload.entityType} clientId=$clientId")
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

    private suspend fun executeRequest(
        request: Request,
        entityType: String,
        clientId: String
    ): RemoteSyncResult = withContext(Dispatchers.IO) {
        try {
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
            .put("local_date", payload.body.optString("date").ifBlank { null })
            .putNullable("timezone", payload.body.optNullableString("timezone"))
            .putNullable("note", payload.body.optNullableString("aiSummary"))
    }

    private fun mealBody(payload: SyncPayload): JSONObject {
        val dailyRecordClientId = payload.body.optString("dailyRecordClientId")
        return baseBody(payload)
            .put("daily_record_client_id", dailyRecordClientId)
            .putNullable("meal_type", payload.body.optNullableString("mealType"))
            .putNullable("logged_at", payload.body.optNullableString("loggedAt"))
            .putNullable("display_order", payload.body.optNullableNumber("displayOrder"))
    }

    private fun foodEntryBody(payload: SyncPayload): JSONObject {
        val mealClientId = payload.body.optString("mealClientId")
        return baseBody(payload)
            .put("meal_client_id", mealClientId)
            .put("name", payload.body.optString("name").ifBlank { "unknown" })
            .putNullable("amount_text", payload.body.optNullableString("quantity"))
            .putNullable("grams", payload.body.optNullableNumber("grams"))
            .putNullable("calories", payload.body.optNullableNumber("estimatedCalories"))
            .putNullable("protein_g", payload.body.optNullableNumber("proteinG"))
            .putNullable("carbs_g", payload.body.optNullableNumber("carbsG"))
            .putNullable("fat_g", payload.body.optNullableNumber("fatG"))
            .putNullable("confidence", payload.body.optNullableNumber("confidence"))
            .putNullable("source", payload.body.optNullableString("source") ?: "confirm_card")
    }

    private fun weightRecordBody(payload: SyncPayload): JSONObject {
        return baseBody(payload)
            .put("local_date", payload.body.optString("measuredDate").ifBlank { null })
            .putNullable("measured_at", payload.body.optNullableString("measuredAt"))
            .put("weight_kg", payload.body.optDouble("weightKg"))
            .putNullable("source", payload.body.optNullableString("source") ?: "confirm_card")
    }

    private fun baseBody(payload: SyncPayload): JSONObject {
        val clientId = payload.clientId()
        return JSONObject()
            .put("id", stableUuid(clientId))
            .put("client_id", clientId)
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
        val value = if (has(name) && !isNull(name)) opt(name) else null
        return when (value) {
            is Number -> value
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun stableUuid(value: String): String {
        return runCatching { UUID.fromString(value).toString() }
            .getOrElse { UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString() }
    }

    private fun tableNameForEntity(entityType: String): String? {
        return when (entityType) {
            "daily_record" -> "daily_records"
            "meal" -> "meals"
            "food_entry" -> "food_entries"
            "weight_record" -> "weight_records"
            else -> null
        }
    }

    private fun deletedAtIso(payload: SyncPayload): String {
        val value = if (payload.body.has("deletedAt") && !payload.body.isNull("deletedAt")) {
            payload.body.opt("deletedAt")
        } else {
            null
        }
        return when (value) {
            is Number -> Instant.ofEpochMilli(value.toLong()).toString()
            is String -> value.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() } ?: value.ifBlank { isoNow() }
            else -> isoNow()
        }
    }

    private fun filterValue(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
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
