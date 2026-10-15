package com.example.agent.vision

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.AffyDiagnostics
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

class AffyScreenVisionEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "AffyScreenVision"
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_ELEMENTS = 1000

        @Volatile
        private var INSTANCE: AffyScreenVisionEngine? = null

        fun getInstance(context: Context): AffyScreenVisionEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AffyScreenVisionEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _latestScreenContext = MutableStateFlow<ScreenContext?>(null)
    val latestScreenContext: StateFlow<ScreenContext?> = _latestScreenContext.asStateFlow()

    private var lastScreenHash: Int = 0

    /**
     * Captures and constructs a complete structured ScreenContext from the active window.
     * Efficient, lightweight, privacy-safe, with zero password/OTP recording.
     */
    suspend fun captureScreenContext(): ScreenContext = withContext(Dispatchers.Default) {
        val a11y = AffyAccessibilityService.instance
        if (a11y == null) {
            val emptyCtx = ScreenContext(isAccessibilityAvailable = false)
            _latestScreenContext.value = emptyCtx
            return@withContext emptyCtx
        }

        // Query fresh root of the active foreground app window, bypassing stale caches & overlay windows
        val root = a11y.getFreshActiveWindowRoot()
        if (root == null) {
            val emptyCtx = ScreenContext(isAccessibilityAvailable = true)
            _latestScreenContext.value = emptyCtx
            return@withContext emptyCtx
        }

        val foregroundPkg = root.packageName?.toString() ?: AffyAccessibilityService.currentAppPackage.value.orEmpty()
        val activityName = root.className?.toString() ?: ""
        val appLabel = resolveAppDisplayName(foregroundPkg)

        val collectedNodes = mutableListOf<UiElementNode>()
        val visibleTexts = mutableListOf<String>()
        var focusedNodeItem: UiElementNode? = null

        fun traverse(node: AccessibilityNodeInfo, depth: Int, clickableAncestorBounds: Rect? = null) {
            if (depth > MAX_TREE_DEPTH || collectedNodes.size >= MAX_ELEMENTS) return

            // Ensure node is fresh and not cached from previous screen state
            node.refresh()

            val isPass = node.isPassword
            val rawText = node.text?.toString()?.trim()
            val rawDesc = node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName
            val className = node.className?.toString() ?: ""

            val cleanText = SemanticUiClassifier.sanitizeText(rawText, isPass)
            val cleanDesc = SemanticUiClassifier.sanitizeText(rawDesc, isPass)

            if (cleanText.isNotBlank() && !cleanText.startsWith("•")) {
                visibleTexts.add(cleanText)
            }
            if (cleanDesc.isNotBlank() && cleanDesc != cleanText) {
                visibleTexts.add(cleanDesc)
            }

            val role = SemanticUiClassifier.classifyNodeRole(node)
            val isClickable = node.isClickable || node.isCheckable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isFocused = node.isFocused
            val isSelected = node.isSelected
            val isChecked = node.isChecked

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isHorizontal = isScrollable && (className.contains("ViewPager", ignoreCase = true) ||
                    className.contains("HorizontalScrollView", ignoreCase = true) ||
                    className.contains("TabLayout", ignoreCase = true))
            val isVertical = isScrollable && !isHorizontal && (className.contains("RecyclerView", ignoreCase = true) ||
                    className.contains("ListView", ignoreCase = true) ||
                    className.contains("ScrollView", ignoreCase = true) ||
                    bounds.height() > 200)

            val currentClickableBounds = if (isClickable && bounds.width() > 0 && bounds.height() > 0) bounds else clickableAncestorBounds

            val isActionRole = role == UiElementRole.SEND_BUTTON || role == UiElementRole.COMMENT_BUTTON || role == UiElementRole.LIKE_BUTTON || role == UiElementRole.SHARE_BUTTON || role == UiElementRole.BUTTON
            if (isClickable || isEditable || isScrollable || cleanText.isNotBlank() || cleanDesc.isNotBlank() || !viewId.isNullOrBlank() || currentClickableBounds != null || (isActionRole && bounds.width() > 0 && bounds.height() > 0)) {
                val elementNode = UiElementNode(
                    viewId = viewId,
                    text = cleanText,
                    contentDescription = cleanDesc,
                    role = role,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isEnabled = node.isEnabled,
                    isSelected = isSelected,
                    isChecked = isChecked,
                    isFocused = isFocused,
                    isPassword = isPass,
                    isScrollable = isScrollable,
                    isVerticalScrollable = isVertical,
                    isHorizontalScrollable = isHorizontal,
                    boundsInScreen = bounds,
                    className = className,
                    depth = depth,
                    hasClickableAncestor = currentClickableBounds != null && !isClickable,
                    clickableAncestorBounds = currentClickableBounds
                )
                collectedNodes.add(elementNode)
                if (isFocused && focusedNodeItem == null) {
                    focusedNodeItem = elementNode
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1, currentClickableBounds)
            }
        }

        traverse(root, 0, null)

        val clickable = collectedNodes.filter { it.isClickable || it.hasClickableAncestor }
        val editable = collectedNodes.filter { it.isEditable }
        val scrollables = collectedNodes.filter { it.isScrollable }
        val (state, errorMsg) = SemanticUiClassifier.classifyScreenState(foregroundPkg, collectedNodes, visibleTexts)

        // Visual Row Clustering: Group items by vertical band
        val validNodes = collectedNodes.filter { !it.boundsInScreen.isEmpty && it.boundsInScreen.width() > 0 && it.boundsInScreen.height() > 0 }
        val sortedByTop = validNodes.sortedWith(compareBy({ it.boundsInScreen.top }, { it.boundsInScreen.left }))
        val visualRows = mutableListOf<VisualRowItem>()
        val currentCluster = mutableListOf<UiElementNode>()

        for (n in sortedByTop) {
            val firstInCluster = currentCluster.firstOrNull()
            if (firstInCluster == null) {
                currentCluster.add(n)
            } else {
                val topDiff = Math.abs(n.boundsInScreen.top - firstInCluster.boundsInScreen.top)
                val centerDiff = Math.abs(n.boundsInScreen.centerY() - firstInCluster.boundsInScreen.centerY())
                if (topDiff < 55 || centerDiff < 45) {
                    currentCluster.add(n)
                } else {
                    // Create row from cluster
                    val rowBounds = Rect(
                        currentCluster.minOf { it.boundsInScreen.left },
                        currentCluster.minOf { it.boundsInScreen.top },
                        currentCluster.maxOf { it.boundsInScreen.right },
                        currentCluster.maxOf { it.boundsInScreen.bottom }
                    )
                    val primaryLbl = currentCluster.firstOrNull { it.displayLabel.isNotBlank() }?.displayLabel ?: ""
                    val clickNode = currentCluster.firstOrNull { it.isClickable } ?: currentCluster.firstOrNull { it.hasClickableAncestor } ?: currentCluster.firstOrNull()
                    val allRowTexts = currentCluster.map { it.displayLabel }.filter { it.isNotBlank() }.distinct()
                    visualRows.add(
                        VisualRowItem(
                            rowIndex = visualRows.size,
                            bounds = rowBounds,
                            primaryLabel = primaryLbl,
                            allTexts = allRowTexts,
                            clickableNode = clickNode,
                            memberNodes = currentCluster.toList()
                        )
                    )
                    currentCluster.clear()
                    currentCluster.add(n)
                }
            }
        }
        if (currentCluster.isNotEmpty()) {
            val rowBounds = Rect(
                currentCluster.minOf { it.boundsInScreen.left },
                currentCluster.minOf { it.boundsInScreen.top },
                currentCluster.maxOf { it.boundsInScreen.right },
                currentCluster.maxOf { it.boundsInScreen.bottom }
            )
            val primaryLbl = currentCluster.firstOrNull { it.displayLabel.isNotBlank() }?.displayLabel ?: ""
            val clickNode = currentCluster.firstOrNull { it.isClickable } ?: currentCluster.firstOrNull { it.hasClickableAncestor } ?: currentCluster.firstOrNull()
            val allRowTexts = currentCluster.map { it.displayLabel }.filter { it.isNotBlank() }.distinct()
            visualRows.add(
                VisualRowItem(
                    rowIndex = visualRows.size,
                    bounds = rowBounds,
                    primaryLabel = primaryLbl,
                    allTexts = allRowTexts,
                    clickableNode = clickNode,
                    memberNodes = currentCluster.toList()
                )
            )
        }

        val topTexts = visibleTexts.take(8).distinct()
        val textHash = (foregroundPkg + topTexts.joinToString("") + clickable.size).hashCode()
        val fingerprint = ScreenFingerprint(
            packageName = foregroundPkg,
            activityName = activityName,
            screenState = state,
            textSummaryHash = textHash,
            interactiveElementCount = clickable.size + editable.size,
            focusedNodeLabel = focusedNodeItem?.displayLabel ?: "",
            topTextDigest = topTexts
        )

        val screenContext = ScreenContext(
            timestamp = System.currentTimeMillis(),
            foregroundPackage = foregroundPkg,
            activityName = activityName,
            appDisplayName = appLabel,
            screenState = state,
            rawTextCount = visibleTexts.size,
            visibleTexts = visibleTexts.distinct(),
            clickableElements = clickable,
            editableFields = editable,
            scrollableContainers = scrollables,
            visualRows = visualRows,
            allInteractiveElements = (clickable + editable).distinctBy { it.displayLabel + it.boundsInScreen.toString() },
            focusedNode = focusedNodeItem,
            errorMessage = errorMsg,
            loadingDetected = state == ScreenStateType.LOADING,
            keyboardOpen = state == ScreenStateType.KEYBOARD_OPEN,
            isAccessibilityAvailable = true,
            fingerprint = fingerprint
        )

        // Screen change tracking
        if (textHash != lastScreenHash) {
            lastScreenHash = textHash
            AffyDiagnostics.logScreenStage(
                "SCREEN_CONTEXT_UPDATED",
                details = "App: $appLabel, State: $state, Rows: ${visualRows.size}, Interactive: ${clickable.size + editable.size}, Texts: ${visibleTexts.size}"
            )
            AffyDiagnostics.logScreenStage("SCREEN_STATE_CHANGED", details = "New state: $state")
        }

        _latestScreenContext.value = screenContext
        screenContext
    }

    /**
     * Captures a fresh ScreenFingerprint of the current live active window.
     */
    suspend fun captureFingerprint(): ScreenFingerprint = withContext(Dispatchers.Default) {
        captureScreenContext().fingerprint
    }

    /**
     * Finds and executes a click on a target query using semantic matching, visual rows,
     * live node lookup, coordinate fallback, and strict change verification.
     */
    suspend fun resolveAndClickTarget(targetQuery: String): TargetResolutionResult = withContext(Dispatchers.Default) {
        // 1. Fresh screen snapshot immediately before resolving
        val screenContext = captureScreenContext()
        val beforeFingerprint = screenContext.fingerprint

        val result = SemanticUiClassifier.resolveTarget(targetQuery, screenContext)

        if (result.needsClarification) {
            Log.w(TAG, "[WRONG_TARGET_PROTECTION] Ambiguity or low confidence for '$targetQuery': ${result.clarificationPrompt}")
            return@withContext result
        }

        val targetNode = result.matchedNode ?: result.matchedRow?.clickableNode
        if (targetNode != null) {
            Log.i(TAG, "[UI_TARGET_RESOLVED] target='$targetQuery' -> resolved='${targetNode.displayLabel}' (confidence=${result.confidence}, reason=${result.matchReason})")

            val a11y = AffyAccessibilityService.instance
            if (a11y != null) {
                var clicked = false
                Log.i(TAG, "[UI_CLICK_ATTEMPT] Dispatching click for '${targetNode.displayLabel}' [bounds=${targetNode.boundsInScreen}]")

                // Step 1: Query live active window immediately before execution
                val liveRoot = a11y.getFreshActiveWindowRoot()
                if (liveRoot != null) {
                    if (targetNode.viewId != null) {
                        val matching = liveRoot.findAccessibilityNodeInfosByViewId(targetNode.viewId)
                        val match = matching.firstOrNull { 
                            val r = Rect()
                            it.getBoundsInScreen(r)
                            r == targetNode.boundsInScreen || Math.abs(r.centerY() - targetNode.boundsInScreen.centerY()) < 30
                        } ?: matching.firstOrNull()
                        if (match != null) {
                            Log.i(TAG, "[UI_CLICK_ACCESSIBILITY] Attempting live node click via ACTION_CLICK on ${match.className}")
                            clicked = a11y.clickNodeWithFallback(match)
                        }
                    }
                    if (!clicked && targetNode.text.isNotBlank()) {
                        val matching = liveRoot.findAccessibilityNodeInfosByText(targetNode.text)
                        for (m in matching) {
                            Log.i(TAG, "[UI_CLICK_ACCESSIBILITY] Attempting live text node click on ${m.className}")
                            if (a11y.clickNodeWithFallback(m)) {
                                clicked = true
                                break
                            }
                        }
                    }
                }

                // Step 2: Fallback to clickElement helpers if live lookup failed
                if (!clicked && targetNode.text.isNotBlank()) {
                    clicked = a11y.clickElementByText(targetNode.text)
                }
                if (!clicked && targetNode.viewId != null) {
                    clicked = a11y.clickElementByViewId(targetNode.viewId)
                }
                if (!clicked && targetNode.contentDescription.isNotBlank()) {
                    clicked = a11y.clickElementByText(targetNode.contentDescription)
                }

                // Step 3: Spatial / coordinate fallback based on exact live node bounds
                if (!clicked && !targetNode.boundsInScreen.isEmpty && targetNode.boundsInScreen.width() > 0 && targetNode.boundsInScreen.height() > 0) {
                    val centerX = targetNode.boundsInScreen.centerX().toFloat()
                    val centerY = targetNode.boundsInScreen.centerY().toFloat()
                    Log.i(TAG, "[UI_CLICK_GESTURE_FALLBACK] ACTION_CLICK failed/unavailable, dispatching live coordinate tap at ($centerX, $centerY)")
                    clicked = a11y.performClickGesture(centerX, centerY)
                }

                // Step 4: Re-query live screen and verify result
                if (clicked) {
                    val screenChanged = waitForMeaningfulChange(1200)
                    val afterFingerprint = captureFingerprint()
                    val changeResult = beforeFingerprint.compareWith(afterFingerprint)

                    if (screenChanged || changeResult.hasChanged) {
                        Log.i(TAG, "[UI_CLICK_VERIFIED] Click on '$targetQuery' verified (${changeResult.description})")
                        AffyDiagnostics.logScreenStage("UI_CLICK_VERIFIED", details = "Clicked '${targetNode.displayLabel}' -> ${changeResult.description}")
                        return@withContext result
                    } else {
                        Log.w(TAG, "[UI_CLICK_FAILED] Click executed on '${targetNode.displayLabel}' but screen did not produce observable change")
                        Log.w(TAG, "[FALSE_SUCCESS_BLOCKED] Blocking unverified click success for '$targetQuery'")
                        AffyDiagnostics.logScreenStage("UI_CLICK_FAILED", details = "No observable change after click on '$targetQuery'", isSuccess = false)
                    }
                } else {
                    Log.w(TAG, "[UI_CLICK_FAILED] Element located but all click dispatch mechanisms failed for '$targetQuery'")
                    AffyDiagnostics.logScreenStage("UI_CLICK_FAILED", details = "Click dispatch failed for '$targetQuery'", isSuccess = false)
                }
            }
        }

        result
    }

    /**
     * Finds and executes a long-press on a target query using semantic matching & confidence scoring.
     */
    suspend fun resolveAndLongPressTarget(targetQuery: String): TargetResolutionResult = withContext(Dispatchers.Default) {
        val screenContext = captureScreenContext()
        val result = SemanticUiClassifier.resolveTarget(targetQuery, screenContext)

        if (result.isFound && result.matchedNode != null) {
            val a11y = AffyAccessibilityService.instance
            if (a11y != null) {
                val node = result.matchedNode
                var longPressed = false
                if (node.text.isNotBlank()) {
                    longPressed = a11y.longPressElementByText(node.text)
                }
                if (!longPressed && !node.boundsInScreen.isEmpty) {
                    val centerX = node.boundsInScreen.centerX().toFloat()
                    val centerY = node.boundsInScreen.centerY().toFloat()
                    longPressed = a11y.performLongPressGesture(centerX, centerY)
                }

                if (longPressed) {
                    AffyDiagnostics.logScreenStage(
                        "SCREEN_VERIFICATION",
                        details = "Long pressed target '${node.displayLabel}' (${result.matchReason})"
                    )
                }
            }
        }
        result
    }

    /**
     * Waits for meaningful screen change after an interaction.
     * Prevents thrashing on microscopic accessibility events.
     */
    suspend fun waitForMeaningfulChange(timeoutMs: Long = 2000L): Boolean = withContext(Dispatchers.Default) {
        val initialHash = lastScreenHash
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(250)
            val updated = captureScreenContext()
            val newHash = (updated.foregroundPackage + updated.visibleTexts.take(8).joinToString("") + updated.clickableElements.size).hashCode()
            if (newHash != initialHash) {
                Log.d(TAG, "Meaningful screen change detected within ${System.currentTimeMillis() - startTime}ms")
                return@withContext true
            }
        }
        return@withContext false
    }

    private fun resolveAppDisplayName(pkg: String): String {
        return when {
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("chrome") -> "Chrome"
            pkg.contains("spotify") -> "Spotify"
            pkg.contains("settings") -> "Settings"
            pkg.contains("camera") -> "Camera"
            pkg.contains("maps") -> "Google Maps"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("facebook") -> "Facebook"
            pkg.isNotBlank() -> {
                try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast(".")
                }
            }
            else -> "Screen"
        }
    }
}
