package com.example.data.local.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10to11Test {

    @Test
    fun testMigration10To11() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath("test_migration_10_11_db")
        dbPath.parentFile?.mkdirs()
        if (dbPath.exists()) dbPath.delete()
        
        // 1. Create a real file-based V10 SQLite database
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        
        // 3. Set PRAGMA user_version = 10
        db.version = 10

        // 2. Set up V10 tables, foreign keys and indexes
        // We must set up all tables that are part of the Room schema in V10
        
        // a. daily_records table
        db.execSQL("""
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
        """)

        // b. sync_queue table and its indexes
        db.execSQL("""
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
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_createdAt ON sync_queue(status, createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_nextAttemptAt ON sync_queue(status, nextAttemptAt)")

        // c. conversations table
        db.execSQL("""
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
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_conversationDate ON conversations(conversationDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_lastActivityAt ON conversations(lastActivityAt)")

        // d. ai_chat_messages table and its indexes
        db.execSQL("""
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
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId ON ai_chat_messages(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId_createdAt ON ai_chat_messages(conversationId, createdAt)")

        // 4. Insert historical Conversation and Messages
        db.execSQL("""
            INSERT INTO conversations (id, conversationDate, title, lastMessagePreview, createdAt, updatedAt, lastActivityAt) 
            VALUES ('conv1', '2023-01-01', 'title', 'preview', 1000, 1000, 1000)
        """)
        
        // Case 1: user message
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType) 
            VALUES ('msg_user', 'conv1', 'User', 'hello user', 2000, 'Text')
        """)
        
        // Case 2: assistant message
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType) 
            VALUES ('msg_assistant', 'conv1', 'Assistant', 'hello assistant', 3000, 'Text')
        """)
        
        // Case 3: card-only message
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, assistantCardsJson) 
            VALUES ('msg_card_only', 'conv1', 'Assistant', '', 4000, 'ChoiceCard', '[{"id":"card_only_id","type":"ChoiceCard"}]')
        """)
        
        // Case 4: contentJson = null
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, contentJson) 
            VALUES ('msg_content_null', 'conv1', 'Assistant', 'null content', 5000, 'Text', null)
        """)
        
        // Case 5: contentJson = {}
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, contentJson) 
            VALUES ('msg_content_empty_obj', 'conv1', 'Assistant', 'empty obj content', 6000, 'ChoiceCard', '{}')
        """)
        
        // Case 6: contentJson = []
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, contentJson) 
            VALUES ('msg_content_empty_arr', 'conv1', 'Assistant', 'empty arr content', 7000, 'ChoiceCard', '[]')
        """)
        
        // Case 7: unknown assistant card fields
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, assistantCardsJson) 
            VALUES ('msg_unknown_fields', 'conv1', 'Assistant', 'unknown card fields', 8000, 'ChoiceCard', '[{"id":"unknown_card","type":"MysteryCard","custom_data":"xyz"}]')
        """)
        
        // Case 8: message with suggested replies
        db.execSQL("""
            INSERT INTO ai_chat_messages (id, conversationId, role, text, createdAt, messageType, suggestedRepliesJson) 
            VALUES ('msg_suggested_replies', 'conv1', 'Assistant', 'suggested replies', 9000, 'Text', '["Reply 1", "Reply 2"]')
        """)
        
        // 5. Close SQLite
        db.close()

        // 6. Use real Room databaseBuilder to open database and apply migration
        val roomDb = Room.databaseBuilder(
            context,
            DayZeroDatabase::class.java,
            "test_migration_10_11_db"
        )
        .addMigrations(DayZeroDatabase.MIGRATION_10_11)
        .allowMainThreadQueries()
        .build()

        // 7. Verify migration results using real DAO
        val messages = kotlinx.coroutines.runBlocking {
            roomDb.aiChatMessageDao().getMessagesForChatBackfill(0, "", 10)
        }
        
        // Assertions: Room successfully opened & all Message rows preserved
        assertEquals(8, messages.size)

        // Let's index messages by ID for convenient assertions
        val messageMap = messages.associateBy { it.id }

        // Assert msg_user
        val msgUser = messageMap["msg_user"]!!
        assertEquals("conv1", msgUser.conversationId)
        assertEquals("User", msgUser.role)
        assertEquals("hello user", msgUser.text)
        assertEquals(2000L, msgUser.createdAt)
        assertEquals("Text", msgUser.messageType)
        assertNull(msgUser.contentJson)
        assertNull(msgUser.assistantCardsJson)
        assertNull(msgUser.suggestedRepliesJson)
        assertEquals(2000L, msgUser.updatedAt) // updatedAt == createdAt
        assertNull(msgUser.deletedAt)          // deletedAt == null

        // Assert msg_assistant
        val msgAssistant = messageMap["msg_assistant"]!!
        assertEquals("conv1", msgAssistant.conversationId)
        assertEquals("Assistant", msgAssistant.role)
        assertEquals("hello assistant", msgAssistant.text)
        assertEquals(3000L, msgAssistant.createdAt)
        assertEquals("Text", msgAssistant.messageType)
        assertEquals(3000L, msgAssistant.updatedAt)
        assertNull(msgAssistant.deletedAt)

        // Assert msg_card_only
        val msgCardOnly = messageMap["msg_card_only"]!!
        assertEquals("conv1", msgCardOnly.conversationId)
        assertEquals("Assistant", msgCardOnly.role)
        assertEquals("", msgCardOnly.text)
        assertEquals(4000L, msgCardOnly.createdAt)
        assertEquals("ChoiceCard", msgCardOnly.messageType)
        assertEquals("[{\"id\":\"card_only_id\",\"type\":\"ChoiceCard\"}]", msgCardOnly.assistantCardsJson)
        assertEquals(4000L, msgCardOnly.updatedAt)
        assertNull(msgCardOnly.deletedAt)

        // Assert msg_content_null
        val msgContentNull = messageMap["msg_content_null"]!!
        assertEquals("conv1", msgContentNull.conversationId)
        assertEquals("Assistant", msgContentNull.role)
        assertEquals("null content", msgContentNull.text)
        assertEquals(5000L, msgContentNull.createdAt)
        assertEquals("Text", msgContentNull.messageType)
        assertNull(msgContentNull.contentJson)
        assertEquals(5000L, msgContentNull.updatedAt)
        assertNull(msgContentNull.deletedAt)

        // Assert msg_content_empty_obj
        val msgContentEmptyObj = messageMap["msg_content_empty_obj"]!!
        assertEquals("conv1", msgContentEmptyObj.conversationId)
        assertEquals("Assistant", msgContentEmptyObj.role)
        assertEquals("empty obj content", msgContentEmptyObj.text)
        assertEquals(6000L, msgContentEmptyObj.createdAt)
        assertEquals("ChoiceCard", msgContentEmptyObj.messageType)
        assertEquals("{}", msgContentEmptyObj.contentJson)
        assertEquals(6000L, msgContentEmptyObj.updatedAt)
        assertNull(msgContentEmptyObj.deletedAt)

        // Assert msg_content_empty_arr
        val msgContentEmptyArr = messageMap["msg_content_empty_arr"]!!
        assertEquals("conv1", msgContentEmptyArr.conversationId)
        assertEquals("Assistant", msgContentEmptyArr.role)
        assertEquals("empty arr content", msgContentEmptyArr.text)
        assertEquals(7000L, msgContentEmptyArr.createdAt)
        assertEquals("ChoiceCard", msgContentEmptyArr.messageType)
        assertEquals("[]", msgContentEmptyArr.contentJson)
        assertEquals(7000L, msgContentEmptyArr.updatedAt)
        assertNull(msgContentEmptyArr.deletedAt)

        // Assert msg_unknown_fields
        val msgUnknownFields = messageMap["msg_unknown_fields"]!!
        assertEquals("conv1", msgUnknownFields.conversationId)
        assertEquals("Assistant", msgUnknownFields.role)
        assertEquals("unknown card fields", msgUnknownFields.text)
        assertEquals(8000L, msgUnknownFields.createdAt)
        assertEquals("ChoiceCard", msgUnknownFields.messageType)
        assertEquals("[{\"id\":\"unknown_card\",\"type\":\"MysteryCard\",\"custom_data\":\"xyz\"}]", msgUnknownFields.assistantCardsJson)
        assertEquals(8000L, msgUnknownFields.updatedAt)
        assertNull(msgUnknownFields.deletedAt)

        // Assert msg_suggested_replies
        val msgSuggestedReplies = messageMap["msg_suggested_replies"]!!
        assertEquals("conv1", msgSuggestedReplies.conversationId)
        assertEquals("Assistant", msgSuggestedReplies.role)
        assertEquals("suggested replies", msgSuggestedReplies.text)
        assertEquals(9000L, msgSuggestedReplies.createdAt)
        assertEquals("Text", msgSuggestedReplies.messageType)
        assertEquals("[\"Reply 1\", \"Reply 2\"]", msgSuggestedReplies.suggestedRepliesJson)
        assertEquals(9000L, msgSuggestedReplies.updatedAt)
        assertNull(msgSuggestedReplies.deletedAt)

        // Assert index and foreign keys exist
        val supportDb = roomDb.openHelper.writableDatabase
        
        // Check foreign keys
        var hasForeignKey = false
        val fkCursor = supportDb.query("PRAGMA foreign_key_list(ai_chat_messages)")
        while (fkCursor.moveToNext()) {
            val table = fkCursor.getString(fkCursor.getColumnIndexOrThrow("table"))
            val from = fkCursor.getString(fkCursor.getColumnIndexOrThrow("from"))
            val to = fkCursor.getString(fkCursor.getColumnIndexOrThrow("to"))
            val onDelete = fkCursor.getString(fkCursor.getColumnIndexOrThrow("on_delete"))
            if (table == "conversations" && from == "conversationId" && to == "id" && onDelete == "CASCADE") {
                hasForeignKey = true
            }
        }
        fkCursor.close()
        assertTrue("Foreign key from ai_chat_messages to conversations table must exist", hasForeignKey)

        // Check indexes
        val indexes = mutableListOf<String>()
        val indexCursor = supportDb.query("PRAGMA index_list(ai_chat_messages)")
        while (indexCursor.moveToNext()) {
            indexes.add(indexCursor.getString(indexCursor.getColumnIndexOrThrow("name")))
        }
        indexCursor.close()
        
        assertTrue("Index index_ai_chat_messages_conversationId must exist", indexes.contains("index_ai_chat_messages_conversationId"))
        assertTrue("Index index_ai_chat_messages_conversationId_createdAt must exist", indexes.contains("index_ai_chat_messages_conversationId_createdAt"))

        roomDb.close()
        
        // 10. Clean up / safety delete
        context.deleteDatabase("test_migration_10_11_db")
    }
}
