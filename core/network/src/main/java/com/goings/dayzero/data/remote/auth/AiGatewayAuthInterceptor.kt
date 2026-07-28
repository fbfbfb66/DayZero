package com.goings.dayzero.data.remote.auth

import com.goings.dayzero.data.remote.SupabaseConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Dedicated dynamic auth for the DayZero AI Gateway request path (assistant-turn-v2 and
 * assistant-turn-v2-stream).
 *
 * Behavior:
 * - Attaches the current Supabase **user** access token as `Authorization: Bearer <token>`,
 *   obtained (and refreshed near expiry) via [AiGatewayTokenProvider].
 * - Preserves the project `apikey` header (still required by the current Supabase Edge target;
 *   ignored by the gateway). This does NOT alter the global apikey behavior used by sync/other
 *   REST clients — it only applies to the AI clients this interceptor is installed on.
 * - On a `401`, refreshes the session exactly once and retries the request exactly once with the
 *   new token. The retry is a transport-level replay of the same request, so it never causes the
 *   caller to re-persist the user message or regenerate a placeholder.
 * - Never logs the access token.
 *
 * Rollback safety: when no user session is available yet, it falls back to the legacy
 * publishable-key `Authorization` so the current Supabase Edge Function target (verify_jwt=false)
 * stays callable. Against the gateway that fallback is correctly rejected (401).
 */
class AiGatewayAuthInterceptor(
    private val tokenProvider: AiGatewayTokenProvider,
    private val publishableKey: String = SupabaseConfig.SUPABASE_PUBLISHABLE_KEY,
    private val publishableKeyConfigured: () -> Boolean = { SupabaseConfig.isConfigured() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider.accessToken(forceRefresh = false)
        val response = chain.proceed(buildAuthorizedRequest(chain.request(), token))
        if (response.code != 401) {
            return response
        }

        val refreshed = tokenProvider.accessToken(forceRefresh = true)
        if (refreshed.isNullOrBlank() || refreshed == token) {
            return response
        }

        // Discard the 401 body before replaying so the connection can be reused, then retry once.
        response.close()
        return chain.proceed(buildAuthorizedRequest(chain.request(), refreshed))
    }

    private fun buildAuthorizedRequest(original: Request, token: String?): Request {
        val builder = original.newBuilder()
            .header("Content-Type", "application/json")

        if (publishableKeyConfigured()) {
            builder.header("apikey", publishableKey)
        }

        when {
            !token.isNullOrBlank() -> builder.header("Authorization", "Bearer $token")
            publishableKeyConfigured() ->
                builder.header("Authorization", "Bearer $publishableKey")
            else -> builder.removeHeader("Authorization")
        }

        return builder.build()
    }
}
