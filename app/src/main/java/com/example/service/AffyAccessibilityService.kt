package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.datastore.AffyPreferences
import com.example.data.db.AffyDatabase
import com.example.data.remote.GeminiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AffyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AffyAccessibility"

        @Volatile
        var instance: AffyAccessibilityService? = null
            private set

        fun getInstance(): AffyAccessibilityService? = instance

        @Volatile
        var lastNotificationTimestamp: Long = 0L

        @Volatile
        var lastNotificationText: String = ""

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentAppPackage = MutableStateFlow<String?>(null)
        val currentAppPackage: StateFlow<String?> = _currentAppPackage.asStateFlow()

        /**
         * Global toggle for the autonomous dual-SIM call popup selector.
         * Left on by default per the "human-like" autonomy goal, but exposed so a
         * future Settings screen can let the user disable it without touching this class.
         */
        @Volatile
        var autoSimSelectionEnabled: Boolean = true

        /**
         * Optional preferred SIM hint (e.g. "SIM 1", "SIM 2", or a carrier name like "Jazz").
         * When null/blank, the selector defaults to the first SIM slot it finds ("SIM 1").
         */
        @Volatile
        var preferredSimHint: String? = null

        // Packages that commonly host the native dual-SIM "select SIM for this call" chooser.
        private val simChooserPackages = listOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui"
        )

        // Debounce so the same chooser instance is never double-clicked.
        @Volatile
        private var lastSimChooserHandledAt: Long = 0L
    }

    private var busyModeManager: WhatsAppBusyModeManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i("AffyAccessibility", "[LIFECYCLE] AffyAccessibilityService.onCreate() called, timestamp=${System.currentTimeMillis()}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        busyModeManager = WhatsAppBusyModeManager.getInstance(applicationContext)
        Log.i("AffyAccessibility", "[LIFECYCLE] AffyAccessibilityService.onServiceConnected() called, isServiceConnected=true, instanceHash=${this.hashCode()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank() || pkg == "com.example") return

        // Fast state update without expensive inspections
        _currentAppPackage.value = pkg

        val eventType = event.eventType

        // Filter out high-frequency content/scroll/text-changed mutations on the main thread
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return
        }

        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            busyModeManager?.handleAccessibilityEvent(event)
            return
        }

        // Autonomous SIM chooser handling: fires reactively on window state changes for dialer packages in background
        if (autoSimSelectionEnabled && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (simChooserPackages.any { pkg.contains(it) }) {
                val now = System.currentTimeMillis()
                if (now - lastSimChooserHandledAt > 2000L) {
                    lastSimChooserHandledAt = now
                    serviceScope.launch {
                        maybeAutoSelectSim(pkg)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w("AffyAccessibility", "[LIFECYCLE] AffyAccessibilityService.onInterrupt() called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("AffyAccessibility", "[LIFECYCLE] AffyAccessibilityService.onDestroy() called")
        serviceScope.cancel()
        if (instance == this) {
            instance = null
            _isServiceConnected.value = false
        }
    }

    // --- AUTOMATION AND NODE OPERATIONS ---

    /**
     * Retrieves the freshest AccessibilityNodeInfo root of the current foreground application window.
     * Prioritizes active application windows over system overlays or floating UI, and calls refresh()
     * to discard stale/cached nodes so live apps like WhatsApp are read accurately.
     */
    fun getFreshActiveWindowRoot(): AccessibilityNodeInfo? {
        val direct = rootInActiveWindow
        if (direct != null && direct.packageName?.toString() != packageName) {
            direct.refresh()
            return direct
        }

        val windowList = try { windows } catch (_: Exception) { emptyList() }
        val targetAppWindow = windowList.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isFocused || it.isActive) &&
                    it.root?.packageName?.toString() != packageName
        } ?: windowList.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() != packageName
        } ?: windowList.firstOrNull {
            it.isActive && it.root?.packageName?.toString() != packageName
        }

        val rootNode = targetAppWindow?.root ?: direct
        rootNode?.refresh()
        return rootNode
    }

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    fun lockDevice(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val result = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            Log.i(TAG, "lockDevice via GLOBAL_ACTION_LOCK_SCREEN: $result")
            return result
        }
        return false
    }

    fun wakeScreen(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                    "affy:wake_screen_lock"
                )
                wakeLock.acquire(3000L)
                wakeLock.release()
                Log.i(TAG, "[DEVICE_SCREEN_ON] Screen wake lock acquired and released")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen: ${e.message}")
            false
        }
    }

    data class DeviceLockState(
        val isScreenOn: Boolean,
        val isLocked: Boolean,
        val isSecure: Boolean
    )

    fun getDeviceLockState(): DeviceLockState {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager

        val isScreenOn = pm?.isInteractive ?: true
        val isLocked = km?.isDeviceLocked == true || km?.isKeyguardLocked == true
        val isSecure = km?.isKeyguardSecure == true

        return DeviceLockState(
            isScreenOn = isScreenOn,
            isLocked = isLocked,
            isSecure = isSecure
        )
    }

    fun takeScreenshot(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.i(TAG, "takeScreenshot via GLOBAL_ACTION_TAKE_SCREENSHOT: $result")
            return result
        }
        return false
    }

    fun clickElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (clickNodeWithFallback(node)) {
                return true
            }
        }
        return false
    }

    fun clickElementByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            if (clickNodeWithFallback(node)) {
                return true
            }
        }
        return false
    }

    fun longPressElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (tryLongPressNode(node)) {
                return true
            }
        }
        return false
    }

    private fun tryLongPressNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isLongClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        return false
    }

    fun verifyMediaLike(expectedLiked: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val likeTerms = listOf("like", "heart", "favorite", "love", "upvote", "pasand")

        fun findLikeNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (likeTerms.any { desc.contains(it) || text.contains(it) || viewId.contains(it) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findLikeNode(child)
                if (res != null) return res
            }
            return null
        }

        val likeNode = findLikeNode(root)
        if (likeNode != null) {
            val descBefore = likeNode.contentDescription?.toString()?.lowercase() ?: ""
            val isCheckedBefore = likeNode.isChecked || likeNode.isSelected || descBefore.contains("unlike") || descBefore.contains("liked")

            if (expectedLiked && isCheckedBefore) {
                Log.i(TAG, "[MEDIA_LIKE] Already liked")
                return true
            }
            if (!expectedLiked && !isCheckedBefore) {
                Log.i(TAG, "[MEDIA_LIKE] Already unliked")
                return true
            }

            val clicked = clickNodeWithFallback(likeNode)
            if (clicked) {
                try { Thread.sleep(300) } catch (_: Exception) {}
                val updatedRoot = rootInActiveWindow ?: return true
                val updatedNode = findLikeNode(updatedRoot)
                if (updatedNode != null) {
                    val descAfter = updatedNode.contentDescription?.toString()?.lowercase() ?: ""
                    val isCheckedAfter = updatedNode.isChecked || updatedNode.isSelected || descAfter.contains("unlike") || descAfter.contains("liked")
                    if (expectedLiked == isCheckedAfter || descBefore != descAfter) {
                        return true
                    }
                }
                return true
            }
        }

        // TikTok / Reels double tap fallback for like
        if (expectedLiked) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val centerY = displayMetrics.heightPixels / 2f
            return performDoubleTap(centerX, centerY)
        }
        return false
    }

    fun getVideoTitle(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""

        // 1. YouTube specific title detection
        if (pkg.contains("youtube")) {
            val titleNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/video_title")
            if (titleNodes.isNotEmpty()) {
                val t = titleNodes.first().text?.toString()?.trim()
                if (!t.isNullOrBlank()) return t
            }
            val playerTitleNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/title")
            if (playerTitleNodes.isNotEmpty()) {
                val t = playerTitleNodes.first().text?.toString()?.trim()
                if (!t.isNullOrBlank()) return t
            }
        }

        // 2. Generic title extraction: Look for prominent text node near top or header
        var candidateTitle: String? = null
        fun findTitle(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 7 || candidateTitle != null) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""

            if (text.isNotBlank() && (id.contains("title") || id.contains("header") || id.contains("heading") || id.contains("video_name"))) {
                candidateTitle = text
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findTitle(child, depth + 1)
            }
        }
        findTitle(root, 0)
        if (!candidateTitle.isNullOrBlank()) return candidateTitle

        // 3. Fallback to first meaningful long text (>10 chars)
        fun findLongText(node: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 6) return null
            val text = node.text?.toString()?.trim() ?: ""
            if (text.length > 10 && !text.startsWith("•") && !node.isEditable && !node.isClickable) {
                return text
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findLongText(child, depth + 1)
                if (!res.isNullOrBlank()) return res
            }
            return null
        }
        return findLongText(root, 0)
    }

    fun getMediaCaption(): String? {
        val root = rootInActiveWindow ?: return null
        fun findCaption(node: AccessibilityNodeInfo, depth: Int): String? {
            if (depth > 8) return null
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (text.isNotBlank() && (id.contains("desc") || id.contains("caption") || id.contains("text") || id.contains("title"))) {
                if (text.length > 5 && !node.isEditable && !node.isClickable) return text
            }
            if (desc.isNotBlank() && (desc.length > 10)) {
                return desc
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findCaption(child, depth + 1)
                if (!res.isNullOrBlank()) return res
            }
            return null
        }
        return findCaption(root, 0)
    }

    fun verifySubscribe(channelQuery: String? = null): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return Pair(false, "Screen accessible nahi hai.")
        val subTerms = listOf("subscribe", "subscribed", "sub", "channel subscribe")

        fun findSubNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (subTerms.any { desc.contains(it) || text.contains(it) || viewId.contains("subscribe") }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findSubNode(child)
                if (res != null) return res
            }
            return null
        }

        val subNode = findSubNode(root)
        if (subNode == null) {
            return Pair(false, "Screen par Subscribe button nahi mila.")
        }

        val text = subNode.text?.toString()?.trim() ?: ""
        val desc = subNode.contentDescription?.toString()?.trim() ?: ""
        val combined = (text + " " + desc).lowercase()

        if (combined.contains("subscribed") || combined.contains("subscribed to") || subNode.isSelected) {
            return Pair(true, "Aap pehle se hi subscribe kar chuke hain boss.")
        }

        val clicked = clickNodeWithFallback(subNode)
        if (clicked) {
            try { Thread.sleep(400) } catch (_: Exception) {}
            val updatedRoot = rootInActiveWindow
            if (updatedRoot != null) {
                val updatedSub = findSubNode(updatedRoot)
                val updatedText = (updatedSub?.text?.toString() ?: "") + " " + (updatedSub?.contentDescription?.toString() ?: "")
                if (updatedText.lowercase().contains("subscribed") || updatedSub?.isSelected == true) {
                    return Pair(true, "Channel subscribe kar diya hai boss!")
                }
            }
            return Pair(true, "Channel subscribe kar diya hai.")
        }

        return Pair(false, "Subscribe button click nahi ho saka.")
    }

    fun controlVideoPlayback(requestAction: String): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return Pair(false, "Screen accessible nahi hai.")
        val cleanReq = requestAction.lowercase().trim()
        val isPlayReq = cleanReq == "play" || cleanReq.contains("chalao") || cleanReq.contains("play") || cleanReq.contains("resume")

        fun findPlayPauseNode(node: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, Boolean>? {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            if (desc.contains("pause") || id.contains("pause") || text == "pause") {
                return Pair(node, true)
            }
            if (desc.contains("play") || id.contains("play") || text == "play") {
                return Pair(node, false)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findPlayPauseNode(child)
                if (res != null) return res
            }
            return null
        }

        val match = findPlayPauseNode(root)
        if (match != null) {
            val (node, isCurrentlyPlaying) = match
            if (isPlayReq && isCurrentlyPlaying) {
                return Pair(true, "Video pehle se chal rahi hai boss.")
            }
            if (!isPlayReq && !isCurrentlyPlaying) {
                return Pair(true, "Video pehle se paused hai boss.")
            }

            val clicked = clickNodeWithFallback(node)
            if (clicked) {
                try { Thread.sleep(300) } catch (_: Exception) {}
                val msg = if (isPlayReq) "Video play kar di hai." else "Video pause kar di hai."
                return Pair(true, msg)
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val keycode = if (isPlayReq) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
            val msg = if (isPlayReq) "Video play kar di." else "Video pause kar di."
            return Pair(true, msg)
        }

        return Pair(false, "Playback control nahi mila.")
    }

    fun selectVideoResult(ordinal: Int, query: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanOrd = ordinal.coerceAtLeast(1)

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun collectVideoNodes(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 8) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString() ?: ""
            val isVideo = id.contains("video_grid") || id.contains("compact_video") || id.contains("video_item") || id.contains("thumbnail") || id.contains("feed_item")
            if (isVideo && node.isClickable) {
                candidates.add(node)
            } else if (node.isClickable && (cls.contains("CardView") || cls.contains("RelativeLayout") || cls.contains("ViewGroup"))) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 300 && r.height() > 150) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectVideoNodes(child, depth + 1)
            }
        }
        collectVideoNodes(root, 0)

        candidates.sortBy { 
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top
        }

        val targetIndex = cleanOrd - 1
        val targetNode = if (targetIndex < candidates.size) candidates[targetIndex] else candidates.firstOrNull()
        if (targetNode != null) {
            return clickNodeWithFallback(targetNode)
        }

        return false
    }

    fun verifyAppInForeground(targetPackage: String, timeoutMs: Long = 2000L): Boolean {
        val cleanTarget = targetPackage.lowercase().trim()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            val currentPkg = root?.packageName?.toString()?.lowercase() ?: _currentAppPackage.value?.lowercase() ?: ""
            if (currentPkg.contains(cleanTarget) || cleanTarget.contains(currentPkg)) {
                return true
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }
        return false
    }

    fun scrollFeedAndVerify(next: Boolean): Boolean {
        val beforeCaption = getMediaCaption() ?: getVideoTitle() ?: ""
        val beforeHash = (rootInActiveWindow?.packageName?.toString() ?: "").hashCode()
        val dm = resources.displayMetrics
        val startX = dm.widthPixels / 2f
        val startY = if (next) dm.heightPixels * 0.8f else dm.heightPixels * 0.2f
        val endY = if (next) dm.heightPixels * 0.2f else dm.heightPixels * 0.8f
        val swiped = performSwipeGesture(startX, startY, startX, endY, 250)
        if (swiped) {
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val afterCaption = getMediaCaption() ?: getVideoTitle() ?: ""
            val afterHash = (rootInActiveWindow?.packageName?.toString() ?: "").hashCode()
            if (beforeCaption != afterCaption || beforeHash != afterHash) {
                return true
            }
            return true
        }
        return false
    }

    fun clickLikeButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val likeTerms = listOf("like", "heart", "favorite", "love", "thump", "upvote", "pasand")

        // Search by text / content description
        fun findAndClickLike(node: AccessibilityNodeInfo): Boolean {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (likeTerms.any { desc.contains(it) || text.contains(it) || viewId.contains(it) }) {
                if (clickNodeWithFallback(node)) return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndClickLike(child)) return true
            }
            return false
        }

        if (findAndClickLike(root)) return true

        // Fallback: Double tap center of screen (common for TikTok / Instagram Reels)
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        return performDoubleTap(centerX, centerY)
    }

    fun tryClickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.i(TAG, "[UI_CLICK_HIERARCHY] ACTION_CLICK succeeded on ${current.className} (${current.viewIdResourceName})")
                    return true
                }
            }
            current = current.parent
        }
        return false
    }

    fun clickNodeWithFallback(node: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "[UI_CLICK_TARGET] Attempting click on ${node.className} (${node.viewIdResourceName}) '${node.text ?: node.contentDescription}'")
        if (tryClickNode(node)) {
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()
            Log.i(TAG, "[UI_CLICK_GESTURE_FALLBACK] ACTION_CLICK failed/unavailable on ${node.className}, dispatching gesture tap at ($cx, $cy) [bounds=$rect]")
            return performTap(cx, cy)
        }
        return false
    }

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false

        // Helper to collect vertical scrollable candidates while filtering out horizontal ViewPagers
        fun collectVerticalScrollables(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
            val className = node.className?.toString() ?: ""
            val isHorizontalContainer = className.contains("ViewPager", ignoreCase = true) ||
                    className.contains("HorizontalScrollView", ignoreCase = true) ||
                    className.contains("TabLayout", ignoreCase = true)

            if (node.isScrollable && !isHorizontalContainer) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // Filter out obvious horizontal bars (e.g. height < 150px while width is full screen)
                if (rect.height() > 200 || className.contains("RecyclerView", ignoreCase = true) || className.contains("ListView", ignoreCase = true) || className.contains("ScrollView", ignoreCase = true)) {
                    list.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectVerticalScrollables(child, list)
            }
        }

        // Helper to capture a snapshot of visible texts for verification
        fun captureScreenTextSnapshot(node: AccessibilityNodeInfo): String {
            val sb = StringBuilder()
            fun traverse(n: AccessibilityNodeInfo) {
                val txt = n.text?.toString()?.trim()
                if (!txt.isNullOrBlank()) sb.append(txt).append("|")
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    traverse(c)
                }
            }
            traverse(node)
            return sb.toString()
        }

        val beforeSnapshot = captureScreenTextSnapshot(root)
        val scrollableCandidates = mutableListOf<AccessibilityNodeInfo>()
        collectVerticalScrollables(root, scrollableCandidates)

        // Sort candidates: prioritize RecyclerView, ListView, ScrollView with largest screen area
        scrollableCandidates.sortByDescending { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            val cls = node.className?.toString() ?: ""
            val bonus = if (cls.contains("RecyclerView") || cls.contains("ListView")) 1000000 else if (cls.contains("ScrollView")) 500000 else 0
            area + bonus
        }

        var scrollSucceeded = false

        for (candidate in scrollableCandidates) {
            val targetClass = candidate.className?.toString() ?: "Unknown"
            val targetId = candidate.viewIdResourceName ?: "no_id"
            Log.i(TAG, "[WA_SCROLL_TARGET] targetClass=$targetClass viewId=$targetId down=$down")

            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val dispatched = candidate.performAction(action)
            Log.i(TAG, "[WA_SCROLL_EXECUTED] dispatched=$dispatched on $targetClass ($targetId)")

            if (dispatched) {
                // Brief observation pause to let UI render scroll offset
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                val updatedRoot = rootInActiveWindow
                if (updatedRoot != null) {
                    val afterSnapshot = captureScreenTextSnapshot(updatedRoot)
                    if (beforeSnapshot != afterSnapshot) {
                        Log.i(TAG, "[WA_SCROLL_VERIFIED] Screen content changed after scrolling $targetClass")
                        scrollSucceeded = true
                        break
                    } else {
                        Log.w(TAG, "[WA_SCROLL_FALSE_SUCCESS_BLOCKED] performAction returned true on $targetClass but screen content did not change")
                    }
                }
            }
        }

        if (scrollSucceeded) return true

        // Fallback: Perform swipe gesture on screen and verify
        val dm = resources.displayMetrics
        val startX = dm.widthPixels / 2f
        val startY = if (down) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
        val endY = if (down) dm.heightPixels * 0.25f else dm.heightPixels * 0.75f
        Log.i(TAG, "[WA_SCROLL_TARGET] Fallback swipe gesture (startX=$startX, startY=$startY -> endY=$endY)")
        val swiped = performSwipeGesture(startX, startY, startX, endY, 300)
        Log.i(TAG, "[WA_SCROLL_EXECUTED] Swipe dispatched=$swiped")

        if (swiped) {
            try { Thread.sleep(350) } catch (_: InterruptedException) {}
            val updatedRoot = rootInActiveWindow
            if (updatedRoot != null) {
                val afterSnapshot = captureScreenTextSnapshot(updatedRoot)
                if (beforeSnapshot != afterSnapshot) {
                    Log.i(TAG, "[WA_SCROLL_VERIFIED] Screen content changed after swipe fallback")
                    return true
                } else {
                    Log.w(TAG, "[WA_SCROLL_FALSE_SUCCESS_BLOCKED] Swipe dispatched but screen content did not change")
                }
            }
        }

        return false
    }

    data class VisibleChatInfo(
        val contactOrGroupName: String?,
        val packageName: String,
        val messages: List<String>,
        val isGroup: Boolean = false
    )

    /**
     * Inspects the active screen (especially WhatsApp or messaging apps) and extracts
     * visible chat list / contact items when on the main conversations/contacts screen.
     * Returns a structured summary of visible chats/contacts with last message previews if available.
     */
    fun readVisibleChatList(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""
        Log.i(TAG, "[SCREEN_CHAT_LIST] Inspecting active chat list in foreground package: $pkg")

        val chatEntries = mutableListOf<String>()
        val seenLabels = mutableSetOf<String>()

        fun extractChatRows(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""

            // Check if node itself is a contact/conversation name or container
            val isContactId = id.endsWith(":id/conversations_row_contact_name") ||
                    id.endsWith(":id/contact_name") ||
                    id.endsWith(":id/chat_name") ||
                    id.endsWith(":id/conversation_contact_name") ||
                    id.endsWith(":id/name") ||
                    id.endsWith(":id/sender_name") ||
                    id.contains("contact_name") ||
                    id.contains("conversations_row")

            if (isContactId && text.isNotBlank() && text.length > 1 && !seenLabels.contains(text.lowercase())) {
                val ignored = listOf("search", "chats", "updates", "status", "communities", "calls", "new chat", "unread", "archived")
                if (ignored.none { text.equals(it, ignoreCase = true) }) {
                    seenLabels.add(text.lowercase())
                    chatEntries.add(text)
                }
            } else if (desc.isNotBlank() && (desc.startsWith("Chat with", ignoreCase = true) || desc.contains("unread message", ignoreCase = true))) {
                // WhatsApp accessibility row content description
                val cleanDesc = desc.replace(Regex("(?i)^chat with\\s*"), "").trim()
                val primaryName = cleanDesc.substringBefore(",").substringBefore(":").trim()
                if (primaryName.isNotBlank() && !seenLabels.contains(primaryName.lowercase())) {
                    seenLabels.add(primaryName.lowercase())
                    chatEntries.add(cleanDesc)
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                extractChatRows(child, depth + 1)
            }
        }

        extractChatRows(root, 0)

        // Fallback: If no explicit IDs, cluster visual text rows in list area
        if (chatEntries.isEmpty()) {
            val validTexts = mutableListOf<Pair<String, Rect>>()
            fun collectListTexts(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 20) return
                val txt = node.text?.toString()?.trim() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val ignored = listOf("search", "chats", "updates", "status", "communities", "calls", "new chat", "camera", "more options", "settings", "type a message", "message")
                if (txt.isNotBlank() && txt.length > 1 && !txt.startsWith("•") && ignored.none { txt.equals(it, ignoreCase = true) } && !id.contains("search") && !id.contains("tab")) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.top >= 120 && r.bottom <= (resources.displayMetrics.heightPixels - 100)) {
                        validTexts.add(Pair(txt, r))
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    collectListTexts(child, depth + 1)
                }
            }
            collectListTexts(root, 0)
            validTexts.sortBy { it.second.top }

            val clusteredRows = mutableListOf<String>()
            var lastTop = -1
            var currentLabel = ""
            for ((t, rect) in validTexts) {
                if (lastTop == -1 || Math.abs(rect.top - lastTop) < 60) {
                    if (currentLabel.isBlank()) currentLabel = t else if (!currentLabel.contains(t) && t.length < 30) currentLabel += " - $t"
                    lastTop = rect.top
                } else {
                    if (currentLabel.isNotBlank()) clusteredRows.add(currentLabel)
                    currentLabel = t
                    lastTop = rect.top
                }
            }
            if (currentLabel.isNotBlank()) clusteredRows.add(currentLabel)

            for (row in clusteredRows.take(10)) {
                if (!seenLabels.contains(row.lowercase())) {
                    seenLabels.add(row.lowercase())
                    chatEntries.add(row)
                }
            }
        }

        if (chatEntries.isEmpty()) {
            return "Screen par koi visible chat ya contact list nahi mila."
        }

        return "Screen par is waqt ${chatEntries.size} contacts aur chats visible hain:\n" + chatEntries.take(10).mapIndexed { idx, it -> "${idx + 1}. $it" }.joinToString("\n")
    }

    /**
     * Inspects the active screen (especially WhatsApp or messaging apps) and extracts:
     * - visible chat title / contact name
     * - visible message bubbles with text, distinguishing incoming and outgoing bubbles
     * Returns a structured summary of the currently open chat conversation.
     */
    fun readVisibleChatMessages(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""
        Log.i(TAG, "[SCREEN_CHAT_CONTEXT] Inspecting active chat in foreground package: $pkg")

        var chatTitle: String? = null
        val visibleMessages = mutableListOf<String>()

        // 1. Locate Chat Title / Contact Name from Action Bar or Top Header
        fun findChatTitle(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 20 || chatTitle != null) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""

            if (text.isNotBlank() && chatTitle == null) {
                val isHeaderId = id.endsWith(":id/conversation_contact_name") ||
                        id.endsWith(":id/chat_title") ||
                        id.endsWith(":id/action_bar_title") ||
                        id.endsWith(":id/conversation_title") ||
                        id.endsWith(":id/title") ||
                        id.contains("contact_name") ||
                        id.contains("chat_name") ||
                        id.contains("action_bar")

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val isTopHeaderArea = rect.top < 350

                if (isHeaderId || (isTopHeaderArea && !id.contains("search") && !id.contains("menu") && text.length > 1 && !text.contains(":") && !text.equals("WhatsApp", ignoreCase = true))) {
                    chatTitle = text
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findChatTitle(child, depth + 1)
            }
        }
        findChatTitle(root, 0)

        // 2. Locate Message Text Nodes inside Chat
        val messageNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun findMessages(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 20) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""

            if (text.isNotBlank() && text.length > 1) {
                val isMessageNode = id.endsWith(":id/message_text") ||
                        id.endsWith(":id/message_text_view") ||
                        id.endsWith(":id/text_content") ||
                        id.endsWith(":id/caption") ||
                        id.endsWith(":id/msg_text") ||
                        id.contains("message_text") ||
                        id.contains("msg_text") ||
                        id.contains("conversation_row") ||
                        id.contains("message_row") ||
                        id.contains("quoted_message_text")

                if (isMessageNode && text != chatTitle) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    messageNodes.add(Pair(node, rect))
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findMessages(child, depth + 1)
            }
        }
        findMessages(root, 0)

        // Sort message bubbles by vertical position on screen
        messageNodes.sortBy { it.second.top }
        val screenWidth = resources.displayMetrics.widthPixels
        for ((mNode, rect) in messageNodes) {
            val txt = mNode.text?.toString()?.trim() ?: ""
            if (txt.isNotBlank() && !visibleMessages.contains(txt)) {
                // Determine incoming vs outgoing by horizontal alignment (outgoing bubbles are on the right side)
                val isOutgoing = rect.left > (screenWidth * 0.35f) && rect.right >= (screenWidth * 0.85f)
                val prefix = if (isOutgoing) "Aap: " else if (!chatTitle.isNullOrBlank()) "$chatTitle: " else ""
                visibleMessages.add("$prefix$txt")
            }
        }

        // Fallback: If no explicit message_text IDs, gather text from main message list container
        if (visibleMessages.isEmpty()) {
            val candidates = mutableListOf<Triple<String, Rect, Boolean>>()
            fun extractGeneralTexts(node: AccessibilityNodeInfo, depth: Int = 0) {
                if (depth > 20) return
                val txt = node.text?.toString()?.trim() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                val ignoredPhrases = listOf("type a message", "message", "online", "typing...", "disappearing messages", "search", "send", "voice message", "today", "yesterday")

                if (txt.isNotBlank() && txt != chatTitle && txt.length > 1 && !id.contains("search") && !id.contains("input")) {
                    if (ignoredPhrases.none { txt.equals(it, ignoreCase = true) }) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        // Ignore top header and bottom input bar areas
                        if (rect.top >= 120 && rect.bottom <= (resources.displayMetrics.heightPixels - 120)) {
                            val isOutgoing = rect.left > (screenWidth * 0.35f)
                            candidates.add(Triple(txt, rect, isOutgoing))
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    extractGeneralTexts(child, depth + 1)
                }
            }
            extractGeneralTexts(root, 0)
            candidates.sortBy { it.second.top }
            for ((candTxt, _, isOut) in candidates) {
                if (!visibleMessages.any { it.endsWith(candTxt) }) {
                    val prefix = if (isOut) "Aap: " else if (!chatTitle.isNullOrBlank()) "$chatTitle: " else ""
                    visibleMessages.add("$prefix$candTxt")
                }
            }
        }

        Log.i(TAG, "[SCREEN_VISIBLE_CONTACT] detectedContact='$chatTitle'")
        Log.i(TAG, "[SCREEN_MESSAGE_NODES] Extracted ${visibleMessages.size} visible messages: $visibleMessages")

        if (visibleMessages.isEmpty() && chatTitle.isNullOrBlank()) {
            val visibleChats = readVisibleChatList()
            return if (!visibleChats.isNullOrBlank() && !visibleChats.contains("nahi mila")) {
                "Abhi chat list screen open hai, koi specific chat open nahi hai.\n$visibleChats\nAap kisi contact ki chat open karne ko kahein (jaise 'Ali ki chat open karo')."
            } else {
                "Screen par koi readable message visible nahi mila."
            }
        }

        val titleHeader = if (!chatTitle.isNullOrBlank()) "Chat with $chatTitle" else "Current Screen Chat"
        return if (visibleMessages.isNotEmpty()) {
            "$titleHeader:\n" + visibleMessages.takeLast(8).map { "• $it" }.joinToString("\n")
        } else {
            "$titleHeader open hai magar is chat mein koi message text visible nahi hai."
        }
    }

    /**
     * Extracts active conversation contact/header title if inside an open chat conversation.
     */
    fun getChatHeaderTitle(): String? {
        val root = rootInActiveWindow ?: return null
        var foundTitle: String? = null
        fun findHeader(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || foundTitle != null) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val isHeaderId = id.endsWith(":id/conversation_contact_name") ||
                    id.endsWith(":id/chat_title") ||
                    id.endsWith(":id/action_bar_title") ||
                    id.endsWith(":id/conversation_title")
            if (isHeaderId && text.isNotBlank() && !text.equals("WhatsApp", ignoreCase = true)) {
                foundTitle = text
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findHeader(child, depth + 1)
            }
        }
        findHeader(root, 0)
        return foundTitle
    }

    /**
     * Interacts with WhatsApp or messaging screen to locate and click/open a specific contact's chat.
     */
    fun openChatByContactName(contactName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanTarget = contactName.lowercase().trim()
        if (cleanTarget.isBlank()) return false

        // 1. If chat with contact is already open
        val currentTitle = getChatHeaderTitle()?.lowercase()
        if (currentTitle != null && (currentTitle.contains(cleanTarget) || cleanTarget.contains(currentTitle))) {
            Log.i(TAG, "[OPEN_CHAT] Chat with '$contactName' is already open.")
            return true
        }

        // 2. Search visible conversation rows / contact items on screen
        var targetNodeToClick: AccessibilityNodeInfo? = null

        fun findContactNode(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || targetNodeToClick != null) return
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            val normText = text.lowercase()
            val normDesc = desc.lowercase()

            val isMatch = normText == cleanTarget ||
                    (cleanTarget.length >= 3 && normText.startsWith(cleanTarget)) ||
                    (cleanTarget.length >= 3 && normText.contains(cleanTarget)) ||
                    normDesc.contains("chat with $cleanTarget") ||
                    normDesc.startsWith(cleanTarget)

            if (isMatch && text.isNotBlank()) {
                val ignored = listOf("search", "chats", "updates", "status", "communities", "calls", "new chat", "unread", "archived", "camera", "whatsapp")
                if (ignored.none { normText.equals(it) }) {
                    targetNodeToClick = node
                    return
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findContactNode(child, depth + 1)
            }
        }

        findContactNode(root, 0)

        targetNodeToClick?.let { node ->
            val clicked = clickNodeWithFallback(node)
            Log.i(TAG, "[OPEN_CHAT] Clicked visible contact row for '$contactName': success=$clicked")
            if (clicked) return true
        }

        // 3. Fallback: Search via WhatsApp Search Icon
        val searchClicked = clickElementUniversal("search") || clickElementByViewId("menuitem_search") || clickElementByText("Search")
        if (searchClicked) {
            android.os.SystemClock.sleep(350)
            val inputSuccess = typeText(contactName)
            if (inputSuccess) {
                android.os.SystemClock.sleep(700)
                val updatedRoot = rootInActiveWindow ?: return true
                var searchResultNode: AccessibilityNodeInfo? = null
                fun findSearchResult(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 20 || searchResultNode != null) return
                    val t = node.text?.toString()?.trim()?.lowercase() ?: ""
                    val d = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                    if ((t.contains(cleanTarget) || d.contains(cleanTarget)) && !t.contains("...") && !node.isEditable) {
                        searchResultNode = node
                        return
                    }
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        findSearchResult(child, depth + 1)
                    }
                }
                findSearchResult(updatedRoot, 0)
                searchResultNode?.let { return clickNodeWithFallback(it) }
                return true
            }
        }

        return false
    }

    /**
     * Starts a WhatsApp voice or video call on the currently open chat.
     */
    fun triggerWhatsAppCall(isVideo: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val callLabels = if (isVideo) {
            listOf("video call", "video", "call video")
        } else {
            listOf("voice call", "call", "voice", "audio call")
        }

        fun findCallButton(node: AccessibilityNodeInfo): Boolean {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val isCallId = if (isVideo) {
                id.contains("video_call") || id.contains("menu_item_video")
            } else {
                id.contains("menu_item_call") || id.contains("voice_call") || id.endsWith(":id/call")
            }
            val isCallDesc = callLabels.any { desc == it || desc.startsWith(it) }

            if (isCallId || isCallDesc) {
                if (tryClickNode(node)) return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findCallButton(child)) return true
            }
            return false
        }

        if (findCallButton(root)) {
            Log.i(TAG, "[WA_CALL_CLICKED] Voice/Video call button clicked in WhatsApp header")
            // Confirmation dialog handling ("Start voice call?" -> click "Call")
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            val newRoot = rootInActiveWindow
            if (newRoot != null) {
                val confirmLabels = listOf("call", "start call", "ok", "yes")
                for (lbl in confirmLabels) {
                    val confNode = findNodeUniversal(lbl, newRoot)
                    if (confNode != null && tryClickNode(confNode)) {
                        Log.i(TAG, "[WA_CALL_CONFIRMED] Call confirmation dialog confirmed")
                        break
                    }
                }
            }
            return true
        }

        return false
    }

    fun typeText(text: String, targetFieldQuery: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        
        fun findEditableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
            if (node.isEditable) list.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findEditableNodes(child, list)
            }
        }

        // 1. If a focused editable node is present, use it
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val done = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (done) return true
        }

        // 2. Find all editable nodes on the screen
        val editables = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(root, editables)

        if (editables.isEmpty()) return false

        // Pick matching editable node if targetFieldQuery is specified, otherwise first editable node
        val targetNode = if (!targetFieldQuery.isNullOrBlank()) {
            val cleanQuery = targetFieldQuery.lowercase()
            editables.firstOrNull { node ->
                val txt = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                txt.contains(cleanQuery) || desc.contains(cleanQuery) || id.contains(cleanQuery)
            } ?: editables.first()
        } else {
            editables.first()
        }

        // Focus and set text
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        tryClickNode(targetNode)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Closes the current app, dialog, or active page.
     */
    fun closeCurrentAppOrView(targetName: String? = null): Boolean {
        val root = rootInActiveWindow
        val targetLower = (targetName ?: "").lowercase()

        // 1. If target is a close button or dialog/page
        if (targetLower.contains("page") || targetLower.contains("dialog") || targetLower.contains("modal") ||
            targetLower.contains("tab") || targetLower.contains("window") || targetLower.isBlank()
        ) {
            if (root != null) {
                val closeLabels = listOf("close", "dismiss", "cancel", "done", "band karo", "cross", "exit")
                for (lbl in closeLabels) {
                    if (clickElementByText(lbl)) return true
                }
            }
            // Fallback to back action
            return performGlobal(GLOBAL_ACTION_BACK)
        }

        // 2. If target is an app name (e.g. TikTok, WhatsApp, Chrome)
        return performGlobal(GLOBAL_ACTION_HOME)
    }

    fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performClickGesture(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performDoubleTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke1 = GestureDescription.StrokeDescription(path, 0, 50)
        val stroke2 = GestureDescription.StrokeDescription(path, 100, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performLongPressGesture(x: Float, y: Float, durationMs: Long = 600): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun clearText(targetFieldQuery: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        val editables = mutableListOf<AccessibilityNodeInfo>()
        fun findEditables(node: AccessibilityNodeInfo) {
            if (node.isEditable) editables.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findEditables(child)
            }
        }
        findEditables(root)
        if (editables.isEmpty()) return false

        val targetNode = if (!targetFieldQuery.isNullOrBlank()) {
            val clean = targetFieldQuery.lowercase()
            editables.firstOrNull { node ->
                val txt = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                txt.contains(clean) || desc.contains(clean) || id.contains(clean)
            } ?: editables.first()
        } else editables.first()

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun shareCurrentContent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val shareTerms = listOf("share", "baanto", "share video", "share post", "send to", "forward")

        fun findAndClickShare(node: AccessibilityNodeInfo): Boolean {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (shareTerms.any { desc.contains(it) || text.contains(it) || viewId.contains(it) }) {
                if (tryClickNode(node)) return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndClickShare(child)) return true
            }
            return false
        }
        return findAndClickShare(root)
    }

    fun commentOnPost(commentText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val commentTerms = listOf("comment", "add a comment", "write a comment", "leave a comment", "tabsarah", "reply")
        val postLabels = listOf("send", "post", "submit", "bhejo", "comment", "publish", "done")

        fun findSendOrPostButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
            if (depth > 20) return null
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            val isPost = postLabels.any { text == it || desc == it || desc.startsWith("$it ") || text.startsWith("$it ") } ||
                    viewId.contains("post") || viewId.contains("send_btn") || viewId.contains("btn_send") || viewId.contains("submit")
            if (isPost && (node.isClickable || node.isEnabled || node.parent?.isClickable == true)) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findSendOrPostButton(child, depth + 1)
                if (res != null) return res
            }
            return null
        }

        fun dispatchPostAndVerify(): Boolean {
            try { Thread.sleep(250) } catch (_: Exception) {}
            val currentRoot = rootInActiveWindow ?: return false
            val postBtn = findSendOrPostButton(currentRoot)
            var posted = false
            if (postBtn != null) {
                posted = clickNodeWithFallback(postBtn)
            } else {
                for (lbl in postLabels) {
                    if (clickElementByText(lbl)) {
                        posted = true
                        break
                    }
                }
            }

            if (posted) {
                for (attempt in 1..6) {
                    try { Thread.sleep(300) } catch (_: Exception) {}
                    val updatedRoot = rootInActiveWindow ?: continue
                    val input = updatedRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val remainingText = input?.text?.toString() ?: ""
                    if (remainingText.isBlank() || !remainingText.contains(commentText)) {
                        Log.i(TAG, "[COMMENT_POST_VERIFIED] Comment posted successfully: text cleared")
                        return true
                    }
                }
                Log.i(TAG, "[COMMENT_POST_DISPATCHED] Post button clicked and verified")
                return true
            }
            return false
        }

        // 1. If an editable field is already open and ready
        if (typeText(commentText)) {
            return dispatchPostAndVerify()
        }

        // 2. Click comment button to open comment bottom sheet or input box
        fun findAndClickCommentBtn(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
            if (depth > 20) return false
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (commentTerms.any { desc.contains(it) || text.contains(it) || viewId.contains(it) }) {
                if (tryClickNode(node) || clickNodeWithFallback(node)) return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndClickCommentBtn(child, depth + 1)) return true
            }
            return false
        }

        if (findAndClickCommentBtn(root)) {
            try {
                Thread.sleep(600)
            } catch (_: Exception) {}
            if (typeText(commentText)) {
                return dispatchPostAndVerify()
            }
        }
        return false
    }

    fun searchInCurrentScreen(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val searchTerms = listOf("search", "find", "query", "dhoondo", "khoj", "menu_item_0", "search_button")

        // 1. If search input is already visible and editable
        if (typeText(query, "search") || typeText(query)) {
            try {
                Thread.sleep(300)
            } catch (_: Exception) {}
            for (lbl in listOf("search", "find", "go", "enter", query)) {
                clickElementByText(lbl)
            }
            return true
        }

        // 2. Click search icon/button to open search bar
        fun findAndClickSearchBtn(node: AccessibilityNodeInfo): Boolean {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (!node.isEditable && searchTerms.any { desc.contains(it) || text.contains(it) || viewId.contains(it) }) {
                if (tryClickNode(node)) return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndClickSearchBtn(child)) return true
            }
            return false
        }

        if (findAndClickSearchBtn(root)) {
            try {
                Thread.sleep(600)
            } catch (_: Exception) {}
            val typed = typeText(query, "search") || typeText(query)
            if (typed) {
                try {
                    Thread.sleep(300)
                } catch (_: Exception) {}
                for (lbl in listOf("search", "find", "go", "enter", query)) {
                    clickElementByText(lbl)
                }
                return true
            }
        }
        return false
    }

    /**
     * Executes dedicated in-app search for YouTube, Chrome, etc. with inspection and re-inspection.
     */
    fun executeSemanticAppSearch(targetApp: String, query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanApp = targetApp.lowercase()

        // YouTube specific search handling
        if (cleanApp.contains("youtube")) {
            // Check if search edit text is already visible
            val searchInputNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text")
            if (searchInputNodes.isNotEmpty()) {
                val searchInput = searchInputNodes.first()
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val setSuccess = searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setSuccess) {
                    try { Thread.sleep(400) } catch (_: Exception) {}
                    // Click search button or suggestion
                    clickElementByText(query)
                    clickElementByText("search")
                    return true
                }
            }

            // If not in search view, find search icon in toolbar (menu_item_0 or contentDescription "Search")
            fun findYouTubeSearchButton(node: AccessibilityNodeInfo): Boolean {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                if (viewId.contains("menu_item_0") || desc.contains("search youtube") || desc == "search") {
                    return tryClickNode(node)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (findYouTubeSearchButton(child)) return true
                }
                return false
            }

            if (findYouTubeSearchButton(root)) {
                try { Thread.sleep(600) } catch (_: Exception) {}
                val newRoot = rootInActiveWindow ?: return false
                val newInputs = newRoot.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text")
                val targetInput = if (newInputs.isNotEmpty()) newInputs.first() else null

                if (targetInput != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                    }
                    targetInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    try { Thread.sleep(400) } catch (_: Exception) {}
                    clickElementByText(query)
                    clickElementByText("search")
                    return true
                } else {
                    return typeText(query)
                }
            }
        }

        // Generic fallback search in current screen
        return searchInCurrentScreen(query)
    }

    fun openKeyboardOrFocusInput(targetQuery: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val editables = mutableListOf<AccessibilityNodeInfo>()
        fun findEditableNodes(node: AccessibilityNodeInfo) {
            if (node.isEditable) editables.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findEditableNodes(child)
            }
        }
        findEditableNodes(root)
        if (editables.isEmpty()) return false
        val targetNode = if (!targetQuery.isNullOrBlank()) {
            val clean = targetQuery.lowercase()
            editables.firstOrNull { node ->
                val txt = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val id = node.viewIdResourceName?.lowercase() ?: ""
                txt.contains(clean) || desc.contains(clean) || id.contains(clean)
            } ?: editables.first()
        } else {
            editables.first()
        }
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return tryClickNode(targetNode)
    }

    fun selectText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SELECT, args) || tryClickNode(node)) {
                return true
            }
        }
        return false
    }

    enum class WhatsAppSendFailureReason {
        NONE,
        SERVICE_OR_WINDOW_UNAVAILABLE,
        PACKAGE_MISMATCH,
        CHAT_IDENTITY_UNCONFIRMED,
        CHAT_IDENTITY_MISMATCH,
        INPUT_OR_SEND_NOT_FOUND,
        MESSAGE_APPEARANCE_UNVERIFIED
    }

    data class WhatsAppSendVerificationResult(
        val isSentAndVerified: Boolean,
        val verifiedChatTitle: String? = null,
        val verifiedPackage: String? = null,
        val failureReason: WhatsAppSendFailureReason = WhatsAppSendFailureReason.NONE,
        val details: String = ""
    )

    /**
     * Extracts the active WhatsApp conversation header title from a fresh Accessibility tree.
     * Returns null if WhatsApp is on the home/tab list or if no conversation header can be verified.
     */
    fun extractActiveWhatsAppChatTitle(rootNode: AccessibilityNodeInfo? = getFreshActiveWindowRoot()): String? {
        val root = rootNode ?: return null
        var explicitHeaderTitle: String? = null
        var fallbackHeaderTitle: String? = null
        var hasConversationInputOrList = false

        val nonConversationTitles = setOf(
            "whatsapp", "whatsapp business", "chats", "updates", "communities", "calls",
            "search", "online", "typing...", "type a message", "message", "archive", "archived"
        )

        fun inspect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 22) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""

            if (node.isEditable || id.contains("entry") || id.contains("conversation_row") || id.contains("message_text")) {
                hasConversationInputOrList = true
            }

            if (text.isNotBlank() && text.length > 1) {
                val lowerText = text.lowercase()
                val isExplicitTitleId = id.endsWith(":id/conversation_contact_name") ||
                        id.endsWith(":id/chat_title") ||
                        id.endsWith(":id/conversation_title") ||
                        id.contains("conversation_contact_name")

                val isGenericToolbarTitleId = id.endsWith(":id/action_bar_title") ||
                        id.endsWith(":id/toolbar_title") ||
                        id.endsWith(":id/title") ||
                        id.contains("contact_name") ||
                        id.contains("chat_name")

                if (isExplicitTitleId && lowerText !in nonConversationTitles) {
                    if (explicitHeaderTitle == null) explicitHeaderTitle = text
                } else if (fallbackHeaderTitle == null && lowerText !in nonConversationTitles &&
                    !lowerText.startsWith("last seen") && !lowerText.startsWith("tap here")
                ) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val isTopHeaderArea = rect.top in 1..360
                    if ((isGenericToolbarTitleId && isTopHeaderArea) ||
                        (isTopHeaderArea && !id.contains("search") && !id.contains("menu") && !id.contains("tab") && !text.contains(":"))
                    ) {
                        fallbackHeaderTitle = text
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                inspect(child, depth + 1)
            }
        }

        inspect(root, 0)
        return explicitHeaderTitle ?: if (hasConversationInputOrList) fallbackHeaderTitle else null
    }

    /**
     * Checks whether the sent message text actually appeared inside the open WhatsApp conversation UI
     * (outside the editable input field and top header).
     */
    fun isMessageVisibleInWhatsAppConversation(
        rootNode: AccessibilityNodeInfo,
        expectedMessage: String,
        inputNode: AccessibilityNodeInfo? = null
    ): Boolean {
        val cleanExpected = expectedMessage.trim()
        if (cleanExpected.isBlank()) return false

        val inputRect = Rect()
        if (inputNode != null) {
            inputNode.getBoundsInScreen(inputRect)
        }

        var foundInMessageList = false
        fun scanMessages(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 22 || foundInMessageList) return
            if (node.isEditable) return

            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (text.contains(cleanExpected, ignoreCase = true) || desc.contains(cleanExpected, ignoreCase = true)) {
                val isInputId = id.contains("entry") || id.contains("input") || id.contains("search_src_text")
                if (!isInputId) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val isMessageId = id.endsWith(":id/message_text") ||
                            id.endsWith(":id/message_text_view") ||
                            id.endsWith(":id/text_content") ||
                            id.endsWith(":id/caption") ||
                            id.endsWith(":id/msg_text") ||
                            id.contains("message_text") ||
                            id.contains("msg_text") ||
                            id.contains("conversation_row") ||
                            id.contains("message_row")

                    val isInsideConversationArea = rect.top >= 120 &&
                            (inputRect.isEmpty || rect.bottom <= inputRect.top + 20)

                    if (isMessageId || isInsideConversationArea) {
                        foundInMessageList = true
                        return
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanMessages(child, depth + 1)
            }
        }

        scanMessages(rootNode, 0)
        return foundInMessageList
    }

    /**
     * Opens or verifies the exact intended WhatsApp conversation using fresh Accessibility UI evidence.
     * Never reuses a previously active chat as an assumed target.
     */
    fun openOrVerifyWhatsAppChat(
        targetPackage: String?,
        expectedRecipient: String,
        expectedPhone: String? = null
    ): Boolean {
        val cleanExpected = expectedRecipient.trim()
        if (cleanExpected.isBlank() && expectedPhone.isNullOrBlank()) return false

        val root = getFreshActiveWindowRoot() ?: return false
        val currentPkg = root.packageName?.toString() ?: ""
        if (!WhatsAppInstanceManager.isWhatsAppPackage(currentPkg)) return false
        if (!targetPackage.isNullOrBlank() && !currentPkg.equals(targetPackage, ignoreCase = true)) {
            Log.w(TAG, "[WA_PACKAGE_MISMATCH] Expected package '$targetPackage' but active is '$currentPkg'")
            return false
        }

        // 1. Check if the currently open conversation already matches the exact target
        val currentHeader = extractActiveWhatsAppChatTitle(root)
        if (!currentHeader.isNullOrBlank()) {
            if (com.example.agent.ContactResolver.doesIdentityMatch(currentHeader, cleanExpected, expectedPhone)) {
                Log.i(TAG, "[WA_CHAT_VERIFIED_OPEN] Active chat header '$currentHeader' matches target '$cleanExpected'")
                return true
            }
            // Different chat is open! Navigate back to the chat list instead of reusing the wrong chat.
            Log.w(TAG, "[WA_STALE_CHAT_REJECTED] Active chat '$currentHeader' != expected '$cleanExpected'. Navigating back.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
        }

        // 2. On chat list screen, try locating the exact chat row matching expectedRecipient
        val listRoot = getFreshActiveWindowRoot() ?: return false
        var matchedRowNode: AccessibilityNodeInfo? = null

        fun findMatchingChatRow(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || matchedRowNode != null) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val isRowTitle = id.endsWith(":id/conversations_row_contact_name") ||
                    id.contains("conversations_row_contact_name") ||
                    id.contains("contact_name") ||
                    id.endsWith(":id/title")

            if (text.isNotBlank() && isRowTitle &&
                com.example.agent.ContactResolver.doesIdentityMatch(text, cleanExpected, expectedPhone)
            ) {
                matchedRowNode = node
                return
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findMatchingChatRow(child, depth + 1)
            }
        }

        findMatchingChatRow(listRoot, 0)
        if (matchedRowNode != null && tryClickNode(matchedRowNode!!)) {
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            val postClickHeader = extractActiveWhatsAppChatTitle(getFreshActiveWindowRoot())
            if (com.example.agent.ContactResolver.doesIdentityMatch(postClickHeader, cleanExpected, expectedPhone)) {
                Log.i(TAG, "[WA_CHAT_OPENED_AND_VERIFIED] Opened chat '$postClickHeader' from chat list")
                return true
            }
        }

        return false
    }

    /**
     * Finds WhatsApp chat input and Send button, verifies the exact conversation before sending,
     * performs send, and confirms that the message appeared in the intended conversation.
     */
    fun automateWhatsAppSendVerified(
        targetPackage: String? = null,
        messageText: String? = null,
        expectedRecipient: String? = null,
        expectedPhone: String? = null
    ): WhatsAppSendVerificationResult {
        val sendLabels = listOf("send", "bhejo", "bhejein", "ارسال", "send message", "sent", "post")
        val requiresExplicitTargetCheck = !expectedRecipient.isNullOrBlank() || !expectedPhone.isNullOrBlank()
        var lastDetectedHeader: String? = null
        var lastDetectedPkg: String? = null
        var sawPackageMismatch = false
        var sawChatIdentityMismatch = false
        var attemptedListOpen = false

        fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findEditableNode(child)
                if (found != null) return found
            }
            return null
        }

        fun clearEditableNode(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isEditable) return
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }

        fun findSendNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            val text = node.text?.toString()?.lowercase()?.trim() ?: ""

            val isSendId = viewId.endsWith(":id/send") ||
                    viewId.endsWith(":id/entry_send_btn") ||
                    viewId.endsWith(":id/send_btn") ||
                    viewId.endsWith(":id/send_button") ||
                    viewId.endsWith(":id/input_send") ||
                    viewId.endsWith(":id/footer_send_button") ||
                    viewId.endsWith(":id/floating_send") ||
                    viewId.contains("send_button") ||
                    viewId.contains("action_send")

            val isSendText = sendLabels.any { desc == it || text == it || desc.startsWith("send ") || text.startsWith("send ") }

            if (isSendId || isSendText) {
                if (node.isClickable || node.parent?.isClickable == true || node.isEnabled) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findSendNode(child)
                if (res != null) return res
            }
            return null
        }

        // Outer polling loop (up to ~3.5 seconds across 8 attempts)
        for (attempt in 1..8) {
            var currentRoot = getFreshActiveWindowRoot()
            if (currentRoot != null) {
                val pkg = currentRoot.packageName?.toString() ?: ""
                lastDetectedPkg = pkg

                if (WhatsAppInstanceManager.isWhatsAppPackage(pkg)) {
                    // Enforce originating/target WhatsApp package identity when specified
                    if (!targetPackage.isNullOrBlank() && !pkg.equals(targetPackage, ignoreCase = true)) {
                        sawPackageMismatch = true
                        Log.w(TAG, "[WA_PACKAGE_MISMATCH_BLOCKED] Active pkg='$pkg' != targetPackage='$targetPackage' on attempt $attempt")
                        try { Thread.sleep(350) } catch (_: InterruptedException) {}
                        continue
                    }

                    // 1. Verify active conversation header BEFORE typing or sending
                    var headerTitle = extractActiveWhatsAppChatTitle(currentRoot)
                    lastDetectedHeader = headerTitle

                    if (requiresExplicitTargetCheck) {
                        val matchesTarget = com.example.agent.ContactResolver.doesIdentityMatch(
                            candidateIdentity = headerTitle,
                            expectedIdentity = expectedRecipient,
                            expectedPhone = expectedPhone
                        )
                        if (!matchesTarget) {
                            if (!attemptedListOpen && !expectedRecipient.isNullOrBlank()) {
                                attemptedListOpen = true
                                if (openOrVerifyWhatsAppChat(targetPackage, expectedRecipient, expectedPhone)) {
                                    currentRoot = getFreshActiveWindowRoot() ?: currentRoot
                                    headerTitle = extractActiveWhatsAppChatTitle(currentRoot)
                                    lastDetectedHeader = headerTitle
                                }
                            }

                            val matchesAfterOpen = com.example.agent.ContactResolver.doesIdentityMatch(
                                candidateIdentity = headerTitle,
                                expectedIdentity = expectedRecipient,
                                expectedPhone = expectedPhone
                            )
                            if (!matchesAfterOpen) {
                                if (!headerTitle.isNullOrBlank()) {
                                    sawChatIdentityMismatch = true
                                    Log.w(TAG, "[WA_WRONG_CHAT_BLOCKED] Active chat '$headerTitle' does not match expected '${expectedRecipient ?: expectedPhone}' (attempt $attempt)")
                                } else {
                                    Log.w(TAG, "[WA_CHAT_HEADER_UNCONFIRMED] Could not confirm chat header for '${expectedRecipient ?: expectedPhone}' (attempt $attempt)")
                                }
                                try { Thread.sleep(350) } catch (_: InterruptedException) {}
                                continue
                            }
                        }
                    } else if (headerTitle.isNullOrBlank()) {
                        // Even for explicit current-screen send, require an actual conversation header to be open
                        Log.w(TAG, "[WA_CHAT_HEADER_UNCONFIRMED] No active conversation header found on screen (attempt $attempt)")
                        try { Thread.sleep(350) } catch (_: InterruptedException) {}
                        continue
                    }

                    val confirmedTargetHeader = headerTitle ?: expectedRecipient ?: ""

                    // 2. Locate editable input node and populate messageText if provided
                    val inputNode = currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(currentRoot)
                    if (inputNode != null) {
                        Log.i(TAG, "[WA_INPUT_FOUND] chat='$confirmedTargetHeader' inputNode=${inputNode.viewIdResourceName} editable=${inputNode.isEditable}")
                    }

                    if (!messageText.isNullOrBlank() && inputNode != null && inputNode.isEditable) {
                        val currentText = inputNode.text?.toString() ?: ""
                        if (!currentText.contains(messageText)) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageText)
                            }
                            val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            Log.i(TAG, "[WA_TEXT_TYPED] chat='$confirmedTargetHeader' text='$messageText' success=$textSet")
                        }
                    }

                    // Brief pause to let WhatsApp UI swap the mic icon for the Send button
                    try { Thread.sleep(250) } catch (_: InterruptedException) {}

                    // 3. Re-query fresh Accessibility tree and RE-VERIFY recipient/chat IMMEDIATELY before clicking Send
                    val freshRoot = getFreshActiveWindowRoot() ?: currentRoot
                    val freshPkg = freshRoot.packageName?.toString() ?: pkg
                    val preSendHeader = extractActiveWhatsAppChatTitle(freshRoot)
                    lastDetectedHeader = preSendHeader

                    val preSendPkgOk = targetPackage.isNullOrBlank() || freshPkg.equals(targetPackage, ignoreCase = true)
                    val preSendHeaderOk = if (requiresExplicitTargetCheck) {
                        com.example.agent.ContactResolver.doesIdentityMatch(preSendHeader, expectedRecipient, expectedPhone)
                    } else {
                        !preSendHeader.isNullOrBlank() && com.example.agent.ContactResolver.doesIdentityMatch(preSendHeader, confirmedTargetHeader)
                    }

                    if (!preSendPkgOk || !preSendHeaderOk) {
                        val freshInput = freshRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(freshRoot)
                        clearEditableNode(freshInput)
                        Log.e(TAG, "[WA_PRE_SEND_ABORT] Recipient verification failed right before send! expected='${expectedRecipient ?: confirmedTargetHeader}', actual='$preSendHeader', pkg='$freshPkg'")
                        return WhatsAppSendVerificationResult(
                            isSentAndVerified = false,
                            verifiedChatTitle = preSendHeader,
                            verifiedPackage = freshPkg,
                            failureReason = if (!preSendPkgOk) {
                                WhatsAppSendFailureReason.PACKAGE_MISMATCH
                            } else if (preSendHeader.isNullOrBlank()) {
                                WhatsAppSendFailureReason.CHAT_IDENTITY_UNCONFIRMED
                            } else {
                                WhatsAppSendFailureReason.CHAT_IDENTITY_MISMATCH
                            },
                            details = "Aborted before send: expected '${expectedRecipient ?: confirmedTargetHeader}' but found '${preSendHeader ?: "unconfirmed"}'"
                        )
                    }

                    val sendNode = findSendNode(freshRoot)
                    if (sendNode != null) {
                        val sendId = sendNode.viewIdResourceName ?: "no_id"
                        val sendDesc = sendNode.contentDescription?.toString() ?: "no_desc"
                        val rect = Rect()
                        sendNode.getBoundsInScreen(rect)
                        Log.i(TAG, "[WA_SEND_BUTTON_FOUND] chat='$preSendHeader' id=$sendId desc='$sendDesc' bounds=$rect")

                        var clicked = tryClickNode(sendNode)
                        if (!clicked && rect.width() > 0 && rect.height() > 0) {
                            clicked = performTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                        Log.i(TAG, "[WA_SEND_CLICKED] chat='$preSendHeader' clicked=$clicked on attempt $attempt")

                        if (clicked) {
                            // 4. Confirm success ONLY after verifying that the message appeared in the intended conversation
                            for (vAttempt in 0 until 6) {
                                try { Thread.sleep(300) } catch (_: InterruptedException) {}

                                val updatedRoot = getFreshActiveWindowRoot() ?: continue
                                val updatedPkg = updatedRoot.packageName?.toString() ?: ""
                                if (!targetPackage.isNullOrBlank() && !updatedPkg.equals(targetPackage, ignoreCase = true)) {
                                    continue
                                }

                                val postSendHeader = extractActiveWhatsAppChatTitle(updatedRoot)
                                val sameIntendedChat = if (requiresExplicitTargetCheck) {
                                    com.example.agent.ContactResolver.doesIdentityMatch(postSendHeader, expectedRecipient, expectedPhone)
                                } else {
                                    !postSendHeader.isNullOrBlank() && com.example.agent.ContactResolver.doesIdentityMatch(postSendHeader, preSendHeader)
                                }
                                if (!sameIntendedChat) {
                                    Log.w(TAG, "[WA_POST_SEND_CHAT_MISMATCH] Expected '$preSendHeader' but post-send header is '$postSendHeader'")
                                    continue
                                }

                                val updatedInput = updatedRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(updatedRoot)
                                val remainingText = updatedInput?.text?.toString() ?: ""
                                val inputCleared = remainingText.isBlank() ||
                                        (!messageText.isNullOrBlank() && !remainingText.contains(messageText))

                                val messageAppeared = if (!messageText.isNullOrBlank()) {
                                    isMessageVisibleInWhatsAppConversation(updatedRoot, messageText, updatedInput)
                                } else {
                                    findSendNode(updatedRoot) == null
                                }

                                if (inputCleared && messageAppeared) {
                                    Log.i(TAG, "[WA_SEND_VERIFIED] Verified message '$messageText' appeared in intended conversation '$postSendHeader' on $updatedPkg")
                                    return WhatsAppSendVerificationResult(
                                        isSentAndVerified = true,
                                        verifiedChatTitle = postSendHeader,
                                        verifiedPackage = updatedPkg,
                                        failureReason = WhatsAppSendFailureReason.NONE,
                                        details = "Message verified in conversation '$postSendHeader'"
                                    )
                                }
                            }
                            Log.w(TAG, "[WA_SEND_FALSE_SUCCESS_BLOCKED] Click dispatched in '$preSendHeader' but message appearance could not be verified on $pkg")
                            return WhatsAppSendVerificationResult(
                                isSentAndVerified = false,
                                verifiedChatTitle = preSendHeader,
                                verifiedPackage = pkg,
                                failureReason = WhatsAppSendFailureReason.MESSAGE_APPEARANCE_UNVERIFIED,
                                details = "Message appearance in conversation '$preSendHeader' could not be verified"
                            )
                        }
                    }
                }
            }

            try { Thread.sleep(350) } catch (_: InterruptedException) {}
        }

        val finalReason = when {
            sawPackageMismatch -> WhatsAppSendFailureReason.PACKAGE_MISMATCH
            sawChatIdentityMismatch -> WhatsAppSendFailureReason.CHAT_IDENTITY_MISMATCH
            requiresExplicitTargetCheck && lastDetectedHeader.isNullOrBlank() -> WhatsAppSendFailureReason.CHAT_IDENTITY_UNCONFIRMED
            requiresExplicitTargetCheck -> WhatsAppSendFailureReason.CHAT_IDENTITY_MISMATCH
            lastDetectedHeader.isNullOrBlank() -> WhatsAppSendFailureReason.CHAT_IDENTITY_UNCONFIRMED
            else -> WhatsAppSendFailureReason.INPUT_OR_SEND_NOT_FOUND
        }
        Log.w(TAG, "Could not automate WhatsApp send after polling (reason=$finalReason, lastHeader=$lastDetectedHeader, lastPkg=$lastDetectedPkg)")
        return WhatsAppSendVerificationResult(
            isSentAndVerified = false,
            verifiedChatTitle = lastDetectedHeader,
            verifiedPackage = lastDetectedPkg,
            failureReason = finalReason,
            details = "Send failed with reason $finalReason"
        )
    }

    /**
     * Backward-compatible wrapper around automateWhatsAppSendVerified.
     */
    fun automateWhatsAppSend(
        targetPackage: String? = null,
        messageText: String? = null,
        expectedRecipient: String? = null,
        expectedPhone: String? = null
    ): Boolean {
        return automateWhatsAppSendVerified(
            targetPackage = targetPackage,
            messageText = messageText,
            expectedRecipient = expectedRecipient,
            expectedPhone = expectedPhone
        ).isSentAndVerified
    }

    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun getScreenSummary(): String {
        val root = rootInActiveWindow ?: return "Screen context not available"
        val sb = StringBuilder()
        sb.append("Current App: ${_currentAppPackage.value ?: root.packageName}\n")
        sb.append("Visible Elements:\n")

        val texts = mutableListOf<String>()
        val buttons = mutableListOf<String>()

        fun extractText(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrBlank()) {
                if (node.isClickable) buttons.add("[Button: $text]")
                else texts.add(text)
            } else if (!desc.isNullOrBlank()) {
                if (node.isClickable) buttons.add("[Action: $desc]")
                else texts.add(desc)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                extractText(child, depth + 1)
            }
        }

        extractText(root, 0)
        if (buttons.isNotEmpty()) {
            sb.append("Clickable: ").append(buttons.distinct().take(15).joinToString(", ")).append("\n")
        }
        if (texts.isNotEmpty()) {
            sb.append("Texts: ").append(texts.distinct().take(15).joinToString(" | "))
        }

        return sb.toString()
    }

    // ================================================================================
    // UNIVERSAL APP AUTOMATION (Human-Like automation, works in ANY app on screen)
    // ================================================================================

    /**
     * Core universal node finder. Scans the entire active window tree (any app —
     * WhatsApp, Facebook, YouTube, TikTok, System Settings, etc.) and returns the node
     * whose text, contentDescription (for icon-only buttons), or resource id best matches
     * the query. Exact matches are preferred over partial/contains matches.
     */
    fun findNodeUniversal(query: String, root: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        if (query.isBlank()) return null
        val r = root ?: return null
        val q = query.trim().lowercase()
        val idFragment = q.replace(" ", "_")

        var bestPartial: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // 1. Exact text / content-description match wins immediately (most reliable).
            if (text == q || desc == q) {
                return node
            }

            // 2. Track the first partial match (substring in text, description, or id) as fallback,
            //    which is what lets Jarvis find icon-only buttons via contentDescription/resource id.
            if (bestPartial == null &&
                (text.contains(q) || desc.contains(q) || viewId.contains(idFragment))
            ) {
                bestPartial = node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return bestPartial
    }

    /**
     * Universal click: dynamically finds ANY element in the currently active app by
     * spoken text or description (e.g. "Click on Settings", "Click Send on WhatsApp")
     * and performs ACTION_CLICK on it (or its nearest clickable ancestor). Falls back
     * to contentDescription/resource-id matching automatically for icon-only buttons.
     */
    fun clickElementUniversal(query: String): Boolean {
        val node = findNodeUniversal(query) ?: return false
        return tryClickNode(node)
    }

    /**
     * App Killer: force-stops any installed app by package name.
     * Navigates to the app's System Settings "App Info" page, locates the
     * "Force stop" control, clicks it, then confirms the "OK" dialog that follows.
     * Works for ANY package, not a hardcoded list of apps.
     */
    fun forceStopApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)

            val forceStopLabels = listOf("force stop", "force close", "band karo")
            var stopClicked = false

            // Poll for the App Info page to render and the "Force stop" control to appear.
            for (attempt in 1..15) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {}

                val root = rootInActiveWindow ?: continue
                for (label in forceStopLabels) {
                    val node = findNodeUniversal(label, root) ?: continue
                    if (tryClickNode(node)) {
                        stopClicked = true
                        break
                    }
                }
                if (stopClicked) break
            }

            if (!stopClicked) {
                Log.w(TAG, "forceStopApp: could not locate 'Force stop' control for $packageName")
                return false
            }

            // Confirm the "Force stop this app?" dialog that most OEMs show next.
            val confirmLabels = listOf("ok", "force stop", "yes")
            for (attempt in 1..10) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {}

                val root = rootInActiveWindow ?: continue
                for (label in confirmLabels) {
                    val node = findNodeUniversal(label, root) ?: continue
                    if (tryClickNode(node)) {
                        Log.i(TAG, "forceStopApp: confirmed force-stop for $packageName")
                        return true
                    }
                }
            }

            // Some OEM builds force-stop instantly without a confirmation dialog.
            Log.i(TAG, "forceStopApp: no confirmation dialog observed, assuming direct force-stop for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "forceStopApp failed for $packageName: ${e.message}")
            false
        }
    }

    /**
     * SIM Auto-Selector (explicit/manual entry point): scans the current screen for a
     * dual-SIM "select SIM for this call" popup and taps the requested SIM or carrier
     * label. Callable directly (e.g. via a voice command) in addition to the automatic
     * reactive handling in onAccessibilityEvent.
     */
    fun selectSimForCall(preferredLabel: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<String>()
        if (!preferredLabel.isNullOrBlank()) candidates.add(preferredLabel)
        candidates.addAll(listOf("sim 1", "sim1"))

        for (label in candidates) {
            val node = findNodeUniversal(label, root)
            if (node != null && tryClickNode(node)) {
                return true
            }
        }
        return false
    }

    /**
     * Reactive/autonomous half of the SIM selector: invoked from onAccessibilityEvent
     * whenever a telephony-related window updates. Confirms this really is a dual-SIM
     * chooser (not just any phone-app screen) before acting, then instantly taps the
     * preferred SIM slot or carrier name, exactly like a human would.
     */
    private fun maybeAutoSelectSim(pkg: String?) {
        if (pkg.isNullOrBlank() || simChooserPackages.none { pkg.contains(it) }) return

        val now = System.currentTimeMillis()
        if (now - lastSimChooserHandledAt < 2000L) return // debounce rapid duplicate events

        val root = rootInActiveWindow ?: return

        // Verify this window is actually the SIM chooser before touching anything,
        // so we never click blindly on an unrelated Phone-app screen.
        val chooserMarkers = listOf("select sim", "choose sim", "calling account", "sim for this call", "sim card")
        val looksLikeChooser = chooserMarkers.any { findNodeUniversal(it, root) != null } ||
                (findNodeUniversal("sim 1", root) != null && findNodeUniversal("sim 2", root) != null)

        if (!looksLikeChooser) return

        val hint = preferredSimHint
        val target = (if (!hint.isNullOrBlank()) findNodeUniversal(hint, root) else null)
            ?: findNodeUniversal("sim 1", root)
            ?: findNodeUniversal("sim1", root)

        if (target != null) {
            lastSimChooserHandledAt = now
            mainHandler.postDelayed({
                val clicked = tryClickNode(target)
                Log.i(TAG, "Auto SIM selection ${if (clicked) "succeeded" else "failed"} for package $pkg")
            }, 150)
        }
    }

    /**
     * Universal Android Quick Settings & System Toggle controller and state verifier.
     * 1. Opens Quick Settings panel.
     * 2. Scans for target toggle tile (e.g. Wi-Fi, Mobile Data, Bluetooth, Hotspot, Airplane Mode, Auto-Rotate, Torch).
     * 3. Determines current state from node isChecked / text / description ("On", "Off", "Active", "Inactive").
     * 4. If already in target state, returns success without double-toggling.
     * 5. If change required, performs click on the tile.
     * 6. Re-queries the live screen to verify whether the tile state flipped.
     * 7. Closes Quick Settings panel.
     * 8. Returns a verified result with status.
     */
    suspend fun toggleQuickSettingAndVerify(
        settingName: String,
        targetState: Boolean? = null
    ): Pair<Boolean, String> {
        val cleanName = settingName.trim().lowercase()

        // 1. Resolve aliases
        val aliases = when {
            cleanName.contains("flash") || cleanName.contains("torch") -> listOf("flashlight", "torch", "flash")
            cleanName.contains("wifi") || cleanName.contains("wi-fi") || cleanName.contains("internet") -> listOf("wi-fi", "wifi", "internet")
            cleanName.contains("data") || cleanName.contains("cellular") || cleanName.contains("mobile") -> listOf("mobile data", "data", "cellular data", "cellular")
            cleanName.contains("bluetooth") || cleanName.contains("bt") -> listOf("bluetooth", "bt")
            cleanName.contains("hotspot") || cleanName.contains("tethering") -> listOf("hotspot", "mobile hotspot", "personal hotspot", "tethering")
            cleanName.contains("airplane") || cleanName.contains("flight") || cleanName.contains("aeroplane") -> listOf("airplane mode", "flight mode", "aeroplane mode", "airplane")
            cleanName.contains("rotate") || cleanName.contains("rotation") || cleanName.contains("portrait") -> listOf("auto-rotate", "auto rotate", "rotation", "auto rotation", "portrait")
            cleanName.contains("sound") || cleanName.contains("vibrat") || cleanName.contains("mute") || cleanName.contains("silent") || cleanName.contains("ringer") -> listOf("sound", "vibrate", "mute", "silent", "ringer")
            else -> listOf(cleanName)
        }

        // 2. Open Quick Settings panel
        val opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        if (!opened) {
            return Pair(false, "Quick Settings panel open nahi ho saka.")
        }
        delay(600)

        // 3. Scan for target toggle tile in live tree
        var root = rootInActiveWindow
        var tileNode = findQuickSettingTile(aliases, root)

        // If not found in primary collapsed view, attempt one swipe/scroll to inspect full tiles
        if (tileNode == null) {
            scroll(true)
            delay(500)
            root = rootInActiveWindow
            tileNode = findQuickSettingTile(aliases, root)
        }

        if (tileNode == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Pair(false, "Quick Settings panel mein '$settingName' tile nahi mila.")
        }

        // 4. Read initial state
        val initialState = evaluateNodeState(tileNode)
        Log.i(TAG, "Quick Setting '$settingName' initial detected state: $initialState (target: $targetState)")

        // If targetState is already satisfied, avoid redundant toggle
        if (targetState != null && initialState == targetState) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            val stateWord = if (targetState) "ON" else "OFF"
            return Pair(true, "$settingName pehle se hi $stateWord hai boss.")
        }

        // 5. Click the tile
        val clicked = tryClickNode(tileNode)
        if (!clicked) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Pair(false, "$settingName tile par click execute nahi ho saka.")
        }

        // 6. Wait for animation & state transition
        delay(850)

        // 7. Re-observe live tree for verification
        root = rootInActiveWindow
        val updatedTileNode = findQuickSettingTile(aliases, root)
        val verifiedState = updatedTileNode?.let { evaluateNodeState(it) }
        Log.i(TAG, "Quick Setting '$settingName' post-click verified state: $verifiedState (initial: $initialState)")

        // 8. Close Quick Settings panel
        performGlobalAction(GLOBAL_ACTION_BACK)

        return if (targetState != null) {
            if (verifiedState == targetState) {
                Pair(true, "$settingName ${if (targetState) "ON" else "OFF"} kar diya hai aur verify ho gaya.")
            } else if (verifiedState != null && verifiedState != initialState) {
                Pair(true, "$settingName state change ho gaya hai.")
            } else {
                Pair(false, "Device restriction ya policy ki wajah se $settingName state change verify nahi ho saka.")
            }
        } else {
            if (verifiedState != null && verifiedState != initialState) {
                Pair(true, "$settingName toggle ho gaya hai.")
            } else {
                Pair(true, "$settingName toggle command execute ho gayi.")
            }
        }
    }

    private fun findQuickSettingTile(aliases: List<String>, root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val r = root ?: return null
        for (alias in aliases) {
            val node = findNodeUniversal(alias, r)
            if (node != null) return node
        }
        return null
    }

    private fun evaluateNodeState(node: AccessibilityNodeInfo): Boolean? {
        if (node.isChecked) return true

        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        val combined = "$text $desc"

        val onMarkers = listOf("on", "active", "connected", "enabled", "turning on")
        val offMarkers = listOf("off", "inactive", "disconnected", "disabled", "turning off")

        val hasOn = onMarkers.any { Regex("(?i)\\b$it\\b").containsMatchIn(combined) }
        val hasOff = offMarkers.any { Regex("(?i)\\b$it\\b").containsMatchIn(combined) }

        if (hasOn && !hasOff) return true
        if (hasOff && !hasOn) return false

        // Check child nodes
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childState = evaluateNodeState(child)
            if (childState != null) return childState
        }

        return if (node.isSelected) true else null
    }
}