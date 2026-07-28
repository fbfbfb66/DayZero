package com.goings.dayzero.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration12to13Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "test_migration_12_13_db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun testMigration12To13AddsRemoteSyncColumnsWithoutTouchingOldData() {
        val dbPath = context.getDatabasePath(databaseName)
        dbPath.parentFile?.mkdirs()
        if (dbPath.exists()) dbPath.delete()

        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            createVersion12Schema(db)
            insertVersion12MediaAsset(db)
            db.execSQL("PRAGMA user_version = 12")
        }

        val roomDb = Room.databaseBuilder(context, DayZeroDatabase::class.java, databaseName)
            .addMigrations(DayZeroDatabase.MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()

        val supportDb = roomDb.openHelper.writableDatabase
        assertEquals(13, supportDb.version)

        assertExistingMediaRowMigrated(supportDb)
        assertNewColumns(supportDb)
        assertNewIndexPresent(supportDb)
        assertOldMediaColumnsAndIndexesPreserved(supportDb)

        roomDb.close()
    }

    private fun createVersion12Schema(db: SQLiteDatabase) {
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_assets (
                id TEXT NOT NULL,
                ownerLocalId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                sourceMessageId TEXT,
                conversationOrder INTEGER NOT NULL,
                masterRelativePath TEXT,
                thumbnailRelativePath TEXT,
                mimeType TEXT,
                width INTEGER,
                height INTEGER,
                byteSize INTEGER,
                sha256 TEXT,
                source TEXT NOT NULL,
                lifecycleState TEXT NOT NULL,
                failureCode TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER,
                PRIMARY KEY(id),
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_conversationId_conversationOrder ON media_assets(conversationId, conversationOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_conversationId_deletedAt_conversationOrder ON media_assets(conversationId, deletedAt, conversationOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_sourceMessageId ON media_assets(sourceMessageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_lifecycleState_updatedAt ON media_assets(lifecycleState, updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_ownerLocalId ON media_assets(ownerLocalId)")
    }

    private fun insertVersion12MediaAsset(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO conversations (
                id, conversationDate, title, lastMessagePreview, createdAt, updatedAt, lastActivityAt, deletedAt
            ) VALUES ('conv-1', '2026-07-11', 'title', 'preview', 1000, 1100, 1200, NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO media_assets (
                id, ownerLocalId, conversationId, sourceMessageId, conversationOrder,
                masterRelativePath, thumbnailRelativePath, mimeType, width, height,
                byteSize, sha256, source, lifecycleState, failureCode, createdAt, updatedAt, deletedAt
            ) VALUES (
                'media-1', 'owner-1', 'conv-1', 'msg-1', 1,
                'media/master/media-1.jpg', 'media/thumbnail/media-1.jpg', 'image/jpeg', 800, 600,
                12345, 'abc123', 'CAMERA', 'READY', NULL, 5000, 5100, NULL
            )
            """.trimIndent()
        )
    }

    private fun assertExistingMediaRowMigrated(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT * FROM media_assets WHERE id = 'media-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Old data preserved.
            assertEquals("owner-1", cursor.getString(cursor.getColumnIndexOrThrow("ownerLocalId")))
            assertEquals("media/master/media-1.jpg", cursor.getString(cursor.getColumnIndexOrThrow("masterRelativePath")))
            assertEquals("READY", cursor.getString(cursor.getColumnIndexOrThrow("lifecycleState")))
            assertEquals("abc123", cursor.getString(cursor.getColumnIndexOrThrow("sha256")))
            // New columns default correctly for pre-existing rows.
            assertEquals("LOCAL_ONLY", cursor.getString(cursor.getColumnIndexOrThrow("remoteSyncState")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("remoteMasterPath")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("remoteThumbnailPath")))
        }
    }

    private fun assertNewColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val columns = tableColumns(db, "media_assets")
        assertColumn(columns, "remoteSyncState", "TEXT", notNull = true, defaultValue = "'LOCAL_ONLY'")
        assertColumn(columns, "remoteMasterPath", "TEXT", notNull = false, defaultValue = null)
        assertColumn(columns, "remoteThumbnailPath", "TEXT", notNull = false, defaultValue = null)
    }

    private fun assertNewIndexPresent(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        assertTrue(indexList(db, "media_assets").containsKey("index_media_assets_remoteSyncState_updatedAt"))
    }

    private fun assertOldMediaColumnsAndIndexesPreserved(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val columns = tableColumns(db, "media_assets")
        assertColumn(columns, "id", "TEXT", notNull = true, defaultValue = null)
        assertColumn(columns, "conversationOrder", "INTEGER", notNull = true, defaultValue = null)
        assertColumn(columns, "lifecycleState", "TEXT", notNull = true, defaultValue = null)
        val indexes = indexList(db, "media_assets")
        assertTrue(indexes["index_media_assets_conversationId_conversationOrder"] == true)
        assertTrue(indexes.containsKey("index_media_assets_conversationId_deletedAt_conversationOrder"))
        assertTrue(indexes.containsKey("index_media_assets_sourceMessageId"))
        assertTrue(indexes.containsKey("index_media_assets_lifecycleState_updatedAt"))
        assertTrue(indexes.containsKey("index_media_assets_ownerLocalId"))
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
