package com.example.agent.vision

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.AffyDiagnostics
import java.util.Locale

object SemanticUiClassifier {

    private const val TAG = "AffySemanticUi"

    // Sensitive field indicators for strict privacy compliance (never log/store passwords, OTPs)
    private val SENSITIVE_KEYWORDS = listOf(
        "password", "pin", "otp", "passcode", "cvv", "security code", "secret", "token"
    )

    /**
     * Sanitizes strings to prevent recording sensitive passwords or OTPs
     */
    fun sanitizeText(text: String?, isPassword: Boolean): String {
        if (text == null) return ""
        if (isPassword) return "••••••"
        val lower = text.lowercase(Locale.ROOT)
        for (kw in SENSITIVE_KEYWORDS) {
            if (lower.contains(kw) && text.length > 20) {
                return "[SENSITIVE_CONTENT_MASKED]"
            }
        }
        return text
    }

    /**
     * Classifies an AccessibilityNodeInfo into a high-level UI semantic role.
     */
    fun classifyNodeRole(node: AccessibilityNodeInfo): UiElementRole {
        val className = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
        val combined = "$text $desc $viewId"

        return when {
            // Search inputs (editable search fields)
            node.isEditable && (combined.contains("search") || combined.contains("find") || combined.contains("query") || combined.contains("khoj") || viewId.contains("search_edit_text")) -> {
                UiElementRole.SEARCH_INPUT
            }
            // Search buttons / search icons
            (node.isClickable || className.contains("imageview") || className.contains("button") || className.contains("view")) &&
            !node.isEditable && (combined.contains("search") || combined.contains("dhoondo") || combined.contains("khoj") || viewId.contains("menu_item_0") || viewId.contains("search_button") || desc.contains("search")) -> {
                UiElementRole.SEARCH_BUTTON
            }
            // Generic text fields
            node.isEditable -> {
                UiElementRole.TEXT_FIELD
            }
            // Play / Media controls
            combined.contains("play") || combined.contains("chalao") || combined.contains("video_play") || viewId.endsWith(":id/play") -> {
                UiElementRole.PLAY_CONTROL
            }
            // Pause controls
            combined.contains("pause") || combined.contains("roko") || combined.contains("video_pause") -> {
                UiElementRole.PAUSE_CONTROL
            }
            // Subscribe button
            combined.contains("subscribe") || combined.contains("subscribed") || viewId.contains("subscribe") -> {
                UiElementRole.SUBSCRIBE_BUTTON
            }
            // Video card item
            (node.isClickable || className.contains("viewgroup") || className.contains("layout") || className.contains("card")) &&
            (viewId.contains("video") || viewId.contains("thumbnail") || combined.contains("views") || combined.contains("ago") || combined.contains("duration")) -> {
                UiElementRole.VIDEO_CARD
            }
            // Like / Favorite
            combined.contains("like") || combined.contains("heart") || combined.contains("favorite") || combined.contains("pasand") || viewId.contains("like_button") -> {
                UiElementRole.LIKE_BUTTON
            }
            // Comment
            combined.contains("comment") || combined.contains("tabsarah") || combined.contains("reply") || combined.contains("jawb") || viewId.contains("comment") -> {
                UiElementRole.COMMENT_BUTTON
            }
            // Share
            combined.contains("share") || combined.contains("baanto") || combined.contains("bhejo") && !combined.contains("send") || viewId.contains("share") -> {
                UiElementRole.SHARE_BUTTON
            }
            // Clear text / remove
            combined.contains("clear") || combined.contains("saf karo") || combined.contains("mitao") || combined.contains("delete text") || viewId.contains("clear") -> {
                UiElementRole.CLEAR_BUTTON
            }
            // Send buttons
            combined.contains("send") || combined.contains("bhejo") || combined.contains("submit") || combined.contains("post") || viewId.contains("send_btn") -> {
                UiElementRole.SEND_BUTTON
            }
            // Tab item
            className.contains("tab") || combined.contains("tab") -> {
                UiElementRole.TAB_ITEM
            }
            // Settings buttons
            combined.contains("settings") || combined.contains("setting") || combined.contains("gear") || combined.contains("preferences") -> {
                UiElementRole.SETTINGS_BUTTON
            }
            // Close / Cancel / Dismiss
            combined.contains("close") || combined.contains("dismiss") || combined.contains("cancel") || combined.contains("band karo") || viewId.contains("close") -> {
                UiElementRole.CLOSE_BUTTON
            }
            // Back buttons
            combined.contains("navigate up") || combined.contains("back") || combined.contains("piche") || viewId.contains("back") -> {
                UiElementRole.BACK_BUTTON
            }
            // Switches / Checkboxes
            className.contains("checkbox") || className.contains("switch") || className.contains("togglebutton") -> {
                UiElementRole.CHECKBOX_SWITCH
            }
            // Buttons
            node.isClickable || className.contains("button") -> {
                UiElementRole.BUTTON
            }
            // Images
            className.contains("imageview") || className.contains("image") -> {
                UiElementRole.IMAGE_MEDIA
            }
            // Static text
            text.isNotBlank() -> {
                UiElementRole.STATIC_LABEL
            }
            else -> UiElementRole.UNKNOWN
        }
    }

