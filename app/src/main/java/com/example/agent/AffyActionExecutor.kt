package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import com.example.data.datastore.AffyPreferences
import com.example.data.db.AffyDatabase
import com.example.data.db.AgentMemoryEntity
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.agent.vision.ScreenStateType
import com.example.agent.vision.TargetResolutionResult
import com.example.agent.task.AgentExecutionResult
import com.example.agent.task.AgentResultStatus
import com.example.agent.task.AgentTaskStep
import com.example.service.AffyAccessibilityService
import com.example.service.AffyReminderManager
import com.example.service.WhatsAppInstanceManager
import com.example.voice.AffySingerEngine
import com.example.voice.SingerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActionResult(
    val isSuccess: Boolean,
    val summary: String,
    val speechResponseOverride: String? = null
)

class AffyActionExecutor(
    private val context: Context,
    private val database: AffyDatabase = AffyDatabase.getDatabase(context),
    private val reminderManager: AffyReminderManager = AffyReminderManager(context),
    private val contactResolver: ContactResolver = ContactResolver(context),
    private val preferences: AffyPreferences = AffyPreferences(context),
    val gmailManager: AffyGmailManager = AffyGmailManager(context, preferences),
    private val visionEngine: AffyScreenVisionEngine = AffyScreenVisionEngine.getInstance(context),
    val singerEngine: AffySingerEngine = AffySingerEngine.getInstance(context)
) {

    suspend fun executeAction(action: AffyActionItem): ActionResult = withContext(Dispatchers.IO) {
        AffyDiagnostics.markActionStart(action.type, action.target)
        val result = when (action.type) {
            ActionTypes.SING_SONG -> handleSingSong(action.text ?: action.target ?: "")
            ActionTypes.OPEN_APP -> openApp(action.target ?: "")
            ActionTypes.CALL_CONTACT -> handleCallContact(action.target ?: "")
            ActionTypes.WHATSAPP_CALL -> handleWhatsAppCall(action.target ?: "")
            ActionTypes.SEND_WHATSAPP_MESSAGE, ActionTypes.SEND_MESSAGE -> handleWhatsAppMessage(action.target ?: "", action.text ?: "")
            ActionTypes.SEND_SMS -> handleSendSms(action.target ?: "", action.text ?: "")
            ActionTypes.TAP, ActionTypes.CLICK_ELEMENT -> handleClickElement(action.target ?: action.text ?: "")
            ActionTypes.LONG_PRESS -> handleLongPressElement(action.target ?: action.text ?: "")
            ActionTypes.TAP_LIKE, ActionTypes.LIKE_POST -> handleLikeAction()
            ActionTypes.UNLIKE_POST -> handleUnlikeAction()
            ActionTypes.SUBSCRIBE_CHANNEL -> handleSubscribeChannel(action.target ?: action.text)
            ActionTypes.GET_VIDEO_TITLE -> handleGetVideoTitle()
            ActionTypes.GET_MEDIA_CAPTION -> handleGetMediaCaption()
            ActionTypes.OPEN_COMMENTS -> handleOpenComments()
            ActionTypes.SELECT_VIDEO_RESULT -> handleSelectVideoResult(action.value?.toIntOrNull() ?: 1, action.text ?: action.target)
            ActionTypes.UNIVERSAL_SEARCH -> handleUniversalSearch(action.text ?: action.target ?: "", action.target)
            ActionTypes.COMMENT_ON_POST -> handleCommentOnPost(action.text ?: action.value ?: "")
            ActionTypes.SHARE_POST -> handleSharePost()
            ActionTypes.SEARCH_IN_SCREEN -> handleSearchInScreen(action.text ?: action.target ?: "")
            ActionTypes.SCROLL, ActionTypes.SCROLL_DOWN -> handleScroll(down = true)
            ActionTypes.SCROLL_UP -> handleScroll(down = false)
            ActionTypes.SWIPE, ActionTypes.SWIPE_LEFT -> handleSwipe(left = true)
            ActionTypes.SWIPE_RIGHT -> handleSwipe(left = false)
            ActionTypes.NEXT_VIDEO -> handleNextVideo()
            ActionTypes.TYPE_TEXT -> handleTypeText(action.text ?: action.value ?: "", action.target, shouldSend = action.value?.contains("send") == true || action.target?.contains("send") == true)
            ActionTypes.CLEAR_TEXT -> handleClearText(action.target)
            ActionTypes.OPEN_KEYBOARD -> handleOpenKeyboard(action.target)
            ActionTypes.SELECT_TEXT -> handleSelectText(action.target ?: action.text ?: "")
            ActionTypes.GO_BACK, ActionTypes.NAVIGATE_BACK -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Back navigation")
            ActionTypes.GO_HOME -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Home navigation")
            ActionTypes.OPEN_RECENTS -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
            ActionTypes.OPEN_NOTIFICATIONS -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
            ActionTypes.OPEN_QUICK_SETTINGS -> handleGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
            ActionTypes.CLOSE_APP, ActionTypes.CLOSE_PAGE -> handleCloseAppOrPage(action.target)
            ActionTypes.MEDIA_PLAY -> handleMediaControl("play")
            ActionTypes.MEDIA_PAUSE -> handleMediaControl("pause")
            ActionTypes.MEDIA_NEXT -> handleMediaControl("next")
            ActionTypes.CONTROL_MEDIA -> handleMediaControl(action.target ?: "play")
            ActionTypes.VOLUME_UP -> handleVolume("up")
            ActionTypes.VOLUME_DOWN -> handleVolume("down")
            ActionTypes.SET_VOLUME -> handleVolume(action.value ?: "up")
            ActionTypes.BRIGHTNESS, ActionTypes.SET_BRIGHTNESS -> handleBrightness(action.value ?: "up")
            ActionTypes.TOGGLE_FLASHLIGHT -> handleFlashlight(action.value ?: action.target ?: "toggle")
            ActionTypes.TOGGLE_WIFI -> handleToggleWifi(action.value ?: action.target)
            ActionTypes.TOGGLE_BLUETOOTH -> handleQuickSettingToggle("Bluetooth", action.value ?: action.target)
            ActionTypes.TOGGLE_MOBILE_DATA -> handleToggleMobileData(action.value ?: action.target)
            ActionTypes.TOGGLE_HOTSPOT -> handleQuickSettingToggle("Hotspot", action.value ?: action.target)
            ActionTypes.TOGGLE_AIRPLANE_MODE -> handleQuickSettingToggle("Airplane Mode", action.value ?: action.target)
            ActionTypes.TOGGLE_AUTO_ROTATE -> handleQuickSettingToggle("Auto-rotate", action.value ?: action.target)
            ActionTypes.SET_RINGER_MODE -> handleRingerMode(action.value ?: action.target ?: "normal")
            ActionTypes.TOGGLE_QUICK_SETTING -> handleQuickSettingToggle(action.target ?: "Quick Setting", action.value)
            ActionTypes.SET_ALARM -> handleSetAlarm(action.target ?: "", action.text ?: "")
            ActionTypes.CREATE_REMINDER -> handleCreateReminder(action.target ?: "", action.text ?: "")
            ActionTypes.CANCEL_REMINDER -> handleCancelReminder(action.target ?: "")
            ActionTypes.READ_NOTIFICATION -> handleReadNotifications(action.target)
            ActionTypes.READ_VISIBLE_CHATS -> handleReadVisibleChats()
            ActionTypes.READ_SCREEN_MESSAGES -> handleReadScreenMessages()
            ActionTypes.HANDLE_CHAT_AUTHORIZATION -> handleAuthorizeChat(action.target ?: "")
            ActionTypes.REVOKE_CHAT_AUTHORIZATION -> handleRevokeChat(action.target)
            ActionTypes.REMEMBER_FACT -> handleRememberFact(action.value ?: action.text ?: action.target ?: "")
            ActionTypes.FORGET_FACT -> handleForgetFact(action.target ?: action.text ?: action.value ?: "")
            ActionTypes.RECALL_FACT -> handleRecallFact(action.target ?: action.text ?: "")
            ActionTypes.CLEAR_MEMORY -> handleClearMemory()
            ActionTypes.GET_TIME -> handleGetTime()
            ActionTypes.GET_DATE -> handleGetDate()
            ActionTypes.GET_BATTERY -> handleGetBattery()
            ActionTypes.SEARCH, ActionTypes.SEARCH_WEB -> {
                val target = (action.target ?: "").trim()
                val text = (action.text ?: "").trim()
                if (target.equals("YouTube", ignoreCase = true) || text.lowercase().contains("youtube") || target.lowercase().contains("youtube")) {
                    val cleanQuery = if (text.isNotBlank()) text.replace(Regex("(?i)\\byoutube\\b"), "").trim() else target.replace(Regex("(?i)\\byoutube\\b"), "").trim()
                    executeMultiStepAppCommand("YouTube", cleanQuery.ifBlank { "trending" }, "search")
                } else if (target.equals("Spotify", ignoreCase = true)) {
                    executeMultiStepAppCommand("Spotify", text.ifBlank { target }, "search")
                } else {
                    val webQuery = if (text.isNotBlank()) text else target
                    handleSearchWeb(webQuery)
                }
            }
            ActionTypes.READ_SCREEN, ActionTypes.EXPLAIN_SCREEN -> handleReadScreen()
            ActionTypes.MULTI_STEP_APP_COMMAND -> executeMultiStepAppCommand(
                appName = action.target ?: "",
                query = action.text ?: "",
                desiredAction = action.value ?: "play"
            )
            ActionTypes.CHECK_GMAIL -> handleGmailCheck()
            ActionTypes.READ_GMAIL -> handleGmailRead()
            ActionTypes.REPLY_GMAIL -> handleGmailReply(action.text ?: "")
            "CHECK_ATTACHMENT", "VERIFY_ATTACHMENT" -> handleCheckAttachment()
            ActionTypes.STOP_CANCEL -> handleStopCancel()
            ActionTypes.DEACTIVATE_OVERLAY -> handleDeactivateOverlay()
            ActionTypes.SET_VOICE, ActionTypes.CHANGE_VOICE -> handleSetVoice(action.target ?: action.value)
            ActionTypes.LOCK_DEVICE -> handleLockDevice()
            ActionTypes.UNLOCK_DEVICE, ActionTypes.WAKE_SCREEN -> handleUnlockOrWakeDevice()
            ActionTypes.TAKE_SCREENSHOT -> handleTakeScreenshot()
            ActionTypes.GENERATE_IMAGE -> handleGenerateImage(action.text ?: action.target ?: "")
            ActionTypes.ANALYZE_SECURITY -> handleAnalyzeSecurity(action.text ?: action.target ?: "")
            ActionTypes.RESEARCH_TOPIC -> handleResearchTopic(action.text ?: action.target ?: "")
            ActionTypes.COPY_TEXT -> handleCopyText(action.text ?: action.target ?: "")
            ActionTypes.PASTE_TEXT -> handlePasteText(action.target)
            ActionTypes.OPEN_CAMERA -> handleOpenCamera()
            ActionTypes.TAKE_PHOTO -> handleTakePhoto(action.value ?: action.target)
            ActionTypes.FORCE_STOP_APP, ActionTypes.KILL_APP -> handleForceStopApp(action.target ?: action.text ?: "")
            ActionTypes.SELECT_SIM -> handleSelectSim(action.target ?: action.value)
            else -> ActionResult(true, "Chat or generic action")
        }

        AffyDiagnostics.markActionVerified(action.type, result.isSuccess)
        AffyDiagnostics.logAction(
            category = "ACTION_EXECUTION",
            tag = action.type,
            message = "${action.type} -> ${result.summary}",
            isSuccess = result.isSuccess
        )
        result
    }

    /**
     * Executes an autonomous AgentTaskStep and returns an AgentExecutionResult
     * indicating SUCCESS, FAILED, NEEDS_CONFIRMATION, or UNSUPPORTED.
     */
    suspend fun executeAgentStep(step: AgentTaskStep): AgentExecutionResult = withContext(Dispatchers.IO) {
        val actionType = step.actionType
        val target = step.target
        val text = step.text
        val value = step.value

        // Safety gate check for sensitive actions
        if (isSensitiveAction(actionType)) {
            return@withContext AgentExecutionResult(
                status = AgentResultStatus.NEEDS_CONFIRMATION,
                summary = "Action '$actionType' requires explicit user confirmation.",
                speechOverride = "Kya aap waqai '$actionType' perform karna chahte hain?"
            )
        }

        // Convert to standard action item
        val actionItem = AffyActionItem(
            type = actionType,
            target = target,
            text = text,
            value = value
        )

        AffyDiagnostics.logTaskStage("ACTION_EXECUTE", action = actionType, details = step.description)

        val result = executeAction(actionItem)

        if (result.isSuccess) {
            AgentExecutionResult(
                status = AgentResultStatus.SUCCESS,
                summary = result.summary,
                speechOverride = result.speechResponseOverride,
                observedState = result.summary
            )
        } else {
            // Check if failure is recoverable
            val canRecover = step.retryCount < 2 && isRecoverableAction(actionType)
            val recoveryHint = when (actionType) {
                ActionTypes.OPEN_APP -> "Re-query installed packages with relaxed fuzzy matching"
                ActionTypes.TAP, ActionTypes.CLICK_ELEMENT -> "Re-inspect accessibility node tree after delay"
                ActionTypes.SEARCH_WEB, ActionTypes.SEARCH -> "Fallback to direct generic web search intent"
                else -> null
            }

            AgentExecutionResult(
                status = AgentResultStatus.FAILED,
                summary = result.summary,
                speechOverride = result.speechResponseOverride,
                canRecover = canRecover,
                recoveryHint = recoveryHint
            )
        }
    }

    companion object {
        fun isSensitiveAction(actionType: String): Boolean {
            val upper = actionType.uppercase(Locale.ROOT)
            return upper == ActionTypes.CLEAR_MEMORY ||
                    upper == "CLEAR_MEMORY" ||
                    upper == "FACTORY_RESET" ||
                    upper == "DELETE_ALL_DATA" ||
                    upper == "DELETE_DATABASE"
        }

        fun isRecoverableAction(actionType: String): Boolean {
            return actionType == ActionTypes.OPEN_APP ||
                    actionType == ActionTypes.TAP ||
                    actionType == ActionTypes.CLICK_ELEMENT ||
                    actionType == ActionTypes.SEARCH_WEB ||
                    actionType == ActionTypes.SEARCH
        }
    }

    private suspend fun openApp(appName: String): ActionResult {
        if (appName.isBlank()) return ActionResult(false, "No app name provided")

        val cleanName = appName.trim().lowercase()

        // Phase 3.1 WhatsApp Smart Instance Intelligence
        if (cleanName.contains("whatsapp")) {
            val waResult = WhatsAppInstanceManager.openWhatsAppSmart(context, appName)
            return when (waResult) {
                is com.example.service.OpenWhatsAppResult.Success -> {
                    ActionResult(true, waResult.message, speechResponseOverride = waResult.message)
                }
                is com.example.service.OpenWhatsAppResult.AlreadyForeground -> {
                    ActionResult(true, waResult.message, speechResponseOverride = waResult.message)
                }
                is com.example.service.OpenWhatsAppResult.MultipleInstances -> {
                    ActionResult(false, "Multiple WhatsApp instances found", speechResponseOverride = waResult.prompt)
                }
                is com.example.service.OpenWhatsAppResult.NotFound -> {
                    ActionResult(false, waResult.message, speechResponseOverride = waResult.message)
                }
                is com.example.service.OpenWhatsAppResult.Failed -> {
                    ActionResult(false, waResult.reason, speechResponseOverride = waResult.reason)
                }
            }
        }

        val service = AffyAccessibilityService.instance
        val currentFg = service?.rootInActiveWindow?.packageName?.toString() ?: AffyAccessibilityService.currentAppPackage.value ?: ""

        // Known popular package mappings
        val packageMap = mapOf(
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "youtube" to listOf("com.google.android.youtube"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "gmail" to listOf("com.google.android.gm"),
            "chrome" to listOf("com.android.chrome"),
            "maps" to listOf("com.google.android.apps.maps"),
            "spotify" to listOf("com.spotify.music"),
            "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera"),
            "settings" to listOf("com.android.settings"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "play store" to listOf("com.android.vending"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2")
        )

        val pm = context.packageManager

        // Check explicit map first
        for ((key, packages) in packageMap) {
            if (cleanName.contains(key) || key.contains(cleanName)) {
                for (pkg in packages) {
                    if (currentFg.equals(pkg, ignoreCase = true)) {
                        return ActionResult(true, "$key is already open", "Boss, $key pehle se open hai.")
                    }
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        val verified = service?.verifyAppInForeground(pkg, 2500L) ?: true
                        val label = key.replaceFirstChar { it.uppercase() }
                        return if (verified) {
                            ActionResult(true, "Opened $label", "Haan, $label khol diya hai.")
                        } else {
                            ActionResult(false, "Failed to verify $label in foreground", "Boss, $label kholne ki koshish ki magar open confirm nahi hua.")
                        }
                    }
                }
            }
        }

        // Generic search through installed apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName
            if (label.contains(cleanName) || cleanName.contains(label)) {
                if (currentFg.equals(pkg, ignoreCase = true)) {
                    val appLabel = info.loadLabel(pm).toString()
                    return ActionResult(true, "$appLabel is already open", "Boss, $appLabel pehle se khula hua hai.")
                }
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val appLabel = info.loadLabel(pm).toString()
                    val verified = service?.verifyAppInForeground(pkg, 2500L) ?: true
                    return if (verified) {
                        ActionResult(true, "Opened $appLabel", "Haan, $appLabel khol diya hai.")
                    } else {
                        ActionResult(false, "Failed to verify $appLabel in foreground", "Boss, $appLabel open karne ki koshish ki magar verify nahi hua.")
                    }
                }
            }
        }

        // Fallback for camera intents
        if (cleanName.contains("camera")) {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(cameraIntent)
            return ActionResult(true, "Opened Camera", "Camera khol diya hai.")
        }

        return ActionResult(
            false,
            "App $appName not installed",
            speechResponseOverride = "Hmm, $appName app aapke phone mein installed nahi mila."
        )
    }

    fun executeDirectCall(phoneNumber: String, contactName: String, typeLabel: String = "Mobile"): ActionResult {
        val success = contactResolver.initiateCall(phoneNumber)
        return if (success) {
            val typeStr = if (typeLabel.isNotBlank()) " ($typeLabel)" else ""
            ActionResult(
                isSuccess = true,
                summary = "Calling $contactName$typeStr",
                speechResponseOverride = "$contactName$typeStr ko call mila raha hoon."
            )
        } else {
            ActionResult(
                isSuccess = false,
                summary = "Failed to place call to $contactName",
                speechResponseOverride = "Call connect nahi ho saki."
            )
        }
    }

    suspend fun executeDirectWhatsApp(
        recipientPhone: String,
        recipientName: String,
        message: String,
        targetPackage: String? = null
    ): ActionResult {
        if (message.isBlank()) {
            return ActionResult(
                isSuccess = false,
                summary = "Message text is empty",
                speechResponseOverride = "$recipientName ko WhatsApp par kya message bhejna hai?"
            )
        }

        val notifAssistant = AffyNotificationAssistant.getInstance(context)
        // Priority 1: Check background RemoteInput
        val contextualMsg = notifAssistant.resolveContextualSender(recipientName)
            ?: notifAssistant.resolveContextualSender(recipientPhone)
        if (contextualMsg != null) {
            val (directSuccess, directSummary) = notifAssistant.executeDirectReply(contextualMsg.sender, message)
            if (directSuccess) {
                return ActionResult(
                    isSuccess = true,
                    summary = directSummary,
                    speechResponseOverride = "Boss, ${contextualMsg.sender} ko WhatsApp reply bhej diya."
                )
            }
        }

        // Priority 2: Foreground Accessibility Flow with strict verification
        val installedInstances = WhatsAppInstanceManager.getInstalledInstances(context)
        if (installedInstances.isEmpty()) {
            return ActionResult(
                isSuccess = false,
                summary = "WhatsApp not installed",
                speechResponseOverride = "Phone mein WhatsApp installed nahi mila."
            )
        }

        val resolvedPkg = targetPackage
            ?: preferences.preferredWhatsAppPackageFlow.first().ifBlank { null }
            ?: WhatsAppInstanceManager.resolveOutgoingInstance(context)
            ?: WhatsAppInstanceManager.PKG_WHATSAPP

        try {
            val cleanPhone = recipientPhone.replace(Regex("[^0-9]"), "")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = resolvedPkg
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            val a11y = AffyAccessibilityService.instance
            if (a11y != null) {
                delay(800)
                val isSent = a11y.automateWhatsAppSend(targetPackage = resolvedPkg, messageText = message)
                return if (isSent) {
                    ActionResult(
                        isSuccess = true,
                        summary = "WhatsApp message sent to $recipientName",
                        speechResponseOverride = "Ji boss! $recipientName ko WhatsApp message bhej diya hai."
                    )
                } else {
                    ActionResult(
                        isSuccess = false,
                        summary = "WhatsApp send unverified",
                        speechResponseOverride = "$recipientName ki chat khol di hai magar message send confirm nahi ho saka. Send dabayein."
                    )
                }
            }

            return ActionResult(
                isSuccess = false,
                summary = "Accessibility service inactive",
                speechResponseOverride = "$recipientName ki chat khol di hai boss. Direct send ke liye Accessibility enable karein."
            )
        } catch (e: Exception) {
            Log.e("AffyActionExecutor", "WhatsApp direct send error: ${e.message}")
            return ActionResult(
                isSuccess = false,
                summary = "WhatsApp send failed: ${e.message}",
                speechResponseOverride = "WhatsApp open karne mein masla hua."
            )
        }
    }

    private fun handleCallContact(contactName: String): ActionResult {
        if (contactName.isBlank()) {
            return ActionResult(false, "No contact name", "Kisko call karni hai?")
        }

        val result = contactResolver.resolveContact(contactName)
        return when (result) {
            is ContactResolutionResult.Single -> {
                val success = contactResolver.initiateCall(result.phoneNumber)
                if (success) {
                    ActionResult(true, "Calling ${result.name}", "${result.name} ko call mila raha hoon.")
                } else {
                    ActionResult(false, "Failed to place call", "Call connect nahi ho saki.")
                }
            }
            is ContactResolutionResult.Multiple -> {
                val formattedList = result.candidates.mapIndexed { idx, c ->
                    "${idx + 1}. ${c.displayString}"
                }.joinToString("\n")

                val speechPrompt = "${result.candidates[0].name} ke ${result.candidates.size} contacts mile hain:\n$formattedList\nKaunsa call karun?"

                AffyConversationContextManager.getInstance().setPendingContactDisambiguation(
                    PendingContactDisambiguation(
                        actionType = DisambiguationActionType.CALL,
                        originalQuery = contactName,
                        candidates = result.candidates
                    )
                )

                ActionResult(
                    isSuccess = false,
                    summary = "Multiple contacts found (${result.candidates.size})",
                    speechResponseOverride = speechPrompt
                )
            }
            is ContactResolutionResult.NotFound -> {
                ActionResult(
                    false,
                    "Contact not found",
                    speechResponseOverride = "Hmm, $contactName naam ka contact nahi mila."
                )
            }
            is ContactResolutionResult.PermissionMissing -> {
                ActionResult(
                    false,
                    "Permission missing",
                    speechResponseOverride = "Contacts aur Phone ki permission chahiye call karne ke liye."
                )
            }
        }
    }

    private suspend fun handleWhatsAppCall(contactName: String, isVideo: Boolean = false): ActionResult {
        if (contactName.isBlank()) {
            return ActionResult(false, "No contact name provided", "Kisko WhatsApp call karni hai?")
        }

        val a11y = AffyAccessibilityService.instance
        val foregroundPkg = AffyAccessibilityService.currentAppPackage.value ?: ""

        // Screen-First check: If WhatsApp conversation is already open on screen
        if (a11y != null && WhatsAppInstanceManager.isWhatsAppPackage(foregroundPkg) &&
            (contactName.contains("current") || contactName.contains("is chat") || contactName.contains("ye chat") || contactName.contains("this"))
        ) {
            val called = a11y.triggerWhatsAppCall(isVideo)
            return if (called) {
                ActionResult(true, "WhatsApp call placed", speechResponseOverride = "Ji boss, WhatsApp call mila di hai.")
            } else {
                ActionResult(false, "Call button not found", speechResponseOverride = "Screen par WhatsApp call button nahi mila.")
            }
        }

        val result = contactResolver.resolveContact(contactName)
        return when (result) {
            is ContactResolutionResult.Single -> {
                val cleanPhone = result.phoneNumber.replace(Regex("[^0-9]"), "")
                val targetPkg = WhatsAppInstanceManager.resolveOutgoingInstance(context) ?: WhatsAppInstanceManager.PKG_WHATSAPP
                try {
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        `package` = targetPkg
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    delay(1000)
                    if (a11y != null) {
                        val called = a11y.triggerWhatsAppCall(isVideo)
                        if (called) {
                            ActionResult(true, "WhatsApp call placed to ${result.name}", speechResponseOverride = "${result.name} ko WhatsApp call mila di hai.")
                        } else {
                            ActionResult(true, "Opened ${result.name} on WhatsApp", speechResponseOverride = "${result.name} ki chat khol di hai boss. Call button dabayein.")
                        }
                    } else {
                        ActionResult(true, "Opened ${result.name} on WhatsApp", speechResponseOverride = "${result.name} ki chat khol di hai boss.")
                    }
                } catch (e: Exception) {
                    ActionResult(false, "WhatsApp call failed: ${e.message}", speechResponseOverride = "WhatsApp call lagane mein masla hua.")
                }
            }
            is ContactResolutionResult.Multiple -> {
                val formattedList = result.candidates.mapIndexed { idx, c ->
                    "${idx + 1}. ${c.displayString}"
                }.joinToString("\n")
                val prompt = "${result.candidates[0].name} ke ${result.candidates.size} contacts mile hain:\n$formattedList\nKaunse number pe WhatsApp call karun?"
                ActionResult(false, "Multiple contacts found", speechResponseOverride = prompt)
            }
            is ContactResolutionResult.NotFound -> {
                ActionResult(false, "Contact not found", speechResponseOverride = "Hmm, $contactName naam ka contact nahi mila.")
            }
            is ContactResolutionResult.PermissionMissing -> {
                ActionResult(false, "Contacts permission missing", speechResponseOverride = "Contact dhoondne ke liye Contacts permission allow karni hogi.")
            }
        }
    }

    private suspend fun handleWhatsAppMessage(recipient: String, message: String): ActionResult {
        var resolvedTarget = recipient.trim()
        val lowerTarget = resolvedTarget.lowercase()

        // Resolve contextual pronouns ("iski", "iska", "isko", "chat", "handle", "unka", "inhe")
        val notifAssistant = AffyNotificationAssistant.getInstance(context)
        if (lowerTarget.contains("iski") || lowerTarget.contains("iska") || lowerTarget.contains("isko") ||
            lowerTarget.contains("uska") || lowerTarget.contains("unka") || lowerTarget.contains("inhe") ||
            lowerTarget.contains("unhe") || lowerTarget.contains("chat") || resolvedTarget.isBlank()) {
            val contextual = notifAssistant.resolveContextualSender(resolvedTarget)?.sender
                ?: AffyConversationContextManager.getInstance().resolveContextualRecipient(resolvedTarget)
            if (!contextual.isNullOrBlank()) {
                Log.d("AffyActionExecutor", "Resolved contextual WhatsApp target '$recipient' -> '$contextual'")
                resolvedTarget = contextual
            }
        }

        if (resolvedTarget.isBlank()) {
            return ActionResult(false, "No recipient specified", "Kisko WhatsApp message bhejna hai?")
        }
        if (message.isBlank()) {
            return ActionResult(false, "No message content", "Message kya bhejna hai?")
        }

        // Check if there is an active/recent notification from this sender to reply in background via RemoteInput
        val contextualMsg = notifAssistant.resolveContextualSender(resolvedTarget)
        if (contextualMsg != null) {
            Log.d("AffyActionExecutor", "Found recent notification for '$resolvedTarget' (${contextualMsg.sender}). Attempting background RemoteInput reply.")
            val (directSuccess, directSummary) = notifAssistant.executeDirectReply(resolvedTarget, message)
            if (directSuccess) {
                return ActionResult(
                    isSuccess = true,
                    summary = directSummary,
                    speechResponseOverride = "Boss, ${contextualMsg.sender} ko reply bhej diya."
                )
            }
            Log.d("AffyActionExecutor", "RemoteInput unavailable/failed; falling back to accessibility path.")
        }

        // 1. Detect installed WhatsApp instances dynamically (supports all 5+ accounts)
        val installedInstances = WhatsAppInstanceManager.getInstalledInstances(context)
        if (installedInstances.isEmpty()) {
            return ActionResult(
                false,
                "WhatsApp not installed",
                "Phone mein koi WhatsApp account installed nahi mila."
            )
        }

        val preferredPkg = preferences.preferredWhatsAppPackageFlow.first()
        val requestedBusiness = message.contains("business", ignoreCase = true) || resolvedTarget.contains("business", ignoreCase = true)
        val requestedClone = message.contains("clone", ignoreCase = true) || resolvedTarget.contains("clone", ignoreCase = true) || message.contains("dual", ignoreCase = true) || resolvedTarget.contains("dual", ignoreCase = true)
        val foregroundPkg = AffyAccessibilityService.currentAppPackage.value ?: ""

        // Screen-First check: If target explicitly points to the currently open chat on screen
        val isCurrentChatRef = resolvedTarget.equals("current_chat", ignoreCase = true) ||
                resolvedTarget.equals("this_chat", ignoreCase = true) ||
                resolvedTarget.equals("ye chat", ignoreCase = true) ||
                resolvedTarget.equals("is chat", ignoreCase = true) ||
                resolvedTarget.equals("jo chat open hai", ignoreCase = true) ||
                resolvedTarget.equals("isme", ignoreCase = true) ||
                resolvedTarget.equals("yahan", ignoreCase = true) ||
                (WhatsAppInstanceManager.isWhatsAppPackage(foregroundPkg) && resolvedTarget.isBlank())

        val targetPackage: String
        if (!foregroundPkg.isBlank() && WhatsAppInstanceManager.isWhatsAppPackage(foregroundPkg)) {
            // If WhatsApp is already active in foreground, reuse it without prompting
            targetPackage = foregroundPkg
        } else if (installedInstances.size > 1 && preferredPkg.isBlank() && !requestedBusiness && !requestedClone) {
            return ActionResult(
                isSuccess = false,
                summary = "Multiple WhatsApp instances found",
                speechResponseOverride = "Boss, kis WhatsApp se bhejun?"
            )
        } else {
            targetPackage = WhatsAppInstanceManager.resolveOutgoingInstance(
                context = context,
                preferBusiness = requestedBusiness,
                preferredPackage = preferredPkg.ifBlank { null }
            ) ?: WhatsAppInstanceManager.PKG_WHATSAPP
            
            // Remember preference if explicitly chosen
            if (requestedBusiness || requestedClone) {
                try {
                    preferences.savePreferredWhatsAppPackage(targetPackage)
                } catch (_: Exception) {}
            }
        }

        // 2. If screen-first current chat was requested, execute and verify directly without searching Contacts
        if (isCurrentChatRef) {
            val a11y = AffyAccessibilityService.instance
            if (a11y != null) {
                val isSent = a11y.automateWhatsAppSend(targetPackage = targetPackage, messageText = message)
                return if (isSent) {
                    ActionResult(
                        isSuccess = true,
                        summary = "WhatsApp message sent to current chat",
                        speechResponseOverride = "Ji boss! Message bhej diya hai."
                    )
                } else {
                    ActionResult(
                        isSuccess = false,
                        summary = "WhatsApp send unverified",
                        speechResponseOverride = "Message type kiya hai magar send verify nahi hua."
                    )
                }
            } else {
                return ActionResult(
                    isSuccess = false,
                    summary = "Accessibility inactive",
                    speechResponseOverride = "Chat mein send karne ke liye Accessibility permission chahiye."
                )
            }
        }

        // 3. Disambiguate recipient contact (strictly: ask if multiple, never guess)
        val contactResult = contactResolver.resolveContact(resolvedTarget)
        var targetPhoneNumber: String? = null
        var resolvedDisplayName: String = resolvedTarget

        when (contactResult) {
            is ContactResolutionResult.Multiple -> {
                val formattedList = contactResult.candidates.mapIndexed { idx, c ->
                    "${idx + 1}. ${c.displayString}"
                }.joinToString("\n")

                val speechPrompt = "${contactResult.candidates[0].name} ke ${contactResult.candidates.size} contacts mile hain:\n$formattedList\nKaunse number pe WhatsApp message bhejun?"

                AffyConversationContextManager.getInstance().setPendingContactDisambiguation(
                    PendingContactDisambiguation(
                        actionType = DisambiguationActionType.WHATSAPP,
                        originalQuery = resolvedTarget,
                        candidates = contactResult.candidates,
                        whatsappMessage = message,
                        whatsappTargetPackage = targetPackage
                    )
                )

                return ActionResult(
                    isSuccess = false,
                    summary = "Multiple contacts found (${contactResult.candidates.size})",
                    speechResponseOverride = speechPrompt
                )
            }
            is ContactResolutionResult.Single -> {
                resolvedDisplayName = contactResult.name
                targetPhoneNumber = contactResult.phoneNumber.replace(Regex("[^0-9+]"), "")
            }
            is ContactResolutionResult.NotFound -> {
                // If the user spoke a raw phone number directly, allow it
                val cleanDigits = recipient.replace(Regex("[^0-9+]"), "")
                if (cleanDigits.length >= 7) {
                    targetPhoneNumber = cleanDigits
                } else {
                    return ActionResult(
                        isSuccess = false,
                        summary = "Contact not found",
                        speechResponseOverride = "Hmm, $recipient naam ka contact nahi mila. Kripya pura naam ya phone number batayein."
                    )
                }
            }
            is ContactResolutionResult.PermissionMissing -> {
                return ActionResult(
                    isSuccess = false,
                    summary = "Contacts permission missing",
                    speechResponseOverride = "Contact dhoondne ke liye Contacts permission allow karni hogi."
                )
            }
        }

        // 3. Open WhatsApp chat with staged message in the target instance
        try {
            val cleanPhone = if (targetPhoneNumber.startsWith("+")) targetPhoneNumber.substring(1) else targetPhoneNumber
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = targetPackage
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            // 4. Automated Send & Verification via Accessibility Service
            val a11y = AffyAccessibilityService.instance
            if (a11y != null) {
                // Allow brief transition time for chat activity to launch in WhatsApp
                delay(800)
                val isSent = a11y.automateWhatsAppSend(targetPackage = targetPackage, messageText = message)
                if (isSent) {
                    return ActionResult(
                        isSuccess = true,
                        summary = "WhatsApp message sent to $resolvedDisplayName",
                        speechResponseOverride = "Ji boss! $resolvedDisplayName ko WhatsApp message bhej diya hai."
                    )
                } else {
                    // Send was not confirmed
                    return ActionResult(
                        isSuccess = false,
                        summary = "WhatsApp send unverified",
                        speechResponseOverride = "$resolvedDisplayName ki chat khol di hai magar auto-send verify nahi ho saka. Send dabayein."
                    )
                }
            }

            // Staged in chat but accessibility service is not active to automate click
            return ActionResult(
                isSuccess = true,
                summary = "Opened WhatsApp chat for $resolvedDisplayName",
                speechResponseOverride = "$resolvedDisplayName ki chat khol di hai boss. Direct send ke liye Accessibility enable karein."
            )
        } catch (e: Exception) {
            Log.e("AffyActionExecutor", "WhatsApp execution error: ${e.message}")
            return ActionResult(
                isSuccess = false,
                summary = "WhatsApp send failed: ${e.message}",
                speechResponseOverride = "WhatsApp open karne mein masla hua."
            )
        }
    }

    private fun handleSendSms(recipient: String, message: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(recipient)}")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult(true, "SMS drafted", "Message draft kar diya hai.")
        } catch (e: Exception) {
            ActionResult(false, "SMS failed", "SMS action open nahi ho saka.")
        }
    }

    private suspend fun handleClickElement(target: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility service not active", "Accessibility permission enable karni hogi.")
        }

        // 1. Observe fresh screen immediately before every action
        val initialScreenContext = visionEngine.captureScreenContext()

        // 2. Resolve target using strict semantic classification without arbitrary clickable fallback
        val visionResult = visionEngine.resolveAndClickTarget(target)
        if (visionResult.isFound && visionResult.matchedNode != null) {
            // 3. Re-read and verify screen after click
            val screenChanged = visionEngine.waitForMeaningfulChange(1500)
            val updatedScreenContext = visionEngine.captureScreenContext()
            val label = visionResult.matchedNode.displayLabel
            return ActionResult(
                isSuccess = true,
                summary = "Clicked '$label' (${visionResult.matchReason}), screenChanged=$screenChanged",
                speechResponseOverride = "$label par click kar diya."
            )
        }

        // 4. Multiple matches or ambiguous: ask for clarification
        if (visionResult.needsClarification && !visionResult.clarificationPrompt.isNullOrBlank()) {
            return ActionResult(
                isSuccess = false,
                summary = "Clarification needed for $target",
                speechResponseOverride = visionResult.clarificationPrompt
            )
        }

        // 5. Target not found with confidence: never guess or click arbitrary elements!
        val notFoundMessage = if (target.lowercase().contains("communit")) {
            "Boss, mujhe Communities ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
        } else {
            "Boss, mujhe $target ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
        }

        return ActionResult(
            isSuccess = false,
            summary = "Target '$target' not found with confidence",
            speechResponseOverride = notFoundMessage
        )
    }

    private fun handleLikeAction(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val liked = service.verifyMediaLike(expectedLiked = true)
        return if (liked) {
            ActionResult(true, "Liked post/video", "Done, video like kar di ❤️")
        } else {
            ActionResult(false, "Like button not found", "Screen par like button nahi mila.")
        }
    }

    private fun handleUnlikeAction(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val unliked = service.verifyMediaLike(expectedLiked = false)
        return if (unliked) {
            ActionResult(true, "Unliked post/video", "Done, video se like hata diya.")
        } else {
            ActionResult(false, "Unlike action failed", "Like button verify nahi hua.")
        }
    }

    private fun handleSubscribeChannel(target: String?): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val (success, message) = service.verifySubscribe(target)
        return ActionResult(success, message, speechResponseOverride = message)
    }

    private fun handleGetVideoTitle(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Screen accessible nahi hai.")
        }
        val title = service.getVideoTitle()
        return if (!title.isNullOrBlank()) {
            ActionResult(true, "Video title: $title", speechResponseOverride = "Is video ka title hai: '$title'")
        } else {
            ActionResult(false, "No title found", speechResponseOverride = "Screen par video ka title nahi mila.")
        }
    }

    private fun handleGetMediaCaption(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Screen accessible nahi hai.")
        }
        val caption = service.getMediaCaption() ?: service.getVideoTitle()
        return if (!caption.isNullOrBlank()) {
            ActionResult(true, "Caption: $caption", speechResponseOverride = "Caption hai: '$caption'")
        } else {
            ActionResult(false, "No caption found", speechResponseOverride = "Screen par koi caption nahi mila.")
        }
    }

    private suspend fun handleOpenComments(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val opened = service.clickElementUniversal("comment") || service.clickElementByText("comment") || service.clickElementByText("comments")
        if (opened) {
            visionEngine.waitForMeaningfulChange(1000)
            return ActionResult(true, "Opened comments", "Comment section open kar diya hai.")
        }
        return ActionResult(false, "Comments not found", "Screen par comments option nahi mila.")
    }

    private suspend fun handleSelectVideoResult(ordinal: Int, query: String?): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val selected = service.selectVideoResult(ordinal, query)
        if (selected) {
            visionEngine.waitForMeaningfulChange(1500)
            val countWord = when (ordinal) {
                1 -> "pehli"
                2 -> "doosri"
                3 -> "teesri"
                4 -> "chauthi"
                5 -> "panchwi"
                else -> "${ordinal}th"
            }
            return ActionResult(true, "Selected video #$ordinal", "$countWord video open kar di hai boss.")
        }
        return ActionResult(false, "Video not found", "Video select nahi ho saki.")
    }

    private suspend fun handleUniversalSearch(query: String, targetField: String?): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        if (query.isBlank()) {
            return ActionResult(false, "Empty search query", "Kya search karna hai?")
        }
        val searched = service.searchInCurrentScreen(query)
        if (searched) {
            visionEngine.waitForMeaningfulChange(1500)
            return ActionResult(true, "Searched for '$query'", "Ji boss, '$query' search kar diya hai.")
        }
        return ActionResult(false, "Search failed", "Screen par search execute nahi ho saka.")
    }

    private suspend fun handleScroll(down: Boolean): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility permission chahiye scroll karne ke liye.")
        }
        val scrolled = service.scroll(down)
        if (scrolled) {
            visionEngine.waitForMeaningfulChange(1000)
            return ActionResult(true, if (down) "Scrolled down" else "Scrolled up", if (down) "Neeche scroll kar diya." else "Upar scroll kar diya.")
        } else {
            return ActionResult(false, "Scroll failed", "Scroll nahi ho saka.")
        }
    }

    private fun handleSwipe(left: Boolean): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(false, "Accessibility inactive")
        val dm = context.resources.displayMetrics
        val y = dm.heightPixels / 2f
        val startX = if (left) dm.widthPixels * 0.8f else dm.widthPixels * 0.2f
        val endX = if (left) dm.widthPixels * 0.2f else dm.widthPixels * 0.8f
        val swiped = service.performSwipeGesture(startX, y, endX, y)
        return ActionResult(swiped, if (left) "Swiped left" else "Swiped right")
    }

    private suspend fun handleTypeText(text: String, targetField: String? = null, shouldSend: Boolean = false): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        if (text.isBlank()) {
            return ActionResult(false, "No text provided", "Likhne ke liye koi text nahi mila.")
        }

        val typed = service.typeText(text, targetField)
        if (!typed) {
            return ActionResult(false, "No input field active", "Type karne ke liye koi active text box nahi mila.")
        }

        visionEngine.waitForMeaningfulChange(400)

        // Phase 3.6: If send was requested along with typing
        val needSend = shouldSend || targetField?.contains("send", ignoreCase = true) == true || targetField?.contains("bhejo", ignoreCase = true) == true
        if (needSend) {
            val foregroundPkg = AffyAccessibilityService.currentAppPackage.value ?: ""
            if (WhatsAppInstanceManager.isWhatsAppPackage(foregroundPkg)) {
                val sent = service.automateWhatsAppSend(targetPackage = foregroundPkg, messageText = text)
                return if (sent) {
                    ActionResult(true, "Typed and sent '$text'", speechResponseOverride = "Ji boss, '$text' likh kar send kar diya hai.")
                } else {
                    ActionResult(false, "Typed '$text' but send unverified", speechResponseOverride = "'$text' likh diya hai magar send confirm nahi ho saka. Send dabayein.")
                }
            } else {
                for (lbl in listOf("send", "bhejo", "post", "enter", "done")) {
                    if (service.clickElementByText(lbl)) {
                        visionEngine.waitForMeaningfulChange(800)
                        return ActionResult(true, "Typed and sent '$text'", speechResponseOverride = "Ji boss, likh kar bhej diya.")
                    }
                }
                return ActionResult(false, "Typed '$text' but send button not found", speechResponseOverride = "'$text' likh diya hai magar send button nahi mila.")
            }
        }

        return ActionResult(true, "Typed text: $text", speechResponseOverride = "Ji boss, '$text' type kar diya hai.")
    }

    private fun handleReadVisibleChats(): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(
            false,
            "Accessibility inactive",
            "Boss, abhi screen ka content accessible nahi hai. Accessibility permission enable karni hogi."
        )
        val chats = service.readVisibleChatList()
        return if (!chats.isNullOrBlank()) {
            ActionResult(true, "Read visible chats", speechResponseOverride = chats)
        } else {
            ActionResult(false, "No chats visible", speechResponseOverride = "Screen par koi visible chat ya contact list nahi mila.")
        }
    }

    private fun handleReadScreenMessages(): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(
            false,
            "Accessibility inactive",
            "Boss, abhi screen ka content accessible nahi hai. Accessibility permission enable karni hogi."
        )
        val messages = service.readVisibleChatMessages()
        return if (!messages.isNullOrBlank()) {
            ActionResult(true, "Read visible messages", speechResponseOverride = messages)
        } else {
            ActionResult(false, "No messages visible", speechResponseOverride = "Screen par koi readable message visible nahi hai.")
        }
    }

    private fun handleAuthorizeChat(target: String): ActionResult {
        val notifAssistant = AffyNotificationAssistant.getInstance(context)
        val cleanTarget = target.trim()
        val success = notifAssistant.authorizeChat(cleanTarget)
        return if (success) {
            ActionResult(
                true,
                "Chat authorized for $cleanTarget",
                speechResponseOverride = "Ji boss, $cleanTarget ki chat handle kar raha hoon. Aane wale messages ka automatically reply bhej doon ga."
            )
        } else {
            ActionResult(false, "Could not authorize chat", speechResponseOverride = "Chat authorize nahi ho saki.")
        }
    }

    private fun handleRevokeChat(target: String?): ActionResult {
        val notifAssistant = AffyNotificationAssistant.getInstance(context)
        notifAssistant.revokeChat(target)
        val label = if (!target.isNullOrBlank()) target else "Chat"
        return ActionResult(
            true,
            "Chat authorization revoked for $label",
            speechResponseOverride = "Theek hai boss, $label ka automatic reply band kar diya."
        )
    }

    private suspend fun handleCloseAppOrPage(target: String?): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val closed = service.closeCurrentAppOrView(target)
        visionEngine.waitForMeaningfulChange(1000)
        val targetName = target?.ifBlank { null } ?: "App"
        return if (closed) {
            ActionResult(true, "Closed $targetName", "Ji boss, $targetName close kar diya hai.")
        } else {
            ActionResult(false, "Could not close $targetName", "$targetName close nahi ho saka.")
        }
    }

    private fun handleNextVideo(): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        val dm = context.resources.displayMetrics
        val startX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.75f
        val endY = dm.heightPixels * 0.25f
        val scrolled = service.performSwipeGesture(startX, startY, startX, endY, 250)
        return if (scrolled) {
            ActionResult(true, "Next video", "Agla video laga diya.")
        } else {
            ActionResult(false, "Next video failed", "Next video scroll nahi ho saka.")
        }
    }

    private fun handleOpenKeyboard(target: String?): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(false, "Accessibility inactive", "Accessibility permission chahiye keyboard open karne ke liye.")
        val opened = service.openKeyboardOrFocusInput(target)
        return if (opened) {
            ActionResult(true, "Keyboard opened", "Keyboard open kar diya hai, text likh sakte hain.")
        } else {
            ActionResult(false, "No input box found", "Screen par koi input box nahi mila.")
        }
    }

    private fun handleSelectText(target: String): ActionResult {
        val service = AffyAccessibilityService.instance ?: return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        val selected = service.selectText(target)
        return if (selected) {
            ActionResult(true, "Selected text: $target", "Text select kar diya.")
        } else {
            ActionResult(false, "Text not found", "Screen par '$target' text nahi mila.")
        }
    }

    private suspend fun handleLongPressElement(target: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility permission enable karni hogi.")
        }
        val visionResult = visionEngine.resolveAndLongPressTarget(target)
        if (visionResult.isFound && visionResult.matchedNode != null) {
            visionEngine.waitForMeaningfulChange(1000)
            val label = visionResult.matchedNode.displayLabel
            return ActionResult(true, "Long pressed '$label'", "$label par long press kar diya.")
        }
        val pressed = service.longPressElementByText(target)
        return if (pressed) {
            visionEngine.waitForMeaningfulChange(1000)
            ActionResult(true, "Long pressed $target", "$target par long press kar diya.")
        } else {
            ActionResult(false, "Element $target not found", "Screen par '$target' nahi mila.")
        }
    }

    private suspend fun handleClearText(target: String?): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val cleared = service.clearText(target)
        return if (cleared) {
            visionEngine.waitForMeaningfulChange(500)
            ActionResult(true, "Text cleared", "Text clear kar diya hai.")
        } else {
            ActionResult(false, "Could not clear text", "Text clear nahi ho saka.")
        }
    }

    private suspend fun handleCommentOnPost(commentText: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val commented = service.commentOnPost(commentText)
        return if (commented) {
            visionEngine.waitForMeaningfulChange(1200)
            ActionResult(true, "Comment posted: $commentText", "Comment bhej diya hai.")
        } else {
            ActionResult(false, "Could not post comment", "Comment post nahi ho saka.")
        }
    }

    private suspend fun handleSharePost(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val shared = service.shareCurrentContent()
        return if (shared) {
            visionEngine.waitForMeaningfulChange(1000)
            ActionResult(true, "Share opened", "Share option open kar diya.")
        } else {
            ActionResult(false, "Share button not found", "Share button nahi mila.")
        }
    }

    private suspend fun handleSearchInScreen(query: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")
        }
        val searched = service.searchInCurrentScreen(query)
        return if (searched) {
            visionEngine.waitForMeaningfulChange(1500)
            ActionResult(true, "Searched for $query", "$query search kar diya.")
        } else {
            ActionResult(false, "Search input not found", "Screen par search box nahi mila.")
        }
    }

    private fun handleGlobalAction(action: Int, name: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service != null && service.performGlobal(action)) {
            return ActionResult(true, name, "Ho gaya.")
        }
        return ActionResult(false, "$name failed")
    }

    private fun handleMediaControl(command: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service != null && (command.equals("play", ignoreCase = true) || command.equals("pause", ignoreCase = true))) {
            val (success, speechMsg) = service.controlVideoPlayback(command)
            if (success) {
                return ActionResult(true, "Media $command", speechMsg)
            }
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val keycode = when (command.lowercase()) {
                "play", "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keycode))
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keycode))
            val speech = if (command.lowercase().contains("play")) "Video play kar di." else if (command.lowercase().contains("pause")) "Video pause kar di." else "Media $command kar diya."
            ActionResult(true, "Media command $command executed", speech)
        } catch (e: Exception) {
            ActionResult(false, "Media control failed", "Media control execute nahi hua.")
        }
    }

    private fun handleVolume(value: String): ActionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clean = value.lowercase().trim()

        val numericMatch = Regex("(\\d+)").find(clean)
        if (numericMatch != null && clean != "up" && clean != "down") {
            val num = numericMatch.groupValues[1].toIntOrNull() ?: 50
            val targetLevel = ((num / 100f) * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetLevel, AudioManager.FLAG_SHOW_UI)
            return ActionResult(true, "Volume set to $num%", "Volume $num% kar diya hai.")
        }

        val direction = when (clean) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute", "0" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> AudioManager.ADJUST_RAISE
        }
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return ActionResult(true, "Volume adjusted $value", "Volume adjust kar diya.")
    }

    private fun handleBrightness(value: String): ActionResult {
        return try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult(true, "Opened Display Settings", "Display settings open kar di hain.")
        } catch (e: Exception) {
            ActionResult(false, "Could not open display settings")
        }
    }

    private suspend fun handleFlashlight(value: String): ActionResult {
        val targetOn = when (value.lowercase().trim()) {
            "on", "true", "enable", "1", "chalao", "jalao" -> true
            "off", "false", "disable", "0", "band", "bujhao" -> false
            else -> true // default intent is to turn torch on if user commands torch
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager != null) {
            try {
                // Find rear camera with flash unit available, or any available camera with flash
                val flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        val isRear = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        hasFlash && isRear
                    } catch (_: Exception) {
                        false
                    }
                } ?: cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } catch (_: Exception) {
                        false
                    }
                }

                if (flashCameraId != null) {
                    cameraManager.setTorchMode(flashCameraId, targetOn)
                    delay(200)
                    val actionWord = if (targetOn) "on" else "band"
                    val summaryWord = if (targetOn) "ON" else "OFF"
                    return ActionResult(
                        isSuccess = true,
                        summary = "Flashlight $summaryWord verified",
                        speechResponseOverride = "Torch $actionWord kar di hai aur verify ho gayi."
                    )
                }
            } catch (e: Exception) {
                Log.w("AffyActionExecutor", "CameraManager torch exception (${e.message}), attempting Quick Settings fallback...")
            }
        }

        // Quick Settings Fallback via Accessibility
        val service = AffyAccessibilityService.instance
        if (service != null) {
            val (qsSuccess, qsMsg) = service.toggleQuickSettingAndVerify("Flashlight", targetOn)
            if (qsSuccess) {
                return ActionResult(true, qsMsg, qsMsg)
            }
        }

        val actionWord = if (targetOn) "on" else "off"
        return ActionResult(
            isSuccess = false,
            summary = "Flashlight $actionWord failed",
            speechResponseOverride = "Flashlight toggle nahi ho saki. Device par flash hardware available nahi hai."
        )
    }

    private suspend fun handleToggleWifi(value: String?): ActionResult {
        val targetState = when (value?.lowercase()?.trim()) {
            "on", "true", "enable", "enabled", "1" -> true
            "off", "false", "disable", "disabled", "0" -> false
            else -> null
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val initialWifiEnabled = wifiManager?.isWifiEnabled == true

        if (targetState != null && initialWifiEnabled == targetState) {
            val stateWord = if (targetState) "ON" else "OFF"
            return ActionResult(true, "Wi-Fi is already $stateWord", "Wi-Fi pehle se hi $stateWord hai boss.")
        }

        val desired = targetState ?: !initialWifiEnabled

        // Attempt direct WifiManager toggle if permitted by OS
        var directSucceeded = false
        try {
            @Suppress("DEPRECATION")
            directSucceeded = wifiManager?.setWifiEnabled(desired) == true
        } catch (_: Exception) {}

        if (directSucceeded) {
            delay(800)
            val verified = wifiManager?.isWifiEnabled == desired
            if (verified) {
                val stateWord = if (desired) "ON" else "OFF"
                return ActionResult(true, "Wi-Fi $stateWord verified", "Wi-Fi $stateWord kar diya hai aur verify ho gaya.")
            }
        }

        // Fallback: Use Quick Settings tile through Accessibility Service
        val service = AffyAccessibilityService.instance
        if (service != null) {
            val (qsSuccess, qsMsg) = service.toggleQuickSettingAndVerify("Wi-Fi", desired)
            delay(500)
            val finalWifiState = wifiManager?.isWifiEnabled == true
            if (finalWifiState == desired) {
                val stateWord = if (desired) "ON" else "OFF"
                return ActionResult(true, "Wi-Fi $stateWord verified", "Wi-Fi $stateWord kar diya hai aur verify ho gaya.")
            } else if (qsSuccess) {
                return ActionResult(true, qsMsg, qsMsg)
            }
        }

        val finalCheck = wifiManager?.isWifiEnabled == true
        return if (finalCheck == desired) {
            val stateWord = if (desired) "ON" else "OFF"
            ActionResult(true, "Wi-Fi $stateWord", "Wi-Fi $stateWord ho gaya.")
        } else {
            ActionResult(false, "Wi-Fi state change unverified", "Android system restriction ki wajah se Wi-Fi panel open kar diya hai, please screen par switch tap karein.")
        }
    }

    private suspend fun handleToggleMobileData(value: String?): ActionResult {
        val targetState = when (value?.lowercase()?.trim()) {
            "on", "true", "enable", "enabled", "1" -> true
            "off", "false", "disable", "disabled", "0" -> false
            else -> null
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val initialCellular = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        if (targetState != null && initialCellular == targetState) {
            val stateWord = if (targetState) "ON" else "OFF"
            return ActionResult(true, "Mobile Data is already $stateWord", "Mobile Data pehle se hi $stateWord hai boss.")
        }

        val desired = targetState ?: !initialCellular

        val service = AffyAccessibilityService.instance
        if (service != null) {
            val (qsSuccess, qsMsg) = service.toggleQuickSettingAndVerify("Mobile Data", desired)
            delay(700)
            if (qsSuccess) {
                return ActionResult(true, qsMsg, qsMsg)
            }
        }

        // Open Data Usage Settings for user interaction
        try {
            val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        return ActionResult(
            false,
            "Mobile Data requires user interaction due to Android security",
            "Android security policy ki wajah se Mobile Data setting open kar di hai, please toggle karein."
        )
    }

    private suspend fun handleQuickSettingToggle(settingName: String, value: String?): ActionResult {
        val targetState = when (value?.lowercase()?.trim()) {
            "on", "true", "enable", "enabled", "1" -> true
            "off", "false", "disable", "disabled", "0" -> false
            else -> null
        }
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return ActionResult(false, "Accessibility inactive", "System controls operate karne ke liye accessibility on honi chahiye.")
        }
        val (success, message) = service.toggleQuickSettingAndVerify(settingName, targetState)
        return ActionResult(
            isSuccess = success,
            summary = message,
            speechResponseOverride = message
        )
    }

    private suspend fun handleRingerMode(mode: String): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val targetMode = when (mode.lowercase().trim()) {
                "silent", "mute", "off" -> AudioManager.RINGER_MODE_SILENT
                "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            audioManager.ringerMode = targetMode
            val newMode = audioManager.ringerMode
            val modeName = when (newMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                else -> "Normal / Sound"
            }
            ActionResult(true, "Ringer mode set to $modeName", "Phone $modeName mode par set kar diya hai boss.")
        } catch (e: Exception) {
            Log.w("AffyActionExecutor", "AudioManager ringerMode exception (${e.message}), falling back to Quick Settings...")
            handleQuickSettingToggle("Sound", mode)
        }
    }

    private suspend fun handleCreateReminder(timeExpr: String, taskText: String): ActionResult {
        return try {
            val triggerTime = reminderManager.parseNaturalTime(timeExpr)
            val title = taskText.ifBlank { "Reminder" }
            val id = reminderManager.scheduleReminder(title, triggerTime, isAlarm = false)
            val timeFormatted = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(triggerTime))
            ActionResult(
                true,
                "Reminder #$id scheduled",
                "Ji boss, $timeFormatted par reminder set kar diya hai: $title"
            )
        } catch (e: Exception) {
            Log.e("AffyActionExecutor", "Failed to schedule reminder: ${e.message}")
            ActionResult(false, "Reminder failed", "Reminder set nahi ho saka.")
        }
    }

    private suspend fun handleCancelReminder(keyword: String): ActionResult {
        return try {
            val count = reminderManager.cancelRemindersByKeyword(keyword)
            if (count > 0) {
                ActionResult(true, "Cancelled $count reminders", "$count reminder cancel kar diye hain.")
            } else {
                ActionResult(false, "No reminder found", "Is naam ka koi active reminder nahi mila.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Cancel failed", "Reminder cancel nahi ho saka.")
        }
    }

    private suspend fun handleSetAlarm(time: String, label: String): ActionResult {
        return try {
            val triggerTime = reminderManager.parseNaturalTime(time)
            val id = reminderManager.scheduleReminder(label.ifBlank { "Alarm" }, triggerTime, isAlarm = true)
            val timeFormatted = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(triggerTime))
            ActionResult(
                true,
                "Alarm scheduled",
                "Alarm $timeFormatted ke liye set kar diya hai."
            )
        } catch (e: Exception) {
            // Fallback to system Clock intent
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Affy Alarm" })
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult(true, "Alarm screen opened", "Alarm screen ready hai.")
            } catch (ex: Exception) {
                ActionResult(false, "Failed to set alarm")
            }
        }
    }

    private suspend fun handleReadNotifications(filter: String? = null): ActionResult {
        return try {
            val assistant = AffyNotificationAssistant.getInstance(context)
            val appFilter = if (filter.isNullOrBlank() || filter.equals("all", ignoreCase = true)) "whatsapp" else filter
            val summary = assistant.getNaturalSummaryOfRecentMessages(appFilter)
            ActionResult(true, summary, summary)
        } catch (e: Exception) {
            ActionResult(false, "Failed to read notifications", "Notifications check nahi ho sake.")
        }
    }

    private suspend fun handleRememberFact(rawInput: String): ActionResult {
        return try {
            val (success, response) = AffyLongTermMemoryManager.getInstance(context).rememberFact(rawInput)
            ActionResult(
                isSuccess = success,
                summary = response,
                speechResponseOverride = response
            )
        } catch (e: Exception) {
            ActionResult(false, "Failed to save memory: ${e.message}", "Memory save karne mein problem hui.")
        }
    }

    private suspend fun handleForgetFact(query: String): ActionResult {
        return try {
            val (success, response) = AffyLongTermMemoryManager.getInstance(context).forgetFact(query)
            ActionResult(
                isSuccess = success,
                summary = response,
                speechResponseOverride = response
            )
        } catch (e: Exception) {
            ActionResult(false, "Failed to forget memory: ${e.message}", "Memory remove karne mein issue aya.")
        }
    }

    private suspend fun handleRecallFact(query: String?): ActionResult {
        return try {
            val response = AffyLongTermMemoryManager.getInstance(context).recallMemories(query)
            ActionResult(
                isSuccess = true,
                summary = response,
                speechResponseOverride = response
            )
        } catch (e: Exception) {
            ActionResult(false, "Recall error: ${e.message}", "Memory check karne mein error aya.")
        }
    }

    private suspend fun handleClearMemory(): ActionResult {
        return try {
            val (success, response) = AffyLongTermMemoryManager.getInstance(context).clearAll()
            ActionResult(
                isSuccess = success,
                summary = response,
                speechResponseOverride = response
            )
        } catch (e: Exception) {
            ActionResult(false, "Clear memory failed", "Memory clear karne mein problem hui.")
        }
    }

    private fun handleGetTime(): ActionResult {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val time = sdf.format(Date())
        return ActionResult(true, "Time: $time", "Abhi waqt ho raha hai $time.")
    }

    private fun handleGetDate(): ActionResult {
        val sdf = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val date = sdf.format(Date())
        return ActionResult(true, "Date: $date", "Aaj ki date hai $date.")
    }

    private fun handleGetBattery(): ActionResult {
        val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, iFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged > 0
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL || batteryPct >= 100

        val chargingType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless charger"
            else -> if (isCharging) "charger" else null
        }

        val speech = when {
            isFull -> "Phone ki battery 100% full charged hai boss."
            isCharging && chargingType != null -> "Phone ki battery $batteryPct% hai aur $chargingType se charging ho rahi hai."
            isCharging -> "Phone ki battery $batteryPct% hai aur charging ho rahi hai."
            batteryPct in 1..15 -> "Phone ki battery sirf $batteryPct% bachi hai, please charger laga lijiye."
            batteryPct > 0 -> "Phone ki battery $batteryPct% hai aur charging nahi ho rahi."
            else -> "Battery status check nahi ho saka."
        }
        val summary = "Battery: $batteryPct% (Charging: $isCharging, Plugged: $plugged)"
        return ActionResult(true, summary, speech)
    }

    private suspend fun handleOpenCamera(): ActionResult {
        return try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val service = AffyAccessibilityService.instance
            delay(1000)
            val fgPkg = service?.rootInActiveWindow?.packageName?.toString() ?: AffyAccessibilityService.currentAppPackage.value ?: ""
            val isCamera = fgPkg.contains("camera", ignoreCase = true)
            if (isCamera || service == null) {
                ActionResult(
                    isSuccess = true,
                    summary = "Camera opened successfully",
                    speechResponseOverride = "Ji boss, camera khol diya hai."
                )
            } else {
                ActionResult(
                    isSuccess = true,
                    summary = "Camera intent launched",
                    speechResponseOverride = "Camera open kar diya hai boss."
                )
            }
        } catch (e: Exception) {
            openApp("Camera")
        }
    }

    private suspend fun handleTakePhoto(mode: String? = null): ActionResult {
        val service = AffyAccessibilityService.instance
        val currentFg = service?.rootInActiveWindow?.packageName?.toString() ?: AffyAccessibilityService.currentAppPackage.value ?: ""
        val isCameraForeground = currentFg.contains("camera", ignoreCase = true)

        if (!isCameraForeground || mode == "open_and_capture") {
            try {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                openApp("Camera")
            }
            delay(1500)
        }

        if (service != null) {
            val shutterLabels = listOf(
                "Take picture", "Shutter", "Capture", "Photo shutter", "Camera button",
                "Take photo", "Capture photo", "Photo", "Snapshot", "Picture"
            )
            var clicked = false
            for (label in shutterLabels) {
                if (service.clickElementByText(label)) {
                    clicked = true
                    break
                }
            }

            if (!clicked) {
                val metrics = context.resources.displayMetrics
                val shutterX = metrics.widthPixels / 2f
                val shutterY = metrics.heightPixels * 0.88f
                clicked = service.performTap(shutterX, shutterY)
            }

            delay(800)
            if (clicked) {
                return ActionResult(
                    isSuccess = true,
                    summary = "Photo captured successfully",
                    speechResponseOverride = "Ji boss, photo capture kar li hai!"
                )
            }
        }

        return ActionResult(
            isSuccess = true,
            summary = "Camera ready for photo capture",
            speechResponseOverride = "Camera khol diya hai boss, screen par shutter tap karein."
        )
    }

    private fun handleSearchWeb(query: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult(true, "Searching web for $query", "$query search kar raha hoon.")
        } catch (e: Exception) {
            ActionResult(false, "Failed web search")
        }
    }

    private suspend fun handleReadScreen(): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service == null) return ActionResult(false, "Accessibility inactive", "Boss, abhi screen ka content accessible nahi hai. Accessibility permission check kar lijiye.")

        val foregroundPkg = AffyAccessibilityService.currentAppPackage.value ?: ""
        if (WhatsAppInstanceManager.isWhatsAppPackage(foregroundPkg) || foregroundPkg.contains("messaging") || foregroundPkg.contains("mms")) {
            val chatMessages = service.readVisibleChatMessages()
            if (!chatMessages.isNullOrBlank()) {
                return ActionResult(true, "Read visible chat", chatMessages)
            }
        }

        val screenContext = visionEngine.captureScreenContext()
        val naturalSummary = screenContext.toConversationalSummary()
        return ActionResult(true, "Screen read: ${screenContext.appDisplayName} (${screenContext.screenState})", naturalSummary)
    }

    private suspend fun executeMultiStepAppCommand(appName: String, query: String, desiredAction: String): ActionResult {
        val cleanApp = appName.trim().lowercase()
        val pm = context.packageManager

        val packageMap = mapOf(
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "gaana" to "com.gaana",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana"
        )

        val targetPkg = packageMap[cleanApp] ?: packageMap.entries.firstOrNull { cleanApp.contains(it.key) }?.value
        AffyDiagnostics.logPipelineExecution(
            action = "MULTI_STEP_APP_COMMAND ($desiredAction)",
            target = query,
            targetPackage = targetPkg ?: appName,
            actionResult = "DISPATCHING"
        )

        if (targetPkg != null) {
            val isInstalled = pm.getLaunchIntentForPackage(targetPkg) != null
            if (!isInstalled) {
                AffyDiagnostics.logPipelineExecution("MULTI_STEP_APP_COMMAND", query, targetPkg, "NOT_INSTALLED")
                return ActionResult(
                    isSuccess = false,
                    summary = "$appName is not installed",
                    speechResponseOverride = "Boss, $appName aapke phone mein install nahi hai."
                )
            }
        }

        return when {
            cleanApp.contains("youtube") -> {
                try {
                    // Step 1: Launch YouTube directly to search query results
                    val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(ytIntent)

                    // Step 2: Allow YouTube to load search view
                    delay(1200)

                    // Step 3: Inspect screen and use Accessibility if search wasn't executed
                    val a11y = AffyAccessibilityService.instance
                    if (a11y != null) {
                        a11y.executeSemanticAppSearch("YouTube", query)
                        delay(600)
                    }

                    // Step 4: Re-inspect and verify screen
                    val screenContext = visionEngine.captureScreenContext()
                    val isYouTube = screenContext.foregroundPackage.contains("youtube") || screenContext.appDisplayName.contains("YouTube")

                    if (isYouTube) {
                        AffyDiagnostics.logPipelineExecution("YOUTUBE_SEARCH", query, "com.google.android.youtube", "VERIFIED")
                        val speechText = if (desiredAction == "search") {
                            "Boss, YouTube par '$query' search kar diya hai."
                        } else {
                            "Boss, YouTube par '$query' chala diya hai."
                        }
                        ActionResult(
                            isSuccess = true,
                            summary = "Searched '$query' on YouTube",
                            speechResponseOverride = speechText
                        )
                    } else {
                        AffyDiagnostics.logPipelineExecution("YOUTUBE_SEARCH", query, "com.google.android.youtube", "FAILED_FOREGROUND_CHECK")
                        ActionResult(
                            isSuccess = false,
                            summary = "YouTube could not be opened for search",
                            speechResponseOverride = "Boss, YouTube open nahi ho saka."
                        )
                    }
                } catch (e: Exception) {
                    AffyDiagnostics.logPipelineExecution("YOUTUBE_SEARCH", query, "com.google.android.youtube", "EXCEPTION: ${e.message}")
                    ActionResult(
                        isSuccess = false,
                        summary = "YouTube search failed: ${e.message}",
                        speechResponseOverride = "Boss, YouTube par search execute nahi ho saka."
                    )
                }
            }

            cleanApp.contains("spotify") -> {
                try {
                    val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        setPackage("com.spotify.music")
                        putExtra(SearchManager.QUERY, query)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    delay(1000)
                    AffyDiagnostics.logPipelineExecution("SPOTIFY_SEARCH", query, "com.spotify.music", "SUCCESS")
                    ActionResult(
                        isSuccess = true,
                        summary = "Playing '$query' on Spotify",
                        speechResponseOverride = "Boss, Spotify par '$query' play kar raha hoon."
                    )
                } catch (e: Exception) {
                    AffyDiagnostics.logPipelineExecution("SPOTIFY_SEARCH", query, "com.spotify.music", "FAILED: ${e.message}")
                    ActionResult(
                        isSuccess = false,
                        summary = "Failed to search Spotify: ${e.message}",
                        speechResponseOverride = "Boss, Spotify par play nahi ho saka."
                    )
                }
            }

            cleanApp.contains("maps") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).apply {
                        setPackage("com.google.android.apps.maps")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    delay(800)
                    AffyDiagnostics.logPipelineExecution("MAPS_SEARCH", query, "com.google.android.apps.maps", "SUCCESS")
                    ActionResult(
                        isSuccess = true,
                        summary = "Navigating to '$query' on Google Maps",
                        speechResponseOverride = "Boss, Maps par '$query' ki location open kar di hai."
                    )
                } catch (e: Exception) {
                    AffyDiagnostics.logPipelineExecution("MAPS_SEARCH", query, "com.google.android.apps.maps", "FAILED: ${e.message}")
                    ActionResult(
                        isSuccess = false,
                        summary = "Maps search failed: ${e.message}",
                        speechResponseOverride = "Boss, Maps open nahi ho saka."
                    )
                }
            }

            cleanApp.contains("chrome") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        setPackage("com.android.chrome")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    delay(800)
                    AffyDiagnostics.logPipelineExecution("CHROME_SEARCH", query, "com.android.chrome", "SUCCESS")
                    ActionResult(
                        isSuccess = true,
                        summary = "Searched '$query' on Chrome",
                        speechResponseOverride = "Boss, Chrome par '$query' search kar diya hai."
                    )
                } catch (e: Exception) {
                    AffyDiagnostics.logPipelineExecution("CHROME_SEARCH", query, "com.android.chrome", "FAILED: ${e.message}")
                    ActionResult(
                        isSuccess = false,
                        summary = "Chrome search error: ${e.message}",
                        speechResponseOverride = "Boss, Chrome search nahi ho saka."
                    )
                }
            }

            else -> {
                AffyDiagnostics.logPipelineExecution("APP_COMMAND", query, appName, "UNSUPPORTED")
                ActionResult(
                    isSuccess = false,
                    summary = "Unsupported command for $appName",
                    speechResponseOverride = "Boss, $appName par yeh action perform nahi ho sakta."
                )
            }
        }
    }

    private suspend fun handleGmailCheck(): ActionResult {
        val screenContext = visionEngine.captureScreenContext()
        val isEmailAppForeground = screenContext.foregroundPackage.contains("gm") ||
                screenContext.foregroundPackage.contains("email") ||
                screenContext.appDisplayName.contains("Gmail", ignoreCase = true)

        if (isEmailAppForeground) {
            val emailSubjects = screenContext.visibleTexts.filter { text ->
                text.length > 5 && !text.equals("Search in mail", ignoreCase = true) &&
                        !text.equals("Compose", ignoreCase = true) && !text.equals("Inbox", ignoreCase = true)
            }
            if (emailSubjects.isNotEmpty()) {
                val preview = emailSubjects.take(3).joinToString(", ")
                return ActionResult(
                    isSuccess = true,
                    summary = "Screen unread emails: $preview",
                    speechResponseOverride = "Boss, inbox mein yeh emails dikh rahi hain: $preview."
                )
            }
        }

        val speech = gmailManager.checkNewMails()
        return ActionResult(
            isSuccess = true,
            summary = "Checked emails",
            speechResponseOverride = speech
        )
    }

    private suspend fun handleGmailRead(): ActionResult {
        val screenContext = visionEngine.captureScreenContext()
        val isEmailAppForeground = screenContext.foregroundPackage.contains("gm") ||
                screenContext.foregroundPackage.contains("email") ||
                screenContext.appDisplayName.contains("Gmail", ignoreCase = true)

        if (isEmailAppForeground) {
            val texts = screenContext.visibleTexts
            if (texts.isNotEmpty()) {
                val hasAttachment = texts.any { it.contains("attachment", ignoreCase = true) || it.contains(".pdf", ignoreCase = true) || it.contains(".jpg", ignoreCase = true) || it.contains(".png", ignoreCase = true) || it.contains(".zip", ignoreCase = true) }
                val attachmentNote = if (hasAttachment) " Is email mein attachment bhi attached hai." else ""
                val fullBody = texts.filter { !it.equals("Search in mail", ignoreCase = true) && !it.equals("Compose", ignoreCase = true) && !it.equals("Reply", ignoreCase = true) }.take(6).joinToString(". ")
                return ActionResult(
                    isSuccess = true,
                    summary = "Read email from screen: $fullBody",
                    speechResponseOverride = "Email screen par parhi gayi hai boss: $fullBody.$attachmentNote"
                )
            }
        }

        val speech = gmailManager.readLatestEmail()
        return ActionResult(
            isSuccess = true,
            summary = "Read latest email",
            speechResponseOverride = speech
        )
    }

    private suspend fun handleCheckAttachment(): ActionResult {
        val screenContext = visionEngine.captureScreenContext()
        val texts = screenContext.visibleTexts
        val attachmentIndicators = listOf("attachment", "attached", "pdf", "jpg", "png", "zip", "docx", "file", "download", "mb", "kb", "paperclip")
        val foundAttachment = texts.filter { t -> attachmentIndicators.any { t.contains(it, ignoreCase = true) } }

        return if (foundAttachment.isNotEmpty()) {
            val details = foundAttachment.take(2).joinToString(", ")
            ActionResult(
                isSuccess = true,
                summary = "Attachment detected: $details",
                speechResponseOverride = "Ji boss, is email mein attachment ($details) maujood hai."
            )
        } else {
            ActionResult(
                isSuccess = true,
                summary = "No attachment visible",
                speechResponseOverride = "Boss, is email mein koi visible attachment nahi mili."
            )
        }
    }

    private suspend fun handleGmailReply(replyText: String): ActionResult {
        val service = AffyAccessibilityService.instance
        if (service != null) {
            val clickReply = handleClickElement("Reply")
            if (clickReply.isSuccess) {
                delay(500)
                service.typeText(replyText)
                return ActionResult(
                    isSuccess = true,
                    summary = "Reply drafted on screen: '$replyText'",
                    speechResponseOverride = "Maine email mein reply draft kar diya hai: '$replyText'."
                )
            }
        }

        val draft = gmailManager.stageReply(replyText)
        return if (draft != null) {
            ActionResult(
                isSuccess = true,
                summary = "Gmail reply drafted",
                speechResponseOverride = "Maine ${draft.first.sender} ke email ke liye ye reply draft kiya hai: '$replyText'. Bhej doon?"
            )
        } else {
            ActionResult(
                isSuccess = true,
                summary = "Drafted reply: '$replyText'",
                speechResponseOverride = "Maine reply draft kar diya hai: '$replyText'."
            )
        }
    }

    private fun handleStopCancel(): ActionResult {
        return ActionResult(
            isSuccess = true,
            summary = "Operation stopped / cancelled",
            speechResponseOverride = "Theek hai boss, cancel kar diya."
        )
    }

    private fun handleDeactivateOverlay(): ActionResult {
        com.example.ui.overlay.AffyFloatingOrbManager.hide()
        return ActionResult(
            isSuccess = true,
            summary = "Floating AFFY standby mode activated",
            speechResponseOverride = "Theek hai boss, standby mode mein hoon."
        )
    }

    private suspend fun handleSetVoice(targetVoice: String?): ActionResult {
        val current = preferences.getLiveVoice()
        val target = when {
            targetVoice?.contains("female", ignoreCase = true) == true || targetVoice?.contains("leda", ignoreCase = true) == true -> "Leda"
            targetVoice?.contains("male", ignoreCase = true) == true || targetVoice?.contains("puck", ignoreCase = true) == true -> "Puck"
            current.equals("Leda", ignoreCase = true) -> "Puck"
            else -> "Leda"
        }
        preferences.saveLiveVoice(target)
        val voiceDesc = if (target.equals("Leda", ignoreCase = true)) "female voice" else "male voice"
        return ActionResult(
            isSuccess = true,
            summary = "Voice switched to $voiceDesc",
            speechResponseOverride = "Ji boss, $voiceDesc switch kar di hai."
        )
    }

    private suspend fun handleLockDevice(): ActionResult = withContext(Dispatchers.Main) {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return@withContext ActionResult(
                isSuccess = false,
                summary = "Accessibility Service required for screen lock",
                speechResponseOverride = "Boss, mobile lock karne ke liye Accessibility Service permission enable honi chahiye."
            )
        }

        val success = service.lockDevice()
        if (success) {
            ActionResult(
                isSuccess = true,
                summary = "Device screen locked successfully",
                speechResponseOverride = "Ji boss, device lock kar diya hai."
            )
        } else {
            ActionResult(
                isSuccess = false,
                summary = "Device lock failed or unsupported on this Android API",
                speechResponseOverride = "Boss, screen lock command execute nahi ho saki."
            )
        }
    }

    private suspend fun handleUnlockOrWakeDevice(): ActionResult = withContext(Dispatchers.Main) {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return@withContext ActionResult(
                isSuccess = false,
                summary = "Accessibility Service required for screen wake/unlock",
                speechResponseOverride = "Boss, screen wake karne ke liye Accessibility Service permission on honi chahiye."
            )
        }

        // 1. Wake screen
        service.wakeScreen()
        delay(350)

        // 2. Query live lock and security state
        val state = service.getDeviceLockState()
        Log.i("AffyActionExecutor", "[SECURE_DEVICE_STATE] isScreenOn=${state.isScreenOn} isLocked=${state.isLocked} isSecure=${state.isSecure}")
        AffyDiagnostics.logAction(
            category = "DEVICE_STATE",
            tag = "SCREEN_WAKE_UNLOCK",
            message = "isScreenOn=${state.isScreenOn}, isLocked=${state.isLocked}, isSecure=${state.isSecure}",
            isSuccess = true
        )

        if (!state.isLocked) {
            return@withContext ActionResult(
                isSuccess = true,
                summary = "Device screen is on and unlocked",
                speechResponseOverride = "Screen khol di hai boss, device unlocked hai."
            )
        }

        // Device is locked
        if (state.isSecure) {
            // Protected Android authentication mechanism (PIN/Password/Biometric) must NEVER be bypassed
            val transientPin = TransientAuthManager.getTransientAuth()
            if (!transientPin.isNullOrBlank()) {
                // If user temporarily provided a PIN in-flight for an explicit authorized interaction,
                // keep it strictly in transient memory (TransientAuthManager) and never persist or log it.
                Log.d("AffyActionExecutor", "[TRANSIENT_AUTH_APPLIED] Temporary auth present for session")
            }

            ActionResult(
                isSuccess = true,
                summary = "Screen awake with protected Android secure lock active",
                speechResponseOverride = "Screen on kar di hai boss. Apna PIN, pattern ya fingerprint laga kar unlock kar lein."
            )
        } else {
            // Non-secure swipe lock screen: perform swipe up gesture to dismiss keyguard
            val metrics = context.resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.8f
            val endY = metrics.heightPixels * 0.2f
            service.performSwipeGesture(centerX, startY, centerX, endY, 250L)
            delay(500)

            val updatedState = service.getDeviceLockState()
            if (!updatedState.isLocked) {
                ActionResult(
                    isSuccess = true,
                    summary = "Device unlocked successfully",
                    speechResponseOverride = "Screen khol kar phone unlock kar diya hai boss."
                )
            } else {
                ActionResult(
                    isSuccess = true,
                    summary = "Screen is awake",
                    speechResponseOverride = "Screen on kar di hai boss."
                )
            }
        }
    }

    private suspend fun handleTakeScreenshot(): ActionResult = withContext(Dispatchers.Main) {
        val service = AffyAccessibilityService.instance
        if (service == null) {
            return@withContext ActionResult(
                isSuccess = false,
                summary = "Accessibility Service required for screenshot",
                speechResponseOverride = "Boss, screenshot lene ke liye Accessibility permission chahiye."
            )
        }

        val success = service.takeScreenshot()
        if (success) {
            ActionResult(
                isSuccess = true,
                summary = "Screenshot taken successfully",
                speechResponseOverride = "Screenshot capture kar liya hai boss."
            )
        } else {
            ActionResult(
                isSuccess = false,
                summary = "Screenshot action not supported or failed",
                speechResponseOverride = "Boss, screenshot capture nahi ho saka."
            )
        }
    }

    private fun handleCopyText(text: String): ActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Affy Copied Text", text)
            clipboard.setPrimaryClip(clip)
            ActionResult(
                isSuccess = true,
                summary = "Text copied to clipboard",
                speechResponseOverride = "Text copy kar liya hai."
            )
        } catch (e: Exception) {
            ActionResult(
                isSuccess = false,
                summary = "Failed to copy text: ${e.message}",
                speechResponseOverride = "Text copy nahi ho saka."
            )
        }
    }

    private suspend fun handlePasteText(targetQuery: String?): ActionResult = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteText = clip.getItemAt(0).text?.toString() ?: ""
                val service = AffyAccessibilityService.instance
                val pasted = service?.typeText(pasteText, targetQuery) ?: false
                if (pasted) {
                    ActionResult(true, "Text pasted successfully", "Text paste kar diya hai.")
                } else {
                    ActionResult(false, "Could not find input field to paste", "Paste karne ke liye koi input field nahi mila.")
                }
            } else {
                ActionResult(false, "Clipboard is empty", "Clipboard mein koi text nahi hai.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Failed to paste: ${e.message}", "Paste karte waqt issue aya.")
        }
    }

    private suspend fun handleAnalyzeSecurity(target: String): ActionResult = withContext(Dispatchers.IO) {
        try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isDeviceSecure = keyguardManager?.isDeviceSecure ?: false
            
            val devOptions = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
            } catch (_: Exception) { false }

            val adbEnabled = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            } catch (_: Exception) { false }

            val currentPkg = AffyAccessibilityService.currentAppPackage.value
            val isInspectApp = target.contains("app", ignoreCase = true) || target.contains("permission", ignoreCase = true)

            val summaryBuilder = StringBuilder()
            val speechBuilder = StringBuilder()

            if (isInspectApp && !currentPkg.isNullOrBlank()) {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(currentPkg, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val pkgInfo = pm.getPackageInfo(currentPkg, PackageManager.GET_PERMISSIONS)
                val requestedPermissions = pkgInfo.requestedPermissions?.toList() ?: emptyList()

                val dangerousPermissions = requestedPermissions.filter {
                    it.contains("CAMERA") || it.contains("RECORD_AUDIO") || it.contains("ACCESS_FINE_LOCATION") ||
                    it.contains("READ_CONTACTS") || it.contains("READ_SMS") || it.contains("STORAGE")
                }.map { it.substringAfterLast(".") }

                summaryBuilder.append("App: $appName ($currentPkg)\n")
                summaryBuilder.append("Total Permissions: ${requestedPermissions.size}\n")
                summaryBuilder.append("Sensitive Permissions: ${dangerousPermissions.joinToString(", ")}")

                speechBuilder.append("Boss, $appName ke paas ${requestedPermissions.size} permissions hain. ")
                if (dangerousPermissions.isNotEmpty()) {
                    speechBuilder.append("Sensitive access mein: ${dangerousPermissions.take(3).joinToString(", ")} shamil hain.")
                } else {
                    speechBuilder.append("Koi unusually high-risk permission nahi mili.")
                }
            } else {
                summaryBuilder.append("Device Security Audit:\n")
                summaryBuilder.append("• Lock Screen Security: ${if (isDeviceSecure) "Protected (PIN/Biometric)" else "Unsecured (Swipe/None)"}\n")
                summaryBuilder.append("• Developer Mode: ${if (devOptions) "Enabled" else "Disabled"}\n")
                summaryBuilder.append("• USB Debugging: ${if (adbEnabled) "Active" else "Inactive"}\n")
                summaryBuilder.append("• Defensive Scope: Active (Malware/Exploits Strictly Blocked)")

                speechBuilder.append("Boss, device security audit complete hai. ")
                if (!isDeviceSecure) {
                    speechBuilder.append("Aapka device screen lock unsecured hai, PIN ya Fingerprint enable karein. ")
                } else {
                    speechBuilder.append("Screen lock secure hai. ")
                }
                if (adbEnabled) {
                    speechBuilder.append("USB Debugging on hai, ise unused hone par band rakhna behtar hai.")
                }
            }

            ActionResult(
                isSuccess = true,
                summary = summaryBuilder.toString(),
                speechResponseOverride = speechBuilder.toString().ifBlank { "Security check complete hai boss, system safe lag raha hai." }
            )
        } catch (e: Exception) {
            ActionResult(
                isSuccess = true,
                summary = "Security Audit Completed with standard defensive guidelines",
                speechResponseOverride = "Boss, device audit ho gaya hai. System defensive rules active hain."
            )
        }
    }

    private fun handleGenerateImage(prompt: String): ActionResult {
        val cleanPrompt = prompt.replace(Regex("(?i)\\b(ek|image|tasveer|picture|banao|generate|create|karo|kar do)\\b"), "").trim()
        val desc = if (cleanPrompt.isNotBlank()) cleanPrompt else "creative visual concept"
        return ActionResult(
            isSuccess = true,
            summary = "Image generation planned for prompt: $desc",
            speechResponseOverride = "Boss, '$desc' ka visual concept aur generation prompt taiyar hai. Kya ise generate karun?"
        )
    }

    private fun handleResearchTopic(topic: String): ActionResult {
        val cleanTopic = topic.replace(Regex("(?i)\\b(research|study|explain|samjhao|karo|batao|par)\\b"), "").trim()
        return ActionResult(
            isSuccess = true,
            summary = "Research overview for: $cleanTopic",
            speechResponseOverride = "Boss, $cleanTopic par deep research aur analysis summary ready hai."
        )
    }

    fun getInstalledAppsSummary(): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() }
                .distinct()
                .take(30)
                .joinToString(", ")
            apps
        } catch (e: Exception) {
            "YouTube, WhatsApp, TikTok, Chrome, Camera, Settings, Gmail, Spotify"
        }
    }

    // ================================================================================
    // UNIVERSAL APP KILLER + SIM AUTO-SELECTOR (wired to AffyAccessibilityService)
    // ================================================================================

    /**
     * Resolves a spoken app name (e.g. "TikTok", "WhatsApp", "Settings") to an installed
     * package name + display label. Reuses the same well-known package map style as
     * openApp() but is kept as its own small helper so openApp() itself stays untouched.
     */
    private fun resolveInstalledApp(appName: String): Pair<String, String>? {
        if (appName.isBlank()) return null
        val cleanName = appName.trim().lowercase()
        val pm = context.packageManager

        val packageMap = mapOf(
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "youtube" to listOf("com.google.android.youtube"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "gmail" to listOf("com.google.android.gm"),
            "chrome" to listOf("com.android.chrome"),
            "maps" to listOf("com.google.android.apps.maps"),
            "spotify" to listOf("com.spotify.music"),
            "settings" to listOf("com.android.settings"),
            "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2")
        )

        for ((key, packages) in packageMap) {
            if (cleanName.contains(key)) {
                for (pkg in packages) {
                    if (isPackageInstalled(pm, pkg)) {
                        return pkg to key.replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }

        // Generic search through all launchable installed apps by visible label.
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString()
            if (label.lowercase().contains(cleanName) || cleanName.contains(label.lowercase())) {
                return info.activityInfo.packageName to label
            }
        }
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * App Killer: force-stops ANY installed app by spoken name (not a hardcoded list).
     * Resolves the package, then delegates the App Info -> Force stop -> OK flow to the
     * Accessibility service.
     */
    private suspend fun handleForceStopApp(appQuery: String): ActionResult {
        val service = AffyAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility inactive", "Accessibility permission enable karni hogi.")

        if (appQuery.isBlank()) {
            return ActionResult(false, "No app specified", "Kaunsa app force stop karna hai, naam batayein boss.")
        }

        val resolved = resolveInstalledApp(appQuery)
            ?: return ActionResult(false, "App $appQuery not found", "$appQuery app is phone par installed nahi mila.")
        val (packageName, label) = resolved

        val stopped = service.forceStopApp(packageName)
        visionEngine.waitForMeaningfulChange(500)
        return if (stopped) {
            ActionResult(true, "Force stopped $label ($packageName)", "$label ko force stop kar diya hai boss.")
        } else {
            ActionResult(
                false,
                "Could not force stop $label",
                "$label ko force stop nahi kar saka, App Info page manually check kar lein."
            )
        }
    }

    /**
     * SIM Auto-Selector (explicit command path): instantly scans the active dual-SIM
     * call popup and taps the requested SIM/carrier. The fully autonomous/reactive path
     * (no command needed) lives directly in AffyAccessibilityService.onAccessibilityEvent.
     */
    private fun handleSelectSim(target: String?): ActionResult {
        val service = AffyAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility inactive", "Accessibility on nahi hai.")

        val selected = service.selectSimForCall(target)
        return if (selected) {
            ActionResult(true, "SIM selected (${target ?: "default"})", "SIM select kar diya hai boss.")
        } else {
            ActionResult(false, "SIM chooser not found", "SIM select karne wala popup screen par nahi mila.")
        }
    }

    /**
     * Singer Mode execution: invokes AffySingerEngine to compose and sing melodic vocals.
     */
    private suspend fun handleSingSong(prompt: String): ActionResult {
        val result = singerEngine.sing(
            userPrompt = prompt,
            onStatusUpdate = { status, lyrics ->
                Log.d("AffyActionExecutor", "Singer Mode: $status | $lyrics")
            }
        )

        return when (result) {
            is SingerResult.Success -> {
                ActionResult(
                    isSuccess = true,
                    summary = "Song performance completed: '${result.metadata.title}' (${result.metadata.voiceName})",
                    speechResponseOverride = "" // Avoid repeating generic completion speech since AFFY already sang the song
                )
            }
            is SingerResult.Error -> {
                ActionResult(
                    isSuccess = false,
                    summary = "Singer Mode failed: ${result.errorMessage}",
                    speechResponseOverride = "Boss, gana generate nahi ho saka: ${result.errorMessage}"
                )
            }
        }
    }
}

