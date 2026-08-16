package com.goings.dayzero.data.sync

import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.data.sync.title.ConversationTitleSyncContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncPayloadParserTest {

    private val parser = SyncPayloadParser()

    private fun queueEntity(operation: String, payloadJson: String) = SyncQueueEntity(
        id = "queue-id",
        entityType = "entity",
        entityLocalId = "local-id",
        operation = operation,
        payloadJson = payloadJson,
        status = DayZeroSyncConstants.STATUS_PENDING,
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun titleJobPayloadWithoutClientIdParses() {
        val payload = JSONObject()
            .put("requestId", ConversationTitleSyncContract.requestId("conv", "msg"))
            .put("conversationId", "conv")
            .put("firstUserMessageId", "msg")
            .put("firstUserText", "帮我记录午餐")
            .put("schemaVersion", 1)
            .toString()

        val result = parser.parse(
            queueEntity(ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB, payload)
        )

        assertTrue(result.isSuccess)
        assertEquals(
            ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB,
            result.getOrThrow().operation
        )
    }

    @Test
    fun unknownOperationIsRejected() {
        val result = parser.parse(queueEntity("SOME_FUTURE_OP", "{}"))

        assertTrue(result.isFailure)
        assertEquals(
            "unsupported operation SOME_FUTURE_OP",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun nonTitlePayloadStillRequiresClientId() {
        val result = parser.parse(
            queueEntity(DayZeroSyncConstants.OP_UPSERT_DAILY_RECORD, "{}")
        )

        assertTrue(result.isFailure)
        assertEquals("payload clientId is blank", result.exceptionOrNull()?.message)
    }
}