    /**
     * Inspects a collected node tree to infer high-level screen state.
     */
    fun classifyScreenState(
        packageName: String,
        nodes: List<UiElementNode>,
        texts: List<String>
    ): Pair<ScreenStateType, String?> {
        val combinedText = (texts + nodes.map { it.displayLabel }).joinToString(" ").lowercase(Locale.ROOT)

        // 1. Error Dialog / Screen
        val errorKeywords = listOf("something went wrong", "error occurred", "connection failed", "try again", "no internet", "crash", "failed to load", "an error has occurred")
        for (kw in errorKeywords) {
            if (combinedText.contains(kw)) {
                val errorFound = texts.firstOrNull { it.lowercase(Locale.ROOT).contains(kw) } ?: kw
                return Pair(ScreenStateType.ERROR_DIALOG, errorFound)
            }
        }

        // 2. Loading State
        val loadingKeywords = listOf("loading...", "please wait", "buffering", "updating...", "fetching")
        val hasProgress = nodes.any { it.className.lowercase(Locale.ROOT).contains("progressbar") || it.className.lowercase(Locale.ROOT).contains("loading") }
        if (hasProgress || loadingKeywords.any { combinedText.contains(it) }) {
            return Pair(ScreenStateType.LOADING, null)
        }

        // 3. Permission Dialog
        if (combinedText.contains("allow") && (combinedText.contains("permission") || combinedText.contains("access to") || combinedText.contains("deny") || combinedText.contains("while using the app"))) {
            return Pair(ScreenStateType.PERMISSION_DIALOG, null)
        }

        // 4. Login / Authentication
        if ((combinedText.contains("sign in") || combinedText.contains("log in") || combinedText.contains("password") || combinedText.contains("welcome back")) &&
            nodes.any { it.role == UiElementRole.TEXT_FIELD || it.isPassword }) {
            return Pair(ScreenStateType.LOGIN_AUTH, null)
        }

        // 5. Search / Search Results
        if (nodes.any { it.role == UiElementRole.SEARCH_INPUT } || combinedText.contains("search results") || combinedText.contains("showing results for")) {
            return Pair(if (combinedText.contains("showing results") || combinedText.contains("results for") || nodes.size > 15) ScreenStateType.RESULTS else ScreenStateType.SEARCH, null)
        }

        // 6. Chat Conversation
        if (packageName.contains("whatsapp") || packageName.contains("telegram") || packageName.contains("messages") || combinedText.contains("type a message")) {
            return Pair(ScreenStateType.CHAT_CONVERSATION, null)
        }

        // 7. Settings View
        if (packageName.contains("settings") || combinedText.contains("network & internet") || combinedText.contains("connected devices") || combinedText.contains("apps & notifications")) {
            return Pair(ScreenStateType.SETTINGS_VIEW, null)
        }

        // 8. Media Playing
        if (nodes.any { it.role == UiElementRole.PAUSE_CONTROL } || combinedText.contains("now playing")) {
            return Pair(ScreenStateType.MEDIA_PLAYING, null)
        }

        // 9. Home Screen / Launcher
        if (packageName.contains("launcher") || packageName.contains("home") || combinedText.contains("google search widget")) {
            return Pair(ScreenStateType.HOME, null)
        }

        return Pair(ScreenStateType.UNKNOWN, null)
    }

