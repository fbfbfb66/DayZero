package com.goings.dayzero.data.remote.auth

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiGatewayAuthInterceptorTest {

    private val publishableKey = "sb_publishable_test_key"

    /** Terminal interceptor: replays queued response codes and records each request it sees. */
    private class TerminalInterceptor(
        private val responseCodes: ArrayDeque<Int>,
    ) : Interceptor {
        val seenAuthorizations = mutableListOf<String?>()
        val seenApiKeys = mutableListOf<String?>()
        var callCount = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            callCount++
            val request = chain.request()
            seenAuthorizations.add(request.header("Authorization"))
            seenApiKeys.add(request.header("apikey"))
            val code = responseCodes.removeFirstOrNull() ?: 200
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Unauthorized")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class FakeTokenProvider(
        private val tokens: Map<Boolean, String?>,
    ) : AiGatewayTokenProvider {
        var forceRefreshCount = 0
            private set
        var normalCount = 0
            private set

        override fun accessToken(forceRefresh: Boolean): String? {
            if (forceRefresh) forceRefreshCount++ else normalCount++
            return tokens[forceRefresh]
        }
    }

    private fun run(
        tokenProvider: AiGatewayTokenProvider,
        responseCodes: List<Int>,
        configured: Boolean = true,
    ): Pair<Response, TerminalInterceptor> {
        val terminal = TerminalInterceptor(ArrayDeque(responseCodes))
        val client = OkHttpClient.Builder()
            .addInterceptor(
                AiGatewayAuthInterceptor(
                    tokenProvider = tokenProvider,
                    publishableKey = publishableKey,
                    publishableKeyConfigured = { configured },
                ),
            )
            .addInterceptor(terminal)
            .build()
        val response = client.newCall(
            Request.Builder()
                .url("https://gateway.invalid/assistant-turn-v2")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
        return response to terminal
    }

    @Test
    fun attachesBearerUserTokenAndApiKey_whenSessionAvailable() {
        val provider = FakeTokenProvider(mapOf(false to "user-access-token"))
        val (response, terminal) = run(provider, listOf(200))
        response.close()

        assertEquals(1, terminal.callCount)
        assertEquals("Bearer user-access-token", terminal.seenAuthorizations[0])
        // publishable key is still sent as apikey, but never as the user credential.
        assertEquals(publishableKey, terminal.seenApiKeys[0])
        assertEquals(0, provider.forceRefreshCount)
    }

    @Test
    fun fallsBackToPublishableBearer_whenNoSession() {
        val provider = FakeTokenProvider(mapOf(false to null, true to null))
        val (response, terminal) = run(provider, listOf(200))
        response.close()

        // Rollback safety for the current Supabase target (verify_jwt=false): no user session yet.
        assertEquals("Bearer $publishableKey", terminal.seenAuthorizations[0])
    }

    @Test
    fun sendsNoAuthorization_whenNoSessionAndNotConfigured() {
        val provider = FakeTokenProvider(mapOf(false to null, true to null))
        val (response, terminal) = run(provider, listOf(200), configured = false)
        response.close()

        assertNull(terminal.seenAuthorizations[0])
        assertNull(terminal.seenApiKeys[0])
    }

    @Test
    fun on401_refreshesOnceAndRetriesWithNewToken() {
        val provider = FakeTokenProvider(mapOf(false to "stale-token", true to "fresh-token"))
        val (response, terminal) = run(provider, listOf(401, 200))
        val code = response.code
        response.close()

        assertEquals(200, code)
        assertEquals(2, terminal.callCount)
        assertEquals("Bearer stale-token", terminal.seenAuthorizations[0])
        assertEquals("Bearer fresh-token", terminal.seenAuthorizations[1])
        assertEquals(1, provider.forceRefreshCount) // refreshed exactly once
    }

    @Test
    fun on401_retriesAtMostOnce_evenIfStill401() {
        val provider = FakeTokenProvider(mapOf(false to "stale-token", true to "fresh-token"))
        val (response, terminal) = run(provider, listOf(401, 401))
        val code = response.code
        response.close()

        assertEquals(401, code)
        assertEquals(2, terminal.callCount) // original + exactly one retry, no storm
        assertEquals(1, provider.forceRefreshCount)
    }

    @Test
    fun on401_doesNotRetry_whenRefreshYieldsNoNewToken() {
        val provider = FakeTokenProvider(mapOf(false to "stale-token", true to "stale-token"))
        val (response, terminal) = run(provider, listOf(401, 200))
        val code = response.code
        response.close()

        assertEquals(401, code)
        assertEquals(1, terminal.callCount) // no retry when the refreshed token is unchanged
    }
}
