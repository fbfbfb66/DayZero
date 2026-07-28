package com.goings.dayzero.data.sync.media

import com.goings.dayzero.data.identity.SupabaseAuthSession
import com.goings.dayzero.data.identity.SupabaseAuthSessionProvider
import com.goings.dayzero.data.identity.SupabaseAuthSessionStatus
import com.goings.dayzero.domain.identity.AppIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupabaseMediaRemotePullGatewayTest {

    private val identity = AppIdentity("local_1", "remote123", "supabase", true)
    private val session = SupabaseAuthSession("remote123", "token", "refresh", Long.MAX_VALUE)

    private val sessionProvider = object : SupabaseAuthSessionProvider {
        override suspend fun currentSessionOrNull(): SupabaseAuthSession = session
        override suspend fun forceRefreshSession(): SupabaseAuthSession = session
        override fun currentSessionStatus(): SupabaseAuthSessionStatus =
            SupabaseAuthSessionStatus.AccessTokenUsable("remote123")
    }

    private fun mockHttpClient(vararg responses: Pair<Int, String>, onRequest: ((Request) -> Unit)? = null): OkHttpClient {
        var callCount = 0
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest?.invoke(request)
                val pair = if (callCount < responses.size) responses[callCount] else responses.last()
                callCount++
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(pair.first)
                    .message("m")
                    .body(pair.second.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun fetchMediaPage_buildsCursorOrderedUrl() = runBlocking {
        var captured: Request? = null
        val gateway = SupabaseMediaRemotePullGateway(
            okHttpClient = mockHttpClient(200 to "[]") { captured = it },
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        gateway.fetchMediaPage(identity, null, 100)

        val url = captured?.url ?: throw AssertionError("no request")
        assertTrue(url.encodedPath.endsWith("media_assets"))
        assertEquals("server_updated_at.asc,id.asc", url.queryParameter("order"))
        assertEquals("100", url.queryParameter("limit"))
        assertNull(url.queryParameter("or"))
    }

    @Test
    fun fetchMediaPage_parsesRowsAndCursor() = runBlocking {
        val body = """
            [{"id":"m1","conversation_id":"c1","source_message_id":"msg1","conversation_order":2,
              "master_object_path":"remote123/m1/master.jpg","thumbnail_object_path":"remote123/m1/thumb.jpg",
              "mime_type":"image/jpeg","width":800,"height":600,"byte_size":1234,"sha256":"h","source":"CAMERA",
              "created_at":"2026-07-11T00:00:00Z","updated_at":"2026-07-11T00:00:01Z","deleted_at":null,
              "server_updated_at":"2026-07-11T00:00:02Z","schema_version":1}]
        """.trimIndent()
        val gateway = SupabaseMediaRemotePullGateway(
            okHttpClient = mockHttpClient(200 to body),
            sessionProvider = sessionProvider,
            isConfigured = true
        )

        val result = gateway.fetchMediaPage(identity, null, 100)

        assertTrue(result is MediaRemotePullResult.Success)
        val page = (result as MediaRemotePullResult.Success).data
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("m1", item.id)
        assertEquals("remote123/m1/master.jpg", item.masterObjectPath)
        assertEquals(2L, item.conversationOrder)
        assertEquals("2026-07-11T00:00:02Z", page.nextCursor?.serverUpdatedAt)
        assertEquals("m1", page.nextCursor?.id)
    }

    @Test
    fun fetchMediaPage_skippedWhenRemoteDisabled() = runBlocking {
        val gateway = SupabaseMediaRemotePullGateway(
            okHttpClient = mockHttpClient(200 to "[]"),
            sessionProvider = sessionProvider,
            isConfigured = true
        )
        val disabled = AppIdentity("local_1", null, "local", false)

        val result = gateway.fetchMediaPage(disabled, null, 100)

        assertTrue(result is MediaRemotePullResult.Skipped)
    }
}
