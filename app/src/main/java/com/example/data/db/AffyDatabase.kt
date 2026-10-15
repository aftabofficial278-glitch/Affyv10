package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        AgentMemoryEntity::class,
        ReminderEntity::class,
        NotificationLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AffyDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AffyDatabase? = null

        fun getDatabase(context: Context): AffyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AffyDatabase::class.java,
                    "affy_ai_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

