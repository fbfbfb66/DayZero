package com.goings.dayzero.data.identity

import com.goings.dayzero.data.remote.auth.AiGatewayTokenProvider
import kotlinx.coroutines.runBlocking

/**
 * Bridges the existing [SupabaseAuthSessionProvider] (the fixed-password account session used
 * across sync) to the AI Gateway's [AiGatewayTokenProvider].
 *
 * This intentionally reuses the one session and its refresh mechanism — it does not create a
 * second login system, and it hardcodes no token/credential. Near-expiry tokens are refreshed via
 * [SupabaseAuthSession.isUsable] (which keeps a 60s safety margin); a `forceRefresh` goes straight
 * to [SupabaseAuthSessionProvider.forceRefreshSession].
 */
class SessionAiGatewayTokenProvider(
    private val sessionProvider: SupabaseAuthSessionProvider,
) : AiGatewayTokenProvider {

    override fun accessToken(forceRefresh: Boolean): String? = runBlocking {
        val session = if (forceRefresh) {
            sessionProvider.forceRefreshSession()
        } else {
            sessionProvider.currentSessionOrNull()?.takeIf { it.isUsable() }
                ?: sessionProvider.forceRefreshSession()
        }
        session?.accessToken?.takeIf { it.isNotBlank() }
    }
}
