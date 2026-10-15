package com.example.agent.task

import com.example.data.model.ActionTypes
import com.example.data.model.AffyPlan
import java.util.Locale

object DynamicPlanGenerator {

    /**
     * Checks if a prompt indicates a multi-step task requiring sequential dynamic planning.
     */
    fun isMultiStepTaskPrompt(prompt: String): Boolean {
        val clean = prompt.lowercase(Locale.ROOT).trim()
        return (clean.contains(" aur ") || clean.contains(" then ") || clean.contains(" karke ") || clean.contains(" and ") || clean.contains(",")) &&
                (clean.contains("search") || clean.contains("kholo") || clean.contains("open") || clean.contains("chalao") || clean.contains("bhejo") || clean.contains("batao"))
    }

    /**
     * Deconstructs multi-step natural requests into ordered steps.
     * Examples:
     * - "Chrome kholo, Google pe X search karo aur result ka naam batao."
     * - "YouTube open karo aur Imran Khan search karke chalao"
     * - "Settings kholo aur Bluetooth on karo"
     * - "WhatsApp kholo aur Ali ko message bhejo"
     *
     * Generates steps dynamically based on conjunctions ("aur", "phir", "and", "then", ",", "karke")
     * and intent verbs without needing rigid hardcoded full-sentence lists.
     */
    fun createPlanFromPrompt(prompt: String): List<AgentTaskStep> {
        val clean = prompt.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // Split into logical clauses by conjunctions
        val clauses = splitIntoClauses(clean)
        val steps = mutableListOf<AgentTaskStep>()

        for ((index, clause) in clauses.withIndex()) {
            val step = parseClauseToStep(index, clause, lower, clean)
            if (step != null) {
                steps.add(step)
            }
        }

        // If no clauses successfully parsed into structured steps, fallback to goal-oriented interpretation
        if (steps.isEmpty()) {
            steps.addAll(generateFallbackSteps(clean, lower))
        }

        return steps
    }

