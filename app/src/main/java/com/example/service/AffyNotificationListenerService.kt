package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.AffyGmailManager
import com.example.agent.AffyNotificationAssistant

class AffyNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "AffyNotifListener"

        @Volatile
        var instance: AffyNotificationListenerService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var notificationAssistant: AffyNotificationAssistant? = null

        @Volatile
        var gmailManager: AffyGmailManager? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
        Log.i(TAG, "AFFY NotificationListenerService connected and active")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isConnected = false
        Log.w(TAG, "AFFY NotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return // Do not announce AFFY's own foreground notification
        if (sbn.isOngoing) return // Ignore persistent background / ongoing notifications (music player, services)

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        if (title.isBlank() && text.isBlank()) return

        // 1. WhatsApp Notifications (Official, Business, Clones)
        if (WhatsAppInstanceManager.isWhatsAppPackage(pkg)) {
            Log.d(TAG, "WhatsApp notification posted from $pkg (id: ${sbn.id}, title: '$title')")
            AffyDiagnostics.logWhatsAppStage(
                stage = "NOTIFICATION_RECEIVED",
                packageName = pkg,
                sender = title,
                details = text
            )
        }

        // 2. Gmail Notifications
        if (pkg == AffyGmailManager.GMAIL_PACKAGE) {
            Log.d(TAG, "Gmail notification posted: title='$title', text='$text'")
            gmailManager?.onGmailNotificationReceived(title, text, subText)
        }

        // 3. Proactive Spoken Notification Announcement for incoming notifications
        AffyDiagnostics.logNotificationStage(
            stage = "NOTIFICATION_RECEIVED",
            packageName = pkg,
            sender = title,
            details = text
        )
        val assistant = notificationAssistant ?: AffyNotificationAssistant.getInstance(applicationContext)
        assistant.onIncomingNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notification dismissed by user or system
    }
}