    /**
     * Resolves user target intents (e.g., "search box", "play button", "3rd number wali chat", "this chat", "jo button saamne hai")
     * using semantic mapping, visual row indexing, positional sorting, and confidence scoring.
     */
    fun resolveTarget(
        targetQuery: String,
        screenContext: ScreenContext
    ): TargetResolutionResult {
        if (!screenContext.isAccessibilityAvailable) {
            return TargetResolutionResult(
                confidence = 0.0f,
                matchReason = "Accessibility not available",
                needsClarification = false
            )
        }

        val clean = targetQuery.trim().lowercase(Locale.ROOT)
        val candidates = screenContext.allInteractiveElements

        if (candidates.isEmpty() && screenContext.visualRows.isEmpty()) {
            AffyDiagnostics.logScreenStage("TARGET_NOT_FOUND", details = "No interactive elements on screen for '$targetQuery'")
            return TargetResolutionResult(
                confidence = 0.0f,
                matchReason = "No interactive elements found on screen",
                needsClarification = true,
                clarificationPrompt = "Screen par koi click hone wala option nahi mil raha."
            )
        }

        AffyDiagnostics.logScreenStage("SCREEN_ANALYSIS_STARTED", details = "Resolving target: '$targetQuery' among ${candidates.size} elements / ${screenContext.visualRows.size} visual rows")

        // Specialized Target Resolution for "Communities" (WhatsApp, etc.)
        if (clean == "communities" || clean == "community" || clean.contains("communities") || clean.contains("community")) {
            val communityMatches = candidates.filter { node ->
                val t = node.text.lowercase(Locale.ROOT)
                val d = node.contentDescription.lowercase(Locale.ROOT)
                val v = (node.viewId ?: "").lowercase(Locale.ROOT)
                t == "communities" || t == "community" || d == "communities" || d == "community" ||
                        t.contains("communities") || d.contains("communities") || v.contains("communities")
            }.distinctBy { it.boundsInScreen }

            if (communityMatches.size == 1) {
                val match = communityMatches.first()
                AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Exact Communities target resolved: ${match.displayLabel} (confidence 0.98)")
                return TargetResolutionResult(
                    matchedNode = match,
                    confidence = 0.98f,
                    matchReason = "Exact match for visible text/content description/view ID 'Communities'"
                )
            } else if (communityMatches.size > 1) {
                val labels = communityMatches.map { it.displayLabel }.distinct()
                AffyDiagnostics.logScreenStage("TARGET_AMBIGUOUS", details = "Multiple Communities elements on screen: $labels")
                return TargetResolutionResult(
                    matchedNode = null,
                    confidence = 0.60f,
                    matchReason = "Multiple Communities options found on screen",
                    alternativeSuggestions = labels,
                    needsClarification = true,
                    clarificationPrompt = "Boss, screen par ek se zyada Communities options hain. Aap kaunsa click karna chahte hain?"
                )
            } else {
                // Strict rule: Never substitute a generic clickable element for Communities!
                AffyDiagnostics.logScreenStage("TARGET_NOT_FOUND", details = "Communities target not found on screen")
                return TargetResolutionResult(
                    confidence = 0.0f,
                    matchReason = "Communities button not found on screen",
                    needsClarification = true,
                    clarificationPrompt = "Boss, mujhe Communities ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
                )
            }
        }

        // 1. Positional & Ordinal visual row resolution: "3rd number wali chat", "pehli video", "second option", "neeche wala", etc.
        val positionalMatch = resolvePositionalQuery(clean, screenContext)
        if (positionalMatch != null) {
            val node = positionalMatch.first
            val row = positionalMatch.second
            AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Matched positional target: ${node.displayLabel} (confidence 0.94)")
            return TargetResolutionResult(
                matchedNode = node,
                matchedRow = row,
                confidence = 0.94f,
                matchReason = "Matched spatial/ordinal query '$targetQuery'"
            )
        }

        // 2. Current Screen Context Targets: "this channel", "this video", "this chat", "ye button", "jo saamne hai"
        val currentScreenMatch = resolveCurrentScreenContextTarget(clean, screenContext)
        if (currentScreenMatch != null) {
            AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Matched current screen context: ${currentScreenMatch.displayLabel} (confidence 0.95)")
            return TargetResolutionResult(
                matchedNode = currentScreenMatch,
                confidence = 0.95f,
                matchReason = "Matched current visible screen context for '$targetQuery'"
            )
        }

        // 2. Color-based query handling: "red button", "blue icon", "laal wala", "green button", etc.
        val colorMatch = resolveColorQuery(clean, candidates)
        if (colorMatch != null) {
            AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Matched color target: ${colorMatch.displayLabel} for '$targetQuery'")
            return TargetResolutionResult(
                matchedNode = colorMatch,
                confidence = 0.90f,
                matchReason = "Matched color/visual descriptor in '$targetQuery'"
            )
        }

        // 3. Semantic Role queries (e.g., search, like, play, close, settings, send)
        val roleMatch = resolveRoleQuery(clean, candidates)
        if (roleMatch != null) {
            AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Matched semantic role: ${roleMatch.role} (${roleMatch.displayLabel})")
            return TargetResolutionResult(
                matchedNode = roleMatch,
                confidence = 0.88f,
                matchReason = "Matched semantic role for '$targetQuery'"
            )
        }

        // 4. Exact and Fuzzy Text / Label matching with confidence scoring
        val scoredNodes = mutableListOf<Pair<UiElementNode, Float>>()
        val alternatives = mutableListOf<String>()

        for (node in candidates) {
            val label = node.displayLabel.lowercase(Locale.ROOT)
            val score = calculateMatchScore(clean, label, node)

            if (score > 0.35f) {
                scoredNodes.add(Pair(node, score))
                alternatives.add(node.displayLabel)
            }
        }

        scoredNodes.sortByDescending { it.second }

        if (scoredNodes.isEmpty()) {
            val prompt = if (clean.contains("communit")) {
                "Boss, mujhe Communities ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
            } else {
                "Boss, mujhe '$targetQuery' ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
            }
            return TargetResolutionResult(
                confidence = 0.0f,
                matchReason = "No matching target found",
                alternativeSuggestions = emptyList(),
                needsClarification = true,
                clarificationPrompt = prompt
            )
        }

        val topMatch = scoredNodes.first()
        val highestScore = topMatch.second
        val bestNode = topMatch.first

        // Disambiguation check: If top two candidates are different elements with very close scores, ask user
        if (scoredNodes.size > 1) {
            val secondMatch = scoredNodes[1]
            if (highestScore >= 0.60f && secondMatch.second >= 0.55f &&
                (highestScore - secondMatch.second) < 0.10f &&
                bestNode.displayLabel != secondMatch.first.displayLabel
            ) {
                val label1 = bestNode.displayLabel
                val label2 = secondMatch.first.displayLabel
                return TargetResolutionResult(
                    matchedNode = null,
                    confidence = highestScore,
                    matchReason = "Ambiguous between multiple options",
                    alternativeSuggestions = listOf(label1, label2),
                    needsClarification = true,
                    clarificationPrompt = "Boss, screen par ek se zyada options hain: '$label1' ya '$label2'. Aap kaunsa click karna chahte hain?"
                )
            }
        }

        AffyDiagnostics.logScreenStage("TARGET_CONFIDENCE", details = "Best score: $highestScore for query: '$targetQuery'")

        // Low Confidence Guardrail (Strict rule: Ask instead of guessing blindly)
        if (highestScore < 0.65f) {
            val suggestions = alternatives.distinct().take(3)
            val notFoundPrompt = if (clean.contains("communit")) {
                "Boss, mujhe Communities ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
            } else {
                "Boss, mujhe '$targetQuery' ka exact button nazar nahi aa raha. Aap screen par uski jagah bata dein."
            }

            AffyDiagnostics.logScreenStage("TARGET_NOT_FOUND", details = "Confidence $highestScore below threshold for '$targetQuery'")
            return TargetResolutionResult(
                matchedNode = null,
                confidence = highestScore,
                matchReason = "Low confidence match",
                alternativeSuggestions = suggestions,
                needsClarification = true,
                clarificationPrompt = notFoundPrompt
            )
        }

        AffyDiagnostics.logScreenStage("TARGET_FOUND", details = "Resolved '$targetQuery' -> ${bestNode.displayLabel} ($highestScore)")
        return TargetResolutionResult(
            matchedNode = bestNode,
            confidence = highestScore,
            matchReason = "Score $highestScore on label '${bestNode.displayLabel}'",
            alternativeSuggestions = alternatives.distinct().take(3)
        )
    }