    private fun splitIntoClauses(text: String): List<String> {
        // Split on common connectors: comma, "aur", "phir", "and", "then", "karke", "ke baad"
        val delimiters = Regex("(?i)\\s*(?:,|\\s+aur\\s+|\\s+phir\\s+|\\s+and\\s+|\\s+then\\s+|\\s+karke\\s+|\\s+ke baad\\s+)\\s*")
        return text.split(delimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    private fun parseClauseToStep(
        index: Int,
        clause: String,
        fullLower: String,
        fullOriginal: String
    ): AgentTaskStep? {
        val lower = clause.lowercase(Locale.ROOT)

        // 1. COMMENT ON POST ("comment mein likho: Amazing", "comment karo: nice video", "is video par comment karo")
        if (lower.contains("comment") || lower.contains("tabsarah")) {
            val commentContent = clause.replace(Regex("(?i).*?\\b(comment karo|comment mein likho|comment likho|comment|tabsarah|write comment|comment:)\\b[:\\s]*"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Post comment ${if (commentContent.isNotBlank()) "'$commentContent'" else ""}",
                actionType = ActionTypes.COMMENT_ON_POST,
                text = commentContent.ifBlank { null },
                expectedOutcome = "Comment submitted on current screen"
            )
        }

        // 2. SHARE POST / VIDEO ("share karo", "ye video share karo", "post share karo")
        if (lower.contains("share") || lower.contains("baanto")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Share current post/content",
                actionType = ActionTypes.SHARE_POST,
                expectedOutcome = "Share action opened"
            )
        }

        // 3. LONG PRESS ("is par long press karo", "dabaye rakho")
        if (lower.contains("long press") || lower.contains("dabaye rakho") || lower.contains("daba kar rakho")) {
            val target = lower.replace(Regex("(?i)\\b(long press|dabaye rakho|daba kar rakho|karo|kar do|par|pe|button|element)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Long press on '${if (target.isNotBlank()) target else "target"}'",
                actionType = ActionTypes.LONG_PRESS,
                target = target.ifBlank { null },
                expectedOutcome = "Target long-pressed"
            )
        }

        // 4. CLEAR TEXT ("clear karo", "text mita do", "saf karo")
        if (lower.contains("clear karo") || lower.contains("mita do") || lower.contains("saf karo") || lower.contains("delete text")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Clear text field",
                actionType = ActionTypes.CLEAR_TEXT,
                expectedOutcome = "Input field cleared"
            )
        }

        // 4.1 COPY TEXT / CODE ("copy karo", "text copy karo", "code copy karo", "copy")
        if (lower.contains("copy karo") || lower.contains("copy kar lo") || lower.contains("copy code") || lower.contains("copy text") || lower == "copy") {
            val target = clause.replace(Regex("(?i)\\b(copy karo|copy kar lo|copy|karo|kar do|text|code|ye|yeh)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Copy text/code to clipboard",
                actionType = ActionTypes.COPY_TEXT,
                text = target.ifBlank { null },
                expectedOutcome = "Text copied to clipboard"
            )
        }

        // 4.2 PASTE TEXT / CODE ("paste karo", "code paste karo", "paste")
        if (lower.contains("paste karo") || lower.contains("paste kar do") || lower.contains("paste code") || lower.contains("paste text") || lower == "paste") {
            val target = clause.replace(Regex("(?i)\\b(paste karo|paste kar do|paste|karo|kar do|text|code|mein|par|pe)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Paste content from clipboard",
                actionType = ActionTypes.PASTE_TEXT,
                target = target.ifBlank { null },
                expectedOutcome = "Clipboard content pasted"
            )
        }

        // 4.3 SAVE FILE / ACTION ("save karo", "file save karo", "save kar do")
        if (lower.contains("save karo") || lower.contains("file save") || lower.contains("save kar do") || lower == "save") {
            return AgentTaskStep(
                stepIndex = index,
                description = "Save document/file",
                actionType = ActionTypes.TAP,
                target = "Save",
                expectedOutcome = "File saved and verified"
            )
        }

        // 4.4 RESEARCH TOPIC / IDEAS ("ideas research karo", "research karo", "ideas note karo")
        if (lower.contains("research karo") || lower.contains("ideas research") || lower.contains("research") || lower.contains("ideas dhoondo")) {
            val topic = clause.replace(Regex("(?i).*?\\b(research karo|ideas research karo|research|ideas dhoondo|ideas note karo)[:\\s]*"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Research ideas for ${if (topic.isNotBlank()) topic else "topic"}",
                actionType = ActionTypes.RESEARCH_TOPIC,
                text = topic.ifBlank { "ideas" },
                expectedOutcome = "Ideas researched and summarized"
            )
        }

        // 5. SCROLL / SWIPE ("neeche scroll karo", "upar scroll karo", "scroll down", "scroll up")
        if (lower.contains("scroll") || lower.contains("swipe")) {
            val isUp = lower.contains("up") || lower.contains("upar")
            return AgentTaskStep(
                stepIndex = index,
                description = if (isUp) "Scroll up" else "Scroll down",
                actionType = if (isUp) ActionTypes.SCROLL_UP else ActionTypes.SCROLL_DOWN,
                expectedOutcome = "Screen scrolled"
            )
        }

        // 6. NAVIGATION ("back jao", "piche jao", "home jao")
        if (lower.contains("back jao") || lower.contains("piche jao") || lower.contains("go back")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Navigate back",
                actionType = ActionTypes.GO_BACK,
                expectedOutcome = "Navigated back"
            )
        }
        if (lower.contains("home jao") || lower.contains("home screen") || lower.contains("go home")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Go to home screen",
                actionType = ActionTypes.GO_HOME,
                expectedOutcome = "Navigated to home"
            )
        }

        // 6.1 WHATSAPP CHAT OPEN / DIRECT MESSAGE ("Ali ki chat open karo", "Ali ki chat kholo", "Ali se chat kholo")
        if (lower.contains("chat open karo") || lower.contains("chat kholo") || lower.contains("ki chat")) {
            val contactName = clause.replace(Regex("(?i)\\b(whatsapp|chat|open karo|kholo|khol do|ki|se|ko|par|open)\\b"), "").trim(' ', '"', '\'')
            if (contactName.isNotBlank()) {
                return AgentTaskStep(
                    stepIndex = index,
                    description = "Open chat with $contactName",
                    actionType = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    target = contactName,
                    expectedOutcome = "Chat with $contactName opened"
                )
            }
        }

        // 6.2 WHATSAPP / UNIVERSAL MESSAGE COMPOSING & SENDING ("usko bolo main aa raha hoon", "usko message bhejo hello", "message karo: ...")
        if (lower.startsWith("usko bolo") || lower.startsWith("unko bolo") || lower.contains("bolo ") ||
            lower.contains("message bhejo") || lower.contains("message likho aur bhejo") || lower.contains("msg bhejo")) {
            val messageText = clause.replace(Regex("(?i).*?\\b(usko bolo|unko bolo|bolo|message bhejo|msg bhejo|message likho aur bhejo|message karo)[:\\s]*"), "").trim(' ', '"', '\'')
            if (messageText.isNotBlank()) {
                return AgentTaskStep(
                    stepIndex = index,
                    description = "Type and send message: '$messageText'",
                    actionType = ActionTypes.TYPE_TEXT,
                    text = messageText,
                    value = "send",
                    expectedOutcome = "Message sent and verified"
                )
            }
        }

        // 6.3 WHATSAPP / PHONE CALL ("Ali ko call lagao", "Ali ko WhatsApp call karo")
        if (lower.contains("call lagao") || lower.contains("call karo") || lower.contains("phone mila do")) {
            val isWhatsApp = lower.contains("whatsapp")
            val contactName = clause.replace(Regex("(?i)\\b(whatsapp|call lagao|call karo|phone mila do|ko|par|call)\\b"), "").trim(' ', '"', '\'')
            return AgentTaskStep(
                stepIndex = index,
                description = if (isWhatsApp) "Call $contactName on WhatsApp" else "Call $contactName",
                actionType = if (isWhatsApp) ActionTypes.WHATSAPP_CALL else ActionTypes.CALL_CONTACT,
                target = contactName.ifBlank { null },
                expectedOutcome = "Call initiated to $contactName"
            )
        }

        // 6.4 VIDEO / RESULT ORDINAL SELECTION ("third result open karo", "teesra video chalao", "pehli video chalao", "2nd result click karo")
        if (lower.contains("result") || lower.contains("video") || lower.contains("option")) {
            val hasOrdinal = lower.contains("1st") || lower.contains("pehli") || lower.contains("pehla") || lower.contains("first") ||
                    lower.contains("2nd") || lower.contains("doosri") || lower.contains("doosra") || lower.contains("second") ||
                    lower.contains("3rd") || lower.contains("teesri") || lower.contains("teesra") || lower.contains("third") ||
                    lower.contains("4th") || lower.contains("chauthi") || lower.contains("chautha") || lower.contains("fourth")
            if (hasOrdinal) {
                val ordNum = when {
                    lower.contains("2nd") || lower.contains("doosri") || lower.contains("doosra") || lower.contains("second") -> 2
                    lower.contains("3rd") || lower.contains("teesri") || lower.contains("teesra") || lower.contains("third") -> 3
                    lower.contains("4th") || lower.contains("chauthi") || lower.contains("chautha") || lower.contains("fourth") -> 4
                    else -> 1
                }
                return AgentTaskStep(
                    stepIndex = index,
                    description = "Select video/result #$ordNum",
                    actionType = ActionTypes.SELECT_VIDEO_RESULT,
                    value = ordNum.toString(),
                    expectedOutcome = "Item #$ordNum opened"
                )
            }
        }

        // 6.5 EMAIL ACTIONS & INTENT UNDERSTANDING (Universal Screen Intelligence)
        // Check Attachment ("attachment hai ya nahi", "is email mein attachment hai?", "attachment check karo")
        if (lower.contains("attachment") || lower.contains("file attached")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Check email for attachments",
                actionType = "CHECK_ATTACHMENT",
                expectedOutcome = "Attachment status observed on screen"
            )
        }

        // Email Reply ("is email ka reply draft karo", "iska reply likho: ...", "reply likho ...")
        if (lower.contains("reply draft") || lower.contains("reply likho") || lower.contains("reply bhejo") || lower.startsWith("reply: ") || lower.contains("iska reply")) {
            val replyText = clause.replace(Regex("(?i).*?\\b(reply draft karo|reply draft|reply likho|reply bhejo|iska reply likho|iska reply|reply:)\\b[:\\s]*"), "").trim(' ', '"', '\'')
            return AgentTaskStep(
                stepIndex = index,
                description = "Draft email reply ${if (replyText.isNotBlank()) "'$replyText'" else ""}",
                actionType = ActionTypes.REPLY_GMAIL,
                text = replyText.ifBlank { "Received, thanks!" },
                expectedOutcome = "Email reply drafted"
            )
        }

        // Read Email / Latest Email Summary ("latest email dekho", "unread email dekho", "email open karo aur mujhe batao", "latest email parho", "email parho")
        if ((lower.contains("email") || lower.contains("gmail") || lower.contains("mail")) &&
            (lower.contains("parho") || lower.contains("padho") || lower.contains("dekho") || lower.contains("summary") || lower.contains("batao") || lower.contains("read"))) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Observe and read latest email",
                actionType = ActionTypes.READ_GMAIL,
                expectedOutcome = "Email content read and summarized"
            )
        }

        // Unread Emails Check ("unread emails check karo", "check email", "mails check karo")
        if ((lower.contains("email") || lower.contains("gmail") || lower.contains("mail")) &&
            (lower.contains("check") || lower.contains("unread") || lower.contains("kitni hain") || lower.contains("koi mail aya"))) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Check unread emails",
                actionType = ActionTypes.CHECK_GMAIL,
                expectedOutcome = "Inbox status verified"
            )
        }

        // 7. OPEN KEYBOARD / TYPE TEXT ("keyboard open karo", "ye code likho", "search box mein type karo")
        if (lower.contains("keyboard open") || lower.contains("keyboard kholo")) {
            val textToType = if (lower.contains("aur") || lower.contains("likho") || lower.contains("type")) {
                fullOriginal.replace(Regex("(?i).*?\\b(likho|type karo|write)\\b"), "").trim()
            } else ""
            return AgentTaskStep(
                stepIndex = index,
                description = "Open keyboard and focus input",
                actionType = ActionTypes.OPEN_KEYBOARD,
                text = textToType.ifBlank { null },
                expectedOutcome = "Keyboard focused and ready for input"
            )
        }

        if (lower.contains("likho") || lower.contains("type karo") || lower.contains("write ")) {
            val textToType = clause.replace(Regex("(?i)\\b(likho|type karo|type|write|ye|yeh|aur)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Type text '$textToType'",
                actionType = ActionTypes.TYPE_TEXT,
                text = textToType,
                expectedOutcome = "Text typed into active field"
            )
        }

        // 2. NEXT VIDEO / SWIPE REELS ("next video", "agla video", "swipe up", "scroll video")
        if (lower.contains("next video") || lower.contains("agla video") || lower.contains("agla reel") || lower.contains("next reel")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Swipe to next video/reel",
                actionType = ActionTypes.NEXT_VIDEO,
                expectedOutcome = "Next video loaded on screen"
            )
        }

        // 3. LIKE POST / VIDEO ("like karo", "heart dabao", "post like")
        if (lower.contains("like karo") || lower.contains("heart dabao") || lower.contains("like kar do") || lower.contains("video like")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Like current post/video",
                actionType = ActionTypes.LIKE_POST,
                expectedOutcome = "Like button tapped"
            )
        }

        // 4. CLOSE APP / CLOSE PAGE ("page close karo", "app band karo", "cancel karo")
        if (lower.contains("band karo") || lower.contains("close karo") || lower.contains("close kar do") || lower.contains("dismiss")) {
            val target = lower.replace(Regex("(?i)\\b(band karo|close karo|close kar do|dismiss|page|app|ko)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Close ${if (target.isNotBlank()) target else "current view"}",
                actionType = ActionTypes.CLOSE_APP,
                target = target.ifBlank { null },
                expectedOutcome = "View/App closed"
            )
        }

        // 1. OPEN APP
        val openAppMatch = matchOpenAppClause(lower)
        if (openAppMatch != null && !lower.contains("search") && !lower.contains("dhoond") && !lower.contains("play")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Open $openAppMatch application",
                actionType = ActionTypes.OPEN_APP,
                target = openAppMatch,
                expectedOutcome = "App $openAppMatch is launched and active in foreground"
            )
        }

        // 2. SEARCH / MULTI-STEP APP SEARCH
        val searchMatch = matchSearchClause(lower, clause)
        if (searchMatch != null) {
            val (targetEngineOrApp, query) = searchMatch
            val isSpecificApp = targetEngineOrApp.equals("YouTube", ignoreCase = true) ||
                    targetEngineOrApp.equals("Spotify", ignoreCase = true) ||
                    targetEngineOrApp.equals("Chrome", ignoreCase = true)

            return AgentTaskStep(
                stepIndex = index,
                description = "Search for '$query' on $targetEngineOrApp",
                actionType = if (isSpecificApp) ActionTypes.MULTI_STEP_APP_COMMAND else ActionTypes.SEARCH_WEB,
                target = targetEngineOrApp,
                text = query,
                value = "search",
                expectedOutcome = "Search query '$query' submitted on $targetEngineOrApp and results visible"
            )
        }

        // 3. READ SCREEN / REPORT RESULTS ("aur result ka naam batao", "screen parho", "batao kya aya")
        if (lower.contains("batao") || lower.contains("parho") || lower.contains("read") ||
            lower.contains("dikhao") || lower.contains("explain") || lower.contains("result kya")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Observe and read screen content to extract requested information",
                actionType = ActionTypes.READ_SCREEN,
                expectedOutcome = "Screen elements parsed and requested summary captured"
            )
        }

        // 4. CLICK / TAP ELEMENT ("pehla result click karo", "tap search", "like karo")
        if (lower.contains("click") || lower.contains("tap") || lower.contains("dabao") || lower.contains("press")) {
            val target = lower.replace(Regex("(?i)\\b(click|tap|dabao|press|karo|kar do|button|element)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Tap on '${if (target.isNotBlank()) target else "element"}'",
                actionType = ActionTypes.TAP,
                target = target.ifBlank { "first_result" },
                expectedOutcome = "Element tapped successfully"
            )
        }

        // 5. MEDIA CONTROLS
        if (lower.contains("play") || lower.contains("chalao") || lower.contains("laga do")) {
            val query = lower.replace(Regex("(?i)\\b(play|chalao|laga do|song|video|track|karo)\\b"), "").trim()
            return AgentTaskStep(
                stepIndex = index,
                description = "Play '$query'",
                actionType = ActionTypes.MEDIA_PLAY,
                target = "media",
                text = query,
                expectedOutcome = "Media item started playing"
            )
        }

        // 6. VOLUME / DEVICE CONTROLS
        if (lower.contains("volume") || lower.contains("awaz")) {
            val isUp = lower.contains("up") || lower.contains("badhao") || lower.contains("teez")
            return AgentTaskStep(
                stepIndex = index,
                description = if (isUp) "Increase volume" else "Decrease volume",
                actionType = if (isUp) ActionTypes.VOLUME_UP else ActionTypes.VOLUME_DOWN,
                expectedOutcome = "Audio volume adjusted"
            )
        }

        // 7. DEVICE LOCK ("mobile lock kar do", "screen lock karo")
        if (lower.contains("lock") && (lower.contains("mobile") || lower.contains("phone") || lower.contains("screen") || lower.contains("device"))) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Lock device screen",
                actionType = ActionTypes.LOCK_DEVICE,
                expectedOutcome = "Device screen locked"
            )
        }

        // 8. SCREENSHOT
        if (lower.contains("screenshot") || lower.contains("screen capture")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Capture screenshot",
                actionType = ActionTypes.TAKE_SCREENSHOT,
                expectedOutcome = "Screenshot captured"
            )
        }

        // 9. SECURITY AUDIT
        if (lower.contains("security") || lower.contains("permission")) {
            return AgentTaskStep(
                stepIndex = index,
                description = "Inspect security & permissions",
                actionType = ActionTypes.ANALYZE_SECURITY,
                target = clause,
                expectedOutcome = "Security report generated"
            )
        }

        // 7. FLASHLIGHT
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("batti")) {
            val on = !lower.contains("off") && !lower.contains("band")
            return AgentTaskStep(
                stepIndex = index,
                description = if (on) "Turn torch ON" else "Turn torch OFF",
                actionType = ActionTypes.TOGGLE_FLASHLIGHT,
                value = on.toString(),
                expectedOutcome = "Torch state updated to $on"
            )
        }

        return null
    }

    private fun matchOpenAppClause(clauseLower: String): String? {
        val knownApps = listOf(
            "chrome", "youtube", "whatsapp", "spotify", "settings",
            "camera", "maps", "gmail", "calculator", "clock",
            "instagram", "facebook", "tiktok", "gallery",
            "notepad", "notes", "editor", "code editor", "chatgpt",
            "gemini", "telegram", "keep", "files", "browser"
        )
        val hasOpenVerb = clauseLower.contains("kholo") ||
                clauseLower.contains("open") ||
                clauseLower.contains("launch") ||
                clauseLower.contains("chalao") ||
                clauseLower.contains("start")

        for (app in knownApps) {
            if (clauseLower.contains(app) && (hasOpenVerb || clauseLower.startsWith(app))) {
                return app.replaceFirstChar { it.uppercase() }
            }
        }

        // Generic dynamic matching: e.g. "X kholo" or "open X"
        if (hasOpenVerb) {
            val words = clauseLower.split(" ").filter { it.isNotBlank() }
            val kholoIndex = words.indexOfFirst { it == "kholo" || it == "open" || it == "launch" }
            if (kholoIndex > 0) {
                val candidate = words[kholoIndex - 1]
                if (candidate.length > 2 && candidate != "app" && candidate != "aur" && candidate != "phir") {
                    return candidate.replaceFirstChar { it.uppercase() }
                }
            } else if (kholoIndex == 0 && words.size > 1) {
                val candidate = words[1]
                if (candidate.length > 2 && candidate != "app" && candidate != "aur" && candidate != "phir") {
                    return candidate.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return null
    }

    private fun matchSearchClause(clauseLower: String, clauseOriginal: String): Pair<String, String>? {
        if (!clauseLower.contains("search") && !clauseLower.contains("dhoondo") && !clauseLower.contains("find")) {
            return null
        }

        val targetApp = when {
            clauseLower.contains("google") -> "Google"
            clauseLower.contains("chrome") -> "Chrome"
            clauseLower.contains("youtube") -> "YouTube"
            else -> "Web"
        }

        // Extract the search query
        var query = clauseOriginal
            .replace(Regex("(?i)\\b(google|chrome|youtube|pe|par|on|search|dhoondo|find|karo|kar do|aur|then)\\b"), " ")
            .trim()

        if (query.isBlank()) {
            query = "latest updates"
        }

        return Pair(targetApp, query)
    }

    private fun generateFallbackSteps(clean: String, lower: String): List<AgentTaskStep> {
        // Fallback for conversational or unparsed tasks: conversational response rather than unauthorized web search
        return listOf(
            AgentTaskStep(
                stepIndex = 0,
                description = "Respond to user request: $clean",
                actionType = ActionTypes.GENERAL_CHAT,
                text = clean,
                expectedOutcome = "Provide conversational answer or clarify intent"
            )
        )
    }

    /**
     * Converts a planned AffyPlan from Gemini into structured multi-step AgentTaskSteps.
     */
    fun fromAffyPlan(plan: AffyPlan): List<AgentTaskStep> {
        if (plan.actions.isEmpty()) {
            return listOf(
                AgentTaskStep(
                    stepIndex = 0,
                    description = plan.speechResponse.ifBlank { "Respond to user" },
                    actionType = ActionTypes.GENERAL_CHAT,
                    expectedOutcome = "Conversation completed"
                )
            )
        }

        return plan.actions.mapIndexed { idx, action ->
            AgentTaskStep(
                stepIndex = idx,
                description = "Execute ${action.type}${if (!action.target.isNullOrBlank()) " on ${action.target}" else ""}",
                actionType = action.type,
                target = action.target,
                text = action.text,
                value = action.value,
                expectedOutcome = "${action.type} completed successfully"
            )
        }
    }
}
