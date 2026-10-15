package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AffyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AffyReminderReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AffyReminderReceiver"
        private const val CHANNEL_ID = "affy_reminders_channel"
        private const val CHANNEL_NAME = "AFFY Reminders & Alarms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(AffyReminderManager.EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(AffyReminderManager.EXTRA_REMINDER_TITLE) ?: "Reminder"
        val isAlarm = intent.getBooleanExtra(AffyReminderManager.EXTRA_IS_ALARM, false)

        Log.i(TAG, "Triggering reminder #$reminderId: $title")

        // Mark as completed in Room database
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (reminderId > 0) {
                    val db = AffyDatabase.getDatabase(context)
                    db.conversationDao().markReminderCompleted(reminderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error marking reminder completed: ${e.message}")
            }
        }

        // Show system notification with sound
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                if (isAlarm) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders and alarms from AFFY"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, reminderId.toInt(), openIntent, flags)

        val soundUri = RingtoneManager.getDefaultUri(if (isAlarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isAlarm) "⏰ AFFY Alarm" else "🔔 AFFY Reminder")
            .setContentText(title)
            .setPriority(if (isAlarm) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(reminderId.toInt().coerceAtLeast(1001), notification)
    }
}
