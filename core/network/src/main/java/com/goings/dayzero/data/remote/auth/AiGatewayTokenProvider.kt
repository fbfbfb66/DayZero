package com.goings.dayzero.data.remote.auth

/**
 * Supplies a Supabase **user** access token for the dedicated DayZero AI Gateway auth path.
 *
 * Implementations must reuse the existing signed-in session (fixed-password account) and the
 * existing refresh mechanism — this is not a second login system. The gateway only accepts a
 * real user access token; the publishable/anon key is never a valid credential for it.
 *
 * The method is blocking on purpose: it is called from an OkHttp [okhttp3.Interceptor], which
 * runs on OkHttp's background dispatcher threads.
 */
interface AiGatewayTokenProvider {
    /**
     * Returns a usable user access token, or `null` when no session is available.
     *
     * @param forceRefresh when `true`, bypasses any cached/near-expiry token and forces the
     *   existing refresh path (used for the single retry after a 401).
     */
    fun accessToken(forceRefresh: Boolean): String?
}
