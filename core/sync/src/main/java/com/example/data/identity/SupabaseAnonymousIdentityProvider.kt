package com.example.data.identity

import android.content.Context
import android.util.Log
import com.example.data.remote.SupabaseConfig
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

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
    private val sessionMutex = Mutex()
    @Volatile
    private var lastStatus: SupabaseAuthSessionStatus = SupabaseAuthSessionStatus.NoStoredSession

    override suspend fun currentIdentity(): AppIdentity {
        val localIdentity = localIdentityProvider.currentIdentity()
        if (!isConfigured) {
            Log.d("DayZeroAuthDiag", "anonymous sign in skipped: supabase config unavailable")
            return localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }

        val session = currentSessionOrNull()
        if (session != null) {
            Log.w("DayZeroAuthDiag", "Diag: current Identity Provider resolution. Local ID: ${localIdentity.localOwnerId}, Remote User ID: ${session.userId}, Session source: loaded/created")
            return localIdentity.copy(
                remoteUserId = session.userId,
                authProvider = "supabase_anonymous",
                canRemoteSync = true
            )
        } else {
            Log.w("DayZeroAuthDiag", "Diag: current Identity Provider resolution. Local ID: ${localIdentity.localOwnerId}, Remote User ID: null, Session source: ${lastStatus::class.java.simpleName}")
            return localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }
    }

    override suspend fun currentSessionOrNull(): SupabaseAuthSession? {
        if (!isConfigured) {
            lastStatus = SupabaseAuthSessionStatus.NoStoredSession
            return null
        }

        loadSession()?.takeIf { it.isUsable() }?.let {
            lastStatus = SupabaseAuthSessionStatus.AccessTokenUsable(it.userId)
            Log.d("DayZeroAuth", "identity access token usable userId=${it.userId.maskedUserId()}")
            return it
        }

        return sessionMutex.withLock {
            if (isCloudIdentityBlocked()) {
                lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected(
                    loadBlockedReason() ?: "cloud_identity_blocked"
                )
                Log.e("DayZeroAuth", "identity refresh blocked reason=${loadBlockedReason() ?: "cloud_identity_blocked"}")
                return@withLock null
            }

            val stored = loadSession()
            if (stored?.isUsable() == true) {
                lastStatus = SupabaseAuthSessionStatus.AccessTokenUsable(stored.userId)
                Log.d("DayZeroAuth", "identity access token usable after wait userId=${stored.userId.maskedUserId()}")
                return@withLock stored
            }

            if (stored != null) {
                val refreshToken = stored.refreshToken
                if (refreshToken.isNullOrBlank()) {
                    markPermanentFailure("missing_refresh_token")
                    return@withLock null
                }
                refreshSession(stored, refreshToken)
            } else {
                if (hasStoredSessionEvidence() || isCloudIdentityBlocked()) {
                    markPermanentFailure(loadBlockedReason() ?: "stored_session_incomplete")
                    return@withLock null
                }
                val localOwnerId = localIdentityProvider.currentIdentity().localOwnerId
                signInAnonymously(localOwnerId)
            }
        }
    }

    override suspend fun forceRefreshSession(): SupabaseAuthSession? {
        if (!isConfigured) {
            lastStatus = SupabaseAuthSessionStatus.NoStoredSession
            return null
        }

        return sessionMutex.withLock {
            if (isCloudIdentityBlocked()) {
                lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected(
                    loadBlockedReason() ?: "cloud_identity_blocked"
                )
                return@withLock null
            }

            val stored = loadSession()
            if (stored != null) {
                val refreshToken = stored.refreshToken
                if (refreshToken.isNullOrBlank()) {
                    markPermanentFailure("missing_refresh_token")
                    return@withLock null
                }
                refreshSession(stored, refreshToken)
            } else {
                lastStatus = SupabaseAuthSessionStatus.NoStoredSession
                null
            }
        }
    }

    override fun currentSessionStatus(): SupabaseAuthSessionStatus = lastStatus

    private suspend fun signInAnonymously(localOwnerId: String): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        Log.d("DayZeroAuthDiag", "anonymous sign in start")
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
                    lastStatus = if (response.code in RETRYABLE_STATUS_CODES) {
                        SupabaseAuthSessionStatus.RefreshTemporaryFailure("anonymous_signup_http_${response.code}")
                    } else {
                        SupabaseAuthSessionStatus.RefreshPermanentlyRejected("anonymous_signup_http_${response.code}")
                    }
                    Log.e("DayZeroAuth", "identity anonymous signup failed code=${response.code}")
                    return@withContext null
                }

                val session = parseSession(JSONObject(responseBody))
                if (session == null) {
                    lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("anonymous_signup_missing_session")
                    Log.e("DayZeroAuth", "identity anonymous signup error missing session")
                    return@withContext null
                }
                saveSession(session)
                clearCloudIdentityBlocked()
                lastStatus = SupabaseAuthSessionStatus.AnonymousSignUpSucceeded(session.userId)
                Log.d("DayZeroAuth", "identity anonymous signup succeeded userId=${session.userId.maskedUserId()}")
                session
            }
        } catch (e: Exception) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e("DayZeroAuth", "identity anonymous signup temporary failure reason=${e::class.java.simpleName}")
            null
        }
    }

    private suspend fun refreshSession(
        stored: SupabaseAuthSession,
        refreshToken: String
    ): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        Log.d("DayZeroAuth", "identity refresh started userId=${stored.userId.maskedUserId()}")
        try {
            val body = JSONObject()
                .put("refresh_token", refreshToken)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("${normalizedUrl()}auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    handleRefreshFailure(response.code, responseBody, stored.userId)
                    return@withContext null
                }

                val refreshed = parseSession(JSONObject(responseBody))
                if (refreshed == null) {
                    markPermanentFailure("refresh_missing_session")
                    return@withContext null
                }
                if (refreshed.userId != stored.userId) {
                    markPermanentFailure("refresh_user_mismatch")
                    Log.e(
                        "DayZeroAuth",
                        "identity refresh permanently rejected user mismatch old=${stored.userId.maskedUserId()} new=${refreshed.userId.maskedUserId()}"
                    )
                    return@withContext null
                }
                saveSession(refreshed)
                clearCloudIdentityBlocked()
                lastStatus = SupabaseAuthSessionStatus.RefreshSucceeded(refreshed.userId)
                Log.d(
                    "DayZeroAuth",
                    "identity refresh succeeded userId=${refreshed.userId.maskedUserId()} sameUser=true"
                )
                refreshed
            }
        } catch (e: IOException) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e("DayZeroAuth", "identity refresh temporary failure reason=${e::class.java.simpleName}")
            null
        } catch (e: Exception) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e("DayZeroAuth", "identity refresh temporary failure reason=${e::class.java.simpleName}")
            null
        }
    }

    private fun handleRefreshFailure(code: Int, body: String, userId: String) {
        val reason = "refresh_http_$code${authErrorCode(body)?.let { ":$it" }.orEmpty()}"
        if (code in RETRYABLE_STATUS_CODES) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(reason)
            Log.e("DayZeroAuth", "identity refresh temporary failure userId=${userId.maskedUserId()} reason=$reason")
        } else {
            markPermanentFailure(reason)
            Log.e("DayZeroAuth", "identity refresh permanently rejected userId=${userId.maskedUserId()} reason=$reason")
        }
    }

    private fun authErrorCode(body: String): String? {
        return runCatching {
            val json = JSONObject(body)
            json.optString("error_code")
                .ifBlank { json.optString("code") }
                .ifBlank { json.optString("error") }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
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
        val saved = preferences.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .remove(KEY_BLOCKED_REASON)
            .commit()
        if (!saved) {
            Log.e("DayZeroAuth", "identity session save failed userId=${session.userId.maskedUserId()}")
        }
    }

    private fun hasStoredSessionEvidence(): Boolean {
        return preferences.contains(KEY_USER_ID) ||
            preferences.contains(KEY_ACCESS_TOKEN) ||
            preferences.contains(KEY_REFRESH_TOKEN) ||
            preferences.contains(KEY_EXPIRES_AT)
    }

    private fun isCloudIdentityBlocked(): Boolean {
        return preferences.contains(KEY_BLOCKED_REASON)
    }

    private fun loadBlockedReason(): String? {
        return preferences.getString(KEY_BLOCKED_REASON, null)
    }

    private fun markPermanentFailure(reason: String) {
        preferences.edit()
            .putString(KEY_BLOCKED_REASON, reason)
            .commit()
        lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected(reason)
    }

    private fun clearCloudIdentityBlocked() {
        preferences.edit()
            .remove(KEY_BLOCKED_REASON)
            .commit()
    }

    private fun String.maskedUserId(): String {
        return if (length <= 8) "***" else "${take(8)}..."
    }

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_BLOCKED_REASON = "blocked_reason"
    }
}
