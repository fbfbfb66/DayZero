package com.example.data.sync

import com.example.data.identity.SupabaseAuthSession
import com.example.data.identity.SupabaseAuthSessionProvider
import com.example.data.identity.SupabaseAuthSessionStatus
import com.example.domain.identity.AppIdentity
import com.example.domain.model.sync.ChatSyncServerCursor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SupabaseChatRemotePullGatewayTest {

    private val identity = AppIdentity("user123", "remote123", "supabase", true)
    private val session = SupabaseAuthSession("remote123", "token", "refresh", Long.MAX_VALUE)

    private val sessionProvider = object : SupabaseAuthSessionProvider {
        var refreshCount = 0
        override suspend fun currentSessionOrNull(): SupabaseAuthSession = session
        override suspend fun forceRefreshSession(): SupabaseAuthSession {
            refreshCount++
            return session.copy(accessToken = "refreshed_token")
        }
        override fun currentSessionStatus(): SupabaseAuthSessionStatus = SupabaseAuthSessionStatus.AccessTokenUsable("remote123")
    }

    private fun mockHttpClient(vararg responses: Pair<Int, String>, onRequest: ((Request) -> Unit)? = null): OkHttpClient {
        var callCount = 0
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest?.invoke(request)
                
                val responsePair = if (callCount < responses.size) responses[callCount] else responses.last()
                callCount++
                
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responsePair.first)
                    .message("Message")
                    .body(responsePair.second.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun `fetchConversationPage - no cursor builds correct URL`() = runBlocking {
        var capturedRequest: Request? = null
        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(200 to "[]") { capturedRequest = it },
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        gateway.fetchConversationPage(identity, null, 100)

        val url = capturedRequest?.url ?: throw AssertionError("No request captured")
        assertEquals("server_updated_at.asc,id.asc", url.queryParameter("order"))
        assertEquals("100", url.queryParameter("limit"))
        assertNull(url.queryParameter("or"))
    }

    @Test
    fun `fetchConversationPage - with exact string cursor preserves precision`() = runBlocking {
        var capturedRequest: Request? = null
        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(200 to "[]") { capturedRequest = it },
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        // 2026-06-21T10:00:00.123456Z
        val cursor = ChatSyncServerCursor("2026-06-21T10:00:00.123456Z", "test-id")
        gateway.fetchConversationPage(identity, cursor, 100)

        val url = capturedRequest?.url ?: throw AssertionError("No request captured")
        val orFilter = url.queryParameter("or")
        assertNotNull(orFilter)
        assertEquals("(server_updated_at.gt.2026-06-21T10:00:00.123456Z,and(server_updated_at.eq.2026-06-21T10:00:00.123456Z,id.gt.test-id))", orFilter)
    }

    @Test
    fun `fetchConversationPage - parses tombstone properly`() = runBlocking {
        val jsonResponse = """
            [
              {
                "id": "conv-1",
                "conversation_date": "2023-01-01",
                "title": "Title",
                "last_message_preview": "preview",
                "created_at": "2023-01-01T00:00:00Z",
                "updated_at": "2023-01-01T00:00:00Z",
                "last_activity_at": "2023-01-01T00:00:00Z",
                "deleted_at": "2023-01-01T01:00:00Z",
                "server_updated_at": "2026-06-21T10:00:00.123456Z",
                "schema_version": 1
              }
            ]
        """.trimIndent()

        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(200 to jsonResponse),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchConversationPage(identity, null, 100)
        assertTrue(result is ChatRemotePullResult.Success)
        val page = (result as ChatRemotePullResult.Success).data
        assertEquals(1, page.items.size)
        assertNotNull(page.items[0].deletedAtMillis)
        
        assertNotNull(page.nextCursor)
        assertEquals("conv-1", page.nextCursor?.id)
        assertEquals("2026-06-21T10:00:00.123456Z", page.nextCursor?.serverUpdatedAt)
    }

    @Test
    fun `fetchMessagePage - parses jsonb fields correctly without dropping data`() = runBlocking {
        val jsonResponse = """
            [
              {
                "id": "msg-1",
                "conversation_id": "conv-1",
                "role": "assistant",
                "message_type": "Text",
                "text": "",
                "assistant_cards": [
                  {"type": "recipe", "unknown_field": 42}
                ],
                "created_at": "2023-01-01T00:00:00Z",
                "updated_at": "2023-01-01T00:00:00Z",
                "server_updated_at": "2026-06-21T10:00:00.123456Z",
                "schema_version": 1
              }
            ]
        """.trimIndent()

        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(200 to jsonResponse),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchMessagePage(identity, null, 100)
        assertTrue(result is ChatRemotePullResult.Success)
        val page = (result as ChatRemotePullResult.Success).data
        assertEquals(1, page.items.size)
        val snapshot = page.items[0]
        assertEquals("", snapshot.text)
        
        assertNotNull(snapshot.assistantCardsJson)
        assertTrue(snapshot.assistantCardsJson!!.contains("unknown_field"))
        assertTrue(snapshot.assistantCardsJson!!.contains("42"))
    }
    
    @Test
    fun `fetchConversationPage - 401 triggers single refresh and retry`() = runBlocking {
        val requests = mutableListOf<Request>()
        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(
                401 to "{}",
                200 to "[]"
            ) { requests.add(it) },
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchConversationPage(identity, null, 100)
        assertTrue(result is ChatRemotePullResult.Success)
        assertEquals(1, sessionProvider.refreshCount)
        assertEquals(2, requests.size)
        assertEquals("Bearer token", requests[0].header("Authorization"))
        assertEquals("Bearer refreshed_token", requests[1].header("Authorization"))
    }

    @Test
    fun `fetchConversationPage - 401 triggers refresh but still 401 returns fatal`() = runBlocking {
        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(
                401 to "{}",
                401 to "{}"
            ),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchConversationPage(identity, null, 100)
        assertTrue(result is ChatRemotePullResult.FatalFailure)
        assertEquals("http_401", (result as ChatRemotePullResult.FatalFailure).message)
        assertEquals(1, sessionProvider.refreshCount) // Only refreshed once
    }

    @Test
    fun `fetchConversationPage - 429 returns RetryableFailure`() = runBlocking {
        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(429 to ""),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchConversationPage(identity, null, 100)
        assertTrue(result is ChatRemotePullResult.RetryableFailure)
        assertEquals("http_429", (result as ChatRemotePullResult.RetryableFailure).message)
    }

    @Test
    fun `fetchConversationPage - multiple records same millisecond different microsecond preserves order`() = runBlocking {
        // .123001, .123456, .123999
        val jsonResponse = """
            [
              {"id": "c1", "conversation_date": "2023-01-01", "title": "t1", "last_message_preview": "p1", "created_at": "2023-01-01T00:00:00Z", "updated_at": "2023-01-01T00:00:00Z", "last_activity_at": "2023-01-01T00:00:00Z", "server_updated_at": "2026-06-21T10:00:00.123001Z"},
              {"id": "c2", "conversation_date": "2023-01-01", "title": "t2", "last_message_preview": "p2", "created_at": "2023-01-01T00:00:00Z", "updated_at": "2023-01-01T00:00:00Z", "last_activity_at": "2023-01-01T00:00:00Z", "server_updated_at": "2026-06-21T10:00:00.123456Z"},
              {"id": "c3", "conversation_date": "2023-01-01", "title": "t3", "last_message_preview": "p3", "created_at": "2023-01-01T00:00:00Z", "updated_at": "2023-01-01T00:00:00Z", "last_activity_at": "2023-01-01T00:00:00Z", "server_updated_at": "2026-06-21T10:00:00.123999Z"}
            ]
        """.trimIndent()

        val gateway = SupabaseChatRemotePullGateway(
            okHttpClient = mockHttpClient(200 to jsonResponse),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchConversationPage(identity, null, 2)
        assertTrue(result is ChatRemotePullResult.Success)
        val page = (result as ChatRemotePullResult.Success).data
        assertEquals(3, page.items.size)
        assertEquals("2026-06-21T10:00:00.123999Z", page.nextCursor?.serverUpdatedAt)
        assertEquals("c3", page.nextCursor?.id)
        assertTrue(page.hasMore)
    }

    @Test
    fun `parseRemoteTime - Z format parses to correct epoch millis`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        val epoch = gateway.parseRemoteTime("2026-06-21T13:39:20.154Z")
        assertEquals(1782049160154L, epoch)
    }

    @Test
    fun `parseRemoteTime - plus 00 00 offset format parses to same epoch millis`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        val epoch = gateway.parseRemoteTime("2026-06-21T13:39:20.154+00:00")
        assertEquals(1782049160154L, epoch)
    }

    @Test
    fun `parseRemoteTime - microsecond format parses cleanly`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        // .123456 should parse and convert to milliseconds by truncating/dividing, returning 154 ms
        val epoch = gateway.parseRemoteTime("2026-06-21T13:39:20.154321+00:00")
        assertEquals(1782049160154L, epoch)
    }

    @Test
    fun `parseRemoteTime - positive and negative offsets parse correctly`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        val positive = gateway.parseRemoteTime("2026-06-21T13:39:20.154+08:00")
        assertEquals(1782020360154L, positive) // 8 hours earlier in UTC

        val negative = gateway.parseRemoteTime("2026-06-21T13:39:20.154-05:00")
        assertEquals(1782067160154L, negative) // 5 hours later in UTC
    }

    @Test(expected = java.time.format.DateTimeParseException::class)
    fun `parseRemoteTime - invalid format throws DateTimeParseException`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        gateway.parseRemoteTime("invalid-date-time-string")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRemoteTime - blank string throws IllegalArgumentException`() {
        val gateway = SupabaseChatRemotePullGateway(mockHttpClient(), sessionProvider, isConfigured = true)
        gateway.parseRemoteTime("   ")
    }
}
