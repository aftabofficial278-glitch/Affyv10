package com.example.agent

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.datastore.AffyPreferences
import com.example.data.db.AffyDatabase
import com.example.data.db.NotificationLogEntity
import com.example.data.remote.GeminiRepository
import com.example.service.AffyAccessibilityService
import com.example.service.WhatsAppInstanceManager
import com.example.voice.AffyTextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

enum class NotificationAssistantState {
    IDLE,
    AWAITING_READ_CONFIRMATION, // Asked: "Boss, Ali ka WhatsApp message aaya hai. Message sunaun?"
    AWAITING_REPLY_INTENT,      // Asked: "Ali: '...'. Boss, iska reply kar doon?"
    CONFIRMING_REPLY            // Asked: "Maine ye reply draft kiya hai: '...'. Bhej doon?"
}

data class RecentReceivedMessage(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val sender: String,
    val senderCleanName: String,
    val isUnknownNumber: Boolean,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notification: Notification? = null
)

/**
 * Immutable target locked to a specific notification instance.
 * Prevents race conditions and cross-chat misdirection even if user opens another chat.
 */
data class ImmutableNotificationTarget(
    val executionId: String = UUID.randomUUID().toString(),
    val packageName: String,
    val sender: String,
    val messageText: String,
    val notification: Notification,
    val timestamp: Long = System.currentTimeMillis()
)