    private fun resolveColorQuery(clean: String, candidates: List<UiElementNode>): UiElementNode? {
        val colorMap = mapOf(
            "red" to listOf("red", "laal", "lal"),
            "green" to listOf("green", "hara", "hari", "sabz"),
            "blue" to listOf("blue", "neela", "neeli"),
            "yellow" to listOf("yellow", "peela", "peeli", "zard"),
            "black" to listOf("black", "kala", "kali", "siyah"),
            "white" to listOf("white", "safed", "chitta"),
            "pink" to listOf("pink", "gulabi"),
            "orange" to listOf("orange", "narangi", "santri"),
            "purple" to listOf("purple", "baingani", "jamni")
        )

        var requestedColor: String? = null
        for ((color, synonyms) in colorMap) {
            if (synonyms.any { clean.contains(it) }) {
                requestedColor = color
                break
            }
        }

        if (requestedColor == null) return null

        val colorSynonyms = colorMap[requestedColor] ?: listOf(requestedColor)

        return candidates.firstOrNull { node ->
            val label = node.displayLabel.lowercase(Locale.ROOT)
            val desc = node.contentDescription.lowercase(Locale.ROOT)
            val viewId = node.viewId?.lowercase(Locale.ROOT) ?: ""
            colorSynonyms.any { syn -> label.contains(syn) || desc.contains(syn) || viewId.contains(syn) }
        }
    }

