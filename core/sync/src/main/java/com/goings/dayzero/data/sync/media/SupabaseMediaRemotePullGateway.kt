package com.goings.dayzero.data.sync.media

import android.util.Log
import com.goings.dayzero.data.identity.SupabaseAuthSession
import com.goings.dayzero.data.identity.SupabaseAuthSessionProvider
import com.goings.dayzero.data.identity.SupabaseAuthSessionStatus
import com.goings.dayzero.data.remote.SupabaseConfig
import com.goings.dayzero.domain.identity.AppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class SupabaseMediaRemotePullGateway(
    private val okHttpClient: OkHttpClient,
    private val sessionProvider: SupabaseAuthSessionProvider,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : MediaRemotePullGateway {

    override suspend fun fetchMediaPage(
        identity: AppIdentity,
        cursor: MediaSyncServerCursor?,
        limit: Int
    ): MediaRemotePullResult<MediaRemotePage> = withContext(Dispatchers.IO) {
        if (!canPull(identity)) return@withContext MediaRemotePullResult.Skipped("remote_disabled_or_unauthorized")

        val urlBuilder = "${restUrl()}media_assets".toHttpUrl().newBuilder()
            .addQueryParameter(
                "select",
                "id,conversation_id,source_message_id,conversation_order,master_object_path,thumbnail_object_path,mime_type,width,height,byte_size,sha256,source,created_at,updated_at,deleted_at,server_updated_at,schema_version"
            )
            .addQueryParameter("order", "server_updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())
        applyCursorFilter(urlBuilder, cursor)

        var session = sessionProvider.currentSessionOrNull()
            ?: return@withContext sessionUnavailablePullResult()
        var request = buildRequest(urlBuilder, session)
        Log.d("DayZeroRemote", "media pull start limit=$limit cursor=${cursor?.serverUpdatedAt}")

        var response: Response? = null
        try {
            response = okHttpClient.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                response.close()
                session = sessionProvider.forceRefreshSession()
                    ?: return@withContext sessionUnavailablePullResult()
                request = buildRequest(urlBuilder, session)
                response = okHttpClient.newCall(request).execute()
            }

            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val array = JSONArray(body)
                    val items = buildList {
                        for (index in 0 until array.length()) add(array.getJSONObject(index))
                    }
                    val nextCursor = items.lastOrNull()?.let {
                        MediaSyncServerCursor(
                            serverUpdatedAt = array.getJSONObject(array.length() - 1).getString("server_updated_at"),
                            id = it.getString("id")
                        )
                    }
                    val page = MediaRemotePage(
                        items = items.map { snapshotFromJson(it) },
                        nextCursor = nextCursor,
                        hasMore = items.size >= limit
                    )
                    Log.d("DayZeroRemote", "media pull success count=${items.size}")
                    MediaRemotePullResult.Success(page)
                }
                response.code in RETRYABLE_STATUS_CODES ->
                    MediaRemotePullResult.RetryableFailure("http_${response.code}")
                response.code in FATAL_STATUS_CODES ->
                    MediaRemotePullResult.FatalFailure("http_${response.code}")
                else -> MediaRemotePullResult.RetryableFailure("http_${response.code}")
            }
        } catch (e: Exception) {
            Log.e("DayZeroRemote", "media pull retryable reason=${e::class.java.simpleName}")
            MediaRemotePullResult.RetryableFailure(e.message ?: e::class.java.simpleName)
        } finally {
            response?.close()
        }
    }

    private fun buildRequest(urlBuilder: okhttp3.HttpUrl.Builder, session: SupabaseAuthSession): Request {
        return Request.Builder()
            .url(urlBuilder.build())
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "application/json")
            .get()
            .build()
    }

    private fun applyCursorFilter(urlBuilder: okhttp3.HttpUrl.Builder, cursor: MediaSyncServerCursor?) {
        cursor?.let {
            urlBuilder.addQueryParameter(
                "or",
                "(server_updated_at.gt.${it.serverUpdatedAt},and(server_updated_at.eq.${it.serverUpdatedAt},id.gt.${it.id}))"
            )
        }
    }

    private fun canPull(identity: AppIdentity): Boolean {
        return isConfigured && identity.canRemoteSync && !identity.remoteUserId.isNullOrBlank()
    }

    private fun snapshotFromJson(json: JSONObject): MediaRemoteSnapshot {
        return MediaRemoteSnapshot(
            id = json.getString("id"),
            conversationId = json.getString("conversation_id"),
            sourceMessageId = json.optNullableString("source_message_id"),
            conversationOrder = json.getLong("conversation_order"),
            masterObjectPath = json.optNullableString("master_object_path"),
            thumbnailObjectPath = json.optNullableString("thumbnail_object_path"),
            mimeType = json.optNullableString("mime_type"),
            width = json.optNullableInt("width"),
            height = json.optNullableInt("height"),
            byteSize = json.optNullableLong("byte_size"),
            sha256 = json.optNullableString("sha256"),
            source = json.optString("source").ifBlank { "PHOTO_PICKER" },
            createdAtMillis = parseRemoteTime(json.getString("created_at")),
            updatedAtMillis = parseRemoteTime(json.getString("updated_at")),
            deletedAtMillis = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }

    private fun parseNullableRemoteTime(value: String?): Long? {
        return value?.takeIf { it.isNotBlank() }?.let(::parseRemoteTime)
    }

    private fun parseRemoteTime(value: String): Long {
        if (value.isBlank()) throw IllegalArgumentException("Remote time value is blank")
        return try {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            java.time.Instant.parse(value).toEpochMilli()
        }
    }

    private fun restUrl(): String = "${normalizedUrl()}rest/v1/"

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private fun <T> sessionUnavailablePullResult(): MediaRemotePullResult<T> {
        return when (val status = sessionProvider.currentSessionStatus()) {
            is SupabaseAuthSessionStatus.RefreshTemporaryFailure ->
                MediaRemotePullResult.RetryableFailure("identity_temporarily_unavailable:${status.reason}")
            is SupabaseAuthSessionStatus.RefreshPermanentlyRejected ->
                MediaRemotePullResult.FatalFailure("identity_permanently_unavailable:${status.reason}")
            else -> MediaRemotePullResult.Skipped("waiting_for_auth")
        }
    }

    private companion object {
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        private val FATAL_STATUS_CODES = setOf(400, 401, 403, 404, 422)
    }
}
