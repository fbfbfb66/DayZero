package com.example.data.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.sync.DayZeroSyncConstants
import com.example.data.sync.RemoteSyncResult
import com.example.data.sync.SupabaseRemoteSyncGateway
import com.example.data.sync.SyncPayload
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SupabaseAnonymousIdentityProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authPrefs().edit().clear().commit()
    }

    @Test
    fun validSessionReturnsWithoutRefreshOrSignup() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() + 3600)
        val auth = RecordingAuthInterceptor()
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertEquals("user-a", session?.userId)
        assertEquals("access-old", session?.accessToken)
        assertEquals(0, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.AccessTokenUsable)
    }

    @Test
    fun expiringSessionRefreshesAndPersistsRotatedTokenPair() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() + 30)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(AuthResponse.success("user-a", "access-new", "refresh-new"))
            )
        )
        val provider = provider(auth)

        val refreshed = provider.currentSessionOrNull()
        val rebuilt = provider(RecordingAuthInterceptor()).currentSessionOrNull()

        assertEquals("user-a", refreshed?.userId)
        assertEquals("access-new", refreshed?.accessToken)
        assertEquals("refresh-new", refreshed?.refreshToken)
        assertEquals("access-new", rebuilt?.accessToken)
        assertEquals("refresh-new", rebuilt?.refreshToken)
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun expiredSessionRefreshesWithoutAnonymousSignup() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(AuthResponse.success("user-a", "access-new", "refresh-new"))
            )
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertEquals("access-new", session?.accessToken)
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshSucceeded)
    }

    @Test
    fun refreshTemporaryFailureDoesNotSignupOrDeleteOldSessionAndGatewayRetries() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(
                    AuthResponse.http(500, """{"error":"server"}"""),
                    AuthResponse.http(500, """{"error":"server"}""")
                )
            )
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()
        val gatewayResult = SupabaseRemoteSyncGateway(
            okHttpClient = OkHttpClient.Builder().addInterceptor(auth).build(),
            sessionProvider = provider,
            supabaseUrl = SUPABASE_URL,
            anonKey = ANON_KEY,
            isConfigured = true
        ).upsertDailyRecord(samplePayload())

        assertEquals(null, session)
        assertEquals("access-old", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals("refresh-old", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(2, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshTemporaryFailure)
        assertTrue(gatewayResult is RemoteSyncResult.RetryableFailure)
    }

    @Test
    fun revokedRefreshTokenDoesNotSignupAndGatewayReturnsFatal() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(AuthResponse.http(400, """{"error_code":"refresh_token_not_found"}"""))
            )
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()
        val gatewayResult = SupabaseRemoteSyncGateway(
            okHttpClient = OkHttpClient.Builder().addInterceptor(auth).build(),
            sessionProvider = provider,
            supabaseUrl = SUPABASE_URL,
            anonKey = ANON_KEY,
            isConfigured = true
        ).upsertDailyRecord(samplePayload())

        assertEquals(null, session)
        assertEquals("access-old", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals("refresh-old", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshPermanentlyRejected)
        assertTrue(gatewayResult is RemoteSyncResult.FatalFailure)
    }

    @Test
    fun refreshUserIdMismatchBlocksCloudIdentityAndGatewayReturnsFatal() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(AuthResponse.success("user-b", "access-new", "refresh-new"))
            )
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()
        val gatewayResult = SupabaseRemoteSyncGateway(
            okHttpClient = OkHttpClient.Builder().addInterceptor(auth).build(),
            sessionProvider = provider,
            supabaseUrl = SUPABASE_URL,
            anonKey = ANON_KEY,
            isConfigured = true
        ).upsertDailyRecord(samplePayload())

        assertEquals(null, session)
        assertEquals("access-old", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals("refresh-old", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshPermanentlyRejected)
        assertTrue(gatewayResult is RemoteSyncResult.FatalFailure)
    }

    @Test
    fun firstRunSignsUpOnceThenReusesStoredSession() = runTest {
        val auth = RecordingAuthInterceptor(
            signupResponses = ArrayDeque(
                listOf(AuthResponse.success("user-a", "access-created", "refresh-created"))
            )
        )
        val provider = provider(auth)

        val created = provider.currentSessionOrNull()
        val reused = provider.currentSessionOrNull()

        assertEquals("user-a", created?.userId)
        assertEquals("access-created", reused?.accessToken)
        assertEquals("refresh-created", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(1, auth.signupCount)
        assertEquals(0, auth.refreshCount)
    }

    @Test
    fun concurrentRequestsSingleFlightOneRefreshAndNoSignup() = runTest {
        saveStoredSession(accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(
                listOf(AuthResponse.success("user-a", "access-new", "refresh-new"))
            )
        )
        val provider = provider(auth)

        val sessions = (1..10)
            .map { async { provider.currentSessionOrNull() } }
            .awaitAll()

        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.signupCount)
        assertEquals(setOf("access-new"), sessions.map { it?.accessToken }.toSet())
        assertEquals(setOf("refresh-new"), sessions.map { it?.refreshToken }.toSet())
        assertEquals("refresh-new", authPrefs().getString(KEY_REFRESH_TOKEN, null))
    }

    private fun provider(auth: RecordingAuthInterceptor): SupabaseAnonymousIdentityProvider {
        return SupabaseAnonymousIdentityProvider(
            context = context,
            localIdentityProvider = FixedLocalIdentityProvider(),
            okHttpClient = OkHttpClient.Builder().addInterceptor(auth).build(),
            supabaseUrl = SUPABASE_URL,
            anonKey = ANON_KEY,
            isConfigured = true
        )
    }

    private fun authPrefs() = context.getSharedPreferences("dayzero_supabase_auth", Context.MODE_PRIVATE)

    private fun saveStoredSession(
        userId: String = "user-a",
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long
    ) {
        authPrefs().edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .commit()
    }

    private fun samplePayload(): SyncPayload {
        return SyncPayload(
            queueId = "q1",
            entityType = "daily_record",
            entityLocalId = "record-1",
            operation = DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD,
            ownerLocalId = LOCAL_OWNER_ID,
            body = JSONObject()
                .put("clientId", "record-1")
                .put("date", "2026-06-20")
                .put("schemaVersion", 1)
        )
    }

    private class FixedLocalIdentityProvider : CurrentIdentityProvider {
        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = LOCAL_OWNER_ID,
                remoteUserId = null,
                authProvider = "local",
                canRemoteSync = false
            )
        }
    }

    private class RecordingAuthInterceptor(
        private val refreshResponses: ArrayDeque<AuthResponse> = ArrayDeque(),
        private val signupResponses: ArrayDeque<AuthResponse> = ArrayDeque()
    ) : Interceptor {
        var refreshCount = 0
            private set
        var signupCount = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val response = when {
                path.endsWith("/auth/v1/token") -> {
                    refreshCount += 1
                    refreshResponses.removeFirstOrNull()
                        ?: AuthResponse.success("user-a", "access-refresh-$refreshCount", "refresh-refresh-$refreshCount")
                }
                path.endsWith("/auth/v1/signup") -> {
                    signupCount += 1
                    signupResponses.removeFirstOrNull()
                        ?: AuthResponse.success("user-a", "access-signup-$signupCount", "refresh-signup-$signupCount")
                }
                path.startsWith("/rest/v1/") -> AuthResponse.http(201, "")
                else -> AuthResponse.http(404, """{"error":"not_found"}""")
            }
            if (response.throwIo) throw IOException("network down")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message(response.message)
                .body(response.body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
    }

    private data class AuthResponse(
        val code: Int,
        val body: String,
        val message: String = "OK",
        val throwIo: Boolean = false
    ) {
        companion object {
            fun success(userId: String, accessToken: String, refreshToken: String): AuthResponse {
                return AuthResponse(
                    code = 200,
                    body = JSONObject()
                        .put("access_token", accessToken)
                        .put("refresh_token", refreshToken)
                        .put("expires_in", 3600)
                        .put("user", JSONObject().put("id", userId))
                        .toString()
                )
            }

            fun http(code: Int, body: String): AuthResponse = AuthResponse(code = code, body = body)
        }
    }

    private companion object {
        private const val SUPABASE_URL = "https://example.supabase.co/"
        private const val ANON_KEY = "anon-key"
        private const val LOCAL_OWNER_ID = "local-owner"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