    private fun resolveCurrentScreenContextTarget(clean: String, screenContext: ScreenContext): UiElementNode? {
        val candidates = screenContext.allInteractiveElements

        // 1. "this button", "that button", "jo button saamne hai", "saamne wala button", "ye button"
        if (clean == "this button" || clean == "that button" || clean.contains("jo button saamne") ||
            clean.contains("jo samne button") || clean.contains("saamne wala button") || clean.contains("samne wala button") ||
            clean.contains("ye button") || clean.contains("is button") || clean == "button"
        ) {
            return screenContext.focusedNode ?: candidates.firstOrNull { it.role == UiElementRole.BUTTON || it.isClickable }
        }

        // 2. "this video", "current video", "ye video", "is video", "jo video chal rahi hai"
        if (clean == "this video" || clean == "current video" || clean.contains("ye video") || clean.contains("is video") || clean.contains("video chal")) {
            return candidates.firstOrNull { it.role == UiElementRole.PLAY_CONTROL || it.role == UiElementRole.PAUSE_CONTROL || it.role == UiElementRole.VIDEO_CARD || it.role == UiElementRole.IMAGE_MEDIA }
                ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("video") }
        }

        // 3. "this channel", "current channel", "is channel", "ye channel", "channel"
        if (clean == "this channel" || clean == "current channel" || clean.contains("is channel") || clean.contains("ye channel") || clean.contains("channel ko")) {
            return candidates.firstOrNull { it.role == UiElementRole.VIDEO_CARD || it.role == UiElementRole.BUTTON || it.displayLabel.lowercase().contains("channel") }
                ?: candidates.firstOrNull { it.isClickable }
        }

        // 3. "current chat", "this chat", "is chat", "ye chat", "jo chat open hai"
        if (clean == "current chat" || clean == "this chat" || clean.contains("is chat") || clean.contains("ye chat") || clean.contains("chat open")) {
            return candidates.firstOrNull { it.role == UiElementRole.TEXT_FIELD || it.role == UiElementRole.SEND_BUTTON }
                ?: candidates.firstOrNull()
        }

        // 4. "video title", "title", "video ka naam", "is video ka title", "ye video ka naam"
        if (clean.contains("title") || clean.contains("video ka naam") || clean.contains("naam batao")) {
            return candidates.firstOrNull { it.viewId?.contains("title") == true || it.viewId?.contains("video_title") == true }
                ?: candidates.firstOrNull { it.role == UiElementRole.STATIC_LABEL && it.displayLabel.length > 5 }
        }

        // 5. "caption", "description", "caption sunao", "caption parho"
        if (clean.contains("caption") || clean.contains("description")) {
            return candidates.firstOrNull { it.viewId?.contains("desc") == true || it.viewId?.contains("caption") == true }
                ?: candidates.firstOrNull { it.displayLabel.length > 10 }
        }

