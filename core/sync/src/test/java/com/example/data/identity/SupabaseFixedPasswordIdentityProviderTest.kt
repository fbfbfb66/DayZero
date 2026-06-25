package com.example.data.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.identity.AppIdentity
import com.example.domain.identity.CurrentIdentityProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SupabaseFixedPasswordIdentityProviderTest {
    private lateinit var context: Context
    private lateinit var localIdentityProvider: FixedLocalIdentityProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authPrefs().edit().clear().commit()
        localIdentityProvider = FixedLocalIdentityProvider()
    }

    @Test
    fun validFixedSessionReturnsWithoutRefreshOrPasswordLogin() = runTest {
        saveStoredSession(userId = FIXED_USER_ID, accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() + 3600)
        val auth = RecordingAuthInterceptor()

        val session = provider(auth).currentSessionOrNull()

        assertEquals(FIXED_USER_ID, session?.userId)
        assertEquals("access-old", session?.accessToken)
        assertEquals(0, auth.refreshCount)
        assertEquals(0, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun expiringFixedSessionRefreshesAndPersistsRotatedTokenPair() = runTest {
        saveStoredSession(userId = FIXED_USER_ID, accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() + 30)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(listOf(AuthResponse.success(FIXED_USER_ID, "access-new", "refresh-new")))
        )

        val refreshed = provider(auth).currentSessionOrNull()
        val rebuilt = provider(RecordingAuthInterceptor()).currentSessionOrNull()

        assertEquals("access-new", refreshed?.accessToken)
        assertEquals("refresh-new", refreshed?.refreshToken)
        assertEquals("access-new", rebuilt?.accessToken)
        assertEquals("refresh-new", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(false, authPrefs().getBoolean(KEY_IS_ANONYMOUS, true))
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun anonymousStoredSessionIsClearedAndFixedPasswordLoginPreservesLocalOwnerId() = runTest {
        saveStoredSession(
            userId = "anonymous-user",
            accessToken = "access-anon",
            refreshToken = "refresh-anon",
            expiresAt = nowSeconds() + 3600,
            isAnonymous = true
        )
        val auth = RecordingAuthInterceptor(
            passwordResponses = ArrayDeque(listOf(AuthResponse.success(FIXED_USER_ID, "access-fixed", "refresh-fixed")))
        )

        val beforeLocalOwner = localIdentityProvider.currentIdentity().localOwnerId
        val session = provider(auth).currentSessionOrNull()
        val afterLocalOwner = localIdentityProvider.currentIdentity().localOwnerId

        assertEquals(FIXED_USER_ID, session?.userId)
        assertEquals("access-fixed", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals("refresh-fixed", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertEquals(beforeLocalOwner, afterLocalOwner)
        assertEquals(0, auth.refreshCount)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun otherUserStoredSessionIsRejectedAndExpectedFixedUserLogsIn() = runTest {
        saveStoredSession(userId = "other-user", accessToken = "access-other", refreshToken = "refresh-other", expiresAt = nowSeconds() + 3600)
        val auth = RecordingAuthInterceptor(
            passwordResponses = ArrayDeque(listOf(AuthResponse.success(FIXED_USER_ID, "access-fixed", "refresh-fixed")))
        )

        val session = provider(auth).currentSessionOrNull()

        assertEquals(FIXED_USER_ID, session?.userId)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun passwordLoginReturningUnexpectedUserIsFatalAndNotPersisted() = runTest {
        val auth = RecordingAuthInterceptor(
            passwordResponses = ArrayDeque(listOf(AuthResponse.success("wrong-user", "access-wrong", "refresh-wrong")))
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertNull(session)
        assertNull(authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshPermanentlyRejected)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun wrongPasswordIsFatalAndDoesNotAnonymousSignup() = runTest {
        val auth = RecordingAuthInterceptor(
            passwordResponses = ArrayDeque(listOf(AuthResponse.http(400, """{"error_code":"invalid_credentials"}""")))
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertNull(session)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshPermanentlyRejected)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun temporaryPasswordNetworkFailureDoesNotAnonymousSignup() = runTest {
        val auth = RecordingAuthInterceptor(
            passwordResponses = ArrayDeque(listOf(AuthResponse.ioFailure()))
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertNull(session)
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshTemporaryFailure)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun temporaryRefreshFailureKeepsOldSessionAndDoesNotPasswordLoginOrSignup() = runTest {
        saveStoredSession(userId = FIXED_USER_ID, accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(listOf(AuthResponse.http(500, """{"error":"server"}""")))
        )
        val provider = provider(auth)

        val session = provider.currentSessionOrNull()

        assertNull(session)
        assertEquals("access-old", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals("refresh-old", authPrefs().getString(KEY_REFRESH_TOKEN, null))
        assertTrue(provider.currentSessionStatus() is SupabaseAuthSessionStatus.RefreshTemporaryFailure)
        assertEquals(1, auth.refreshCount)
        assertEquals(0, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    @Test
    fun permanentRefreshRejectionReauthenticatesOnlyExpectedFixedUser() = runTest {
        saveStoredSession(userId = FIXED_USER_ID, accessToken = "access-old", refreshToken = "refresh-old", expiresAt = nowSeconds() - 10)
        val auth = RecordingAuthInterceptor(
            refreshResponses = ArrayDeque(listOf(AuthResponse.http(400, """{"error_code":"refresh_token_not_found"}"""))),
            passwordResponses = ArrayDeque(listOf(AuthResponse.success(FIXED_USER_ID, "access-fixed", "refresh-fixed")))
        )

        val session = provider(auth).currentSessionOrNull()

        assertEquals(FIXED_USER_ID, session?.userId)
        assertEquals("access-fixed", authPrefs().getString(KEY_ACCESS_TOKEN, null))
        assertEquals(1, auth.refreshCount)
        assertEquals(1, auth.passwordCount)
        assertEquals(0, auth.signupCount)
    }

    private fun provider(auth: RecordingAuthInterceptor): SupabaseFixedPasswordIdentityProvider {
        return SupabaseFixedPasswordIdentityProvider(
            context = context,
            localIdentityProvider = localIdentityProvider,
            okHttpClient = OkHttpClient.Builder().addInterceptor(auth).build(),
            credentialsProvider = FixedDevelopmentAccountCredentialsProvider(
                email = FIXED_EMAIL,
                password = FIXED_PASSWORD,
                expectedUserId = FIXED_USER_ID
            ),
            supabaseUrl = SUPABASE_URL,
            anonKey = ANON_KEY,
            isConfigured = true
        )
    }

    private fun authPrefs() = context.getSharedPreferences("dayzero_supabase_auth", Context.MODE_PRIVATE)

    private fun saveStoredSession(
        userId: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Long,
        isAnonymous: Boolean = false
    ) {
        authPrefs().edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putBoolean(KEY_IS_ANONYMOUS, isAnonymous)
            .commit()
    }

    private class FixedLocalIdentityProvider : CurrentIdentityProvider {
        private var localOwnerId = "local-owner"

        override suspend fun currentIdentity(): AppIdentity {
            return AppIdentity(
                localOwnerId = localOwnerId,
                remoteUserId = null,
                authProvider = "local",
                canRemoteSync = false
            )
        }
    }

    private class RecordingAuthInterceptor(
        private val refreshResponses: ArrayDeque<AuthResponse> = ArrayDeque(),
        private val passwordResponses: ArrayDeque<AuthResponse> = ArrayDeque()
    ) : Interceptor {
        var refreshCount = 0
            private set
        var passwordCount = 0
            private set
        var signupCount = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = when {
                request.url.encodedPath.endsWith("/auth/v1/token") &&
                    request.url.queryParameter("grant_type") == "refresh_token" -> {
                    refreshCount += 1
                    refreshResponses.removeFirstOrNull()
                        ?: AuthResponse.success(FIXED_USER_ID, "access-refresh-$refreshCount", "refresh-refresh-$refreshCount")
                }
                request.url.encodedPath.endsWith("/auth/v1/token") &&
                    request.url.queryParameter("grant_type") == "password" -> {
                    passwordCount += 1
                    passwordResponses.removeFirstOrNull()
                        ?: AuthResponse.success(FIXED_USER_ID, "access-password-$passwordCount", "refresh-password-$passwordCount")
                }
                request.url.encodedPath.endsWith("/auth/v1/signup") -> {
                    signupCount += 1
                    AuthResponse.success("anonymous-user", "access-anon", "refresh-anon", isAnonymous = true)
                }
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
            fun success(
                userId: String,
                accessToken: String,
                refreshToken: String,
                isAnonymous: Boolean = false
            ): AuthResponse {
                return AuthResponse(
                    code = 200,
                    body = JSONObject()
                        .put("access_token", accessToken)
                        .put("refresh_token", refreshToken)
                        .put("expires_in", 3600)
                        .put("user", JSONObject().put("id", userId).put("is_anonymous", isAnonymous))
                        .toString()
                )
            }

            fun http(code: Int, body: String): AuthResponse = AuthResponse(code = code, body = body)
            fun ioFailure(): AuthResponse = AuthResponse(code = 0, body = "", throwIo = true)
        }
    }

    private companion object {
        private const val SUPABASE_URL = "https://example.supabase.co/"
        private const val ANON_KEY = "anon-key"
        private const val FIXED_EMAIL = "fixed@example.test"
        private const val FIXED_PASSWORD = "test-password"
        private const val FIXED_USER_ID = "fixed-user"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_IS_ANONYMOUS = "is_anonymous"

        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
