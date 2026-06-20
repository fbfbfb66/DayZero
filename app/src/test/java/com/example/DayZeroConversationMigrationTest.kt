package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.DayZeroDatabase
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DayZeroConversationMigrationTest {
    private lateinit var context: Context
    private val databases = mutableListOf<DayZeroDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB_NAME)
    }

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        context.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun migrationWithNoChatMessagesCreatesNoEmptyConversation() {
        createVersion9Database(emptyList())

        val database = openMigratedDatabase()

        assertEquals(0, database.openHelper.readableDatabase.count("conversations"))
        assertEquals(0, database.openHelper.readableDatabase.count("ai_chat_messages"))
    }

    @Test
    fun migrationWithOneDayMessagesCreatesOneConversationAndPreservesOrder() {
        createVersion9Database(
            listOf(
                legacyMessage("m1", "User", "  今天午饭吃了鸡蛋\n米饭  ", timestamp("2026-06-18T02:00:00Z")),
                legacyMessage("m2", "Assistant", "我帮你整理好了", timestamp("2026-06-18T02:00:01Z")),
                legacyMessage("m3", "Assistant", "", timestamp("2026-06-18T02:00:02Z"), assistantCardsJson = """[{"type":"show_confirm_card"}]""")
            )
        )

        val database = openMigratedDatabase()
        val conversations = database.openHelper.readableDatabase.queryRows("SELECT id, conversationDate, title FROM conversations")
        val messages = database.openHelper.readableDatabase.queryRows(
            "SELECT id, conversationId FROM ai_chat_messages ORDER BY createdAt ASC, id ASC"
        )

        assertEquals(1, conversations.size)
        assertTrue(conversations.single().getValue("title")!!.contains("今天午饭吃了鸡蛋 米饭"))
        assertEquals(listOf("m1", "m2", "m3"), messages.map { it.getValue("id") })
        assertTrue(messages.all { it.getValue("conversationId") == conversations.single().getValue("id") })
    }

    @Test
    fun migrationWithMultipleNaturalDaysCreatesConversationPerDay() {
        createVersion9Database(
            listOf(
                legacyMessage("d1-m1", "User", "第一天", timestamp("2026-06-17T01:00:00Z")),
                legacyMessage("d2-m1", "User", "第二天", timestamp("2026-06-18T01:00:00Z")),
                legacyMessage("d2-m2", "Assistant", "收到", timestamp("2026-06-18T01:00:01Z"))
            )
        )

        val database = openMigratedDatabase()
        val conversations = database.openHelper.readableDatabase.queryRows(
            "SELECT id, conversationDate FROM conversations ORDER BY conversationDate ASC"
        )
        val messages = database.openHelper.readableDatabase.queryRows(
            "SELECT id, conversationId FROM ai_chat_messages ORDER BY createdAt ASC"
        )

        assertEquals(2, conversations.size)
        assertEquals(listOf("2026-06-17", "2026-06-18"), conversations.map { it.getValue("conversationDate") })
        assertEquals(conversations[0].getValue("id"), messages[0].getValue("conversationId"))
        assertEquals(conversations[1].getValue("id"), messages[1].getValue("conversationId"))
        assertEquals(conversations[1].getValue("id"), messages[2].getValue("conversationId"))
    }

    @Test
    fun migrationPreservesConfirmCancelAndInteractionCardPayloads() {
        val confirmPayload = """[{"type":"show_confirm_card","payload":{"state":"confirmed","items":[{"name":"rice"}]}}]"""
        val cancelPayload = """[{"type":"show_confirm_card","payload":{"state":"cancelled","items":[{"name":"noodle"}]}}]"""
        val interactionPayload = """{"title":"选择","resolved":true}"""
        createVersion9Database(
            listOf(
                legacyMessage("card-1", "Assistant", "", timestamp("2026-06-18T02:00:00Z"), assistantCardsJson = confirmPayload),
                legacyMessage("card-2", "Assistant", "", timestamp("2026-06-18T02:00:01Z"), assistantCardsJson = cancelPayload),
                legacyMessage("choice-1", "Assistant", "", timestamp("2026-06-18T02:00:02Z"), messageType = "ChoiceCard", contentJson = interactionPayload)
            )
        )

        val database = openMigratedDatabase()
        val rows = database.openHelper.readableDatabase.queryRows(
            "SELECT id, contentJson, assistantCardsJson FROM ai_chat_messages ORDER BY createdAt ASC"
        )

        assertEquals(3, rows.size)
        assertEquals(confirmPayload, rows[0]["assistantCardsJson"])
        assertEquals(cancelPayload, rows[1]["assistantCardsJson"])
        assertEquals(interactionPayload, rows[2]["contentJson"])
        assertEquals(1, database.openHelper.readableDatabase.count("conversations"))
    }

    @Test
    fun migrationProducesNoOrphanMessagesAndReopenDoesNotDuplicate() {
        createVersion9Database(
            listOf(
                legacyMessage("m1", "User", "早饭", timestamp("2026-06-18T00:30:00Z")),
                legacyMessage("m2", "User", "晚饭", timestamp("2026-06-19T12:30:00Z"))
            )
        )

        openMigratedDatabase().close()
        databases.clear()
        val reopened = openMigratedDatabase()
        val orphanCount = reopened.openHelper.readableDatabase.count(
            """
            ai_chat_messages
            LEFT JOIN conversations ON ai_chat_messages.conversationId = conversations.id
            WHERE conversations.id IS NULL
            """.trimIndent()
        )

        assertEquals(0, orphanCount)
        assertEquals(2, reopened.openHelper.readableDatabase.count("conversations"))
        assertEquals(2, reopened.openHelper.readableDatabase.count("ai_chat_messages"))
    }

    @Test
    fun conversationAndMessageDaosQueryByConversationAndSoftDelete() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, DayZeroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        databases += database
        val conversationDao = database.conversationDao()
        val chatDao = database.aiChatMessageDao()

        conversationDao.insertConversation(conversation("c1", lastActivityAt = 1_000L))
        conversationDao.insertConversation(conversation("c2", lastActivityAt = 3_000L))
        chatDao.insertMessage(message("m2", "c1", 2_000L))
        chatDao.insertMessage(message("m1", "c1", 1_000L))
        chatDao.insertMessage(message("m3", "c2", 1_500L))

        assertEquals(listOf("m1", "m2"), chatDao.getMessagesByConversationId("c1").map { it.id })
        assertEquals(listOf("m3"), chatDao.getMessagesByConversationId("c2").map { it.id })
        assertEquals(listOf("c2", "c1"), conversationDao.observeConversationsByLastActivity().first().map { it.id })

        conversationDao.updateConversationSummary(
            id = "c1",
            title = "updated",
            lastMessagePreview = "preview",
            lastActivityAt = 4_000L,
            updatedAt = 4_000L
        )
        val updated = conversationDao.getConversationById("c1")
        assertNotNull(updated)
        assertEquals("preview", updated?.lastMessagePreview)
        assertEquals(4_000L, updated?.lastActivityAt)
        assertEquals(listOf("c1", "c2"), conversationDao.observeConversationsByLastActivity().first().map { it.id })

        conversationDao.softDeleteConversation("c1", deletedAt = 5_000L)
        assertEquals(listOf("c2"), conversationDao.observeConversationsByLastActivity().first().map { it.id })
    }

    private fun openMigratedDatabase(): DayZeroDatabase {
        val database = Room.databaseBuilder(context, DayZeroDatabase::class.java, TEST_DB_NAME)
            .allowMainThreadQueries()
            .addMigrations(*DayZeroDatabase.ALL_MIGRATIONS)
            .build()
        databases += database
        database.openHelper.writableDatabase
        return database
    }

    private fun createVersion9Database(messages: List<LegacyMessage>) {
        val dbFile: File = context.getDatabasePath(TEST_DB_NAME)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("PRAGMA user_version = 9")
        db.execSQL(
            """
            CREATE TABLE daily_records (
                id TEXT NOT NULL PRIMARY KEY,
                date TEXT NOT NULL,
                status TEXT NOT NULL,
                mealsJson TEXT NOT NULL,
                weightKg REAL,
                aiSummary TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                clientId TEXT NOT NULL DEFAULT '',
                remoteId TEXT,
                syncStatus TEXT NOT NULL DEFAULT 'PENDING',
                syncVersion INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER,
                lastSyncedAt INTEGER,
                ownerLocalId TEXT NOT NULL DEFAULT 'local_uninitialized'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sync_queue (
                id TEXT NOT NULL PRIMARY KEY,
                entityType TEXT NOT NULL,
                entityLocalId TEXT NOT NULL,
                operation TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                status TEXT NOT NULL,
                retryCount INTEGER NOT NULL,
                lastError TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                ownerLocalId TEXT NOT NULL DEFAULT 'local_uninitialized',
                nextAttemptAt INTEGER NOT NULL DEFAULT 0,
                lastAttemptAt INTEGER NOT NULL DEFAULT 0,
                lastStatusReason TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_sync_queue_status_createdAt ON sync_queue(status, createdAt)")
        db.execSQL("CREATE INDEX index_sync_queue_status_nextAttemptAt ON sync_queue(status, nextAttemptAt)")
        db.execSQL(
            """
            CREATE TABLE ai_chat_messages (
                id TEXT NOT NULL PRIMARY KEY,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                relatedDraftId TEXT,
                messageType TEXT NOT NULL,
                contentJson TEXT,
                assistantCardsJson TEXT,
                suggestedRepliesJson TEXT
            )
            """.trimIndent()
        )
        messages.forEach { message ->
            db.execSQL(
                """
                INSERT INTO ai_chat_messages (
                    id, role, text, createdAt, relatedDraftId, messageType,
                    contentJson, assistantCardsJson, suggestedRepliesJson
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    message.id,
                    message.role,
                    message.text,
                    message.createdAt,
                    message.relatedDraftId,
                    message.messageType,
                    message.contentJson,
                    message.assistantCardsJson,
                    message.suggestedRepliesJson
                )
            )
        }
        db.close()
    }

    private fun conversation(id: String, lastActivityAt: Long): ConversationEntity {
        return ConversationEntity(
            id = id,
            conversationDate = "2026-06-18",
            title = id,
            lastMessagePreview = id,
            createdAt = lastActivityAt,
            updatedAt = lastActivityAt,
            lastActivityAt = lastActivityAt
        )
    }

    private fun message(id: String, conversationId: String, createdAt: Long): AiChatMessageEntity {
        return AiChatMessageEntity(
            id = id,
            conversationId = conversationId,
            role = "User",
            text = id,
            createdAt = createdAt,
            relatedDraftId = null,
            messageType = "Text"
        )
    }

    private fun legacyMessage(
        id: String,
        role: String,
        text: String,
        createdAt: Long,
        relatedDraftId: String? = null,
        messageType: String = "Text",
        contentJson: String? = null,
        assistantCardsJson: String? = null,
        suggestedRepliesJson: String? = null
    ): LegacyMessage {
        return LegacyMessage(
            id = id,
            role = role,
            text = text,
            createdAt = createdAt,
            relatedDraftId = relatedDraftId,
            messageType = messageType,
            contentJson = contentJson,
            assistantCardsJson = assistantCardsJson,
            suggestedRepliesJson = suggestedRepliesJson
        )
    }

    private data class LegacyMessage(
        val id: String,
        val role: String,
        val text: String,
        val createdAt: Long,
        val relatedDraftId: String?,
        val messageType: String,
        val contentJson: String?,
        val assistantCardsJson: String?,
        val suggestedRepliesJson: String?
    )

    private fun timestamp(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    private fun SupportSQLiteDatabase.count(fromClause: String): Int {
        query("SELECT COUNT(*) FROM $fromClause").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun SupportSQLiteDatabase.queryRows(sql: String): List<Map<String, String?>> {
        query(sql).use { cursor ->
            val rows = mutableListOf<Map<String, String?>>()
            while (cursor.moveToNext()) {
                rows += cursor.columnNames.associateWith { column ->
                    val index = cursor.getColumnIndexOrThrow(column)
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }
            }
            return rows
        }
    }

    private companion object {
        private const val TEST_DB_NAME = "dayzero-conversation-migration-test.db"
    }
}