        return null
    }

    private fun resolvePositionalQuery(clean: String, screenContext: ScreenContext): Pair<UiElementNode, VisualRowItem?>? {
        val visualRows = screenContext.visualRows
        val candidates = screenContext.allInteractiveElements
        val validCandidates = candidates.filter { !it.boundsInScreen.isEmpty && it.boundsInScreen.width() > 0 && it.boundsInScreen.height() > 0 }

        val sortedByX = validCandidates.sortedBy { it.boundsInScreen.left }

        // 1. Directional spatial requests
        if (clean.contains("left wala") || clean.contains("baayein wala") || clean.contains("left option") || clean.contains("left button") || clean.contains("left side")) {
            val node = sortedByX.firstOrNull()
            return if (node != null) Pair(node, null) else null
        }
        if (clean.contains("right wala") || clean.contains("daayein wala") || clean.contains("right option") || clean.contains("right button") || clean.contains("right side")) {
            val node = sortedByX.lastOrNull()
            return if (node != null) Pair(node, null) else null
        }
        if (clean.contains("beech wala") || clean.contains("middle option") || clean.contains("center") || clean.contains("darmian wala")) {
            if (visualRows.size >= 3) {
                val row = visualRows[visualRows.size / 2]
                val clickNode = row.clickableNode ?: row.memberNodes.firstOrNull()
                if (clickNode != null) return Pair(clickNode, row)
            }
            val node = if (validCandidates.size >= 3) validCandidates[validCandidates.size / 2] else validCandidates.firstOrNull()
            return if (node != null) Pair(node, null) else null
        }
        if (clean.contains("sabse upar") || clean.contains("top option") || clean.contains("top wala") || clean.contains("upar wala") || clean.contains("first option") || clean.contains("pehli option")) {
            if (visualRows.isNotEmpty()) {
                val row = visualRows.first()
                val clickNode = row.clickableNode ?: row.memberNodes.firstOrNull()
                if (clickNode != null) {
                    AffyDiagnostics.logScreenStage("UI_ORDINAL_RESOLVED", details = "Top row resolved -> '${clickNode.displayLabel}' at ${clickNode.boundsInScreen}")
                    return Pair(clickNode, row)
                }
            }
            val match = validCandidates.minByOrNull { it.boundsInScreen.top }
            if (match != null) {
                AffyDiagnostics.logScreenStage("UI_ORDINAL_RESOLVED", details = "Top item resolved -> '${match.displayLabel}' at ${match.boundsInScreen}")
                return Pair(match, null)
            }
        }
        if (clean.contains("sabse neeche") || clean.contains("bottom option") || clean.contains("last option") || clean.contains("aakhri") || clean.contains("akhri") || clean.contains("neeche wala")) {
            if (visualRows.isNotEmpty()) {
                val row = visualRows.last()
                val clickNode = row.clickableNode ?: row.memberNodes.firstOrNull()
                if (clickNode != null) {
                    AffyDiagnostics.logScreenStage("UI_ORDINAL_RESOLVED", details = "Bottom row resolved -> '${clickNode.displayLabel}' at ${clickNode.boundsInScreen}")
                    return Pair(clickNode, row)
                }
            }
            val match = validCandidates.maxByOrNull { it.boundsInScreen.bottom }
            if (match != null) {
                AffyDiagnostics.logScreenStage("UI_ORDINAL_RESOLVED", details = "Bottom item resolved -> '${match.displayLabel}' at ${match.boundsInScreen}")
                return Pair(match, null)
            }
        }

        // 2. Parse 1-based numeric or word ordinal
        var requestedIndex: Int? = null

        val digitMatch = Regex("(?:^|\\b)(\\d+)(?:st|nd|rd|th)?(?:\\s*number|\\s*no\\.?)?(?:\\s*wali|\\s*wala|\\s*walay|\\s*wale)?(?:\\s*chat|\\s*option|\\s*item|\\s*button|\\s*row|\\s*video|\\s*result)?\\b").find(clean)
        if (digitMatch != null) {
            requestedIndex = digitMatch.groupValues[1].toIntOrNull()
        }

        if (requestedIndex == null) {
            when {
                clean.contains("first") || clean.contains("1st") || clean.contains("pehla") || clean.contains("pehli") || clean.contains("shuru") -> requestedIndex = 1
                clean.contains("second") || clean.contains("2nd") || clean.contains("doosra") || clean.contains("dusra") || clean.contains("doosri") || clean.contains("dusri") -> requestedIndex = 2
                clean.contains("third") || clean.contains("3rd") || clean.contains("teesra") || clean.contains("teesri") || clean.contains("tisra") || clean.contains("tisri") -> requestedIndex = 3
                clean.contains("fourth") || clean.contains("4th") || clean.contains("chautha") || clean.contains("chauthi") || clean.contains("chotha") || clean.contains("chothi") -> requestedIndex = 4
                clean.contains("fifth") || clean.contains("5th") || clean.contains("panchwa") || clean.contains("panchwi") || clean.contains("paanchwa") || clean.contains("paanchwi") -> requestedIndex = 5
                clean.contains("sixth") || clean.contains("6th") || clean.contains("chatha") || clean.contains("chathi") || clean.contains("chata") || clean.contains("chati") || clean.contains("chehwa") || clean.contains("chehwi") -> requestedIndex = 6
                clean.contains("seventh") || clean.contains("7th") || clean.contains("saatwa") || clean.contains("saatwi") || clean.contains("satwa") || clean.contains("satwi") -> requestedIndex = 7
                clean.contains("eighth") || clean.contains("8th") || clean.contains("aathwa") || clean.contains("aathwi") || clean.contains("athwa") || clean.contains("athwi") -> requestedIndex = 8
                clean.contains("ninth") || clean.contains("9th") || clean.contains("nauwa") || clean.contains("nauwi") || clean.contains("nawa") || clean.contains("nawi") -> requestedIndex = 9
                clean.contains("tenth") || clean.contains("10th") || clean.contains("daswa") || clean.contains("daswi") -> requestedIndex = 10
            }
        }

        if (requestedIndex != null && requestedIndex > 0) {
            val zeroIndex = requestedIndex - 1
            if (visualRows.isNotEmpty()) {
                val row = if (zeroIndex < visualRows.size) visualRows[zeroIndex] else visualRows.last()
                val clickNode = row.clickableNode ?: row.memberNodes.firstOrNull()
                if (clickNode != null) {
                    AffyDiagnostics.logScreenStage(
                        "UI_ORDINAL_RESOLVED",
                        details = "Ordinal $requestedIndex resolved to VisualRow ${row.rowIndex} ('${row.primaryLabel}') bounds=${row.bounds}"
                    )
                    return Pair(clickNode, row)
                }
            } else if (validCandidates.isNotEmpty()) {
                val sortedNodes = validCandidates.sortedWith(compareBy({ it.boundsInScreen.top }, { it.boundsInScreen.left }))
                val node = if (zeroIndex < sortedNodes.size) sortedNodes[zeroIndex] else sortedNodes.last()
                AffyDiagnostics.logScreenStage(
                    "UI_ORDINAL_RESOLVED",
                    details = "Ordinal $requestedIndex resolved to node '${node.displayLabel}' bounds=${node.boundsInScreen}"
                )
                return Pair(node, null)
            }
        }

        return null
    }

    private fun resolveRoleQuery(clean: String, candidates: List<UiElementNode>): UiElementNode? {
        return when {
            clean.contains("search box") || clean.contains("search bar") || clean.contains("search field") || clean.contains("search input") || clean.contains("search dhoondo") -> {
                candidates.firstOrNull { it.role == UiElementRole.SEARCH_INPUT } ?: candidates.firstOrNull { it.isEditable }
            }
            clean.contains("keyboard open") || clean.contains("keyboard kholo") || clean.contains("text box") || clean.contains("input box") || clean.contains("type box") -> {
                candidates.firstOrNull { it.isEditable } ?: candidates.firstOrNull { it.role == UiElementRole.TEXT_FIELD }
            }
            clean.contains("play button") || clean.contains("play karo") || clean.contains("video play") -> {
                candidates.firstOrNull { it.role == UiElementRole.PLAY_CONTROL }
            }
            clean.contains("pause button") || clean.contains("pause karo") || clean.contains("video pause") || clean.contains("roko") -> {
                candidates.firstOrNull { it.role == UiElementRole.PAUSE_CONTROL }
            }
            clean == "like" || clean.contains("like button") || clean.contains("heart button") || clean.contains("video like") || clean.contains("like karo") || clean.contains("pasand karo") || clean.contains("is video ko like") || clean.contains("heart dabao") -> {
                candidates.firstOrNull { it.role == UiElementRole.LIKE_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("like") || it.displayLabel.lowercase().contains("heart") }
            }
            clean == "send" || clean == "bhejo" || clean == "bhej do" || clean.contains("send button") || clean.contains("bhejne wala") || clean.contains("message send") || clean.contains("send karo") || clean.contains("send kar do") || clean.contains("ye message send") || clean.contains("message bhej") -> {
                candidates.firstOrNull { it.role == UiElementRole.SEND_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("send") || it.displayLabel.lowercase().contains("bhejo") }
            }
            clean.contains("settings kholo") || clean.contains("settings button") || clean.contains("gear icon") -> {
                candidates.firstOrNull { it.role == UiElementRole.SETTINGS_BUTTON }
            }
            clean.contains("close button") || clean.contains("cross button") || clean.contains("dismiss") || clean.contains("cancel") || clean.contains("band karo") -> {
                candidates.firstOrNull { it.role == UiElementRole.CLOSE_BUTTON }
            }
            clean.contains("back button") || clean.contains("piche jao") || clean.contains("back jao") -> {
                candidates.firstOrNull { it.role == UiElementRole.BACK_BUTTON }
            }
            clean.contains("subscribe") || clean.contains("sub button") -> {
                candidates.firstOrNull { it.displayLabel.lowercase().contains("subscribe") }
            }
            clean.contains("follow") || clean.contains("follow button") -> {
                candidates.firstOrNull { it.displayLabel.lowercase().contains("follow") }
            }
            clean == "post" || clean == "comment" || clean.contains("post karo") || clean.contains("post button") || clean.contains("comment post") || clean.contains("comment likho aur post") || clean.contains("comment karo") || clean.contains("tabsarah") || clean.contains("comment box") || clean.contains("comment button") || clean.contains("publish") || clean.contains("submit") -> {
                candidates.firstOrNull { it.role == UiElementRole.COMMENT_BUTTON } ?: candidates.firstOrNull { it.role == UiElementRole.SEND_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("post") || it.displayLabel.lowercase().contains("comment") || it.displayLabel.lowercase().contains("send") } ?: candidates.firstOrNull { it.isEditable }
            }
            clean.contains("reply") || clean.contains("reply karo") || clean.contains("reply button") || clean.contains("jawab do") -> {
                candidates.firstOrNull { it.role == UiElementRole.COMMENT_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("reply") }
            }
            clean.contains("share") || clean.contains("baanto") || clean.contains("share button") || clean.contains("share karo") || clean.contains("video share") -> {
                candidates.firstOrNull { it.role == UiElementRole.SHARE_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("share") }
            }
            clean.contains("clear") || clean.contains("saf karo") || clean.contains("mitao") || clean.contains("delete text") || clean.contains("clear text") -> {
                candidates.firstOrNull { it.role == UiElementRole.CLEAR_BUTTON } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("clear") }
            }
            clean.contains("switch tab") -> {
                candidates.firstOrNull { it.role == UiElementRole.TAB_ITEM }
            }
            clean.contains("more options") || clean.contains("three dots") || clean.contains("menu") || clean.contains("options") -> {
                candidates.firstOrNull { it.role == UiElementRole.MENU_OPTION } ?: candidates.firstOrNull { it.displayLabel.lowercase().contains("more") || it.displayLabel.lowercase().contains("menu") }
            }
            else -> null
        }
    }

    private fun calculateMatchScore(query: String, label: String, node: UiElementNode): Float {
        val textLower = node.text.lowercase(Locale.ROOT)
        val descLower = node.contentDescription.lowercase(Locale.ROOT)
        val viewIdLower = (node.viewId ?: "").lowercase(Locale.ROOT)

        if (label == query || textLower == query || descLower == query) return 1.0f
        if (textLower.contains(query) || descLower.contains(query)) return 0.90f
        if (viewIdLower.endsWith("/$query") || viewIdLower.contains(query)) return 0.88f
        if (label.isNotBlank() && label.contains(query)) return 0.85f
        if (label.isNotBlank() && query.contains(label) && label.length > 3) return 0.75f

        val queryWords = query.split(" ").filter { it.length > 2 }
        val labelWords = label.split(" ").filter { it.length > 2 }

        var matchingWordCount = 0
        for (qw in queryWords) {
            if (labelWords.any { it.contains(qw) || qw.contains(it) } || textLower.contains(qw) || descLower.contains(qw)) {
                matchingWordCount++
            }
        }

        if (queryWords.isNotEmpty() && matchingWordCount > 0) {
            return (matchingWordCount.toFloat() / queryWords.size.toFloat()) * 0.70f
        }

        return 0.0f
    }
}
