package com.goings.dayzero.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration13to14Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "test_migration_13_14_db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun testMigration13To14AddsTitleSourceWithFallbackDefault() {
        val dbPath = context.getDatabasePath(databaseName)
        dbPath.parentFile?.mkdirs()
        if (dbPath.exists()) dbPath.delete()

        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            createVersion13Schema(db)
            db.execSQL(
                """
                INSERT INTO conversations (
                    id, conversationDate, title, lastMessagePreview, createdAt, updatedAt, lastActivityAt, deletedAt
                ) VALUES ('conv-1', '2026-08-16', '午餐记录', 'preview', 1000, 1100, 1200, NULL)
                """.trimIndent()
            )
            db.execSQL("PRAGMA user_version = 13")
        }

        val roomDb = Room.databaseBuilder(context, DayZeroDatabase::class.java, databaseName)
            .addMigrations(DayZeroDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()

        val supportDb = roomDb.openHelper.writableDatabase
        assertEquals(14, supportDb.version)

        supportDb.query("PRAGMA table_info(conversations)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "titleSource") {
                    found = true
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("'local_fallback'", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue("missing titleSource column", found)
        }

        supportDb.query("SELECT title, titleSource FROM conversations WHERE id = 'conv-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("午餐记录", cursor.getString(0))
            assertEquals("local_fallback", cursor.getString(1))
        }

        roomDb.close()
    }

    private fun createVersion13Schema(db: SQLiteDatabase) {
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
                remoteSyncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                remoteMasterPath TEXT,
                remoteThumbnailPath TEXT,
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_assets_remoteSyncState_updatedAt ON media_assets(remoteSyncState, updatedAt)")
    }
}
