package com.goings.dayzero.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration11to12Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "test_migration_11_12_db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun testMigration11To12CreatesMediaAssetsWithoutTouchingOldData() {
        val dbPath = context.getDatabasePath(databaseName)
        dbPath.parentFile?.mkdirs()
        if (dbPath.exists()) dbPath.delete()

        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            createVersion11Schema(db)
            insertVersion11Data(db)
            db.execSQL("PRAGMA user_version = 11")
        }

        val roomDb = Room.databaseBuilder(context, DayZeroDatabase::class.java, databaseName)
            .addMigrations(DayZeroDatabase.MIGRATION_11_12, DayZeroDatabase.MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()

        val supportDb = roomDb.openHelper.writableDatabase
        assertEquals(13, supportDb.version)

        assertConversationPreserved(supportDb)
        assertMessagesPreserved(supportDb)
        assertDailyRecordPreserved(supportDb)
        assertSyncQueuePreserved(supportDb)
        assertMediaAssetsSchema(supportDb)
        assertOldSchemaStillPresent(supportDb)

        roomDb.close()
    }

    private fun createVersion11Schema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_records (
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
            CREATE TABLE IF NOT EXISTS sync_queue (
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_createdAt ON sync_queue(status, createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_nextAttemptAt ON sync_queue(status, nextAttemptAt)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT NOT NULL PRIMARY KEY,
                conversationDate TEXT NOT NULL,
                title TEXT NOT NULL,
                lastMessagePreview TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastActivityAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_conversationDate ON conversations(conversationDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_lastActivityAt ON conversations(lastActivityAt)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_chat_messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                relatedDraftId TEXT,
                messageType TEXT NOT NULL,
                contentJson TEXT,
                assistantCardsJson TEXT,
                suggestedRepliesJson TEXT,
                updatedAt INTEGER NOT NULL DEFAULT 0,
                deletedAt INTEGER DEFAULT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId ON ai_chat_messages(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId_createdAt ON ai_chat_messages(conversationId, createdAt)")
    }

    private fun insertVersion11Data(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO conversations (
                id, conversationDate, title, lastMessagePreview, createdAt, updatedAt, lastActivityAt, deletedAt
            ) VALUES ('conv-1', '2026-06-18', 'title', 'preview', 1000, 1100, 1200, NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_chat_messages (
                id, conversationId, role, text, createdAt, relatedDraftId, messageType,
                contentJson, assistantCardsJson, suggestedRepliesJson, updatedAt, deletedAt
            ) VALUES (
                'msg-user', 'conv-1', 'User', 'hello', 2000, NULL, 'Text',
                NULL, NULL, NULL, 2100, NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_chat_messages (
                id, conversationId, role, text, createdAt, relatedDraftId, messageType,
                contentJson, assistantCardsJson, suggestedRepliesJson, updatedAt, deletedAt
            ) VALUES (
                'msg-assistant', 'conv-1', 'Assistant', 'reply', 3000, NULL, 'Text',
                '{}', '[{"id":"card-1","type":"show_confirm_card","state":"pending"}]', '["ok"]', 3100, NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO ai_chat_messages (
                id, conversationId, role, text, createdAt, relatedDraftId, messageType,
                contentJson, assistantCardsJson, suggestedRepliesJson, updatedAt, deletedAt
            ) VALUES (
                'msg-array', 'conv-1', 'Assistant', 'array', 4000, NULL, 'ChoiceCard',
                '[]', NULL, NULL, 4100, NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO daily_records (
                id, date, status, mealsJson, weightKg, aiSummary, createdAt, updatedAt,
                clientId, remoteId, syncStatus, syncVersion, deletedAt, lastSyncedAt, ownerLocalId
            ) VALUES (
                'record-1', '2026-06-18', 'Confirmed',
                '[{"id":"meal-1","mealType":"Lunch","foods":[{"id":"food-1","name":"rice","calories":300}]}]',
                72.5, 'summary', 5000, 5100, 'record-1', 'remote-record-1', 'PENDING',
                5100, NULL, 5200, 'owner-1'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sync_queue (
                id, entityType, entityLocalId, operation, payloadJson, status, retryCount,
                lastError, createdAt, updatedAt, ownerLocalId, nextAttemptAt, lastAttemptAt,
                lastStatusReason
            ) VALUES (
                'queue-1', 'ai_chat_message', 'msg-assistant', 'UPSERT_AI_CHAT_MESSAGE',
                '{"id":"msg-assistant"}', 'PENDING', 1, 'retry', 6000, 6100,
                'owner-1', 6200, 6300, 'reason'
            )
            """.trimIndent()
        )
    }

    private fun assertConversationPreserved(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT * FROM conversations WHERE id = 'conv-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-06-18", cursor.getString(cursor.getColumnIndexOrThrow("conversationDate")))
            assertEquals("title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("preview", cursor.getString(cursor.getColumnIndexOrThrow("lastMessagePreview")))
            assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")))
            assertEquals(1100L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
            assertEquals(1200L, cursor.getLong(cursor.getColumnIndexOrThrow("lastActivityAt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("deletedAt")))
        }
    }

    private fun assertMessagesPreserved(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT COUNT(*) FROM ai_chat_messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        db.query("SELECT * FROM ai_chat_messages WHERE id = 'msg-user'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("User", cursor.getString(cursor.getColumnIndexOrThrow("role")))
            assertEquals("hello", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("contentJson")))
            assertEquals(2100L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("deletedAt")))
        }
        db.query("SELECT * FROM ai_chat_messages WHERE id = 'msg-assistant'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("contentJson")))
            assertEquals(
                "[{\"id\":\"card-1\",\"type\":\"show_confirm_card\",\"state\":\"pending\"}]",
                cursor.getString(cursor.getColumnIndexOrThrow("assistantCardsJson"))
            )
            assertEquals("[\"ok\"]", cursor.getString(cursor.getColumnIndexOrThrow("suggestedRepliesJson")))
        }
        db.query("SELECT * FROM ai_chat_messages WHERE id = 'msg-array'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("contentJson")))
        }
    }

    private fun assertDailyRecordPreserved(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT * FROM daily_records WHERE id = 'record-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Confirmed", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(72.5, cursor.getDouble(cursor.getColumnIndexOrThrow("weightKg")), 0.001)
            assertEquals("owner-1", cursor.getString(cursor.getColumnIndexOrThrow("ownerLocalId")))
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow("mealsJson")).contains("rice"))
            assertNull(cursor.getStringOrNull(cursor.getColumnIndexOrThrow("deletedAt")))
        }
    }

    private fun assertSyncQueuePreserved(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT * FROM sync_queue WHERE id = 'queue-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ai_chat_message", cursor.getString(cursor.getColumnIndexOrThrow("entityType")))
            assertEquals("UPSERT_AI_CHAT_MESSAGE", cursor.getString(cursor.getColumnIndexOrThrow("operation")))
            assertEquals("PENDING", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("reason", cursor.getString(cursor.getColumnIndexOrThrow("lastStatusReason")))
        }
    }

    private fun assertMediaAssetsSchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT COUNT(*) FROM media_assets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        val columns = tableColumns(db, "media_assets")
        assertColumn(columns, "id", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "ownerLocalId", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "conversationId", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "sourceMessageId", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "conversationOrder", "INTEGER", notNull = true, defaultValue = null)
        assertColumn(columns, "masterRelativePath", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "thumbnailRelativePath", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "mimeType", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "width", "INTEGER", notNull = false, defaultValue = null)
        assertColumn(columns, "height", "INTEGER", notNull = false, defaultValue = null)
        assertColumn(columns, "byteSize", "INTEGER", notNull = false, defaultValue = null)
        assertColumn(columns, "sha256", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "source", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "lifecycleState", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "failureCode", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "createdAt", "INTEGER", notNull = true, defaultValue = null)
        assertColumn(columns, "updatedAt", "INTEGER", notNull = true, defaultValue = null)
        assertColumn(columns, "deletedAt", "INTEGER", notNull = false, defaultValue = null)

        val indexes = indexList(db, "media_assets")
        assertTrue(indexes["index_media_assets_conversationId_conversationOrder"] == true)
        assertTrue(indexes.containsKey("index_media_assets_conversationId_deletedAt_conversationOrder"))
        assertTrue(indexes.containsKey("index_media_assets_sourceMessageId"))
        assertTrue(indexes.containsKey("index_media_assets_lifecycleState_updatedAt"))
        assertTrue(indexes.containsKey("index_media_assets_ownerLocalId"))

        db.query("PRAGMA foreign_key_list(media_assets)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conversations", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("conversationId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
            assertEquals("NO ACTION", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertOldSchemaStillPresent(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertTrue(tables.contains("daily_records"))
        assertTrue(tables.contains("sync_queue"))
        assertTrue(tables.contains("conversations"))
        assertTrue(tables.contains("ai_chat_messages"))
        assertTrue(indexList(db, "sync_queue").containsKey("index_sync_queue_status_createdAt"))
        assertTrue(indexList(db, "sync_queue").containsKey("index_sync_queue_status_nextAttemptAt"))
        assertTrue(indexList(db, "conversations").containsKey("index_conversations_conversationDate"))
        assertTrue(indexList(db, "conversations").containsKey("index_conversations_lastActivityAt"))
        assertTrue(indexList(db, "ai_chat_messages").containsKey("index_ai_chat_messages_conversationId"))
        assertTrue(indexList(db, "ai_chat_messages").containsKey("index_ai_chat_messages_conversationId_createdAt"))
        db.query("PRAGMA foreign_key_list(ai_chat_messages)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conversations", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("conversationId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
        }
    }

    private data class ColumnInfo(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?
    )

    private fun tableColumns(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String
    ): Map<String, ColumnInfo> {
        val columns = mutableMapOf<String, ColumnInfo>()
        db.query("PRAGMA table_info($tableName)").use { cursor ->
            while (cursor.moveToNext()) {
                columns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] = ColumnInfo(
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    defaultValue = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        return columns
    }

    private fun assertColumn(
        columns: Map<String, ColumnInfo>,
        name: String,
        type: String,
        notNull: Boolean,
        defaultValue: String?
    ) {
        val column = columns[name] ?: error("Missing column: $name")
        assertEquals(type, column.type)
        assertEquals("Unexpected not-null for $name", notNull, column.notNull)
        assertEquals("Unexpected default for $name", defaultValue, column.defaultValue)
    }

    private fun indexList(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String
    ): Map<String, Boolean> {
        val indexes = mutableMapOf<String, Boolean>()
        db.query("PRAGMA index_list($tableName)").use { cursor ->
            while (cursor.moveToNext()) {
                indexes[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                    cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
            }
        }
        return indexes
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }
}
