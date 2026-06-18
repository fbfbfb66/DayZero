package com.example.data.local.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.dao.SyncQueueDao
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.DailyRecordEntity
import com.example.data.local.entity.SyncQueueEntity

@Database(
    entities = [DailyRecordEntity::class, AiChatMessageEntity::class, SyncQueueEntity::class],
    version = 8,
    exportSchema = false
)
abstract class DayZeroDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun aiChatMessageDao(): AiChatMessageDao
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

        fun getDatabase(context: Context): DayZeroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DayZeroDatabase::class.java,
                    "dayzero_database"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
