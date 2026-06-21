package com.example.data.sync

import android.util.Log
import com.example.data.identity.SupabaseAuthSession
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.identity.SupabaseAuthSessionStatus
import com.example.data.remote.SupabaseConfig
import com.example.domain.identity.AppIdentity
import com.example.domain.model.sync.ChatRemoteConversationPage
import com.example.domain.model.sync.ChatRemoteMessagePage
import com.example.domain.model.sync.ChatSyncConversationSnapshot
import com.example.domain.model.sync.ChatSyncMessageSnapshot
import com.example.domain.model.sync.ChatSyncServerCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class SupabaseChatRemotePullGateway(
    private val okHttpClient: OkHttpClient,
    private val sessionProvider: SupabaseAuthSessionProvider,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : ChatRemotePullGateway {

    override suspend fun fetchConversationPage(
        identity: AppIdentity,
        cursor: ChatSyncServerCursor?,
        limit: Int
    ): ChatRemotePullResult<ChatRemoteConversationPage> {
        if (!canPull(identity)) return ChatRemotePullResult.Skipped("remote_disabled_or_unauthorized")

        val urlBuilder = "${restUrl()}ai_conversations".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,conversation_date,title,last_message_preview,created_at,updated_at,last_activity_at,deleted_at,server_updated_at,schema_version")
            .addQueryParameter("order", "server_updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())

        applyCursorFilter(urlBuilder, cursor)

        return executeWithAuthRetry(
            urlBuilder = urlBuilder,
            tableName = "ai_conversations",
            limit = limit,
            cursorTime = cursor?.serverUpdatedAt
        ) { array, items ->
            val nextCursor = items.lastOrNull()?.let {
                val serverUpdatedAt = array.getJSONObject(array.length() - 1).getString("server_updated_at")
                ChatSyncServerCursor(serverUpdatedAt, it.getString("id"))
            }
            ChatRemoteConversationPage(
                items = items.map { conversationSnapshotFromJson(it) },
                nextCursor = nextCursor,
                hasMore = items.size >= limit
            )
        }
    }

    override suspend fun fetchMessagePage(
        identity: AppIdentity,
        cursor: ChatSyncServerCursor?,
        limit: Int
    ): ChatRemotePullResult<ChatRemoteMessagePage> {
        if (!canPull(identity)) return ChatRemotePullResult.Skipped("remote_disabled_or_unauthorized")

        val urlBuilder = "${restUrl()}ai_chat_messages".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,conversation_id,role,message_type,text,content_json,assistant_cards,suggested_replies_json,created_at,updated_at,deleted_at,server_updated_at,schema_version")
            .addQueryParameter("order", "server_updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())

        applyCursorFilter(urlBuilder, cursor)

        return executeWithAuthRetry(
            urlBuilder = urlBuilder,
            tableName = "ai_chat_messages",
            limit = limit,
            cursorTime = cursor?.serverUpdatedAt
        ) { array, items ->
            val nextCursor = items.lastOrNull()?.let {
                val serverUpdatedAt = array.getJSONObject(array.length() - 1).getString("server_updated_at")
                ChatSyncServerCursor(serverUpdatedAt, it.getString("id"))
            }
            ChatRemoteMessagePage(
                items = items.map { messageSnapshotFromJson(it) },
                nextCursor = nextCursor,
                hasMore = items.size >= limit
            )
        }
    }

    private suspend fun <T> executeWithAuthRetry(
        urlBuilder: okhttp3.HttpUrl.Builder,
        tableName: String,
        limit: Int,
        cursorTime: String?,
        pageBuilder: (JSONArray, List<JSONObject>) -> T
    ): ChatRemotePullResult<T> = withContext(Dispatchers.IO) {
        var session = sessionProvider.currentSessionOrNull() ?: return@withContext sessionUnavailablePullResult()
        
        var request = buildRequest(urlBuilder, session)
        Log.d("DayZeroRemote", "pull start table=$tableName limit=$limit cursor=$cursorTime")

        var response: Response? = null
        try {
            response = okHttpClient.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                // Try refresh exactly once
                response.close()
                Log.d("DayZeroRemote", "pull ${response.code} received table=$tableName, forcing refresh")
                session = sessionProvider.forceRefreshSession() ?: return@withContext sessionUnavailablePullResult()
                request = buildRequest(urlBuilder, session)
                response = okHttpClient.newCall(request).execute()
            }

            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val array = JSONArray(body)
                    val items = buildList {
                        for (index in 0 until array.length()) {
                            add(array.getJSONObject(index))
                        }
                    }
                    val page = pageBuilder(array, items)
                    Log.d("DayZeroRemote", "pull success table=$tableName count=${items.size}")
                    ChatRemotePullResult.Success(page)
                }
                response.code in RETRYABLE_STATUS_CODES -> {
                    Log.e("DayZeroRemote", "pull retryable table=$tableName reason=http_${response.code}")
                    ChatRemotePullResult.RetryableFailure("http_${response.code}")
                }
                response.code in FATAL_STATUS_CODES -> {
                    Log.e("DayZeroRemote", "pull fatal table=$tableName reason=http_${response.code}")
                    ChatRemotePullResult.FatalFailure("http_${response.code}")
                }
                else -> {
                    Log.e("DayZeroRemote", "pull retryable table=$tableName reason=http_${response.code}")
                    ChatRemotePullResult.RetryableFailure("http_${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("DayZeroRemote", "pull retryable table=$tableName reason=${e::class.java.simpleName}")
            ChatRemotePullResult.RetryableFailure(e.message ?: e::class.java.simpleName)
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

    private fun applyCursorFilter(urlBuilder: okhttp3.HttpUrl.Builder, cursor: ChatSyncServerCursor?) {
        cursor?.let {
            val cursorTime = it.serverUpdatedAt
            urlBuilder.addQueryParameter("or", "(server_updated_at.gt.$cursorTime,and(server_updated_at.eq.$cursorTime,id.gt.${it.id}))")
        }
    }

    private fun canPull(identity: AppIdentity): Boolean {
        return isConfigured && identity.canRemoteSync && !identity.remoteUserId.isNullOrBlank()
    }

    private fun conversationSnapshotFromJson(json: JSONObject): ChatSyncConversationSnapshot {
        return ChatSyncConversationSnapshot(
            id = json.getString("id"),
            conversationDate = LocalDate.parse(json.getString("conversation_date")),
            title = json.getString("title"),
            lastMessagePreview = json.getString("last_message_preview"),
            createdAtMillis = parseRemoteTime(json.getString("created_at")),
            updatedAtMillis = parseRemoteTime(json.getString("updated_at")),
            lastActivityAtMillis = parseRemoteTime(json.getString("last_activity_at")),
            deletedAtMillis = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun messageSnapshotFromJson(json: JSONObject): ChatSyncMessageSnapshot {
        return ChatSyncMessageSnapshot(
            id = json.getString("id"),
            conversationId = json.getString("conversation_id"),
            role = json.getString("role"),
            messageType = json.getString("message_type"),
            text = json.optString("text"),
            contentJson = json.optNullableRawJson("content_json"),
            assistantCardsJson = json.optNullableRawJson("assistant_cards"),
            suggestedRepliesJson = json.optNullableRawJson("suggested_replies_json"),
            createdAtMillis = parseRemoteTime(json.getString("created_at")),
            updatedAtMillis = parseRemoteTime(json.getString("updated_at")),
            deletedAtMillis = parseNullableRemoteTime(json.optNullableString("deleted_at")),
            schemaVersion = json.optInt("schema_version", 1)
        )
    }

    private fun JSONObject.optNullableRawJson(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val obj = opt(name) ?: return null
        // If it's a JSONArray or JSONObject, toString() will output valid JSON without losing fields
        return obj.toString()
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
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

    private fun <T> sessionUnavailablePullResult(): ChatRemotePullResult<T> {
        return when (val status = sessionProvider.currentSessionStatus()) {
            is SupabaseAuthSessionStatus.RefreshTemporaryFailure -> {
                ChatRemotePullResult.RetryableFailure("identity_temporarily_unavailable:${status.reason}")
            }
            is SupabaseAuthSessionStatus.RefreshPermanentlyRejected -> {
                ChatRemotePullResult.FatalFailure("identity_permanently_unavailable:${status.reason}")
            }
            else -> ChatRemotePullResult.Skipped("waiting_for_auth")
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
