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
import java.util.Base64

class SupabaseFixedPasswordIdentityProvider(
    context: Context,
    private val localIdentityProvider: CurrentIdentityProvider,
    private val okHttpClient: OkHttpClient,
    private val credentialsProvider: FixedDevelopmentAccountCredentialsProvider = FixedDevelopmentAccountCredentialsProvider(),
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val isConfigured: Boolean = SupabaseConfig.isConfigured()
) : CurrentIdentityProvider, SupabaseAuthSessionProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val sessionMutex = Mutex()

    @Volatile
    private var lastStatus: SupabaseAuthSessionStatus = SupabaseAuthSessionStatus.NoStoredSession

    override suspend fun currentIdentity(): AppIdentity {
        val localIdentity = localIdentityProvider.currentIdentity()
        if (!isConfigured) {
            Log.d(LOG_PREFIX, "fixed password auth skipped: supabase config unavailable")
            return localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }

        val session = currentSessionOrNull()
        return if (session != null) {
            Log.d(LOG_PREFIX, "fixed identity ready userId=${session.userId.maskedUserId()}")
            localIdentity.copy(
                remoteUserId = session.userId,
                authProvider = AUTH_PROVIDER,
                canRemoteSync = true
            )
        } else {
            Log.w(LOG_PREFIX, "fixed identity unavailable status=${lastStatus::class.java.simpleName}")
            localIdentity.copy(authProvider = "local", canRemoteSync = false)
        }
    }

    override suspend fun currentSessionOrNull(): SupabaseAuthSession? {
        if (!isConfigured) {
            lastStatus = SupabaseAuthSessionStatus.NoStoredSession
            return null
        }

        return sessionMutex.withLock {
            val credentials = credentialsProvider.credentials()
            if (!credentials.isConfigured()) {
                lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("fixed_credentials_missing")
                Log.e(LOG_PREFIX, "fixed auth blocked reason=fixed_credentials_missing")
                return@withLock null
            }

            val stored = loadSession()
            if (stored != null) {
                val validation = validateStoredSession(stored, credentials.expectedUserId)
                if (validation != null) {
                    lastStatus = SupabaseAuthSessionStatus.StoredSessionRejected(validation)
                    Log.w(LOG_PREFIX, "stored auth session rejected reason=$validation userId=${stored.userId.maskedUserId()}")
                    clearStoredAuthSession()
                } else if (stored.isUsable() && !stored.refreshToken.isNullOrBlank()) {
                    lastStatus = SupabaseAuthSessionStatus.AccessTokenUsable(stored.userId)
                    Log.d(LOG_PREFIX, "stored fixed session reused userId=${stored.userId.maskedUserId()}")
                    return@withLock stored
                } else if (stored.refreshToken.isNullOrBlank()) {
                    lastStatus = SupabaseAuthSessionStatus.StoredSessionRejected("missing_refresh_token")
                    Log.w(LOG_PREFIX, "stored fixed session rejected reason=missing_refresh_token userId=${stored.userId.maskedUserId()}")
                    clearStoredAuthSession()
                } else {
                    val refreshed = refreshSession(stored, stored.refreshToken, credentials.expectedUserId)
                    if (refreshed != null) return@withLock refreshed
                    if (lastStatus is SupabaseAuthSessionStatus.RefreshTemporaryFailure) {
                        return@withLock null
                    }
                    clearStoredAuthSession()
                }
            }

            signInWithPassword(credentials)
        }
    }

    override suspend fun forceRefreshSession(): SupabaseAuthSession? {
        if (!isConfigured) {
            lastStatus = SupabaseAuthSessionStatus.NoStoredSession
            return null
        }

        return sessionMutex.withLock {
            val credentials = credentialsProvider.credentials()
            if (!credentials.isConfigured()) {
                lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("fixed_credentials_missing")
                return@withLock null
            }

            val stored = loadSession()
            if (stored?.refreshToken.isNullOrBlank()) {
                if (stored != null) clearStoredAuthSession()
                return@withLock signInWithPassword(credentials)
            }

            val validation = validateStoredSession(stored!!, credentials.expectedUserId)
            if (validation != null) {
                lastStatus = SupabaseAuthSessionStatus.StoredSessionRejected(validation)
                clearStoredAuthSession()
                return@withLock signInWithPassword(credentials)
            }

            refreshSession(stored, stored.refreshToken!!, credentials.expectedUserId)
                ?: if (lastStatus is SupabaseAuthSessionStatus.RefreshTemporaryFailure) {
                    null
                } else {
                    clearStoredAuthSession()
                    signInWithPassword(credentials)
                }
        }
    }

    override fun currentSessionStatus(): SupabaseAuthSessionStatus = lastStatus

    private fun validateStoredSession(session: SupabaseAuthSession, expectedUserId: String): String? {
        return when {
            session.userId != expectedUserId -> "unexpected_user_id"
            session.isAnonymous -> "anonymous_session"
            session.accessToken.isBlank() -> "missing_access_token"
            else -> null
        }
    }

    private suspend fun refreshSession(
        stored: SupabaseAuthSession,
        refreshToken: String,
        expectedUserId: String
    ): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        Log.d(LOG_PREFIX, "fixed session refresh started userId=${stored.userId.maskedUserId()}")
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
                    handleAuthFailure("refresh", response.code, responseBody, stored.userId)
                    return@withContext null
                }

                val refreshed = parseSession(JSONObject(responseBody))
                if (refreshed == null || refreshed.refreshToken.isNullOrBlank()) {
                    lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("refresh_missing_token_pair")
                    Log.e(LOG_PREFIX, "fixed session refresh rejected reason=missing_token_pair")
                    return@withContext null
                }
                val validation = validateStoredSession(refreshed, expectedUserId)
                if (validation != null) {
                    lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("refresh_$validation")
                    Log.e(LOG_PREFIX, "fixed session refresh rejected reason=$validation userId=${refreshed.userId.maskedUserId()}")
                    return@withContext null
                }
                saveSession(refreshed)
                lastStatus = SupabaseAuthSessionStatus.RefreshSucceeded(refreshed.userId)
                Log.d(LOG_PREFIX, "fixed session refresh succeeded userId=${refreshed.userId.maskedUserId()}")
                refreshed
            }
        } catch (e: IOException) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e(LOG_PREFIX, "fixed session refresh temporary failure reason=${e::class.java.simpleName}")
            null
        } catch (e: Exception) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e(LOG_PREFIX, "fixed session refresh temporary failure reason=${e::class.java.simpleName}")
            null
        }
    }

    private suspend fun signInWithPassword(credentials: FixedDevelopmentAccountCredentials): SupabaseAuthSession? = withContext(Dispatchers.IO) {
        Log.d(LOG_PREFIX, "fixed password sign-in started")
        try {
            val body = JSONObject()
                .put("email", credentials.email)
                .put("password", credentials.password)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("${normalizedUrl()}auth/v1/token?grant_type=password")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    handleAuthFailure("password", response.code, responseBody, null)
                    return@withContext null
                }

                val session = parseSession(JSONObject(responseBody))
                if (session == null || session.refreshToken.isNullOrBlank()) {
                    lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("password_missing_token_pair")
                    Log.e(LOG_PREFIX, "fixed password sign-in rejected reason=missing_token_pair")
                    return@withContext null
                }
                val validation = validateStoredSession(session, credentials.expectedUserId)
                if (validation != null) {
                    lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected("password_$validation")
                    Log.e(LOG_PREFIX, "fixed password sign-in rejected reason=$validation userId=${session.userId.maskedUserId()}")
                    return@withContext null
                }
                saveSession(session)
                lastStatus = SupabaseAuthSessionStatus.PasswordSignInSucceeded(session.userId)
                Log.d(LOG_PREFIX, "fixed password sign-in succeeded userId=${session.userId.maskedUserId()}")
                session
            }
        } catch (e: IOException) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e(LOG_PREFIX, "fixed password sign-in temporary failure reason=${e::class.java.simpleName}")
            null
        } catch (e: Exception) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(e::class.java.simpleName)
            Log.e(LOG_PREFIX, "fixed password sign-in temporary failure reason=${e::class.java.simpleName}")
            null
        }
    }

    private fun handleAuthFailure(kind: String, code: Int, body: String, userId: String?) {
        val reason = "${kind}_http_$code${authErrorCode(body)?.let { ":$it" }.orEmpty()}"
        if (code in RETRYABLE_STATUS_CODES) {
            lastStatus = SupabaseAuthSessionStatus.RefreshTemporaryFailure(reason)
            Log.e(LOG_PREFIX, "fixed auth temporary failure kind=$kind userId=${userId?.maskedUserId() ?: "none"} reason=$reason")
        } else {
            lastStatus = SupabaseAuthSessionStatus.RefreshPermanentlyRejected(reason)
            Log.e(LOG_PREFIX, "fixed auth fatal failure kind=$kind userId=${userId?.maskedUserId() ?: "none"} reason=$reason")
        }
    }

    private fun parseSession(json: JSONObject): SupabaseAuthSession? {
        val user = json.optJSONObject("user")
        val userId = user?.optString("id").orEmpty()
        val accessToken = json.optString("access_token")
        if (userId.isBlank() || accessToken.isBlank()) return null

        val now = System.currentTimeMillis() / 1000
        val expiresIn = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        val isAnonymous = user?.optBoolean("is_anonymous", false) ?: isAnonymousJwt(accessToken)
        return SupabaseAuthSession(
            userId = userId,
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresAtEpochSeconds = now + expiresIn,
            isAnonymous = isAnonymous
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
            expiresAtEpochSeconds = preferences.getLong(KEY_EXPIRES_AT, 0L),
            isAnonymous = preferences.getBoolean(KEY_IS_ANONYMOUS, isAnonymousJwt(accessToken))
        )
    }

    private fun saveSession(session: SupabaseAuthSession) {
        val saved = preferences.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
            .putBoolean(KEY_IS_ANONYMOUS, session.isAnonymous)
            .remove(KEY_BLOCKED_REASON)
            .commit()
        if (!saved) {
            Log.e(LOG_PREFIX, "fixed session save failed userId=${session.userId.maskedUserId()}")
        }
    }

    private fun clearStoredAuthSession() {
        preferences.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_IS_ANONYMOUS)
            .remove(KEY_BLOCKED_REASON)
            .commit()
    }

    private fun isAnonymousJwt(accessToken: String): Boolean {
        return runCatching {
            val parts = accessToken.split(".")
            if (parts.size < 2) return@runCatching false
            val json = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            JSONObject(json).optBoolean("is_anonymous", false)
        }.getOrDefault(false)
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

    private fun String.maskedUserId(): String {
        return if (length <= 8) "***" else "${take(8)}..."
    }

    private fun normalizedUrl(): String {
        return if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
    }

    private companion object {
        private const val PREFS_NAME = "dayzero_supabase_auth"
        private const val LOG_PREFIX = "DayZeroAuth"
        private const val AUTH_PROVIDER = "supabase_fixed_password"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
        private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_IS_ANONYMOUS = "is_anonymous"
        private const val KEY_BLOCKED_REASON = "blocked_reason"
    }
}
