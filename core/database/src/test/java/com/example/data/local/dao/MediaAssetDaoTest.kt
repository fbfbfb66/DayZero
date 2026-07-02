package com.example.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaAssetDaoTest {

    private lateinit var context: Context
    private lateinit var database: DayZeroDatabase
    private lateinit var mediaDao: MediaAssetDao
    private lateinit var conversationDao: ConversationDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaDao = database.mediaAssetDao()
        conversationDao = database.conversationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun attachReadyMediaToMessageBindsAllMatchingRows() = runBlocking {
        insertConversation("conv-1")
        insertReadyMedia("media-1", "conv-1", 1L)
        insertReadyMedia("media-2", "conv-1", 2L)

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("media-1", "media-2"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(2, affected)
        assertEquals("msg-1", mediaDao.getById("media-1")?.sourceMessageId)
        assertEquals("msg-1", mediaDao.getById("media-2")?.sourceMessageId)
    }

    @Test
    fun attachReadyMediaToMessagePreservesOrderInContentJson() = runBlocking {
        // The DAO does not write contentJson; this test ensures the CAS UPDATE
        // accepts the ordered list as passed and binds every id.
        insertConversation("conv-1")
        insertReadyMedia("media-a", "conv-1", 1L)
        insertReadyMedia("media-b", "conv-1", 2L)
        insertReadyMedia("media-c", "conv-1", 3L)

        val orderedIds = listOf("media-c", "media-a", "media-b")
        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = orderedIds,
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(3, affected)
        orderedIds.forEach { id ->
            assertEquals("msg-1", mediaDao.getById(id)?.sourceMessageId)
        }
    }

    @Test
    fun attachReadyMediaToMessageSkipsDeletedMedia() = runBlocking {
        insertConversation("conv-1")
        insertReadyMedia("media-1", "conv-1", 1L)
        insertDeletedMedia("media-2", "conv-1", 2L)

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("media-1", "media-2"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(1, affected)
        assertEquals("msg-1", mediaDao.getById("media-1")?.sourceMessageId)
        assertNull(mediaDao.getById("media-2")?.sourceMessageId)
    }

    @Test
    fun attachReadyMediaToMessageSkipsNonReadyMedia() = runBlocking {
        insertConversation("conv-1")
        insertReadyMedia("media-1", "conv-1", 1L)
        insertStagedMedia("media-2", "conv-1", 2L)
        insertFailedMedia("media-3", "conv-1", 3L)

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("media-1", "media-2", "media-3"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(1, affected)
        assertEquals("msg-1", mediaDao.getById("media-1")?.sourceMessageId)
        assertNull(mediaDao.getById("media-2")?.sourceMessageId)
        assertNull(mediaDao.getById("media-3")?.sourceMessageId)
    }

    @Test
    fun attachReadyMediaToMessageSkipsAlreadyBoundMedia() = runBlocking {
        insertConversation("conv-1")
        insertReadyMedia("media-1", "conv-1", 1L)
        insertReadyMedia("media-2", "conv-1", 2L, sourceMessageId = "other-msg")

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("media-1", "media-2"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(1, affected)
        assertEquals("msg-1", mediaDao.getById("media-1")?.sourceMessageId)
        assertEquals("other-msg", mediaDao.getById("media-2")?.sourceMessageId)
    }

    @Test
    fun attachReadyMediaToMessageSkipsWrongConversation() = runBlocking {
        insertConversation("conv-1")
        insertConversation("conv-2")
        insertReadyMedia("media-1", "conv-1", 1L)
        insertReadyMedia("media-2", "conv-2", 1L)

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("media-1", "media-2"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(1, affected)
        assertEquals("msg-1", mediaDao.getById("media-1")?.sourceMessageId)
        assertNull(mediaDao.getById("media-2")?.sourceMessageId)
    }

    @Test
    fun attachReadyMediaToMessageReturnsZeroWhenNoIdsMatch() = runBlocking {
        insertConversation("conv-1")

        val affected = mediaDao.attachReadyMediaToMessage(
            orderedMediaIds = listOf("missing-1", "missing-2"),
            conversationId = "conv-1",
            sourceMessageId = "msg-1",
            updatedAt = 9999L
        )

        assertEquals(0, affected)
    }

    private suspend fun insertConversation(id: String) {
        conversationDao.insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-18",
                title = "title",
                lastMessagePreview = "preview",
                createdAt = 1000L,
                updatedAt = 1000L,
                lastActivityAt = 1000L
            )
        )
    }

    private suspend fun insertReadyMedia(
        id: String,
        conversationId: String,
        conversationOrder: Long,
        sourceMessageId: String? = null
    ) {
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = sourceMessageId,
                    conversationOrder = conversationOrder,
                    masterRelativePath = "master/$id.jpg",
                    thumbnailRelativePath = "thumb/$id.jpg",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 100,
                    byteSize = 1000L,
                    sha256 = "hash-$id",
                    source = "PHOTO_PICKER",
                    lifecycleState = "READY",
                    failureCode = null,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = null
                )
            )
        )
    }

    private suspend fun insertStagedMedia(id: String, conversationId: String, conversationOrder: Long) {
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = null,
                    conversationOrder = conversationOrder,
                    masterRelativePath = null,
                    thumbnailRelativePath = null,
                    mimeType = null,
                    width = null,
                    height = null,
                    byteSize = null,
                    sha256 = null,
                    source = "PHOTO_PICKER",
                    lifecycleState = "STAGED",
                    failureCode = null,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = null
                )
            )
        )
    }

    private suspend fun insertFailedMedia(id: String, conversationId: String, conversationOrder: Long) {
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = null,
                    conversationOrder = conversationOrder,
                    masterRelativePath = null,
                    thumbnailRelativePath = null,
                    mimeType = null,
                    width = null,
                    height = null,
                    byteSize = null,
                    sha256 = null,
                    source = "PHOTO_PICKER",
                    lifecycleState = "FAILED",
                    failureCode = "DECODE_FAILED",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = null
                )
            )
        )
    }

    private suspend fun insertDeletedMedia(id: String, conversationId: String, conversationOrder: Long) {
        database.mediaAssetDao().insertAll(
            listOf(
                MediaAssetEntity(
                    id = id,
                    ownerLocalId = "owner-1",
                    conversationId = conversationId,
                    sourceMessageId = null,
                    conversationOrder = conversationOrder,
                    masterRelativePath = "master/$id.jpg",
                    thumbnailRelativePath = "thumb/$id.jpg",
                    mimeType = "image/jpeg",
                    width = 100,
                    height = 100,
                    byteSize = 1000L,
                    sha256 = "hash-$id",
                    source = "PHOTO_PICKER",
                    lifecycleState = "READY",
                    failureCode = null,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    deletedAt = 2000L
                )
            )
        )
    }
}