class AffyNotificationAssistant(
    private val context: Context,
    private val preferences: AffyPreferences = AffyPreferences(context),
    private var onSpeakRequest: ((text: String, onComplete: (() -> Unit)?) -> Unit)? = null,
    private var onListenRequest: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "NotificationAssistant"

        @Volatile
        private var instance: AffyNotificationAssistant? = null

        fun getInstance(context: Context): AffyNotificationAssistant {
            return instance ?: synchronized(this) {
                instance ?: AffyNotificationAssistant(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val geminiRepository = GeminiRepository()

    private val _assistantState = MutableStateFlow(NotificationAssistantState.IDLE)
    val assistantState: StateFlow<NotificationAssistantState> = _assistantState.asStateFlow()

    private var activePendingNotification: ImmutableNotificationTarget? = null
    private var stagedReplyText: String? = null

    // In-memory queue of recent received messages (WhatsApp, SMS, etc.)
    private val recentReceivedMessages = ConcurrentLinkedDeque<RecentReceivedMessage>()

    // Dedup cache for recent notification hashes
    private val recentNotificationHashes = ConcurrentHashMap<String, Long>()

    // Phase 4.5 & 4.6: Specific Chat Handling Authorization (Key: "$packageName::$sender" -> expiry timestamp)
    private val authorizedChats = ConcurrentHashMap<String, Long>()

    // Dedicated fallback TTS to guarantee audible speech when UI callbacks are not attached
    private val fallbackTts: AffyTextToSpeech by lazy {
        AffyTextToSpeech(context.applicationContext) { /* speaking state */ }
    }

    fun bind(onSpeak: (String, (() -> Unit)?) -> Unit, onListen: () -> Unit) {
        this.onSpeakRequest = onSpeak
        this.onListenRequest = onListen
    }

    fun unbind() {
        this.onSpeakRequest = null
        this.onListenRequest = null
    }

    // ================================================================================
    // CHAT HANDLING AUTHORIZATION (Phase 4.5, 4.6, 4.7)
    // ================================================================================

    fun authorizeChat(sender: String, packageName: String? = null, durationMinutes: Int = 60): Boolean {
        val cleanSender = sender.trim()
        if (cleanSender.isBlank()) return false
        val pkg = packageName ?: WhatsAppInstanceManager.PKG_WHATSAPP
        val key = "$pkg::${cleanSender.lowercase()}"
        val expiry = System.currentTimeMillis() + (durationMinutes * 60_000L)
        authorizedChats[key] = expiry

        Log.i(TAG, "[WA_CHAT_AUTHORIZED] Authorized autonomous handling for '$cleanSender' on $pkg until $expiry")
        AffyDiagnostics.logWhatsAppStage(
            stage = "CHAT_AUTHORIZED",
            packageName = pkg,
            sender = cleanSender,
            details = "Chat authorized for $durationMinutes minutes"
        )
        return true
    }

    fun revokeChat(sender: String? = null, packageName: String? = null) {
        if (sender.isNullOrBlank()) {
            authorizedChats.clear()
            Log.i(TAG, "[WA_CHAT_REVOKED] Revoked all chat authorizations")
            AffyDiagnostics.logWhatsAppStage(
                stage = "CHAT_REVOKED",
                packageName = "all",
                sender = "all",
                details = "All authorizations cleared"
            )
        } else {
            val cleanSender = sender.trim().lowercase()
            val iterator = authorizedChats.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.endsWith("::$cleanSender") || (packageName != null && key == "$packageName::$cleanSender")) {
                    iterator.remove()
                    Log.i(TAG, "[WA_CHAT_REVOKED] Revoked chat authorization for key '$key'")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "CHAT_REVOKED",
                        packageName = packageName ?: "whatsapp",
                        sender = sender,
                        details = "Authorization revoked"
                    )
                }
            }
        }
    }

    fun isChatAuthorized(sender: String, packageName: String): Boolean {
        val key = "$packageName::${sender.trim().lowercase()}"
        val expiry = authorizedChats[key] ?: return false
        if (System.currentTimeMillis() > expiry) {
            authorizedChats.remove(key)
            return false
        }
        return true
    }

    /**
     * Executes real audible speech via UI callback if active, or via fallback TTS directly.
     */
    private fun speakAudible(text: String, onComplete: (() -> Unit)? = null) {
        AffyDiagnostics.logNotificationStage(
            stage = "TTS_REQUESTED",
            packageName = activePendingNotification?.packageName ?: "system",
            details = "Text: '$text'"
        )

        // Pause speech recognizer while speaking to prevent self-triggering and audio conflicts
        com.example.service.AffyVoiceService.instance?.pauseForSpeaking()

        val speakCallback = onSpeakRequest
        if (speakCallback != null) {
            speakCallback(text, onComplete)
        } else {
            // Standalone background speech via internal TTS
            scope.launch {
                val apiKey = preferences.getEffectiveApiKey()
                fallbackTts.setApiKey(apiKey)
                val liveVoice = preferences.liveVoiceFlow.first()
                fallbackTts.setVoiceName(liveVoice)
                AffyDiagnostics.logNotificationStage(
                    stage = "TTS_ENGINE_SELECTED",
                    packageName = activePendingNotification?.packageName ?: "system",
                    details = fallbackTts.getCurrentVoiceProvider()
                )
                AffyDiagnostics.logNotificationStage(
                    stage = "AUDIO_STARTED",
                    packageName = activePendingNotification?.packageName ?: "system",
                    details = "Speaking via fallback TTS"
                )
                fallbackTts.speak(text) {
                    AffyDiagnostics.logNotificationStage(
                        stage = "AUDIO_COMPLETED",
                        packageName = activePendingNotification?.packageName ?: "system",
                        details = "Spoken successfully"
                    )
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * Called when a supported messaging notification arrives.
     * Implements Phase 4 New Design:
     * NOTIFICATION -> IDENTIFY -> ASK -> READ -> USER DECISION -> REPLY -> VERIFY
     */
    fun onIncomingNotification(sbn: StatusBarNotification) {
        scope.launch {
            val pkg = sbn.packageName ?: return@launch
            if (pkg == context.packageName) {
                return@launch // Do not announce our own app's foreground service
            }
            if (sbn.isOngoing) {
                return@launch // Ignore persistent background or ongoing notifications
            }

            if (!isNetworkAvailable()) {
                Log.d(TAG, "Internet is not available. Skipping proactive spoken notification prompt.")
                return@launch
            }

            val isAssistantEnabled = preferences.notificationAssistantEnabledFlow.first()
            if (!isAssistantEnabled) {
                return@launch
            }

            val isQuietMode = preferences.quietModeEnabledFlow.first()
            if (isQuietMode) {
                Log.d(TAG, "Quiet mode is active. Skipping spoken notification prompt.")
                return@launch
            }

            val now = System.currentTimeMillis()

            val notification = sbn.notification ?: return@launch
            val extras = notification.extras ?: return@launch

            // Extract using MessagingStyle if available
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            val latestMsg = messagingStyle?.messages?.lastOrNull()

            var rawTitle = latestMsg?.person?.name?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                ?: "Kisi"

            // Clean title: "John Doe (2 messages)" -> "John Doe"
            if (rawTitle.contains("(") && rawTitle.contains("message", ignoreCase = true)) {
                rawTitle = rawTitle.substringBefore("(").trim()
            }

            val text = latestMsg?.text?.toString()?.trim()
                ?: (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
                ?: ""

            // Strict filtering of channels, system messages, downloads, newsletters, and media
            val filterReason = getFilterRejectionReason(sbn, pkg, rawTitle, text)
            if (filterReason != null) {
                Log.d(TAG, "Notification rejected by filter: $filterReason ($rawTitle: $text)")
                return@launch
            }

            // Deduplication by sender + message + pkg + sbn key within 20 seconds
            val sbnKey = sbn.key ?: "${sbn.id}"
            val messageHash = "$pkg::$rawTitle::${text.take(40).lowercase()}::$sbnKey"
            val lastSeen = recentNotificationHashes[messageHash] ?: 0L
            if (now - lastSeen < 20_000L) {
                Log.d(TAG, "Duplicate notification prompt suppressed for $messageHash")
                return@launch
            }
            // Also check sender-level recent prompt deduplication
            val senderHash = "$pkg::$rawTitle::last_prompt"
            val lastSenderPrompt = recentNotificationHashes[senderHash] ?: 0L
            if (now - lastSenderPrompt < 15_000L && text.isBlank()) {
                Log.d(TAG, "Duplicate sender notification prompt suppressed for $senderHash")
                return@launch
            }
            recentNotificationHashes[messageHash] = now
            recentNotificationHashes[senderHash] = now
            cleanupHashCache(now)

            // Determine if sender is an unknown phone number
            val isUnknownNumber = rawTitle.matches(Regex("^\\+?[0-9\\s\\-()]{7,}$")) || rawTitle.startsWith("+")
            val cleanSenderName = if (isUnknownNumber) "Unknown number ($rawTitle)" else rawTitle

            val receivedMessage = RecentReceivedMessage(
                packageName = pkg,
                sender = rawTitle,
                senderCleanName = cleanSenderName,
                isUnknownNumber = isUnknownNumber,
                messageText = text,
                timestamp = now,
                notification = notification
            )

            // Add to in-memory queue
            recentReceivedMessages.addFirst(receivedMessage)
            while (recentReceivedMessages.size > 30) {
                recentReceivedMessages.pollLast()
            }

            // Update persistent database log (Sensitive data like OTPs, passwords, and banking secrets are never permanently stored)
            try {
                val isSensitive = isSensitiveContent(text)
                val sanitizedText = if (isSensitive) "[Protected Security Code / OTP]" else text
                val db = AffyDatabase.getDatabase(context)
                db.conversationDao().insertNotificationLog(
                    NotificationLogEntity(
                        packageName = pkg,
                        title = rawTitle,
                        text = sanitizedText,
                        timestamp = now
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist notification to DB: ${e.message}")
            }

            // Update active conversation context with this notification
            AffyConversationContextManager.getInstance().updateActiveNotification(
                sender = rawTitle,
                message = text,
                packageName = pkg
            )

            // IMMUTABLE TARGET LOCK (Phase 4.9)
            val immutableTarget = ImmutableNotificationTarget(
                packageName = pkg,
                sender = rawTitle,
                messageText = text,
                notification = notification,
                timestamp = now
            )

            // 1. Check if this chat has ACTIVE AUTONOMOUS AUTHORIZATION (Phase 4.5 D & 4.6)
            if (isChatAuthorized(rawTitle, pkg)) {
                Log.i(TAG, "[WA_AUTONOMOUS_REPLY] Chat '$rawTitle' is authorized -> generating autonomous background reply")
                AffyDiagnostics.logWhatsAppStage(
                    stage = "WA_BG_REPLY_STARTED",
                    packageName = pkg,
                    sender = rawTitle,
                    details = "Authorized autonomous background reply"
                )

                // Generate contextual reply
                val autoReplyText = generateContextualReply(rawTitle, text)
                val sent = sendDirectReply(notification, autoReplyText)

                if (sent) {
                    Log.i(TAG, "[WA_AUTONOMOUS_REPLY_VERIFIED] Reply sent to '$rawTitle' via RemoteInput: '$autoReplyText'")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_VERIFIED",
                        packageName = pkg,
                        sender = rawTitle,
                        details = "Autonomous reply: '$autoReplyText'",
                        isSuccess = true
                    )
                } else {
                    Log.w(TAG, "[WA_AUTONOMOUS_REPLY_FAILED] Autonomous background reply failed for '$rawTitle'")
                    AffyDiagnostics.logWhatsAppStage(
                        stage = "WA_BG_REPLY_UNSUPPORTED",
                        packageName = pkg,
                        sender = rawTitle,
                        details = "RemoteInput not available",
                        isSuccess = false
                    )
                }
                return@launch
            }

            // 2. Normal Interactive Flow (IDENTIFY -> ASK -> READ -> USER DECISION)
            // Check if assistant is currently busy with another prompt
            val currentState = _assistantState.value
            val pending = activePendingNotification
            if (currentState != NotificationAssistantState.IDLE) {
                if (pending != null && now - pending.timestamp > 25_000L) {
                    reset()
                } else {
                    Log.d(TAG, "Assistant is actively interacting (${currentState}). Skipping new prompt.")
                    return@launch
                }
            }

            activePendingNotification = immutableTarget
            _assistantState.value = NotificationAssistantState.AWAITING_READ_CONFIRMATION

            val pendingList = AffyConversationContextManager.getInstance().getPendingNotifications(rawTitle)
            val olderUnread = pendingList.filter { it.readState == NotificationReadState.ANNOUNCED_BUT_NOT_READ && (now - it.timestamp) > 5_000L }

            val appName = if (WhatsAppInstanceManager.isWhatsAppPackage(pkg)) "WhatsApp" else resolveAppName(pkg)
            val prompt = "Boss, notification aya hai. Sunaun?"

            AffyConversationContextManager.getInstance().markNotificationAnnounced(rawTitle)

            AffyDiagnostics.logNotificationStage(
                stage = "ANNOUNCEMENT_CREATED",
                packageName = pkg,
                sender = rawTitle,
                details = prompt
            )

            Log.i(TAG, "Audibly prompting user: '$prompt' for notification from $rawTitle on $pkg")
            speakAudible(prompt) {
                requestListenResponseWindow()
            }
        }
    }

    private fun requestListenResponseWindow() {
        val listenCallback = onListenRequest
        if (listenCallback != null) {
            listenCallback()
        } else {
            val voiceService = com.example.service.AffyVoiceService.instance
            voiceService?.startTemporaryResponseWindow()
        }
    }

    private fun getFilterRejectionReason(sbn: StatusBarNotification, pkg: String, title: String, text: String): String? {
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        val notification = sbn.notification ?: return "NO_NOTIFICATION"
        val extras = notification.extras ?: Bundle()

        if (text.isBlank()) return "EMPTY_TEXT"

        // 1. WhatsApp Channel / Newsletter metadata inspection
        if (WhatsAppInstanceManager.isWhatsAppPackage(pkg)) {
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.lowercase() ?: ""
            if (subText.contains("channel") || subText.contains("newsletter") || subText.contains("broadcast") ||
                (subText.contains("update") && !subText.contains("message"))) {
                return "WHATSAPP_CHANNEL_METADATA_SUBTEXT"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = notification.channelId?.lowercase() ?: ""
                if (channelId.contains("newsletter") || channelId.contains("channel") || channelId.contains("broadcast") ||
                    channelId.contains("feed") || channelId.contains("status")) {
                    return "WHATSAPP_CHANNEL_NOTIFICATION_CHANNEL_ID"
                }
            }

            val convTitle = extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.lowercase() ?: ""
            if (convTitle.contains("channel") || convTitle.contains("newsletter") || convTitle.contains("broadcast")) {
                return "WHATSAPP_CHANNEL_CONVERSATION_TITLE"
            }

            val category = notification.category
            if (category != null && category != Notification.CATEGORY_MESSAGE &&
                (category == Notification.CATEGORY_STATUS || category == Notification.CATEGORY_PROMO ||
                        category == Notification.CATEGORY_RECOMMENDATION || category == Notification.CATEGORY_SOCIAL)) {
                return "WHATSAPP_NON_MESSAGE_CATEGORY"
            }

            val hasReplyAction = hasDirectReplyAction(notification)
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (!hasReplyAction && messagingStyle == null) {
                return "WHATSAPP_CHANNEL_NO_REPLY_ACTION"
            }
        }

        if (lowerTitle.contains("@newsletter") || lowerTitle.contains("channel") || lowerTitle.contains("broadcast") ||
            lowerText.contains("follow this channel") || lowerText.contains("view channel") ||
            lowerText.contains("channel update") || lowerText.contains("updates from")) {
            return "CHANNEL_OR_BROADCAST_KEYWORDS"
        }
        if (lowerTitle.contains("whatsapp") || lowerTitle.contains("checking for new messages")) {
            return "SYSTEM_SYNC_MESSAGE"
        }
        if (lowerText.contains("checking for new messages") || lowerText.contains("whatsapp web is currently active")) {
            return "WHATSAPP_STATUS_MESSAGE"
        }
        val mediaOnly = listOf("photo", "image", "video", "sticker", "gif", "voice message", "audio", "missed voice call", "missed video call", "missed call")
        if (mediaOnly.any { lowerText == it }) {
            return "MEDIA_WITHOUT_TEXT"
        }
        return null
    }

    private fun hasDirectReplyAction(notification: Notification): Boolean {
        val compatCount = NotificationCompat.getActionCount(notification)
        for (i in 0 until compatCount) {
            val action = NotificationCompat.getAction(notification, i)
            if (!action?.remoteInputs.isNullOrEmpty() && action?.actionIntent != null) {
                return true
            }
        }
        val actions = notification.actions
        if (!actions.isNullOrEmpty()) {
            for (action in actions) {
                if (!action.remoteInputs.isNullOrEmpty() && action.actionIntent != null) {
                    return true
                }
            }
        }
        return false
    }

    private fun cleanupHashCache(now: Long) {
        val iterator = recentNotificationHashes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 60_000L) {
                iterator.remove()
            }
        }
    }

    /**
     * Intercepts user speech when assistant is in an active notification session.
     * Returns true if the speech was consumed by the notification assistant flow.
     */
    fun handleSpeech(userSpeech: String): Boolean {
        val currentState = _assistantState.value
        val pending = activePendingNotification

        val lower = userSpeech.lowercase().trim()

        // 1. Handle Chat Authorization command at ANY time: "Is chat ko handle karo"
        if (lower.contains("is chat ko handle karo") || lower.contains("iski chat handle karo") ||
            lower.contains("chat handle karo") || lower.contains("handle this chat") ||
            lower.contains("chat ko handle karo")
        ) {
            val targetSender = pending?.sender ?: recentReceivedMessages.firstOrNull()?.sender
            val targetPkg = pending?.packageName ?: recentReceivedMessages.firstOrNull()?.packageName ?: WhatsAppInstanceManager.PKG_WHATSAPP
            if (!targetSender.isNullOrBlank()) {
                authorizeChat(targetSender, targetPkg)
                reset()
                speakAudible("Ji boss, $targetSender ki chat handle kar raha hoon. Aane wale messages ka automatically reply bhej doon ga.", null)
                return true
            }
        }

        // 2. Stop Chat Authorization command: "Is chat ko handle karna band karo" / "Isko stop karo"
        if (lower.contains("handle karna band") || lower.contains("handle karna roko") ||
            lower.contains("handle band") || lower.contains("stop handling") ||
            lower.contains("automatically reply mat karna") || lower.contains("auto reply band")
        ) {
            val targetSender = pending?.sender ?: recentReceivedMessages.firstOrNull()?.sender
            revokeChat(targetSender)
            reset()
            val senderName = targetSender ?: "is"
            speakAudible("Theek hai boss, $senderName chat ka automatic reply band kar diya.", null)
            return true
        }

        if (currentState == NotificationAssistantState.IDLE || pending == null) {
            return false
        }

        // Check for cancellation or dismissal
        if (lower.contains("nahi") || lower.contains("no") || lower.contains("cancel") ||
            lower.contains("ruk") || lower.contains("stop") || lower.contains("mat parho") ||
            lower.contains("chup") || lower.contains("off ho jao") || lower.contains("band ho jao") ||
            lower.contains("ignore") || lower.contains("rehndo") || lower.contains("rehne do")
        ) {
            reset()
            return true
        }

        when (currentState) {
            NotificationAssistantState.AWAITING_READ_CONFIRMATION -> {
                val isPositive = lower.contains("haan") || lower.contains("suna") || lower.contains("sunao") ||
                        lower.contains("sunaao") || lower.contains("parho") || lower.contains("parh") ||
                        lower.contains("batao") || lower.contains("bataiye") || lower.contains("kya aaya") ||
                        lower.contains("bol do") || lower.contains("yes") || lower.contains("bolo") ||
                        lower.contains("read") || lower.contains("theek hai") || lower.contains("kya hai") ||
                        lower.contains("ok")

                if (isPositive) {
                    AffyConversationContextManager.getInstance().markNotificationRead(pending.sender)
                    val senderPrefix = if (pending.sender.isNotBlank() && pending.sender != "Kisi") "${pending.sender}: " else ""
                    val contentText = "$senderPrefix'${pending.messageText}'"

                    val hasReply = hasDirectReplyAction(pending.notification)
                    if (hasReply) {
                        _assistantState.value = NotificationAssistantState.AWAITING_REPLY_INTENT
                        val readText = "$contentText. Boss, iska reply kar doon?"
                        speakAudible(readText) {
                            requestListenResponseWindow()
                        }
                    } else {
                        reset()
                        speakAudible(contentText, null)
                    }
                    return true
                }
                return false
            }

            NotificationAssistantState.AWAITING_REPLY_INTENT -> {
                // Check if user authorized chat handling
                if (lower.contains("handle karo") || lower.contains("handle this")) {
                    authorizeChat(pending.sender, pending.packageName)
                    reset()
                    speakAudible("Ji boss, ${pending.sender} ki chat handle kar raha hoon. Automatically reply kar doon ga.", null)
                    return true
                }

                // Check if user gave an exact custom reply text
                var customReply = ""
                when {
                    lower.startsWith("reply karo: ") -> customReply = userSpeech.substringAfter("reply karo: ").trim()
                    lower.startsWith("reply karo ") -> customReply = userSpeech.substringAfter("reply karo ").trim()
                    lower.startsWith("bolo ") -> customReply = userSpeech.substringAfter("bolo ").trim()
                    lower.startsWith("bol do ") -> customReply = userSpeech.substringAfter("bol do ").trim()
                    lower.startsWith("likho ") -> customReply = userSpeech.substringAfter("likho ").trim()
                    lower.startsWith("bhejo ") -> customReply = userSpeech.substringAfter("bhejo ").trim()
                }

                if (customReply.isNotBlank()) {
                    scope.launch {
                        dispatchReply(pending, customReply)
                    }
                    return true
                }

                // If user said affirmative ("haan", "reply karna hai", "yes", "kar do") without reply text, ask what to reply
                val isAffirmativeOnly = lower == "haan" || lower == "yes" || lower == "ji" ||
                        lower.contains("reply karna hai") || lower == "kar do" || lower == "theek hai" || lower == "ok"

                if (isAffirmativeOnly) {
                    _assistantState.value = NotificationAssistantState.AWAITING_REPLY_INTENT
                    speakAudible("Kya reply bhejun boss?") {
                        requestListenResponseWindow()
                    }
                    return true
                }

                // User spoke arbitrary reply content directly in response window (e.g., "Main 10 minute mein pohanch raha hoon")
                if (userSpeech.isNotBlank() && userSpeech.length >= 2) {
                    scope.launch {
                        dispatchReply(pending, userSpeech)
                    }
                    return true
                }

                return false
            }

            NotificationAssistantState.CONFIRMING_REPLY -> {
                val isConfirmed = lower.contains("haan") || lower.contains("bhej do") ||
                        lower.contains("yes") || lower.contains("send") || lower.contains("theek hai") ||
                        lower.contains("kar do") || lower.contains("ok") || lower.contains("bhejo")

                if (isConfirmed) {
                    val replyToSend = stagedReplyText ?: ""
                    if (replyToSend.isNotBlank()) {
                        scope.launch {
                            dispatchReply(pending, replyToSend)
                        }
                    }
                    return true
                }
                return false
            }

            else -> return false
        }
    }

    private suspend fun generateContextualReply(sender: String, messageText: String): String {
        return try {
            val prompt = "Generate a very short, polite, natural WhatsApp reply in Urdu/Hindi (Roman English script, max 10 words) for this incoming message from $sender: \"$messageText\". Keep it concise and natural."
            val reply = geminiRepository.generateText(prompt)?.trim()?.replace("\"", "")
            if (!reply.isNullOrBlank() && reply.length < 80) {
                reply
            } else {
                "Ji theek hai, main thodi der mein baat karta hoon."
            }
        } catch (e: Exception) {
            "Theek hai, main check karke batata hoon."
        }
    }

    private suspend fun dispatchReply(pending: ImmutableNotificationTarget, replyText: String) {
        val pkg = pending.packageName
        Log.i(TAG, "[WA_BG_REPLY_STARTED] Dispatching locked target reply to ${pending.sender} via $pkg: '$replyText'")

        // 1. Direct Reply via Notification RemoteInput (Background, no UI)
        var sent = sendDirectReply(pending.notification, replyText)

        // 2. Fallback via Accessibility Service only if RemoteInput is genuinely unavailable
        if (!sent) {
            try {
                if (pending.notification.contentIntent != null) {
                    pending.notification.contentIntent.send()
                    delay(1000)
                    val a11y = AffyAccessibilityService.instance
                    if (a11y != null) {
                        sent = a11y.automateWhatsAppSend(targetPackage = pkg, messageText = replyText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback send failed: ${e.message}")
            }
        }

        reset()

        if (sent) {
            AffyDiagnostics.logWhatsAppStage(
                stage = "WA_BG_REPLY_VERIFIED",
                packageName = pkg,
                sender = pending.sender,
                details = "Reply sent: '$replyText'",
                isSuccess = true
            )
            speakAudible("Boss, reply bhej diya.", null)
        } else {
            AffyDiagnostics.logWhatsAppStage(
                stage = "WA_BG_REPLY_UNSUPPORTED",
                packageName = pkg,
                sender = pending.sender,
                details = "Could not send reply via RemoteInput or Accessibility",
                isSuccess = false,
                failureReason = "Direct reply failed"
            )
            speakAudible("Boss, background reply available nahi ho saka.", null)
        }
    }

    private fun sendDirectReply(notification: Notification, replyText: String): Boolean {
        // 1. Check NotificationCompat actions
        val compatCount = NotificationCompat.getActionCount(notification)
        for (i in 0 until compatCount) {
            val action = NotificationCompat.getAction(notification, i)
            val remoteInputs = action?.remoteInputs
            val pendingIntent = action?.actionIntent
            if (!remoteInputs.isNullOrEmpty() && pendingIntent != null) {
                val bundle = Bundle()
                for (ri in remoteInputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                androidx.core.app.RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
                try {
                    pendingIntent.send(context, 0, fillInIntent)
                    Log.i(TAG, "[WA_BG_REPLY_REMOTE_INPUT_FOUND] Direct reply dispatched via NotificationCompat action $i")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "NotificationCompat action $i send failed: ${e.message}")
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
                    val bundle = Bundle()
                    for (ri in remoteInputs) {
                        bundle.putCharSequence(ri.resultKey, replyText)
                    }
                    val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    android.app.RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
                    try {
                        pendingIntent.send(context, 0, fillInIntent)
                        Log.i(TAG, "[WA_BG_REPLY_REMOTE_INPUT_FOUND] Direct reply dispatched via standard action")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct reply failed: ${e.message}")
                    }
                }
            }
        }

        return false
    }

    /**
     * Returns recent received messages filtered by app package if requested.
     */
    fun getRecentMessages(appFilter: String? = null, maxAgeMs: Long = 7200_000L): List<RecentReceivedMessage> {
        val now = System.currentTimeMillis()
        val list = recentReceivedMessages.toList()
        return list.filter { msg ->
            (now - msg.timestamp) <= maxAgeMs &&
                    (appFilter == null ||
                            (appFilter.equals("whatsapp", ignoreCase = true) && WhatsAppInstanceManager.isWhatsAppPackage(msg.packageName)) ||
                            msg.packageName.contains(appFilter, ignoreCase = true))
        }
    }

    /**
     * Generates a natural, human-like conversational summary of recent notifications.
     */
    fun getNaturalSummaryOfRecentMessages(appFilter: String? = "whatsapp"): String {
        val messages = getRecentMessages(appFilter)
        if (messages.isEmpty()) {
            return if (appFilter.equals("whatsapp", ignoreCase = true)) {
                "Boss, abhi WhatsApp pe koi naya message nahi aya hai."
            } else {
                "Boss, abhi koi naya notification ya message nahi aya hai."
            }
        }

        val groupedBySender = messages.groupBy { it.sender }
        val senderSummaries = mutableListOf<String>()

        for ((sender, msgs) in groupedBySender) {
            val isUnknown = msgs.firstOrNull()?.isUnknownNumber == true
            val latestText = msgs.firstOrNull()?.messageText ?: ""
            val count = msgs.size

            val summaryPart = when {
                isUnknown && count == 1 -> "Ek unknown number ($sender) se hai: '$latestText'"
                isUnknown && count > 1 -> "Ek unknown number ($sender) se $count messages hain: '$latestText'"
                count == 1 -> "$sender ne '$latestText' bheja hai"
                else -> "$sender ne $count messages bheje hain, aakhri hai: '$latestText'"
            }
            senderSummaries.add(summaryPart)
        }

        val totalMsgs = messages.size
        val countWord = if (totalMsgs == 1) "ek message aya hai" else "$totalMsgs messages aaye hain"
        val joined = senderSummaries.joinToString(" aur ")

        return "Boss, $countWord. $joined."
    }

    fun resolveContextualSender(reference: String): RecentReceivedMessage? {
        val cleanRef = reference.lowercase().trim()
        val list = recentReceivedMessages.toList()
        if (list.isEmpty()) return null

        val active = activePendingNotification
        if (active != null) {
            val activeMsg = list.find { it.sender.equals(active.sender, ignoreCase = true) }
            if (activeMsg != null) return activeMsg
        }

        val isPronoun = cleanRef.contains("iski") || cleanRef.contains("iska") || cleanRef.contains("isko") ||
                cleanRef.contains("uska") || cleanRef.contains("unka") || cleanRef.contains("inhe") ||
                cleanRef.contains("unhe") || cleanRef.contains("latest") || cleanRef.contains("pehla") ||
                cleanRef.contains("first") || cleanRef.contains("ye") || cleanRef.contains("chat") ||
                cleanRef.contains("handle") || cleanRef == "haan" || cleanRef == "yes"

        if (isPronoun) {
            return list.firstOrNull()
        }

        val matched = list.firstOrNull { msg ->
            msg.sender.contains(cleanRef, ignoreCase = true) || cleanRef.contains(msg.sender.lowercase())
        }
        if (matched != null) return matched

        return list.firstOrNull()
    }

    suspend fun executeDirectReply(recipientOrReference: String, replyText: String): Pair<Boolean, String> {
        val target = resolveContextualSender(recipientOrReference)
        if (target == null) {
            return Pair(false, "Koi active message ya contact nahi mila jisko reply bheja ja sake.")
        }

        Log.i(TAG, "[WA_BG_REPLY_STARTED] Executing direct reply to ${target.sender} (${target.packageName}): '$replyText'")

        var sent = false
        if (target.notification != null) {
            sent = sendDirectReply(target.notification, replyText)
        }

        if (!sent) {
            try {
                if (target.notification?.contentIntent != null) {
                    target.notification.contentIntent.send()
                    delay(1000)
                    val a11y = AffyAccessibilityService.instance
                    if (a11y != null) {
                        sent = a11y.automateWhatsAppSend(targetPackage = target.packageName, messageText = replyText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback accessibility reply failed: ${e.message}")
            }
        }

        reset()

        return if (sent) {
            AffyDiagnostics.logWhatsAppStage(
                stage = "WA_BG_REPLY_VERIFIED",
                packageName = target.packageName,
                sender = target.sender,
                details = "Reply sent: '$replyText'",
                isSuccess = true
            )
            Pair(true, "Ho gaya Boss. ${target.sender} ko reply bhej diya.")
        } else {
            AffyDiagnostics.logWhatsAppStage(
                stage = "WA_BG_REPLY_UNSUPPORTED",
                packageName = target.packageName,
                sender = target.sender,
                details = "Could not send reply",
                isSuccess = false,
                failureReason = "Failed to send via notification actions or accessibility"
            )
            Pair(false, "Boss, reply send nahi ho saka.")
        }
    }

    fun reset() {
        _assistantState.value = NotificationAssistantState.IDLE
        activePendingNotification = null
        stagedReplyText = null
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    private fun resolveAppName(packageName: String): String {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            packageName.contains("youtube", ignoreCase = true) -> "YouTube"
            packageName.contains("instagram", ignoreCase = true) -> "Instagram"
            packageName.contains("tiktok", ignoreCase = true) || packageName.contains("musically", ignoreCase = true) || packageName.contains("trill", ignoreCase = true) -> "TikTok"
            packageName.contains("telegram", ignoreCase = true) -> "Telegram"
            packageName.contains("gm", ignoreCase = true) || packageName.contains("gmail", ignoreCase = true) -> "Gmail"
            packageName.contains("facebook", ignoreCase = true) || packageName.contains("katana", ignoreCase = true) -> "Facebook"
            packageName.contains("orca", ignoreCase = true) -> "Messenger"
            packageName.contains("twitter", ignoreCase = true) || packageName.contains("x.android", ignoreCase = true) -> "X"
            packageName.contains("snapchat", ignoreCase = true) -> "Snapchat"
            packageName.contains("messaging", ignoreCase = true) || packageName.contains("mms", ignoreCase = true) -> "Messages"
            else -> {
                try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString().trim()
                    if (label.isNotBlank()) label else "ek app"
                } catch (e: Exception) {
                    "ek app"
                }
            }
        }
    }

    private fun isSensitiveContent(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("otp") || lower.contains("verification code") ||
                lower.contains("one time password") || lower.contains("security code") ||
                lower.contains("secret code") || lower.contains("passcode") ||
                lower.contains("login code") || lower.contains("pin is") ||
                lower.contains("cvv") || lower.contains("bank code") ||
                lower.contains("bank secret") || lower.contains("atm pin")
    }
}
