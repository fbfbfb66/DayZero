package com.example.data.identity

import android.content.Context
import android.util.Log
import com.example.data.remote.SupabaseConfig
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SupabaseAnonymousIdentityProvider(
    context: Context,
    private val localIdentityProvider: CurrentIdentityProvider,
    private val okHttpClient: OkHttpClient,
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : CurrentIdentityProvider, SupabaseAuthSessionProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dayzero_supabase_auth",
        Context.MODE_PRIVATE
    )

    override suspend fun currentIdentity(): AppIdentity {
        val localIdentity = localIdentityProvider.currentIdentity()
        if (!isConfigured) {
            Log.d("DayZeroAuth", "anonymous sign in skipped: supabase config unavailable")
            return localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }

        val session = currentSessionOrNull()
        return if (session != null) {
            localIdentity.copy(
                remoteUserId = session.userId,
                authProvider = "supabase_anonymous",
                canRemoteSync = true
            )
        } else {
            localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }
    }

    override suspend fun currentSessionOrNull(): SupabaseAuthSession? {
        loadSession()?.takeIf { it.isUsable() }?.let { return it }
        val localOwnerId = localIdentityProvider.currentIdentity().localOwnerId
        return signInAnonymously(localOwnerId)
    }

    private suspend fun signInAnonymously(localOwnerId: String): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        Log.d("DayZeroAuth", "anonymous sign in start")
        try {
            val body = JSONObject()
                .put("data", JSONObject().put("local_owner_id", localOwnerId))
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("${normalizedUrl()}auth/v1/signup")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e("DayZeroAuth", "anonymous sign in error code=${response.code}")
                    return@withContext null
                }

                val session = parseSession(JSONObject(responseBody))
                if (session == null) {
                    Log.e("DayZeroAuth", "anonymous sign in error missing session")
                    return@withContext null
                }
                saveSession(session)
                Log.d("DayZeroAuth", "anonymous sign in success userId=${session.userId.take(8)}...")
                session
            }
        } catch (e: Exception) {
            Log.e("DayZeroAuth", "anonymous sign in error", e)
            null
        }
    }

    private fun parseSession(json: JSONObject): SupabaseAuthSession? {
        val userId = json.optJSONObject("user")?.optString("id").orEmpty()
        val accessToken = json.optString("access_token")
        if (userId.isBlank() || accessToken.isBlank()) return null

        val now = System.currentTimeMillis() / 1000
        val expiresIn = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        return SupabaseAuthSession(
            userId = userId,
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAtEpochSeconds = now + expiresIn
        )
    }

    private fun loadSession(): SupabaseAuthSession? {
        val userId = preferences.getString(KEY_USER_ID, null).orEmpty()
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        if (userId.isBlank() || accessToken.isBlank()) return null

        return SupabaseAuthSession(
            userId = userId,
            accessToken = accessToken,
            refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null),
            expiresAtEpochSeconds = preferences.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    private fun saveSession(session: SupabaseAuthSession) {
        preferences.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .apply()
    }

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
