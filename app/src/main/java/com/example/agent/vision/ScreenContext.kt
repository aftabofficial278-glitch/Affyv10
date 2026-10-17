package com.example.agent.vision

import android.graphics.Rect

/**
 * High-level classification of what state the current visible screen is in.
 */
enum class ScreenStateType {
    UNKNOWN,
    HOME,
    LOADING,
    SEARCH,
    RESULTS,
    LOGIN_AUTH,
    PERMISSION_DIALOG,
    KEYBOARD_OPEN,
    ERROR_DIALOG,
    SUCCESS_CONFIRMATION,
    MEDIA_PLAYING,
    SETTINGS_VIEW,
    CHAT_CONVERSATION
}

/**
 * Functional semantic role of an element on screen.
 */
enum class UiElementRole {
    BUTTON,
    SEARCH_BUTTON,
    SEARCH_INPUT,
    TEXT_FIELD,
    PLAY_CONTROL,
    PAUSE_CONTROL,
    LIKE_BUTTON,
    COMMENT_BUTTON,
    SHARE_BUTTON,
    CLEAR_BUTTON,
    TAB_ITEM,
    SEND_BUTTON,
    CLOSE_BUTTON,
    BACK_BUTTON,
    SETTINGS_BUTTON,
    MENU_OPTION,
    CHECKBOX_SWITCH,
    IMAGE_MEDIA,
    SUBSCRIBE_BUTTON,
    VIDEO_CARD,
    SCROLL_CONTAINER,
    STATIC_LABEL,
    UNKNOWN
}

/**
 * Classified type of screen change detected across before/after snapshots.
 */
enum class ScreenChangeType {
    NO_CHANGE,
    NAVIGATION,
    DIALOG_OPENED,
    DIALOG_CLOSED,
    TAB_CHANGED,
    LIST_SCROLLED,
    BUTTON_STATE_CHANGED,
    TEXT_INPUT_CHANGED,
    CONTENT_UPDATED
}

/**
 * Screen change comparison result.
 */
data class ScreenChangeResult(
    val hasChanged: Boolean,
    val changeType: ScreenChangeType,
    val description: String
)

/**
 * Compact, immutable screen fingerprint for high-accuracy before/after change verification.
 */
data class ScreenFingerprint(
    val packageName: String = "",
    val activityName: String = "",
    val screenState: ScreenStateType = ScreenStateType.UNKNOWN,
    val textSummaryHash: Int = 0,
    val interactiveElementCount: Int = 0,
    val focusedNodeLabel: String = "",
    val topTextDigest: List<String> = emptyList()
) {
    fun compareWith(other: ScreenFingerprint): ScreenChangeResult {
        if (packageName != other.packageName && other.packageName.isNotBlank() && packageName.isNotBlank()) {
            return ScreenChangeResult(
                hasChanged = true,
                changeType = ScreenChangeType.NAVIGATION,
                description = "Navigated from $packageName to ${other.packageName}"
            )
        }
        if (screenState != other.screenState && other.screenState != ScreenStateType.UNKNOWN) {
            if (other.screenState == ScreenStateType.PERMISSION_DIALOG || other.screenState == ScreenStateType.ERROR_DIALOG) {
                return ScreenChangeResult(
                    hasChanged = true,
                    changeType = ScreenChangeType.DIALOG_OPENED,
                    description = "Dialog opened: ${other.screenState}"
                )
            }
            if (screenState == ScreenStateType.PERMISSION_DIALOG || screenState == ScreenStateType.ERROR_DIALOG) {
                return ScreenChangeResult(
                    hasChanged = true,
                    changeType = ScreenChangeType.DIALOG_CLOSED,
                    description = "Dialog dismissed"
                )
            }
            return ScreenChangeResult(
                hasChanged = true,
                changeType = ScreenChangeType.CONTENT_UPDATED,
                description = "Screen state changed from $screenState to ${other.screenState}"
            )
        }
        if (focusedNodeLabel != other.focusedNodeLabel && other.focusedNodeLabel.isNotBlank()) {
            return ScreenChangeResult(
                hasChanged = true,
                changeType = ScreenChangeType.TEXT_INPUT_CHANGED,
                description = "Focus shifted to '${other.focusedNodeLabel}'"
            )
        }
        if (textSummaryHash != other.textSummaryHash) {
            val isScrollLikely = topTextDigest != other.topTextDigest && topTextDigest.isNotEmpty() && other.topTextDigest.isNotEmpty()
            return ScreenChangeResult(
                hasChanged = true,
                changeType = if (isScrollLikely) ScreenChangeType.LIST_SCROLLED else ScreenChangeType.CONTENT_UPDATED,
                description = if (isScrollLikely) "Visible list items scrolled" else "Visible text content updated"
            )
        }
        if (interactiveElementCount != other.interactiveElementCount) {
            return ScreenChangeResult(
                hasChanged = true,
                changeType = ScreenChangeType.CONTENT_UPDATED,
                description = "Interactive element count changed ($interactiveElementCount -> ${other.interactiveElementCount})"
            )
        }
        return ScreenChangeResult(
            hasChanged = false,
            changeType = ScreenChangeType.NO_CHANGE,
            description = "No observable screen changes"
        )
    }
}

/**
 * Structured, sanitized representation of a single UI node on screen.
 */
