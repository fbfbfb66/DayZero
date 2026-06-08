package com.example.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.AiChatMessageDao
import com.example.data.local.dao.DailyRecordDao
import com.example.data.local.entity.AiChatMessageEntity
import com.example.data.local.entity.DailyRecordEntity

@Database(entities = [DailyRecordEntity::class, AiChatMessageEntity::class], version = 5, exportSchema = false)
abstract class DayZeroDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun aiChatMessageDao(): AiChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: DayZeroDatabase? = null

        fun getDatabase(context: Context): DayZeroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DayZeroDatabase::class.java,
                    "dayzero_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
