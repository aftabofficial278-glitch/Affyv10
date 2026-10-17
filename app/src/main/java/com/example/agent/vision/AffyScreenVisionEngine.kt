package com.example.agent.vision

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.AffyAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AffyScreenVisionEngine(private val context: Context? = null) {

    companion object {
        private const val TAG = "AffyScreenVisionEngine"

        @Volatile
        private var instance: AffyScreenVisionEngine? = null

        fun getInstance(context: Context? = null): AffyScreenVisionEngine {
            return instance ?: synchronized(this) {
                instance ?: AffyScreenVisionEngine(context?.applicationContext).also { instance = it }
            }
        }
    }

    sealed class ImageObservationResult {
        data class Ready(val evidenceNode: AccessibilityNodeInfo, val statusMessage: String?) : ImageObservationResult()
        data class Error(val errorMessage: String) : ImageObservationResult()
        object Timeout : ImageObservationResult()
    }

    /**
     * Captures a structured snapshot of visible text, interactable elements,
     * and visual rows from the active window.
     */
    fun captureScreenContext(): ScreenContext {
        val service = AffyAccessibilityService.instance
        val root = service?.rootInActiveWindow
        if (service == null || root == null) {
            return ScreenContext(
                isAccessibilityAvailable = service != null,
                foregroundPackage = AffyAccessibilityService.currentAppPackage.value ?: ""
            )
        }

        val visibleTexts = mutableListOf<String>()
        val clickableElements = mutableListOf<UiElementNode>()
        val editableFields = mutableListOf<UiElementNode>()
        val scrollableContainers = mutableListOf<UiElementNode>()
        val allInteractiveElements = mutableListOf<UiElementNode>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) return

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            if (text.isNotBlank()) visibleTexts.add(text)
            if (desc.isNotBlank() && desc != text) visibleTexts.add(desc)

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val uiNode = UiElementNode(
                viewId = viewId,
                text = text,
                contentDescription = desc,
                isClickable = isClickable,
                isEditable = isEditable,
                isEnabled = node.isEnabled,
                isSelected = node.isSelected,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                isScrollable = isScrollable,
                boundsInScreen = bounds,
                className = node.className?.toString() ?: "",
                depth = depth
            )

            if (isClickable) clickableElements.add(uiNode)
            if (isEditable) editableFields.add(uiNode)
            if (isScrollable) scrollableContainers.add(uiNode)
            if (uiNode.isInteractive) allInteractiveElements.add(uiNode)

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root)

        val pkg = root.packageName?.toString() ?: AffyAccessibilityService.currentAppPackage.value ?: ""
        val visualRows = buildVisualRows(allInteractiveElements, visibleTexts)

        return ScreenContext(
            foregroundPackage = pkg,
            appDisplayName = resolveDisplayName(pkg),
            rawTextCount = visibleTexts.size,
            visibleTexts = visibleTexts.distinct(),
            clickableElements = clickableElements,
            editableFields = editableFields,
            scrollableContainers = scrollableContainers,
            visualRows = visualRows,
            allInteractiveElements = allInteractiveElements,
            isAccessibilityAvailable = true
        )
    }

    private fun resolveDisplayName(pkg: String): String {
        return when {
            pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            pkg.contains("youtube", ignoreCase = true) -> "YouTube"
            pkg.contains("instagram", ignoreCase = true) -> "Instagram"
            pkg.contains("gm", ignoreCase = true) || pkg.contains("email", ignoreCase = true) -> "Gmail"
            pkg.contains("chrome", ignoreCase = true) -> "Chrome"
            pkg.contains("dialer", ignoreCase = true) || pkg.contains("telecom", ignoreCase = true) -> "Phone"
            pkg.contains("messaging", ignoreCase = true) || pkg.contains("mms", ignoreCase = true) -> "Messages"
            pkg.contains("settings", ignoreCase = true) -> "Settings"
            pkg.contains("camera", ignoreCase = true) -> "Camera"
            pkg.contains("photos", ignoreCase = true) || pkg.contains("gallery", ignoreCase = true) -> "Gallery"
            else -> pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        }
    }

    private fun buildVisualRows(
        interactive: List<UiElementNode>,
        texts: List<String>
    ): List<VisualRowItem> {
        val rows = mutableListOf<VisualRowItem>()
        val groupedByY = interactive.groupBy { it.boundsInScreen.centerY() / 60 }
        var index = 0
        for ((_, group) in groupedByY) {
            val primary = group.firstOrNull { it.displayLabel.isNotBlank() }?.displayLabel ?: ""
            if (primary.isNotBlank()) {
                val unionBounds = Rect(group.first().boundsInScreen)
                group.forEach { unionBounds.union(it.boundsInScreen) }
                rows.add(
                    VisualRowItem(
                        rowIndex = index++,
                        bounds = unionBounds,
                        primaryLabel = primary,
                        allTexts = group.map { it.displayLabel }.filter { it.isNotBlank() },
                        clickableNode = group.firstOrNull { it.isClickable },
                        memberNodes = group
                    )
                )
            }
        }
        return rows
    }

    /**
     * Resolves target element on screen and triggers a click on it.
     */
    fun resolveAndClickTarget(target: String): TargetResolutionResult {
        val service = AffyAccessibilityService.instance ?: return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Accessibility service not running"
        )
        val root = service.rootInActiveWindow ?: return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Active window root is null"
        )

        val cleanTarget = target.trim().lowercase()
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0f
        var matchReason = ""

        fun evaluate(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            var score = 0f
            if (text == cleanTarget || desc == cleanTarget) {
                score = 1.0f
                matchReason = "Exact text match '$cleanTarget'"
            } else if (text.startsWith(cleanTarget) || desc.startsWith(cleanTarget)) {
                score = 0.85f
                matchReason = "Prefix match '$cleanTarget'"
            } else if (text.contains(cleanTarget) || desc.contains(cleanTarget) || id.contains(cleanTarget)) {
                score = 0.70f
                matchReason = "Substring match '$cleanTarget'"
            }

            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }

            for (i in 0 until node.childCount) {
                evaluate(node.getChild(i))
            }
        }

        evaluate(root)

        if (bestNode != null && bestScore >= 0.50f) {
            val bounds = Rect()
            bestNode?.getBoundsInScreen(bounds)
            val clicked = bestNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (!clicked) {
                service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().apply {
                        val path = android.graphics.Path().apply {
                            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                        addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                    }.build(),
                    null,
                    null
                )
            }

            val uiNode = UiElementNode(
                text = bestNode?.text?.toString() ?: "",
                contentDescription = bestNode?.contentDescription?.toString() ?: "",
                boundsInScreen = bounds,
                isClickable = true
            )

            return TargetResolutionResult(
                matchedNode = uiNode,
                confidence = bestScore,
                matchReason = matchReason
            )
        }

        return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Element '$target' not found on screen"
        )
    }

    /**
     * Resolves target element on screen and triggers a long press.
     */
    fun resolveAndLongPressTarget(target: String): TargetResolutionResult {
        val service = AffyAccessibilityService.instance ?: return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Accessibility service not running"
        )
        val root = service.rootInActiveWindow ?: return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Active window root is null"
        )

        val cleanTarget = target.trim().lowercase()
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0f

        fun evaluate(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

            if (text == cleanTarget || desc == cleanTarget) {
                bestScore = 1.0f
                bestNode = node
                return
            } else if ((text.contains(cleanTarget) || desc.contains(cleanTarget)) && bestScore < 0.7f) {
                bestScore = 0.7f
                bestNode = node
            }
            for (i in 0 until node.childCount) {
                evaluate(node.getChild(i))
            }
        }

        evaluate(root)

        if (bestNode != null) {
            val bounds = Rect()
            bestNode?.getBoundsInScreen(bounds)
            val longClicked = bestNode?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
            if (!longClicked) {
                service.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().apply {
                        val path = android.graphics.Path().apply {
                            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                        addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 800))
                    }.build(),
                    null,
                    null
                )
            }

            val uiNode = UiElementNode(
                text = bestNode?.text?.toString() ?: "",
                contentDescription = bestNode?.contentDescription?.toString() ?: "",
                boundsInScreen = bounds,
                isClickable = true
            )

            return TargetResolutionResult(
                matchedNode = uiNode,
                confidence = bestScore,
                matchReason = "Long pressed '$target'"
            )
        }

        return TargetResolutionResult(
            confidence = 0f,
            matchReason = "Target '$target' not found for long press"
        )
    }

    suspend fun waitForMeaningfulChange(timeoutMs: Long = 1500L): Boolean = withContext(Dispatchers.IO) {
        delay(timeoutMs.coerceIn(100L, 2000L))
        true
    }

    suspend fun observeImageGeneration(
        timeoutMs: Long = 45000L,
        pollIntervalMs: Long = 1500L
    ): ImageObservationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var observedGeneratingState = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val service = AffyAccessibilityService.instance
            val root = service?.rootInActiveWindow

            if (root != null) {
                val statusMsg = SemanticUiClassifier.findVisibleStatusMessage(root)
                if (statusMsg != null) {
                    val lower = statusMsg.lowercase()
                    if (lower.contains("error") || lower.contains("failed") || lower.contains("couldn't")) {
                        return@withContext ImageObservationResult.Error(statusMsg)
                    }
                    if (lower.contains("generating") || lower.contains("creating") || lower.contains("thinking")) {
                        observedGeneratingState = true
                    }
                }

                val imageNode = SemanticUiClassifier.findGeneratedImageNode(root)
                if (imageNode != null) {
                    val isStillLoading = statusMsg?.lowercase()?.let {
                        it.contains("generating") || it.contains("creating")
                    } ?: false

                    if (!isStillLoading) {
                        return@withContext ImageObservationResult.Ready(imageNode, statusMsg)
                    }
                }
            }

            delay(pollIntervalMs)
        }

        ImageObservationResult.Timeout
    }

    suspend fun observeDownloadConfirmation(
        timeoutMs: Long = 10000L,
        pollIntervalMs: Long = 800L
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val notifTime = AffyAccessibilityService.lastNotificationTimestamp
            val notifText = AffyAccessibilityService.lastNotificationText
            if (notifTime >= startTime && notifText.isNotBlank()) {
                val lower = notifText.lowercase()
                if (lower.contains("saved") || lower.contains("downloaded") || lower.contains("success")) {
                    return@withContext Pair(true, notifText)
                }
                if (lower.contains("failed") || lower.contains("error")) {
                    return@withContext Pair(false, notifText)
                }
            }

            val service = AffyAccessibilityService.instance
            val root = service?.rootInActiveWindow
            if (root != null) {
                val visibleStatus = SemanticUiClassifier.findVisibleStatusMessage(root)
                if (visibleStatus != null) {
                    val lower = visibleStatus.lowercase()
                    if (lower.contains("saved to") || lower.contains("download complete") || lower.contains("saved to photos")) {
                        return@withContext Pair(true, visibleStatus)
                    }
                    if (lower.contains("failed") || lower.contains("error")) {
                        return@withContext Pair(false, visibleStatus)
                    }
                }
            }

            delay(pollIntervalMs)
        }

        Pair(false, "Download confirmation message was not observed within timeout.")
    }
}
