package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.db.AffyDatabase
import com.example.data.db.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AffyReminderManager(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val database = AffyDatabase.getDatabase(context)

    companion object {
        private const val TAG = "AffyReminderManager"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
        const val EXTRA_IS_ALARM = "extra_is_alarm"
    }

    suspend fun scheduleReminder(
        title: String,
        triggerTimeMs: Long,
        isAlarm: Boolean = false
    ): Long {
        val reminder = ReminderEntity(
            title = title,
            triggerTime = triggerTimeMs,
            isCompleted = false,
            isAlarm = isAlarm
        )
        val id = database.conversationDao().insertReminder(reminder)
        setAlarmExact(id, title, triggerTimeMs, isAlarm)
        return id
    }

    private fun setAlarmExact(id: Long, title: String, triggerTimeMs: Long, isAlarm: Boolean) {
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager not available")
            return
        }

        val intent = Intent(context, AffyReminderReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, id)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_IS_ALARM, isAlarm)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            }
            Log.i(TAG, "Scheduled reminder #$id: '$title' at $triggerTimeMs")
        } catch (e: SecurityException) {
            // In case exact alarm permission is missing on Android 12+
            Log.w(TAG, "Exact alarm permission missing, fallback to set(): ${e.message}")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm: ${e.message}")
        }
    }

    suspend fun cancelRemindersByKeyword(keyword: String): Int {
        val active = database.conversationDao().getActiveRemindersList()
        var count = 0
        for (item in active) {
            if (item.title.contains(keyword, ignoreCase = true) || keyword.contains(item.title, ignoreCase = true)) {
                cancelAlarm(item.id)
                database.conversationDao().deleteReminder(item.id)
                count++
            }
        }
        return count
    }

    fun cancelAlarm(id: Long) {
        val intent = Intent(context, AffyReminderReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
        alarmManager?.cancel(pendingIntent)
    }

    /**
     * Parse natural language time expressions:
     * - "30 minutes baad" -> +30m
     * - "8 baje" -> 8:00 AM/PM
     * - "kal 10 baje" -> tomorrow 10:00 AM
     * - "1 hour" -> +1h
     */
    fun parseNaturalTime(timeExpr: String): Long {
        val lower = timeExpr.lowercase().trim()
        val now = Calendar.getInstance()

        // Relative minutes: "30 minutes", "10 min", "5 minute baad"
        val minRegex = Regex("(\\d+)\\s*(?:min|minute|minutes|m)")
        val minMatch = minRegex.find(lower)
        if (minMatch != null) {
            val mins = minMatch.groupValues[1].toIntOrNull() ?: 0
            if (mins > 0) {
                return System.currentTimeMillis() + mins * 60 * 1000L
            }
        }

        // Relative hours: "2 ghante", "1 hour", "2 hours baad"
        val hrRegex = Regex("(\\d+)\\s*(?:hour|hours|ghante|ghanta|hr|hrs)")
        val hrMatch = hrRegex.find(lower)
        if (hrMatch != null) {
            val hrs = hrMatch.groupValues[1].toIntOrNull() ?: 0
            if (hrs > 0) {
                return System.currentTimeMillis() + hrs * 60 * 60 * 1000L
            }
        }

        // Relative seconds: "45 seconds"
        val secRegex = Regex("(\\d+)\\s*(?:sec|second|seconds|s)")
        val secMatch = secRegex.find(lower)
        if (secMatch != null) {
            val secs = secMatch.groupValues[1].toIntOrNull() ?: 0
            if (secs > 0) {
                return System.currentTimeMillis() + secs * 1000L
            }
        }

        // Specific time: "8 baje", "8:30", "10 am", "8 pm", "subah 8 baje", "shaam 6 baje"
        val isTomorrow = lower.contains("kal") || lower.contains("tomorrow")
        val isEvening = lower.contains("shaam") || lower.contains("raat") || lower.contains("pm") || lower.contains("night")
        val isMorning = lower.contains("subah") || lower.contains("am") || lower.contains("morning")

        val timePattern = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(?:baje|am|pm)?")
        val match = timePattern.find(lower)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 8
            val minute = match.groupValues[2].toIntOrNull() ?: 0

            if (isEvening && hour in 1..11) {
                hour += 12
            } else if (!isMorning && !isEvening && hour in 1..6) {
                // assume afternoon/evening for 1-6 baje if ambiguous
                hour += 12
            }

            val targetCal = Calendar.getInstance().apply {
                if (isTomorrow) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If time already passed today and "kal" wasn't specified, push to tomorrow
            if (!isTomorrow && targetCal.timeInMillis <= System.currentTimeMillis()) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            return targetCal.timeInMillis
        }

        // Default to 1 hour from now
        return System.currentTimeMillis() + 60 * 60 * 1000L
    }
}
