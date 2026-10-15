package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentConversations(limit: Int = 20): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentConversationsList(limit: Int = 10): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(item: ConversationEntity): Long

    @Query("DELETE FROM conversations")
    suspend fun clearHistory()

    // Agent memory
    @Query("SELECT * FROM agent_memories ORDER BY updatedAt DESC")
    fun getAllMemories(): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memories ORDER BY updatedAt DESC")
    suspend fun getAllMemoriesList(): List<AgentMemoryEntity>

    @Query("SELECT value FROM agent_memories WHERE `key` = :key LIMIT 1")
    suspend fun getMemory(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMemory(memory: AgentMemoryEntity)

    @Query("DELETE FROM agent_memories WHERE `key` = :key")
    suspend fun deleteMemory(key: String)

    @Query("DELETE FROM agent_memories")
    suspend fun clearAllMemories()

    // Reminders
    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY triggerTime ASC")
    fun getActiveReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY triggerTime ASC")
    suspend fun getActiveRemindersList(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET isCompleted = 1 WHERE id = :id")
    suspend fun markReminderCompleted(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("DELETE FROM reminders WHERE title LIKE '%' || :keyword || '%'")
    suspend fun cancelRemindersByKeyword(keyword: String): Int

    // Notification Logs
    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentNotificationLogs(limit: Int = 20): Flow<List<NotificationLogEntity>>

    @Query("SELECT * FROM notification_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentNotificationLogsList(limit: Int = 20): List<NotificationLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationLog(log: NotificationLogEntity): Long

    @Query("DELETE FROM notification_logs")
    suspend fun clearNotificationLogs()
}

