package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.domain.model.media.MediaLifecycleState
import com.example.domain.model.media.MediaSource
import com.example.domain.model.media.NewMediaAssetRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomMediaRepositoryTest {
    private lateinit var database: DayZeroDatabase
    private lateinit var repository: RoomMediaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomMediaRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createStagedMediaAssignsStableConversationOrder() = runTest {
        insertConversation("conv-a")

        val single = repository.createStagedMedia(listOf(request("a1", "conv-a")), now = 1000L)
        assertEquals(1, single.size)
        assertEquals(1L, single.single().conversationOrder)
        assertEquals(MediaLifecycleState.STAGED, single.single().lifecycleState)

        val batch = repository.createStagedMedia(
            listOf(
                request("a2", "conv-a"),
                request("a3", "conv-a"),
                request("a4", "conv-a")
            ),
            now = 2000L
        )

        assertEquals(listOf("a2", "a3", "a4"), batch.map { it.id })
        assertEquals(listOf(2L, 3L, 4L), batch.map { it.conversationOrder })

        val secondBatch = repository.createStagedMedia(
            listOf(request("a5", "conv-a")),
            now = 3000L
        )
        assertEquals(5L, secondBatch.single().conversationOrder)
    }

    @Test
    fun conversationOrdersAreIndependentAndConcurrentCreatesRemainUnique() = runTest {
        insertConversation("conv-a")
        insertConversation("conv-b")

        val aFirst = repository.createStagedMedia(listOf(request("a1", "conv-a")), now = 1000L)
        val bFirst = repository.createStagedMedia(listOf(request("b1", "conv-b")), now = 1000L)
        assertEquals(1L, aFirst.single().conversationOrder)
        assertEquals(1L, bFirst.single().conversationOrder)

        val first = async {
            withContext(Dispatchers.IO) {
                repository.createStagedMedia(
                    listOf(request("a2", "conv-a"), request("a3", "conv-a"), request("a4", "conv-a")),
                    now = 2000L
                )
            }
        }
        val second = async {
            withContext(Dispatchers.IO) {
                repository.createStagedMedia(
                    listOf(request("a5", "conv-a"), request("a6", "conv-a"), request("a7", "conv-a")),
                    now = 2000L
                )
            }
        }
        first.await()
        second.await()

        val orders = repository.getConversationMedia("conv-a").map { it.conversationOrder }
        assertEquals(orders.distinct().size, orders.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), orders.sorted())
    }

    @Test
    fun conversationQueriesAreIsolatedActiveOnlyAndStableSorted() = runTest {
        insertConversation("conv-a")
        insertConversation("conv-b")
        repository.createStagedMedia(listOf(request("a1", "conv-a"), request("a2", "conv-a")), now = 1000L)
        repository.createStagedMedia(listOf(request("b1", "conv-b")), now = 1000L)

        repository.softDeleteMedia("a1", "conv-a", now = 2000L)

        assertEquals(listOf("a2"), repository.getConversationMedia("conv-a").map { it.id })
        assertEquals(listOf("b1"), repository.getConversationMedia("conv-b").map { it.id })
        assertEquals(listOf("a2"), repository.observeConversationMedia("conv-a").first().map { it.id })
    }

    @Test
    fun foreignKeysBlockMissingConversationButDoNotBindSourceMessage() = runTest {
        val missingConversationResult = runCatching {
            repository.createStagedMedia(listOf(request("missing-conv-media", "missing-conv")), now = 1000L)
        }
        assertTrue(missingConversationResult.isFailure)

        insertConversation("conv-a")
        insertMessage("message-1", "conv-a")
        repository.createStagedMedia(listOf(request("a1", "conv-a")), now = 1000L)
        repository.attachMediaToMessage(listOf("a1"), "conv-a", "message-that-does-not-exist", now = 2000L)
        assertEquals("message-that-does-not-exist", database.mediaAssetDao().getById("a1")?.sourceMessageId)

        database.aiChatMessageDao().deleteAllMessages()
        assertNotNull(database.mediaAssetDao().getById("a1"))

        val deleteConversationResult = runCatching { database.conversationDao().deleteAllConversations() }
        assertTrue(deleteConversationResult.isFailure)
        assertNotNull(database.mediaAssetDao().getById("a1"))
    }

    @Test
    fun lifecycleTransitionsValidateReadyMetadataAndDoNotResurrectDeletedMedia() = runTest {
        insertConversation("conv-a")
        repository.createStagedMedia(
            listOf(
                request("ready-from-staged", "conv-a"),
                request("ready-from-failed", "conv-a"),
                request("deleted", "conv-a"),
                request("stale", "conv-a")
            ),
            now = 1000L
        )

        val ready = repository.markMediaReady("ready-from-staged", "conv-a", master(), thumb(), "image/jpeg", 100, 80, 4096, "abc123", now = 2000L)
        assertEquals(MediaLifecycleState.READY, ready.lifecycleState)
        assertNull(ready.failureCode)

        val failed = repository.markMediaFailed("ready-from-failed", "conv-a", "decode_failed", now = 2000L)
        assertEquals(MediaLifecycleState.FAILED, failed.lifecycleState)
        assertEquals("decode_failed", failed.failureCode)
        val readyAgain = repository.markMediaReady("ready-from-failed", "conv-a", master("2"), thumb("2"), "image/jpeg", 120, 90, 5000, "def456", now = 3000L)
        assertEquals(MediaLifecycleState.READY, readyAgain.lifecycleState)

        val idempotentReady = repository.markMediaReady("ready-from-staged", "conv-a", master(), thumb(), "image/jpeg", 100, 80, 4096, "abc123", now = 4000L)
        assertEquals(ready.updatedAt, idempotentReady.updatedAt)

        val invalidReady = runCatching {
            repository.markMediaReady("stale", "conv-a", "", thumb(), "image/jpeg", 100, 80, 4096, "hash", now = 2000L)
        }
        assertTrue(invalidReady.isFailure)
        assertEquals(MediaLifecycleState.STAGED.name, database.mediaAssetDao().getById("stale")?.lifecycleState)

        repository.softDeleteMedia("deleted", "conv-a", now = 2500L)
        assertNotNull(database.mediaAssetDao().getById("deleted")?.deletedAt)
        assertTrue(
            runCatching {
                repository.markMediaReady("deleted", "conv-a", master("deleted"), thumb("deleted"), "image/jpeg", 10, 10, 10, "deleted", now = 3000L)
            }.isFailure
        )
        assertTrue(runCatching { repository.markMediaFailed("deleted", "conv-a", "x", now = 3000L) }.isFailure)
        assertTrue(runCatching { repository.attachMediaToMessage(listOf("deleted"), "conv-a", "message-1", now = 3000L) }.isFailure)

        repository.softDeleteMedia("deleted", "conv-a", now = 4000L)
        assertEquals(2500L, database.mediaAssetDao().getById("deleted")?.deletedAt)

        assertEquals(listOf("stale"), repository.findStaleStagedMedia(updatedBefore = 1500L).map { it.id })
    }

    @Test
    fun batchAttachIsAtomicAndValidatesConversationAndMissingIds() = runTest {
        insertConversation("conv-a")
        insertConversation("conv-b")
        repository.createStagedMedia(listOf(request("a1", "conv-a"), request("a2", "conv-a")), now = 1000L)
        repository.createStagedMedia(listOf(request("b1", "conv-b")), now = 1000L)

        repository.attachMediaToMessage(listOf("a1", "a2"), "conv-a", "message-1", now = 2000L)
        assertEquals("message-1", database.mediaAssetDao().getById("a1")?.sourceMessageId)
        assertEquals("message-1", database.mediaAssetDao().getById("a2")?.sourceMessageId)

        val crossConversation = runCatching {
            repository.attachMediaToMessage(listOf("a1", "b1"), "conv-a", "message-2", now = 3000L)
        }
        assertTrue(crossConversation.isFailure)
        assertEquals("message-1", database.mediaAssetDao().getById("a1")?.sourceMessageId)
        assertNull(database.mediaAssetDao().getById("b1")?.sourceMessageId)

        val missingId = runCatching {
            repository.attachMediaToMessage(listOf("a1", "missing"), "conv-a", "message-3", now = 4000L)
        }
        assertTrue(missingId.isFailure)
        assertEquals("message-1", database.mediaAssetDao().getById("a1")?.sourceMessageId)
    }

    private suspend fun insertConversation(id: String) {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = id,
                conversationDate = "2026-06-18",
                title = id,
                lastMessagePreview = "preview",
                createdAt = 1L,
                updatedAt = 1L,
                lastActivityAt = 1L
            )
        )
    }

    private suspend fun insertMessage(id: String, conversationId: String) {
        database.aiChatMessageDao().insertMessage(
            AiChatMessageEntity(
                id = id,
                conversationId = conversationId,
                role = "User",
                text = "hello",
                createdAt = 2L,
                relatedDraftId = null,
                messageType = "Text",
                updatedAt = 2L
            )
        )
    }

    private fun request(
        id: String,
        conversationId: String,
        ownerLocalId: String = "owner-1",
        source: MediaSource = MediaSource.PHOTO_PICKER
    ): NewMediaAssetRequest {
        return NewMediaAssetRequest(
            id = id,
            ownerLocalId = ownerLocalId,
            conversationId = conversationId,
            source = source
        )
    }

    private fun master(suffix: String = "1"): String = "media/master/$suffix.jpg"

    private fun thumb(suffix: String = "1"): String = "media/thumbnail/$suffix.jpg"
}
