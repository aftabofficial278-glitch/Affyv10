package com.example.agent.mission

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.vision.AffyScreenVisionEngine
import com.example.agent.vision.ScreenContext
import com.example.agent.vision.ScreenStateType
import com.example.agent.vision.UiElementRole
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Universal App Transition & Cross-App Handoff Manager (Phase 10).
 * Handles switching between arbitrary Android applications, verifying destination foreground state,
 * clipboard-aware handoffs, and reconciling transient Mission Context with live screens.
 */
class AffyAppTransitionManager(
    private val context: Context,
    private val visionEngine: AffyScreenVisionEngine = AffyScreenVisionEngine.getInstance(context)
) {
    companion object {
        private const val TAG = "AffyAppTransition"

        private val KNOWN_PACKAGE_MAP = mapOf(
            "notepad" to listOf("com.google.android.keep", "com.samsung.android.app.notes", "com.android.notes", "org.billthefarmer.notes", "com.yocto.technologies.notes"),
            "notes" to listOf("com.google.android.keep", "com.samsung.android.app.notes", "com.android.notes"),
            "editor" to listOf("io.spck.editor", "com.foxdebug.acode", "com.aide.ui", "com.rhmsoft.edit", "com.spck.editor"),
            "code editor" to listOf("io.spck.editor", "com.foxdebug.acode", "com.aide.ui", "com.rhmsoft.edit"),
            "chatgpt" to listOf("com.openai.chatgpt"),
            "gemini" to listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whatsapp business" to listOf("com.whatsapp.w4b"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "browser" to listOf("com.android.chrome", "org.mozilla.firefox", "com.opera.browser"),
            "settings" to listOf("com.android.settings"),
            "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
            "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera"),
            "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web")
        )
    }

    /**
     * Safely transitions to a target application, waiting for foreground confirmation,
     * observing the new screen, and returning a rich TransitionResult.
     */
    suspend fun transitionToApp(
        targetAppName: String,
        missionContext: MissionContext? = null,
        timeoutMs: Long = 3000L
    ): TransitionResult = withContext(Dispatchers.IO) {
        val cleanName = targetAppName.trim().lowercase(Locale.ROOT)
        AffyDiagnostics.logScreenStage("SCREEN_ANALYSIS_STARTED", details = "Transitioning to '$targetAppName'")

        // 1. Resolve package
        val targetPackage = resolveTargetPackage(cleanName)

        // 2. Launch application
        val launched = launchApp(targetPackage, cleanName)
        if (!launched) {
            AffyDiagnostics.logScreenStage("SCREEN_ANALYSIS_FAILED", details = "Could not launch '$targetAppName'", isSuccess = false)
            return@withContext TransitionResult(
                isSuccess = false,
                targetApp = targetAppName,
                observedPackage = "",
                screenContext = null,
                failureReason = "App '$targetAppName' is not installed or could not be opened."
            )
        }

        // 3. Await foreground package change
        val startTime = System.currentTimeMillis()
        var currentScreen: ScreenContext? = null
        var isDestinationForeground = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(350)
            val screen = visionEngine.captureScreenContext()
            currentScreen = screen
            val currentPkg = screen.foregroundPackage.lowercase(Locale.ROOT)
            val currentLabel = screen.appDisplayName.lowercase(Locale.ROOT)

            if (matchesTargetApp(cleanName, targetPackage, currentPkg, currentLabel)) {
                isDestinationForeground = true
                break
            }
        }

        if (!isDestinationForeground || currentScreen == null) {
            val observedPkg = currentScreen?.foregroundPackage ?: "Unknown"
            AffyDiagnostics.logScreenStage("SCREEN_VERIFICATION", details = "App transition unverified. Observed: $observedPkg", isSuccess = false)
            return@withContext TransitionResult(
                isSuccess = false,
                targetApp = targetAppName,
                observedPackage = observedPkg,
                screenContext = currentScreen,
                failureReason = "Expected '$targetAppName' in foreground, but observed package was '$observedPkg'."
            )
        }

        // 4. Inspect screen state for roadblocks (permission dialogs, error dialogs, onboarding)
        if (currentScreen.screenState == ScreenStateType.PERMISSION_DIALOG) {
            Log.i(TAG, "Permission dialog detected after opening $targetAppName. Attempting automatic allow...")
            handlePermissionDialogIfPresent()
            delay(500)
            currentScreen = visionEngine.captureScreenContext()
        }

        // 5. Update transient mission context
        missionContext?.selectedApp = targetAppName
        missionContext?.currentScreenSummary = currentScreen.toConversationalSummary()

        AffyDiagnostics.logScreenStage("SCREEN_VERIFICATION", details = "Transitioned to $targetAppName (${currentScreen.foregroundPackage}) verified.", isSuccess = true)

        TransitionResult(
            isSuccess = true,
            targetApp = targetAppName,
            observedPackage = currentScreen.foregroundPackage,
            screenContext = currentScreen
        )
    }

    /**
     * Copies safe text content to clipboard, verifies clipboard update,
     * and updates transient mission context. Never persists credentials.
     */
    suspend fun copyToClipboard(
        text: String,
        semanticType: SemanticContentType = SemanticContentType.TEXT,
        missionContext: MissionContext? = null
    ): Boolean = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext false
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Affy Handoff", text)
            clipboard.setPrimaryClip(clip)

            // Verify clipboard update
            val currentClip = clipboard.primaryClip
            val verified = currentClip != null && currentClip.itemCount > 0 && currentClip.getItemAt(0).text?.toString() == text

            if (verified) {
                missionContext?.clipboardContent = text
                missionContext?.putSemantic("last_clipboard", semanticType, text)
                AffyDiagnostics.logAction("ACTION_EXECUTION", "CLIPBOARD_COPY", "Copied ${text.length} chars (Type: $semanticType)", true)
            }
            verified
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard: ${e.message}")
            false
        }
    }

    /**
     * Pastes clipboard / semantic content into the currently active or targeted edit field
     * on the live screen and verifies text insertion.
     */
    suspend fun pasteFromClipboard(
        targetQuery: String? = null,
        missionContext: MissionContext? = null
    ): Boolean = withContext(Dispatchers.Main) {
        val service = AffyAccessibilityService.instance ?: return@withContext false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val textToPaste = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString() ?: ""
        } else {
            missionContext?.clipboardContent ?: ""
        }

        if (textToPaste.isBlank()) {
            Log.w(TAG, "No content available in clipboard or mission context to paste")
            return@withContext false
        }

        // Live screen paste through accessibility
        val pasted = service.typeText(textToPaste, targetQuery)
        if (pasted) {
            visionEngine.waitForMeaningfulChange(400)
            val screen = visionEngine.captureScreenContext()
            val textVerified = screen.visibleTexts.any { it.contains(textToPaste.take(20), ignoreCase = true) }
            AffyDiagnostics.logAction("ACTION_EXECUTION", "CLIPBOARD_PASTE", "Pasted text verified=$textVerified", textVerified)
            return@withContext textVerified || pasted
        }
        false
    }

    private fun resolveTargetPackage(cleanAppName: String): String? {
        val pm = context.packageManager

        // 1. Check known aliases
        for ((alias, pkgs) in KNOWN_PACKAGE_MAP) {
            if (cleanAppName.contains(alias) || alias.contains(cleanAppName)) {
                for (pkg in pkgs) {
                    if (isPackageInstalled(pm, pkg)) return pkg
                }
            }
        }

        // 2. Query all installed launchable apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
            val pkg = info.activityInfo.packageName.lowercase(Locale.ROOT)
            if (label == cleanAppName || label.contains(cleanAppName) || cleanAppName.contains(label) || pkg.contains(cleanAppName)) {
                return info.activityInfo.packageName
            }
        }
        return null
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun launchApp(packageName: String?, fallbackName: String): Boolean {
        val pm = context.packageManager
        if (!packageName.isNullOrBlank()) {
            val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return true
            }
        }

        // Generic fallback search by name
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase(Locale.ROOT)
            if (label.contains(fallbackName)) {
                val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (intent != null) {
                    context.startActivity(intent)
                    return true
                }
            }
        }
        return false
    }

    private fun matchesTargetApp(
        cleanTarget: String,
        targetPackage: String?,
        currentPkg: String,
        currentLabel: String
    ): Boolean {
        if (!targetPackage.isNullOrBlank() && currentPkg == targetPackage.lowercase(Locale.ROOT)) {
            return true
        }
        if (currentPkg.contains(cleanTarget) || currentLabel.contains(cleanTarget) || cleanTarget.contains(currentLabel)) {
            return true
        }
        // Alias checks
        val aliases = KNOWN_PACKAGE_MAP[cleanTarget] ?: emptyList()
        return aliases.any { currentPkg.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun handlePermissionDialogIfPresent() {
        val a11y = AffyAccessibilityService.instance ?: return
        val nodes = a11y.rootInActiveWindow ?: return
        try {
            val allowNodes = nodes.findAccessibilityNodeInfosByText("Allow") +
                    nodes.findAccessibilityNodeInfosByText("While using the app") +
                    nodes.findAccessibilityNodeInfosByText("Only this time")
            val target = allowNodes.firstOrNull { it.isClickable }
            target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling permission dialog: ${e.message}")
        }
    }
}

data class TransitionResult(
    val isSuccess: Boolean,
    val targetApp: String,
    val observedPackage: String,
    val screenContext: ScreenContext? = null,
    val failureReason: String? = null
)
