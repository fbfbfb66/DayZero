package com.goings.dayzero.data.sync.title

import com.goings.dayzero.data.local.dao.SyncQueueDao
import com.goings.dayzero.data.local.entity.SyncQueueEntity
import com.goings.dayzero.data.sync.DayZeroSyncConstants
import com.goings.dayzero.domain.identity.AppIdentity
import org.json.JSONObject

class ConversationTitleJobQueueWriter(
    private val syncQueueDao: SyncQueueDao
) {
    suspend fun enqueue(
        conversationId: String,
        firstUserMessageId: String,
        firstUserText: String,
        identity: AppIdentity,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val text = firstUserText.trim()
        if (text.isBlank()) return false

        val payload = JSONObject()
            .put("requestId", ConversationTitleSyncContract.requestId(conversationId, firstUserMessageId))
            .put("conversationId", conversationId)
            .put("firstUserMessageId", firstUserMessageId)
            .put("firstUserText", text)
            .put("schemaVersion", 1)

        return syncQueueDao.insertIgnore(
            SyncQueueEntity(
                id = ConversationTitleSyncContract.queueId(conversationId),
                entityType = ConversationTitleSyncContract.ENTITY_TITLE_JOB,
                entityLocalId = conversationId,
                operation = ConversationTitleSyncContract.OP_SUBMIT_TITLE_JOB,
                payloadJson = payload.toString(),
                status = DayZeroSyncConstants.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
                ownerLocalId = identity.localOwnerId
            )
        ) != -1L
    }
}
