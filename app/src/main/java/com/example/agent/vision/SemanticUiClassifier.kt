package com.example.agent.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object SemanticUiClassifier {

    data class ClassifiedElement(
        val node: AccessibilityNodeInfo,
        val role: String,
        val text: String,
        val bounds: Rect,
        val confidence: Float
    )

    fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val keywords = listOf("send", "submit", "bhejo", "arrow", "post", "enter")
        return findMatchingNode(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val txt = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            node.isClickable && (
                keywords.any { desc.contains(it) || txt.contains(it) || id.contains(it) } ||
                id.contains("send_button") || id.contains("button_send")
            )
        }
    }

    fun findPromptInputField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        return findMatchingNode(root) { node ->
            node.isEditable ||
            (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) ||
            node.viewIdResourceName?.contains("input", ignoreCase = true) == true ||
            node.viewIdResourceName?.contains("prompt", ignoreCase = true) == true
        }
    }

    fun findDownloadButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val keywords = listOf("download", "save", "save to device", "export", "share", "save image")
        return findMatchingNode(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val txt = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            node.isClickable && (
                keywords.any { desc.contains(it) || txt.contains(it) || id.contains(it) } ||
                id.contains("download") || id.contains("save")
            )
        }
    }

    fun findGeneratedImageNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        return findMatchingNode(root) { node ->
            val cls = node.className?.toString() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val isImage = cls.contains("ImageView", ignoreCase = true) || cls.contains("Image", ignoreCase = true)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isLarge = (rect.width() > 200 && rect.height() > 200)

            isImage && isLarge && (
                desc.contains("generated") || desc.contains("image") || desc.contains("wallpaper") ||
                id.contains("image") || id.contains("photo") || node.isClickable
            )
        }
    }

    fun findVisibleStatusMessage(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val statusKeywords = listOf(
            "generating", "creating", "working on it", "thinking",
            "downloading", "saved to photos", "download complete",
            "saved to device", "couldn't generate", "failed to create",
            "something went wrong", "error", "try again"
        )
        var foundStatus: String? = null
        findMatchingNode(root) { node ->
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val candidate = if (txt.isNotBlank()) txt else desc
            val lower = candidate.lowercase()
            if (statusKeywords.any { lower.contains(it) }) {
                foundStatus = candidate
                true
            } else {
                false
            }
        }
        return foundStatus
    }

    private fun findMatchingNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findMatchingNode(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
