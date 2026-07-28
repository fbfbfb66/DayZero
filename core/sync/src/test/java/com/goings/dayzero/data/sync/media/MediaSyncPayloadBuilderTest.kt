package com.goings.dayzero.data.sync.media

import com.goings.dayzero.data.local.entity.MediaAssetEntity
import com.goings.dayzero.domain.identity.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class MediaSyncPayloadBuilderTest {

    private val builder = MediaSyncPayloadBuilder()
    private val identity = AppIdentity("local_1", "remote_1", "supabase", true)

    private fun media(
        id: String = "m1",
        deletedAt: Long? = null
    ) = MediaAssetEntity(
        id = id,
        ownerLocalId = "local_1",
        conversationId = "conv-1",
        sourceMessageId = "msg-1",
        conversationOrder = 3,
        masterRelativePath = "media/master/m1.jpg",
        thumbnailRelativePath = "media/thumbnail/m1.jpg",
        mimeType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 12345,
        sha256 = "abc",
        source = "CAMERA",
        lifecycleState = "READY",
        failureCode = null,
        createdAt = 1000L,
        updatedAt = 2000L,
        deletedAt = deletedAt
    )

    @Test
    fun upsertPayload_serializesMetadataAndLocalPaths() {
        val json = builder.upsertPayload(media(), identity)

        assertEquals("m1", json.getString("clientId"))
        assertEquals("remote_1", json.getString("remoteUserId"))
        assertEquals("conv-1", json.getString("conversationId"))
        assertEquals("msg-1", json.getString("sourceMessageId"))
        assertEquals(3L, json.getLong("conversationOrder"))
        assertEquals("media/master/m1.jpg", json.getString("masterRelativePath"))
        assertEquals("media/thumbnail/m1.jpg", json.getString("thumbnailRelativePath"))
        assertEquals("image/jpeg", json.getString("mimeType"))
        assertEquals(800, json.getInt("width"))
        assertEquals(12345L, json.getLong("byteSize"))
        assertEquals("CAMERA", json.getString("source"))
        assertEquals(Instant.ofEpochMilli(1000L).toString(), json.getString("createdAt"))
        assertEquals(Instant.ofEpochMilli(2000L).toString(), json.getString("updatedAt"))
        assertTrue(json.isNull("deletedAt"))
        assertEquals(1, json.getInt("schemaVersion"))
    }

    @Test
    fun upsertPayload_softDeletedSerializesDeletedAt() {
        val json = builder.upsertPayload(media(deletedAt = 5000L), identity)
        assertEquals(Instant.ofEpochMilli(5000L).toString(), json.getString("deletedAt"))
    }

    @Test
    fun downloadPayload_carriesClientIdOnly() {
        val json = builder.downloadPayload(media(), identity)
        assertEquals("m1", json.getString("clientId"))
        assertTrue(json.has("remoteUserId"))
        assertEquals(1, json.getInt("schemaVersion"))
    }
}
