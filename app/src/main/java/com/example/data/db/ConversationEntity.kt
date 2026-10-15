package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String, // "USER", "AFFY", "SYSTEM"
    val message: String,
    val intent: String? = null,
    val actionSummary: String? = null,
    val status: String = "SUCCESS" // "SUCCESS", "FAILED", "PENDING"
)

@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val category: String = "fact", // "preference", "fact", "contact_alias", "system"
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val triggerTime: Long, // timestamp in ms
    val isCompleted: Boolean = false,
    val isAlarm: Boolean = false,
    val repeatInterval: Long = 0L // 0 = one-time
)

@Entity(tableName = "notification_logs")
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isHandled: Boolean = false
)

