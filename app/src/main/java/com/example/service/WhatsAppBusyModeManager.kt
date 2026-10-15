package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.example.agent.AffyDiagnostics
import com.example.agent.ContactResolutionResult
import com.example.agent.ContactResolver
import com.example.data.datastore.AffyPreferences
import com.example.data.db.AffyDatabase
import com.example.data.db.ConversationEntity
import com.example.data.db.NotificationLogEntity
import com.example.data.remote.GeminiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AutoReplyEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val incomingMessage: String,
    val replySent: String,
    val isSuccess: Boolean,
    val instancePackage: String = ""
)

/**
 * Immutable target locked to a specific notification instance to prevent race conditions.
 */
data class ImmutableNotificationTarget(
    val executionId: String = UUID.randomUUID().toString(),
    val notificationKey: String,
    val sourcePackage: String,
    val contactName: String,
    val incomingMessage: String,
    val notificationTimestamp: Long,
    val isGroup: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Captured RemoteInput binding for background reply dispatch.
 */
data class NotificationRemoteInputBinding(
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>?,
    val compatRemoteInputs: Array<androidx.core.app.RemoteInput>?,
    val resultKey: String,
    val isCompat: Boolean
)

class WhatsAppBusyModeManager(
    private val context: Context,
    private val preferences: AffyPreferences,
    private val geminiRepository: GeminiRepository,
    private val database: AffyDatabase
) {
    companion object {
        private const val TAG = "AffyBusyMode"
        const val MAX_CONSECUTIVE_REPLIES = 2

        @Volatile
        private var INSTANCE: WhatsAppBusyModeManager? = null

        fun getInstance(context: Context): WhatsAppBusyModeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WhatsAppBusyModeManager(
                    context.applicationContext,
                    AffyPreferences(context.applicationContext),
                    GeminiRepository(),
                    AffyDatabase.getDatabase(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }

        private val IGNORED_TEXTS = setOf(
            "whatsapp web is active",
            "whatsapp web is currently active",
            "checking for new messages",
            "calling...",
            "missed voice call",
            "missed video call",
            "incoming voice call",
            "incoming video call",
            "backup in progress",
            "deleting messages",
            "sending message...",
            "photo",
            "sticker",
            "gif",
            "video",
            "audio",
            "voice message",
            "location",
            "contact card"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contactResolver = ContactResolver(context)

    // Active executions tracked by executionId for strict concurrency and race protection
    private val activeExecutions = ConcurrentHashMap<String, ImmutableNotificationTarget>()
    private val cancelledExecutions = ConcurrentHashMap<String, Long>()

    // Cache: sender+message hash -> timestamp (to prevent duplicate handling within 60s)
    private val processedMessagesCache = ConcurrentHashMap<String, Long>()

    // Cache: notification key -> timestamp (to prevent duplicate processing between listeners)
    private val processedNotificationKeys = ConcurrentHashMap<String, Long>()

    // Cache: sender -> last reply timestamp (cooldown of 15s per sender)
    private val lastSenderReplyTime = ConcurrentHashMap<String, Long>()

    // Cache: sender -> last sent reply text (to avoid replying to our own text)
    private val lastSentReplyPerSender = ConcurrentHashMap<String, String>()

    // Loop protection: consecutive auto-replies sent to a sender without manual user chat
    private val senderReplyStreak = ConcurrentHashMap<String, Int>()

    private val dailyReplyCount = AtomicInteger(0)
    private var lastReplyDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    private val _lastAutoReplyEvent = MutableStateFlow<AutoReplyEvent?>(null)
    val lastAutoReplyEvent: StateFlow<AutoReplyEvent?> = _lastAutoReplyEvent.asStateFlow()

    private val _isBusyModeActive = MutableStateFlow(false)
    val isBusyModeActive: StateFlow<Boolean> = _isBusyModeActive.asStateFlow()

    init {
        // WhatsApp autonomous auto-send is strictly disabled for safety
        _isBusyModeActive.value = false
    }

    fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val parcelable = event.parcelableData
            if (parcelable is Notification) {
                // Log notification for intelligence
                logNotification(parcelable, pkg)

                // Dynamically support all WhatsApp instances (official, business, and clones)
                if (WhatsAppInstanceManager.isWhatsAppPackage(pkg)) {
                    if (_isBusyModeActive.value) {
                        processWhatsAppNotification(parcelable, pkg, notificationKey = null)
                    } else {
                        Log.i(TAG, "[WA_BUSY_MODE_BLOCKED] Busy mode is OFF, skipping auto-reply for $pkg")
                        AffyDiagnostics.logWhatsAppStage(
                            stage = "WA_BUSY_MODE_BLOCKED",
                            packageName = pkg,
                            sender = "Unknown",
                            details = "Busy Mode & Auto-Reply are OFF",
                            isSuccess = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Entry point from AffyNotificationListenerService with full status bar notification metadata.
     */
    fun processStatusBarNotification(sbn: android.service.notification.StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!WhatsAppInstanceManager.isWhatsAppPackage(pkg)) return

        if (!_isBusyModeActive.value) {
            Log.i(TAG, "[WA_BUSY_MODE_BLOCKED] Busy mode is OFF, skipping auto-reply for $pkg")
            AffyDiagnostics.logWhatsAppStage(
                stage = "WA_BUSY_MODE_BLOCKED",
                packageName = pkg,
                sender = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown",
                details = "Busy Mode & Auto-Reply are OFF",
                isSuccess = false
            )
            return
        }

        val notification = sbn.notification ?: return
        val key = sbn.key ?: "${sbn.packageName}_${sbn.id}"
        logNotification(notification, pkg)
        processWhatsAppNotification(notification, pkg, notificationKey = key)
    }

    private fun logNotification(notification: Notification, pkg: String) {
        scope.launch {
            try {
                val extras = notification.extras ?: return@launch
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""

                if (title.isNotBlank() || text.isNotBlank()) {
                    database.conversationDao().insertNotificationLog(
                        NotificationLogEntity(
                            packageName = pkg,
                            title = title,
                            text = text
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error logging notification: ${e.message}")
            }
        }
    }

    private fun processWhatsAppNotification(
        notification: Notification,
        pkgName: String,
        notificationKey: String? = null
    ) {
        scope.launch {
            try {
                val extras = notification.extras ?: return@launch

                // Modern WhatsApp extraction using MessagingStyle
                val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                val latestMsg = messagingStyle?.messages?.lastOrNull()

                val title = latestMsg?.person?.name?.toString()?.trim()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                val text = latestMsg?.text?.toString()?.trim()
                    ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()

                // Fail safely instead of inventing a reply if notification lacks usable text
                if (title.isNullOrBlank() || text.isNullOrBlank() || !isUsableMessageText(text)) {
                    Log.d(TAG, "Ignoring notification with unusable text: '$text' from '$title'")
                    return@launch
                }

                // Check auto-reply / busy mode settings
                val busyModeEnabled = preferences.busyModeEnabledFlow.first()
                val autoReplyEnabled = preferences.autoReplyEnabledFlow.first()
                if (!busyModeEnabled && !autoReplyEnabled) {
                    Log.i(TAG, "[WA_BUSY_MODE_BLOCKED] Auto-reply and Busy Mode are both toggled OFF in settings.")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BUSY_MODE_BLOCKED",
                        packageName = pkgName,
                        sender = title,
                        details = "Auto-reply and Busy Mode are toggled OFF",
                        isSuccess = false
                    )
                    return@launch
                }

                val isBusy = busyModeEnabled
                val isGroup = extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION, false)
                val resolvedKey = notificationKey ?: "${pkgName}_${title.hashCode()}_${System.currentTimeMillis()}"

                // 1. Capture and lock immutable target
                val executionId = UUID.randomUUID().toString()
                val immutableTarget = ImmutableNotificationTarget(
                    executionId = executionId,
                    notificationKey = resolvedKey,
                    sourcePackage = pkgName,
                    contactName = title,
                    incomingMessage = text,
                    notificationTimestamp = notification.`when`,
                    isGroup = isGroup
                )
                activeExecutions[executionId] = immutableTarget

                Log.i(TAG, "[WA_BG_REPLY_STARTED] execId=$executionId target='$title' on pkg '$pkgName'")
                AffyDiagnostics.logWhatsAppStage(
                    stage = "WA_BG_REPLY_STARTED",
                    packageName = pkgName,
                    sender = title,
                    details = "execId=$executionId Text: '$text' (BusyMode: $isBusy)"
                )

                // 2. Obtain notification RemoteInput/reply action
                val remoteInputBinding = extractRemoteInputBinding(notification, pkgName)
                if (remoteInputBinding == null) {
                    activeExecutions.remove(executionId)
                    Log.w(TAG, "[WA_BG_REPLY_UNSUPPORTED] execId=$executionId Notification for $title has no valid RemoteInput action. Cancelling auto-reply without opening UI.")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_UNSUPPORTED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId No RemoteInput action found on notification. Background reply unsupported.",
                        isSuccess = false,
                        failureReason = "NO_REMOTE_INPUT_ACTION"
                    )
                    return@launch
                }

                Log.i(TAG, "[WA_BG_REPLY_REMOTE_INPUT_FOUND] execId=$executionId resultKey=${remoteInputBinding.resultKey}")
                AffyDiagnostics.logWhatsAppStage(
                    stage = "WA_BG_REPLY_REMOTE_INPUT_FOUND",
                    packageName = pkgName,
                    sender = title,
                    details = "execId=$executionId resultKey=${remoteInputBinding.resultKey}"
                )

                // Check preferred WhatsApp account filter
                val preferredPkg = preferences.preferredWhatsAppPackageFlow.first()
                if (preferredPkg.isNotBlank() && preferredPkg != "all" && !pkgName.equals(preferredPkg, ignoreCase = true)) {
                    Log.d(TAG, "Notification received on $pkgName, but target account is $preferredPkg. Skipping.")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                val allowGroupChats = preferences.busyModeGroupChatsFlow.first()
                val allowIndividualChats = preferences.autoReplyIndividualChatsFlow.first()

                if (isGroup && !allowGroupChats) {
                    Log.d(TAG, "Group chat auto-reply disabled. Skipping message from $title")
                    activeExecutions.remove(executionId)
                    return@launch
                }
                if (!isGroup && !allowIndividualChats) {
                    Log.d(TAG, "Individual chat auto-reply disabled. Skipping message from $title")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                if (!isEligibleMessage(title, text, extras, allowGroupChats)) {
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Check unknown contacts restriction
                val allowUnknown = preferences.autoReplyUnknownContactsFlow.first()
                if (!allowUnknown) {
                    val contactRes = contactResolver.resolveContact(title)
                    if (contactRes is ContactResolutionResult.NotFound) {
                        Log.d(TAG, "Unknown contact $title ignored as per settings.")
                        activeExecutions.remove(executionId)
                        return@launch
                    }
                }

                // Check daily limit
                val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                if (currentDay != lastReplyDay) {
                    dailyReplyCount.set(0)
                    lastReplyDay = currentDay
                }
                val dailyLimit = preferences.busyModeDailyLimitFlow.first()
                if (dailyReplyCount.get() >= dailyLimit) {
                    Log.w(TAG, "Daily auto-reply limit ($dailyLimit) reached. Skipping.")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                val now = System.currentTimeMillis()

                // Deduplicate by notification key across listeners
                val lastNotif = processedNotificationKeys[resolvedKey] ?: 0L
                if (now - lastNotif < 60_000L) {
                    Log.d(TAG, "Ignoring duplicate notification ID: $resolvedKey")
                    activeExecutions.remove(executionId)
                    return@launch
                }
                processedNotificationKeys[resolvedKey] = now

                val senderKey = title.lowercase()
                val messageKey = "$pkgName::$senderKey::${text.lowercase()}"

                // Deduplication: ignore identical message within 60 seconds
                val lastProcessedTime = processedMessagesCache[messageKey] ?: 0L
                if (now - lastProcessedTime < 60_000L) {
                    Log.d(TAG, "Ignoring duplicate message from $title on $pkgName: $text")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Cooldown per sender: 15 seconds
                val lastSenderTime = lastSenderReplyTime[senderKey] ?: 0L
                if (now - lastSenderTime < 15_000L) {
                    Log.d(TAG, "Sender $title is in cooldown")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Loop protection: Maximum consecutive replies check
                val currentStreak = senderReplyStreak[senderKey] ?: 0
                if (currentStreak >= MAX_CONSECUTIVE_REPLIES) {
                    Log.w(TAG, "Loop protection active: reached max consecutive replies ($MAX_CONSECUTIVE_REPLIES) for $title. Skipping.")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Check if the message is our own last sent reply
                val lastSentText = lastSentReplyPerSender[senderKey]
                if (lastSentText != null && (lastSentText.equals(text, ignoreCase = true) || text.contains(lastSentText, ignoreCase = true))) {
                    Log.d(TAG, "Ignoring echoed outgoing reply for $title")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Mark as being processed
                processedMessagesCache[messageKey] = now
                cleanupCache(now)

                // Configured delay before reply
                val delaySec = preferences.busyModeDelaySecondsFlow.first()
                if (delaySec > 0) {
                    delay(delaySec * 1000L)
                }

                // Concurrency & Stale Check after delay
                if (cancelledExecutions.containsKey(executionId) || !activeExecutions.containsKey(executionId)) {
                    Log.w(TAG, "[WA_BG_REPLY_CANCELLED] execId=$executionId Execution was cancelled during delay")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_CANCELLED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId Cancelled during delay",
                        isSuccess = false
                    )
                    activeExecutions.remove(executionId)
                    return@launch
                }

                val useAi = preferences.autoReplyAiGeneratedFlow.first()
                val replyText: String? = if (useAi) {
                    val apiKey = preferences.getEffectiveApiKey()
                    if (apiKey.isNotBlank()) {
                        val model = preferences.modelFlow.first()
                        val recentHistory = try {
                            val entities = database.conversationDao().getRecentConversationsList(6)
                            entities.filter { it.sender.contains(title, ignoreCase = true) || it.sender.contains("AFFY", ignoreCase = true) }
                                .map { it.sender to it.message }
                        } catch (e: Exception) {
                            emptyList()
                        }

                        val replyResult = geminiRepository.generateWhatsAppReply(
                            apiKey = apiKey,
                            model = model,
                            sender = title,
                            incomingMessage = text,
                            recentChatHistory = recentHistory,
                            isBusyMode = isBusy
                        )
                        val raw = replyResult.getOrNull()?.trim() ?: getSmartContextualFallback(text, isBusy)
                        raw?.let { geminiRepository.cleanWhatsAppReply(it) }
                    } else {
                        getSmartContextualFallback(text, isBusy)?.let { geminiRepository.cleanWhatsAppReply(it) }
                    }
                } else {
                    getSmartContextualFallback(text, isBusy)?.let { geminiRepository.cleanWhatsAppReply(it) }
                }

                if (replyText.isNullOrBlank()) {
                    Log.i(TAG, "[WA_BG_REPLY_CANCELLED] execId=$executionId No contextual reply generated for '$text' from $title. Skipping auto-reply safely.")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_CANCELLED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId No contextual reply generated",
                        isSuccess = false
                    )
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Prevent sending identical duplicate response to the same contact
                if (lastSentText != null && lastSentText.equals(replyText, ignoreCase = true)) {
                    Log.i(TAG, "Generated reply is identical to previous sent reply for $title. Skipping repetition.")
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // Race Condition & Target Integrity Protection:
                // Verify that execution target is still bound to the original notification and not redirected
                val currentTarget = activeExecutions[executionId]
                if (currentTarget == null || currentTarget.notificationKey != immutableTarget.notificationKey || currentTarget.sourcePackage != immutableTarget.sourcePackage) {
                    Log.w(TAG, "[WA_BG_REPLY_STALE_TARGET_BLOCKED] execId=$executionId Stale or redirected notification target detected. Send aborted.")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_STALE_TARGET_BLOCKED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId Stale target mismatch. Send blocked.",
                        isSuccess = false,
                        failureReason = "STALE_TARGET_MISMATCH"
                    )
                    activeExecutions.remove(executionId)
                    return@launch
                }

                // 3. Dispatch reply strictly via RemoteInput in background (NEVER open WhatsApp UI)
                val sent = dispatchRemoteInputReply(remoteInputBinding, replyText)

                if (sent) {
                    Log.i(TAG, "[WA_BG_REPLY_SENT] execId=$executionId Dispatched reply to $title via RemoteInput: '$replyText'")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_SENT",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId Dispatched via RemoteInput: '$replyText'"
                    )

                    Log.i(TAG, "[WA_BG_REPLY_VERIFIED] execId=$executionId Auto-reply verified and completed in background on $pkgName")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_VERIFIED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId Auto-reply verified in background",
                        isSuccess = true
                    )

                    dailyReplyCount.incrementAndGet()
                    lastSenderReplyTime[senderKey] = System.currentTimeMillis()
                    lastSentReplyPerSender[senderKey] = replyText
                    senderReplyStreak[senderKey] = currentStreak + 1

                    // Record in Database
                    try {
                        database.conversationDao().insertConversation(
                            ConversationEntity(
                                sender = title,
                                message = text,
                                intent = "WHATSAPP_INCOMING",
                                actionSummary = "From WhatsApp instance $pkgName",
                                status = "RECEIVED"
                            )
                        )
                        database.conversationDao().insertConversation(
                            ConversationEntity(
                                sender = "AFFY (Busy Mode)",
                                message = replyText,
                                intent = "WHATSAPP_AUTO_REPLY",
                                actionSummary = "Auto-replied via RemoteInput to $title",
                                status = "SENT"
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error saving auto-reply to database: ${e.message}")
                    }

                    _lastAutoReplyEvent.value = AutoReplyEvent(
                        sender = title,
                        incomingMessage = text,
                        replySent = replyText,
                        isSuccess = true,
                        instancePackage = pkgName
                    )
                } else {
                    Log.w(TAG, "[WA_BG_REPLY_UNSUPPORTED] execId=$executionId RemoteInput dispatch failed for $title on $pkgName")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_UNSUPPORTED",
                        packageName = pkgName,
                        sender = title,
                        details = "execId=$executionId RemoteInput dispatch failed",
                        isSuccess = false,
                        failureReason = "REMOTE_INPUT_DISPATCH_FAILED"
                    )
                }

                activeExecutions.remove(executionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error in WhatsApp Busy Mode pipeline: ${e.message}", e)
            }
        }
    }

    /**
     * Extracts RemoteInput binding from the notification or active status bar notifications.
     */
    private fun extractRemoteInputBinding(
        notification: Notification,
        targetPackage: String
    ): NotificationRemoteInputBinding? {
        // 1. Check NotificationCompat actions on the notification
        val compatCount = NotificationCompat.getActionCount(notification)
        for (i in 0 until compatCount) {
            val action = NotificationCompat.getAction(notification, i)
            val remoteInputs = action?.remoteInputs
            val pendingIntent = action?.actionIntent
            if (!remoteInputs.isNullOrEmpty() && pendingIntent != null) {
                val key = remoteInputs[0].resultKey
                if (!key.isNullOrBlank()) {
                    return NotificationRemoteInputBinding(
                        pendingIntent = pendingIntent,
                        remoteInputs = null,
                        compatRemoteInputs = remoteInputs,
                        resultKey = key,
                        isCompat = true
                    )
                }
            }
        }

        // 2. Check standard notification.actions
        val actions = notification.actions
        if (!actions.isNullOrEmpty()) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                val pendingIntent = action.actionIntent
                if (!remoteInputs.isNullOrEmpty() && pendingIntent != null) {
                    val key = remoteInputs[0].resultKey
                    if (!key.isNullOrBlank()) {
                        return NotificationRemoteInputBinding(
                            pendingIntent = pendingIntent,
                            remoteInputs = remoteInputs,
                            compatRemoteInputs = null,
                            resultKey = key,
                            isCompat = false
                        )
                    }
                }
            }
        }

        // 3. Fallback: Check AffyNotificationListenerService active notifications for the package
        try {
            val activeSbns = AffyNotificationListenerService.instance?.activeNotifications
            if (!activeSbns.isNullOrEmpty()) {
                for (sbn in activeSbns) {
                    if (sbn.packageName == targetPackage) {
                        val sbnNotif = sbn.notification ?: continue
                        val sbnCompatCount = NotificationCompat.getActionCount(sbnNotif)
                        for (i in 0 until sbnCompatCount) {
                            val action = NotificationCompat.getAction(sbnNotif, i)
                            val remoteInputs = action?.remoteInputs
                            val pendingIntent = action?.actionIntent
                            if (!remoteInputs.isNullOrEmpty() && pendingIntent != null) {
                                val key = remoteInputs[0].resultKey
                                if (!key.isNullOrBlank()) {
                                    return NotificationRemoteInputBinding(
                                        pendingIntent = pendingIntent,
                                        remoteInputs = null,
                                        compatRemoteInputs = remoteInputs,
                                        resultKey = key,
                                        isCompat = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking active notifications for RemoteInput: ${e.message}")
        }

        return null
    }

    /**
     * Dispatches the reply in the background using the notification's RemoteInput PendingIntent.
     */
    private fun dispatchRemoteInputReply(
        binding: NotificationRemoteInputBinding,
        replyText: String
    ): Boolean {
        return try {
            val bundle = Bundle()
            bundle.putCharSequence(binding.resultKey, replyText)
            val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

            if (binding.isCompat && binding.compatRemoteInputs != null) {
                androidx.core.app.RemoteInput.addResultsToIntent(binding.compatRemoteInputs, fillInIntent, bundle)
            } else if (binding.remoteInputs != null) {
                android.app.RemoteInput.addResultsToIntent(binding.remoteInputs, fillInIntent, bundle)
            } else {
                val remoteInputBundle = Bundle().apply {
                    putCharSequence(binding.resultKey, replyText)
                }
                fillInIntent.putExtra(android.app.RemoteInput.EXTRA_RESULTS_DATA, remoteInputBundle)
            }

            binding.pendingIntent.send(context, 0, fillInIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending RemoteInput PendingIntent: ${e.message}", e)
            false
        }
    }

    private fun isUsableMessageText(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank() || lower.length < 2) return false
        val nonUsable = listOf(
            "photo", "image", "video", "sticker", "gif", "voice message",
            "audio", "missed voice call", "missed video call", "missed call",
            "calling...", "incoming call", "location", "contact card",
            "document", "file", "new messages", "checking for new messages",
            "this message was deleted", "message deleted"
        )
        return !nonUsable.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }
    }

    /**
     * Contextual rule-based fallback if Gemini API is offline/unavailable.
     */
    private fun getSmartContextualFallback(text: String, isBusy: Boolean): String? {
        val lower = text.lowercase().trim()

        if (lower.contains("salam") || lower.contains("assalam")) {
            return if (isBusy) {
                "Wa Alaikum Assalam! Thora masroof hoon abhi, thori der mein baat karta hoon."
            } else {
                "Wa Alaikum Assalam 😊 Kaise hain aap?"
            }
        }
        if (lower == "hi" || lower == "hello" || lower == "hey" || lower.startsWith("hi ") || lower.startsWith("hello ")) {
            return if (isBusy) {
                "Hi! Currently busy right now, will get back to you shortly."
            } else {
                "Hello! How can I help you today?"
            }
        }
        if (lower.contains("kahan ho") || lower.contains("kidhar ho") || lower.contains("where are you")) {
            return if (isBusy) {
                "Masroof hoon thora, free ho kar call/msg karta hoon."
            } else {
                "Main theek hoon, aap sunayein?"
            }
        }
        if (lower.contains("urgent") || lower.contains("zaroori") || lower.contains("jaldi")) {
            return "Aapka message mil gaya hai. Jaise hi free hota hoon foran contact karta hoon."
        }
        if (lower.contains("ok") || lower.contains("theek") || lower.contains("acha") || lower.contains("shukriya") || lower.contains("thanks")) {
            return null // Do not trigger auto-reply on acknowledgment messages
        }

        return if (isBusy) {
            "Salam! Abhi busy hoon, thori der mein contact karta hoon."
        } else {
            "Message mil gaya hai, thori der mein reply karta hoon."
        }
    }

    private fun isEligibleMessage(
        title: String,
        text: String,
        extras: Bundle,
        allowGroupChats: Boolean
    ): Boolean {
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.lowercase() ?: ""
        if (subText.contains("channel") || subText.contains("newsletter") || subText.contains("broadcast") ||
            (subText.contains("update") && !subText.contains("message"))) {
            Log.d(TAG, "Ignoring WhatsApp Channel/Newsletter by subText: $subText")
            return false
        }

        val convTitle = extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.lowercase() ?: ""
        if (convTitle.contains("channel") || convTitle.contains("newsletter") || convTitle.contains("broadcast")) {
            Log.d(TAG, "Ignoring WhatsApp Channel by conversation title: $convTitle")
            return false
        }

        if (lowerTitle.contains("@newsletter") ||
            lowerTitle.contains("newsletter") ||
            lowerTitle.contains("channel") ||
            lowerTitle.contains("broadcast") ||
            lowerText.contains("follow this channel") ||
            lowerText.contains("view channel") ||
            lowerText.contains("channel update") ||
            lowerText.contains("updates from")
        ) {
            Log.d(TAG, "Ignoring WhatsApp Channel/Newsletter broadcast: $title")
            return false
        }

        val isGroup = extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION, false)
        if (isGroup && !allowGroupChats) {
            return false
        }

        if (lowerTitle.contains("messages from") ||
            lowerTitle.contains("new messages") ||
            lowerTitle.contains("whatsapp") ||
            lowerTitle.contains("@g.us") ||
            lowerTitle.contains("you")
        ) {
            return false
        }

        if (!allowGroupChats) {
            if (lowerTitle.contains(":") || lowerText.contains("messages) :") || lowerText.contains("messages):")) {
                return false
            }
        }

        for (ignored in IGNORED_TEXTS) {
            if (lowerText == ignored || lowerText.startsWith("$ignored ") || lowerText.endsWith(" $ignored")) {
                return false
            }
        }

        return true
    }

    private fun cleanupCache(now: Long) {
        if (processedMessagesCache.size > 200) {
            val expiredKeys = processedMessagesCache.filter { now - it.value > 120_000L }.keys
            expiredKeys.forEach { processedMessagesCache.remove(it) }
        }
    }
}
