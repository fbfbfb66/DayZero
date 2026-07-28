package com.goings.dayzero.data.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAiGatewayTokenProviderTest {

    private class FakeSessionProvider(
        private val current: SupabaseAuthSession?,
        private val refreshed: SupabaseAuthSession?,
    ) : SupabaseAuthSessionProvider {
        var currentCalls = 0
            private set
        var refreshCalls = 0
            private set

        override suspend fun currentSessionOrNull(): SupabaseAuthSession? {
            currentCalls++
            return current
        }

        override suspend fun forceRefreshSession(): SupabaseAuthSession? {
            refreshCalls++
            return refreshed
        }
    }

    private fun session(token: String, expiresInSeconds: Long): SupabaseAuthSession {
        val now = System.currentTimeMillis() / 1000
        return SupabaseAuthSession(
            userId = "user-1",
            accessToken = token,
            refreshToken = "refresh",
            expiresAtEpochSeconds = now + expiresInSeconds,
        )
    }

    @Test
    fun usableSession_returnsToken_withoutRefreshing() {
        val provider = FakeSessionProvider(
            current = session("usable-token", expiresInSeconds = 3600),
            refreshed = session("should-not-be-used", expiresInSeconds = 3600),
        )
        val subject = SessionAiGatewayTokenProvider(provider)

        assertEquals("usable-token", subject.accessToken(forceRefresh = false))
        assertEquals(0, provider.refreshCalls)
    }

    @Test
    fun nearExpirySession_triggersRefresh() {
        val provider = FakeSessionProvider(
            // expires within the 60s safety margin -> not usable -> refresh.
            current = session("stale-token", expiresInSeconds = 30),
            refreshed = session("fresh-token", expiresInSeconds = 3600),
        )
        val subject = SessionAiGatewayTokenProvider(provider)

        assertEquals("fresh-token", subject.accessToken(forceRefresh = false))
        assertEquals(1, provider.refreshCalls)
    }

    @Test
    fun forceRefresh_goesStraightToRefreshMechanism() {
        val provider = FakeSessionProvider(
            current = session("usable-token", expiresInSeconds = 3600),
            refreshed = session("fresh-token", expiresInSeconds = 3600),
        )
        val subject = SessionAiGatewayTokenProvider(provider)

        assertEquals("fresh-token", subject.accessToken(forceRefresh = true))
        assertEquals(1, provider.refreshCalls)
        assertEquals(0, provider.currentCalls)
    }

    @Test
    fun noSessionAndNoRefresh_returnsNull() {
        val provider = FakeSessionProvider(current = null, refreshed = null)
        val subject = SessionAiGatewayTokenProvider(provider)

        assertNull(subject.accessToken(forceRefresh = false))
    }
}
