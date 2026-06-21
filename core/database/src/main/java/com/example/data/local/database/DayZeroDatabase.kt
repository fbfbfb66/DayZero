package com.example.data.local.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.DailyRecordEntity
import com.example.data.local.entity.SyncQueueEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Database(
    entities = [DailyRecordEntity::class, AiChatMessageEntity::class, ConversationEntity::class, SyncQueueEntity::class],
    version = 11,
    exportSchema = false
)
abstract class DayZeroDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun aiChatMessageDao(): AiChatMessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        @Volatile
        private var INSTANCE: DayZeroDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 5->6")
                try {
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN clientId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE daily_records SET clientId = id WHERE clientId = ''")
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN remoteId TEXT")
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN syncVersion INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE daily_records SET syncVersion = updatedAt WHERE syncVersion = 0")
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN deletedAt INTEGER")
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN lastSyncedAt INTEGER")
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
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_createdAt ON sync_queue(status, createdAt)")
                    Log.d("DayZeroSync", "room migration success 5->6")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 5->6", e)
                    throw e
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 6->7")
                try {
                    db.execSQL("ALTER TABLE daily_records ADD COLUMN ownerLocalId TEXT NOT NULL DEFAULT 'local_uninitialized'")
                    db.execSQL("ALTER TABLE sync_queue ADD COLUMN ownerLocalId TEXT NOT NULL DEFAULT 'local_uninitialized'")
                    Log.d("DayZeroSync", "room migration success 6->7")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 6->7", e)
                    throw e
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 7->8")
                try {
                    db.execSQL("ALTER TABLE sync_queue ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                    Log.d("DayZeroSync", "room migration success 7->8")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 7->8", e)
                    throw e
                }
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 8->9")
                try {
                    db.execSQL("ALTER TABLE sync_queue ADD COLUMN lastAttemptAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE sync_queue ADD COLUMN lastStatusReason TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_queue_status_nextAttemptAt ON sync_queue(status, nextAttemptAt)")
                    Log.d("DayZeroSync", "room migration success 8->9")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 8->9", e)
                    throw e
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 9->10")
                try {
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

                    val rows = mutableListOf<LegacyChatMessageRow>()
                    db.query(
                        """
                        SELECT id, role, text, createdAt, relatedDraftId, messageType, contentJson,
                               assistantCardsJson, suggestedRepliesJson
                        FROM ai_chat_messages
                        ORDER BY createdAt ASC, id ASC
                        """.trimIndent()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            rows += LegacyChatMessageRow(
                                id = cursor.getString(0),
                                role = cursor.getString(1),
                                text = cursor.getString(2),
                                createdAt = cursor.getLong(3),
                                relatedDraftId = cursor.getStringOrNull(4),
                                messageType = cursor.getString(5),
                                contentJson = cursor.getStringOrNull(6),
                                assistantCardsJson = cursor.getStringOrNull(7),
                                suggestedRepliesJson = cursor.getStringOrNull(8)
                            )
                        }
                    }

                    // The project does not yet have a shared timezone abstraction for chat history.
                    // Old single-stream messages are grouped by the device local timezone during migration.
                    val zoneId = ZoneId.systemDefault()
                    val groupedRows = rows.groupBy { it.localDateString(zoneId) }
                    groupedRows.forEach { (date, messages) ->
                        val conversationId = stableLegacyConversationId(date)
                        val firstCreatedAt = messages.minOf { it.createdAt }
                        val lastCreatedAt = messages.maxOf { it.createdAt }
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO conversations (
                                id, conversationDate, title, lastMessagePreview,
                                createdAt, updatedAt, lastActivityAt, deletedAt
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                conversationId,
                                date,
                                titleForLegacyMessages(date, messages),
                                previewForLegacyMessages(messages),
                                firstCreatedAt,
                                lastCreatedAt,
                                lastCreatedAt
                            )
                        )
                    }

                    db.execSQL(
                        """
                        CREATE TABLE ai_chat_messages_new (
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
                        """.trimIndent()
                    )
                    rows.forEach { row ->
                        db.execSQL(
                            """
                            INSERT INTO ai_chat_messages_new (
                                id, conversationId, role, text, createdAt, relatedDraftId, messageType,
                                contentJson, assistantCardsJson, suggestedRepliesJson
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                row.id,
                                stableLegacyConversationId(row.localDateString(zoneId)),
                                row.role,
                                row.text,
                                row.createdAt,
                                row.relatedDraftId,
                                row.messageType,
                                row.contentJson,
                                row.assistantCardsJson,
                                row.suggestedRepliesJson
                            )
                        )
                    }
                    db.query("SELECT COUNT(*) FROM ai_chat_messages_new WHERE conversationId IS NULL").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                            "Migration 9->10 produced orphan chat messages"
                        }
                    }
                    db.execSQL("DROP TABLE ai_chat_messages")
                    db.execSQL("ALTER TABLE ai_chat_messages_new RENAME TO ai_chat_messages")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_conversationDate ON conversations(conversationDate)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_lastActivityAt ON conversations(lastActivityAt)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId ON ai_chat_messages(conversationId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_chat_messages_conversationId_createdAt ON ai_chat_messages(conversationId, createdAt)")
                    Log.d("DayZeroSync", "room migration success 9->10")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 9->10", e)
                    throw e
                }
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.d("DayZeroSync", "room migration start 10->11")
                try {
                    db.execSQL("ALTER TABLE ai_chat_messages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE ai_chat_messages ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                    db.execSQL("UPDATE ai_chat_messages SET updatedAt = createdAt WHERE updatedAt = 0")
                    Log.d("DayZeroSync", "room migration success 10->11")
                } catch (e: Exception) {
                    Log.e("DayZeroSync", "room migration error 10->11", e)
                    throw e
                }
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

        fun getDatabase(context: Context): DayZeroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DayZeroDatabase::class.java,
                    "dayzero_database"
                )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private data class LegacyChatMessageRow(
            val id: String,
            val role: String,
            val text: String,
            val createdAt: Long,
            val relatedDraftId: String?,
            val messageType: String,
            val contentJson: String?,
            val assistantCardsJson: String?,
            val suggestedRepliesJson: String?
        ) {
            fun localDateString(zoneId: ZoneId): String {
                return Instant.ofEpochMilli(createdAt).atZone(zoneId).toLocalDate().toString()
            }
        }

        private fun stableLegacyConversationId(date: String): String {
            return UUID.nameUUIDFromBytes("dayzero-legacy-ai-chat-$date".toByteArray(StandardCharsets.UTF_8)).toString()
        }

        private fun titleForLegacyMessages(date: String, messages: List<LegacyChatMessageRow>): String {
            val firstUserText = messages.firstOrNull { it.role == "User" && it.text.normalizedPreviewText().isNotBlank() }
                ?.text
                ?.normalizedPreviewText()
            return firstUserText?.limitPreview() ?: neutralLegacyTitle(date)
        }

        private fun previewForLegacyMessages(messages: List<LegacyChatMessageRow>): String {
            return messages.asReversed()
                .firstOrNull { it.text.normalizedPreviewText().isNotBlank() }
                ?.text
                ?.normalizedPreviewText()
                ?.limitPreview()
                ?: "这条对话包含一张记录卡片"
        }

        private fun String.normalizedPreviewText(): String {
            return trim().replace(Regex("\\s+"), " ")
        }

        private fun String.limitPreview(maxLength: Int = 32): String {
            return if (length <= maxLength) this else take(maxLength).trimEnd() + "..."
        }

        private fun neutralLegacyTitle(date: String): String {
            val localDate = java.time.LocalDate.parse(date)
            return DateTimeFormatter.ofPattern("M月d日的对话").format(localDate)
        }

        private fun android.database.Cursor.getStringOrNull(index: Int): String? {
            return if (isNull(index)) null else getString(index)
        }
    }
}
