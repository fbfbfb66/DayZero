package com.goings.dayzero.data.sync.title

import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.domain.identity.AppIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationTitleJobQueueWriterTest {
    private val identity = AppIdentity(
        localOwnerId = "local-owner",
        remoteUserId = "00000000-0000-4000-8000-000000000001",
        authProvider = "test",
        canRemoteSync = true
    )

    @Test
    fun stableQueueIdMakesDuplicateEnqueueIdempotentAndPayloadIsTextOnly() = runTest {
        val dao = mockk<SyncQueueDao>()
        val captured = mutableListOf<SyncQueueEntity>()
        coEvery { dao.insertIgnore(capture(captured)) } returnsMany listOf(1L, -1L)
        val writer = ConversationTitleJobQueueWriter(dao)

        val first = writer.enqueue(
            conversationId = "00000000-0000-4000-8000-000000000010",
            firstUserMessageId = "00000000-0000-4000-8000-000000000011",
            firstUserText = "  帮我记录午餐  ",
            identity = identity,
            now = 100L
        )
        val duplicate = writer.enqueue(
            conversationId = "00000000-0000-4000-8000-000000000010",
            firstUserMessageId = "00000000-0000-4000-8000-000000000011",
            firstUserText = "帮我记录午餐",
            identity = identity,
            now = 200L
        )

        assertTrue(first)
        assertFalse(duplicate)
        assertEquals(captured[0].id, captured[1].id)
        val body = JSONObject(captured[0].payloadJson)
        assertEquals("帮我记录午餐", body.getString("firstUserText"))
        assertFalse(body.has("attachments"))
        assertFalse(body.has("base64"))
        assertFalse(body.has("history"))
        assertFalse(body.has("lastMessagePreview"))
    }

    @Test
    fun blankTextDoesNotCreateTitleTask() = runTest {
        val dao = mockk<SyncQueueDao>(relaxed = true)
        val result = ConversationTitleJobQueueWriter(dao).enqueue(
            conversationId = "00000000-0000-4000-8000-000000000010",
            firstUserMessageId = "00000000-0000-4000-8000-000000000011",
            firstUserText = "   ",
            identity = identity
        )

        assertFalse(result)
        coVerify(exactly = 0) { dao.insertIgnore(any()) }
    }
}