data class UiElementNode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val viewId: String? = null,
    val text: String = "",
    val contentDescription: String = "",
    val role: UiElementRole = UiElementRole.UNKNOWN,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isEnabled: Boolean = true,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false,
    val isPassword: Boolean = false,
    val isScrollable: Boolean = false,
    val isVerticalScrollable: Boolean = false,
    val isHorizontalScrollable: Boolean = false,
    val boundsInScreen: Rect = Rect(),
    val className: String = "",
    val depth: Int = 0,
    val parentVisualRowIndex: Int = -1,
    val hasClickableAncestor: Boolean = false,
    val clickableAncestorBounds: Rect? = null
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            viewId?.substringAfterLast("/")?.isNotBlank() == true -> viewId.substringAfterLast("/")
            else -> role.name.lowercase().replace('_', ' ')
        }

    val isInteractive: Boolean get() = isClickable || isEditable || isScrollable || hasClickableAncestor
}

/**
 * Represents a cohesive horizontal visual row / list item container
 * grouping child text views, icons, timestamps, and buttons.
 */
data class VisualRowItem(
    val rowIndex: Int, // 0-based index from top to bottom
    val bounds: Rect,
    val primaryLabel: String,
    val allTexts: List<String>,
    val clickableNode: UiElementNode?,
    val memberNodes: List<UiElementNode>
)

/**
 * Complete structured snapshot of what is visible on the Android screen.
 * Strictly respects user privacy by scrubbing sensitive inputs (passwords, OTPs).
 */
data class ScreenContext(
    val timestamp: Long = System.currentTimeMillis(),
    val foregroundPackage: String = "",
    val activityName: String = "",
    val appDisplayName: String = "",
    val screenState: ScreenStateType = ScreenStateType.UNKNOWN,
    val rawTextCount: Int = 0,
    val visibleTexts: List<String> = emptyList(),
    val clickableElements: List<UiElementNode> = emptyList(),
    val editableFields: List<UiElementNode> = emptyList(),
    val scrollableContainers: List<UiElementNode> = emptyList(),
    val visualRows: List<VisualRowItem> = emptyList(),
    val allInteractiveElements: List<UiElementNode> = emptyList(),
    val focusedNode: UiElementNode? = null,
    val errorMessage: String? = null,
    val loadingDetected: Boolean = false,
    val keyboardOpen: Boolean = false,
    val isAccessibilityAvailable: Boolean = true,
    val fingerprint: ScreenFingerprint = ScreenFingerprint()
) {
    /**
     * Generates a conversational natural-language summary of what is visible.
     */
    fun toConversationalSummary(): String {
        if (!isAccessibilityAvailable) {
            return "Boss, abhi screen ka content accessible nahi hai. Accessibility permission check kar lijiye."
        }
        if (allInteractiveElements.isEmpty() && visibleTexts.isEmpty()) {
            return "Boss, abhi screen ka content accessible nahi hai. Accessibility permission check kar lijiye."
        }

        val sb = StringBuilder()
        if (appDisplayName.isNotBlank()) {
            sb.append("Aap abhi $appDisplayName mein hain. ")
        }

        if (foregroundPackage.contains("whatsapp", ignoreCase = true) || appDisplayName.contains("whatsapp", ignoreCase = true)) {
            val contactRows = visualRows
                .map { it.primaryLabel.trim() }
                .filter { it.isNotBlank() && it.length in 2..35 && !it.equals("WhatsApp", ignoreCase = true) && !it.equals("Chats", ignoreCase = true) && !it.equals("Updates", ignoreCase = true) && !it.equals("Calls", ignoreCase = true) && !it.equals("Search", ignoreCase = true) }
                .distinct()
            if (contactRows.isNotEmpty()) {
                sb.append("Screen par ${contactRows.size} contacts/chats visible hain: ${contactRows.take(6).joinToString(", ")}. ")
            }
        }

        if (errorMessage != null) {
            sb.append("Screen par ek error ya alert hai: \"$errorMessage\". ")
        } else if (loadingDetected) {
            sb.append("Screen par loading ho rahi hai. ")
        }

        if (visualRows.isNotEmpty() && !foregroundPackage.contains("whatsapp", ignoreCase = true)) {
            val keyRows = visualRows
                .map { it.primaryLabel.trim() }
                .filter { it.isNotBlank() && it.length < 35 }
                .take(4)
            if (keyRows.isNotEmpty()) {
                sb.append("Visible items: ${keyRows.joinToString(", ")}. ")
            }
        } else if (clickableElements.isNotEmpty()) {
            val keyButtons = clickableElements
                .map { it.displayLabel.trim() }
                .filter { it.isNotBlank() && it.length < 35 }
                .distinct()
                .take(6)
            if (keyButtons.isNotEmpty()) {
                sb.append("Options mein ${keyButtons.joinToString(", ")} shamil hain. ")
            }
        }

        if (visibleTexts.isNotEmpty()) {
            val sampleTexts = visibleTexts
                .filter { it.length > 2 && it.length < 60 }
                .distinct()
                .take(4)
            if (sampleTexts.isNotEmpty()) {
                sb.append("Main text: ${sampleTexts.joinToString(" | ")}.")
            }
        }

        return sb.toString().trim()
    }
}

/**
 * Result of matching a user request against the screen context.
 */
data class TargetResolutionResult(
    val matchedNode: UiElementNode? = null,
    val matchedRow: VisualRowItem? = null,
    val confidence: Float = 0.0f, // 0.0 to 1.0
    val matchReason: String = "",
    val alternativeSuggestions: List<String> = emptyList(),
    val needsClarification: Boolean = false,
    val clarificationPrompt: String? = null
) {
    val isFound: Boolean get() = (matchedNode != null || matchedRow != null) && confidence >= 0.50f && !needsClarification
}

