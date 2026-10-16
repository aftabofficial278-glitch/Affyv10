package com.example.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DisambiguationActionType {
    CALL,
    WHATSAPP
}

enum class SpeechAttentionCategory {
    DIRECTED_TO_AFFY,
    LIKELY_DIRECTED_TO_OTHER_PERSON,
    BACKGROUND_CONVERSATION,
    AMBIGUOUS,
    EXPLICIT_HELP_REQUEST,
    FOLLOW_UP_TO_AFFY,
    INTERRUPTION_OF_AFFY
}

enum class NotificationReadState {
    NEW,
    ANNOUNCED_BUT_NOT_READ,
    DISMISSED,
    READ_BY_USER
}

enum class WorkflowStage {
    INITIATED,
    PROMPT_SUBMITTED,
    GENERATING_IMAGE,
    IMAGE_READY,
    IMAGE_DOWNLOADED,
    WALLPAPER_APPLIED,
    COMPLETED,
    FAILED
}

data class TaskWorkflow(
    val id: String = java.util.UUID.randomUUID().toString(),
    var stage: WorkflowStage = WorkflowStage.INITIATED,
    var lastObservedStatus: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingNotificationContext(
    val id: String = java.util.UUID.randomUUID().toString(),
    val packageName: String,
    val sender: String,
    val conversationTitle: String = sender,
    val notificationKey: String = "",
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    var readState: NotificationReadState = NotificationReadState.NEW
)

data class PendingContactDisambiguation(
    val actionType: DisambiguationActionType,
    val originalQuery: String,
    val candidates: List<ContactCandidate>,
    val whatsappMessage: String? = null,
    val whatsappTargetPackage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(maxAgeMs: Long = 60_000L): Boolean {
        return (System.currentTimeMillis() - timestamp) > maxAgeMs
    }

    /**
     * Resolves user utterance to a specific candidate from the pending list.
     * Supports:
     * - Ordinal positions: "pehla wala", "pehla", "first", "1", "1st", "number 1", etc.
     * - "doosra wala", "doosra", "second", "2", "2nd", "two", "do"
     * - "teesra wala", "teesra", "third", "3", "3rd", "three", "teen"
     * - Type label: "mobile wala", "mobile", "work wala", "work", "home wala", "home"
     * - Suffix digits: "...1234", "1234", "last 1234"
     * - Exact/partial name match when candidates have distinct names
     */
    fun matchCandidate(utterance: String): ContactCandidate? {
        val clean = utterance.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()
        if (clean.isBlank() || candidates.isEmpty()) return null

        // 1. Check numeric / ordinal selections
        val index = when {
            clean == "1" || clean == "one" || clean == "ek" || clean == "1st" ||
                    clean.contains("pehla") || clean.contains("pehli") || clean.contains("first") ||
                    clean == "number 1" || clean == "number one" || clean == "no 1" || clean == "opt 1" ||
                    clean.startsWith("1 ") || clean.endsWith(" 1") -> 0

            clean == "2" || clean == "two" || clean == "do" || clean == "2nd" ||
                    clean.contains("doosra") || clean.contains("doosri") || clean.contains("dusra") || clean.contains("dusri") || clean.contains("second") ||
                    clean == "number 2" || clean == "number two" || clean == "no 2" || clean == "opt 2" ||
                    clean.startsWith("2 ") || clean.endsWith(" 2") -> 1

            clean == "3" || clean == "three" || clean == "teen" || clean == "3rd" ||
                    clean.contains("teesra") || clean.contains("teesri") || clean.contains("tisra") || clean.contains("tisri") || clean.contains("third") ||
                    clean == "number 3" || clean == "number three" || clean == "no 3" || clean == "opt 3" ||
                    clean.startsWith("3 ") || clean.endsWith(" 3") -> 2

            clean == "4" || clean == "four" || clean == "char" || clean == "chaar" || clean == "4th" ||
                    clean.contains("chautha") || clean.contains("chauthi") || clean.contains("fourth") ||
                    clean == "number 4" || clean == "number four" || clean == "no 4" ||
                    clean.startsWith("4 ") || clean.endsWith(" 4") -> 3

            clean == "5" || clean == "five" || clean == "paanch" || clean == "panch" || clean == "5th" ||
                    clean.contains("panchwa") || clean.contains("panchwi") || clean.contains("fifth") ||
                    clean == "number 5" || clean == "number five" || clean == "no 5" ||
                    clean.startsWith("5 ") || clean.endsWith(" 5") -> 4

            else -> -1
        }

        if (index in candidates.indices) {
            return candidates[index]
        }

        // 2. Check type labels ("mobile", "work", "home", "office", "personal")
        val typeKeywords = listOf("mobile", "work", "home", "office", "personal", "main", "other")
        for (kw in typeKeywords) {
            if (clean.contains(kw)) {
                val matched = candidates.filter { it.typeLabel.lowercase().contains(kw) }
                if (matched.size == 1) {
                    return matched[0]
                }
            }
        }

        // 3. Check number suffix (e.g. "1234", "...1234", "last 1234", or spoken digits)
        val spokenDigits = clean.replace(Regex("[^0-9]"), "")
        if (spokenDigits.length >= 3) {
            val matchedByDigits = candidates.filter {
                val normDigits = it.phoneNumber.replace(Regex("[^0-9]"), "")
                normDigits.endsWith(spokenDigits) || it.numberSuffix.contains(spokenDigits)
            }
            if (matchedByDigits.size == 1) {
                return matchedByDigits[0]
            }
        }

        // 4. Check candidate name match if candidates have different names
        val normalizedUtterance = ContactResolver.normalizeName(clean)
        val nameMatches = candidates.filter {
            val candNorm = ContactResolver.normalizeName(it.name)
            candNorm == normalizedUtterance || candNorm.contains(normalizedUtterance) || normalizedUtterance.contains(candNorm)
        }
        if (nameMatches.size == 1) {
            return nameMatches[0]
        }

        return null
    }

    /**
     * Checks if the user speech represents an invalid selection attempt (e.g. number out of bounds or ordinal reference)
     */
    fun isInvalidSelectionAttempt(utterance: String): Boolean {
        val clean = utterance.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()
        val digits = clean.replace(Regex("[^0-9]"), "")
        if (digits.isNotBlank()) {
            val num = digits.toIntOrNull()
            if (num != null && (num < 1 || num > candidates.size)) {
                return true
            }
        }
        val ordinalKeywords = listOf("wala", "wali", "option", "number", "kaunsa", "batao", "pehla", "doosra", "teesra", "chautha", "panchwa", "first", "second", "third", "fourth", "fifth")
        return ordinalKeywords.any { clean.contains(it) }
    }
}

data class ConversationalContext(
    val currentUtterance: String = "",
    val previousUtterance: String? = null,
    val incompleteSpeech: String? = null,
    val corrections: List<String> = emptyList(),
    val currentIntent: String? = null,
    val recentAffyResponse: String? = null,
    val lastActiveApp: String? = null,
    val lastSearchQuery: String? = null,
    val lastUpdateTime: Long = 0L,
    val activeSessionState: String = "IDLE",
    val pendingClarification: String? = null,
    val pendingContactDisambiguation: PendingContactDisambiguation? = null,
    val pendingNotifications: List<PendingNotificationContext> = emptyList(),
    val activeNotificationSender: String? = null,
    val activeNotificationConversationTitle: String? = null,
    val activeNotificationKey: String? = null,
    val activeNotificationText: String? = null,
    val activeNotificationPackage: String? = null,
    val activeNotificationTimestamp: Long = 0L,
    val userEmotion: UserEmotion = UserEmotion.NEUTRAL,
    val affyEmotion: AffyEmotion = AffyEmotion.NORMAL
)

class AffyConversationContextManager private constructor() {

    companion object {
        private const val TAG = "AffyContextManager"
        private const val CONTEXT_EXPIRATION_MS = 45_000L // 45 seconds context lifetime

        @Volatile
        private var instance: AffyConversationContextManager? = null

        fun getInstance(): AffyConversationContextManager {
            return instance ?: synchronized(this) {
                instance ?: AffyConversationContextManager().also { instance = it }
            }
        }
    }

    private val _context = MutableStateFlow(ConversationalContext())
    val context: StateFlow<ConversationalContext> = _context.asStateFlow()

    fun getSnapshot(): ConversationalContext = _context.value

    /**
     * Determines whether the given speech fragment represents incomplete speech,
     * hesitation, trailing phrase, or an unfinished thought.
     */
    fun isIncompleteSpeech(rawSpeech: String, normalized: String): Boolean {
        val clean = normalized.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()
        val rawLower = rawSpeech.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()
        if (clean.isBlank()) return true

        // 1. Single or two-word trailing particles / conjunctions / adverbs
        val trailingParticles = listOf(
            "mujhe kal", "affy mujhe kal", "affy mujhe", "mujhe", "parson", "ek kaam karo",
            "actually", "main keh raha tha", "woh jo", "lekin", "aur", "to", "ke", "par",
            "pe", "ko", "wala", "wali", "se", "mein", "me", "ka", "ki", "for", "about",
            "and", "because", "that", "if", "when", "suno", "affy suno", "acha suno",
            "ek minute", "wait", "ruko", "ummm", "uhhh", "woh kya hai na", "mera", "meri",
            "mere", "aap", "tum"
        )
        if (clean in trailingParticles || rawLower in trailingParticles) {
            return true
        }

        // 2. Trailing endings indicating incomplete sentence
        val words = clean.split("\\s+".toRegex())
        val lastWord = words.lastOrNull() ?: ""
        val incompleteEndings = listOf(
            "kal", "parson", "ko", "par", "pe", "se", "mein", "me", "ke", "ka", "ki",
            "liye", "aur", "ya", "lekin", "to", "phir", "and", "or", "but", "for", "to", "with"
        )
        if (words.size <= 4 && lastWord in incompleteEndings) {
            // Check if it's not a complete phrase like "torch off karo"
            val isActionVerbPresent = clean.contains("karo") || clean.contains("chalao") ||
                    clean.contains("kholo") || clean.contains("bhejo") || clean.contains("lagao") ||
                    clean.contains("sunao") || clean.contains("batao") || clean.contains("dekh")
            if (!isActionVerbPresent) {
                return true
            }
        }

        // 3. Incomplete specific intent prefixes missing essential targets
        if (clean == "ali ko whatsapp pe" || clean == "whatsapp pe" || clean == "whatsapp karo" ||
            clean == "ali ko message" || clean == "message karo" || clean == "call karo" ||
            clean == "phone milao" || clean == "kholo" || clean == "open karo" || clean == "chalao" ||
            clean == "yaad dilana" || clean == "reminder lagao" || clean == "alarm lagao") {
            return true
        }

        // 4. Very short fragments without verb or predicate (<= 2 words that aren't greetings or commands)
        if (words.size <= 2) {
            val validShortCommands = listOf(
                "hello", "hi", "hey", "salam", "assalam", "chup", "stop", "cancel", "back",
                "home", "recents", "time", "date", "battery", "play", "pause", "next",
                "yes", "haan", "no", "nahi", "theek hai", "ok", "done", "help"
            )
            val isGreetingOrCommand = validShortCommands.any { clean == it || clean.startsWith(it) }
            if (!isGreetingOrCommand && !clean.contains("torch") && !clean.contains("volume")) {
                return true
            }
        }

        return false
    }

    /**
     * Stitches previous incomplete or contextual speech with the user's current speech,
     * seamlessly resolving self-corrections, restarts, and topic adjustments.
     */
    fun resolveStitchedSpeech(rawSpeech: String, normalized: String): String {
        val now = System.currentTimeMillis()
        val currentContext = _context.value
        val incomplete = currentContext.incompleteSpeech
        val prevUtterance = currentContext.previousUtterance
        val isContextFresh = (now - currentContext.lastUpdateTime) < CONTEXT_EXPIRATION_MS

        val clean = normalized.trim()
        val lower = clean.lowercase()

        // If no prior context or context expired, clean repetition in current speech
        if (!isContextFresh || (incomplete.isNullOrBlank() && prevUtterance.isNullOrBlank())) {
            return cleanSpeechRepetitions(clean)
        }

        val anchorSpeech = incomplete ?: prevUtterance ?: ""
        val anchorLower = anchorSpeech.lowercase()

        // 1. Correction markers detection ("actually", "nahi", "no", "sorry", "matlab", "parson", "kal nahi")
        val isCorrection = lower.startsWith("actually") || lower.startsWith("nahi") ||
                lower.startsWith("no") || lower.startsWith("sorry") || lower.startsWith("matlab") ||
                lower.startsWith("parson") || lower.contains("kal nahi") || lower.contains("nahi nahi") ||
                lower.startsWith("instead") || lower.startsWith("mera matlab")

        if (isCorrection) {
            Log.d(TAG, "Self-correction detected! Merging '$anchorSpeech' with correction '$clean'")
            _context.update {
                it.copy(
                    corrections = it.corrections + clean,
                    incompleteSpeech = null,
                    lastUpdateTime = now
                )
            }

            // Correction Case A: Time adjustment ("Affy, mujhe kal..." -> "Actually parson meeting ke liye yaad dilana")
            if ((anchorLower.contains("kal") || anchorLower.contains("aaj")) &&
                (lower.contains("parson") || lower.contains("kal") || lower.contains("aaj"))) {
                // Return clean correction containing full corrected thought
                val stripped = clean.replace(Regex("(?i)^(actually|nahi|sorry|matlab|mera matlab tha|no)\\s*"), "").trim()
                return if (stripped.isNotBlank()) stripped else clean
            }

            // Correction Case B: Contact replacement ("Ali ko message bhejo" -> "Nahi Hamza ko bolo...")
            if (lower.contains("ko bolo") || lower.contains("ko message") || lower.contains("ko whatsapp") || lower.contains("ko call")) {
                val stripped = clean.replace(Regex("(?i)^(actually|nahi|sorry|matlab|mera matlab tha|no)\\s*"), "").trim()
                return if (stripped.isNotBlank()) stripped else clean
            }

            // Correction Case C: General correction stripped
            val stripped = clean.replace(Regex("(?i)^(actually|nahi|sorry|matlab|mera matlab tha|no)\\s*"), "").trim()
            return if (stripped.isNotBlank()) stripped else clean
        }

        // 2. Continuation of incomplete speech ("Affy, mujhe kal..." -> "meeting ke liye yaad dilana")
        if (!incomplete.isNullOrBlank()) {
            Log.d(TAG, "Continuation detected! Stitching incomplete '$incomplete' with '$clean'")
            _context.update { it.copy(incompleteSpeech = null, lastUpdateTime = now) }

            // Merge smoothly
            val merged = "${incomplete.trim()} ${clean.trim()}".trim()
            return cleanSpeechRepetitions(merged)
        }

        return cleanSpeechRepetitions(clean)
    }

    /**
     * Cleans stutter, wake word repetition, and speech glitches.
     * e.g. "Affy Affy suno Ali ko call karo" -> "Ali ko call karo"
     */
    fun cleanSpeechRepetitions(speech: String): String {
        var text = speech.trim()
        // Deduplicate repeated consecutive words like "affy affy", "suno suno", "karo karo"
        text = text.replace(Regex("(?i)\\b(\\w+)\\s+\\1\\b"), "$1")
        // Remove trailing stuttering hesitations
        text = text.replace(Regex("(?i)^(ummm|uhhh|errr|acha suno|suno na|ek minute)\\s*"), "").trim()
        return text
    }

    /**
     * Produces a natural, comforting conversational response when incomplete speech is received.
     */
    fun getNaturalIncompleteReassurance(incompleteSpeech: String): String {
        val lower = incompleteSpeech.lowercase()
        return when {
            lower.contains("kal") || lower.contains("parson") || lower.contains("yaad") ->
                "Boss, aap keh rahe thay..."
            lower.contains("message") || lower.contains("whatsapp") || lower.contains("call") ->
                "Ji Boss, kiske baare mein keh rahe hain?"
            else ->
                "Take your time, Boss. Main sun raha hoon."
        }
    }

    fun recordIncompleteSpeech(speech: String) {
        val now = System.currentTimeMillis()
        _context.update {
            it.copy(
                incompleteSpeech = speech,
                currentUtterance = speech,
                lastUpdateTime = now,
                activeSessionState = "WAITING_COMPLETION"
            )
        }
    }

    fun clearIncompleteSpeech() {
        _context.update {
            it.copy(incompleteSpeech = null)
        }
    }

    fun recordSuccessfulTurn(
        userUtterance: String,
        affyResponse: String?,
        intent: String?,
        lastApp: String? = null,
        lastQuery: String? = null,
        userEmotion: UserEmotion = AffyEmotionEngine.getInstance().lastUserEmotion.value,
        affyEmotion: AffyEmotion = AffyEmotionEngine.getInstance().currentAffyEmotion.value
    ) {
        val now = System.currentTimeMillis()
        _context.update {
            it.copy(
                currentUtterance = userUtterance,
                previousUtterance = userUtterance,
                incompleteSpeech = null,
                currentIntent = intent,
                recentAffyResponse = affyResponse,
                lastActiveApp = lastApp ?: it.lastActiveApp,
                lastSearchQuery = lastQuery ?: it.lastSearchQuery,
                lastUpdateTime = now,
                activeSessionState = "IDLE",
                pendingClarification = null,
                pendingContactDisambiguation = null,
                userEmotion = userEmotion,
                affyEmotion = affyEmotion
            )
        }
    }

    fun setPendingContactDisambiguation(disambiguation: PendingContactDisambiguation) {
        _context.update {
            it.copy(
                pendingContactDisambiguation = disambiguation,
                activeSessionState = "AWAITING_CONTACT_SELECTION",
                lastUpdateTime = System.currentTimeMillis()
            )
        }
        Log.d(TAG, "Pending contact disambiguation set for ${disambiguation.actionType} (${disambiguation.candidates.size} candidates)")
    }

    fun getPendingContactDisambiguation(): PendingContactDisambiguation? {
        val current = _context.value.pendingContactDisambiguation ?: return null
        if (current.isExpired()) {
            clearPendingContactDisambiguation()
            return null
        }
        return current
    }

    fun clearPendingContactDisambiguation() {
        _context.update {
            it.copy(
                pendingContactDisambiguation = null,
                activeSessionState = if (it.activeSessionState == "AWAITING_CONTACT_SELECTION") "IDLE" else it.activeSessionState
            )
        }
        Log.d(TAG, "Cleared pending contact disambiguation")
    }

    fun setPendingClarification(clarificationQuestion: String) {
        _context.update {
            it.copy(
                pendingClarification = clarificationQuestion,
                activeSessionState = "AWAITING_CLARIFICATION",
                lastUpdateTime = System.currentTimeMillis()
            )
        }
    }

    fun updateActiveNotification(
        sender: String,
        message: String,
        packageName: String,
        conversationTitle: String = sender,
        notificationKey: String = ""
    ) {
        val now = System.currentTimeMillis()
        val notif = PendingNotificationContext(
            packageName = packageName,
            sender = sender,
            conversationTitle = conversationTitle,
            notificationKey = notificationKey,
            messageText = message,
            timestamp = now,
            readState = NotificationReadState.NEW
        )
        _context.update { current ->
            val targetChanged = !current.activeNotificationSender.isNullOrBlank() &&
                    (!ContactResolver.doesIdentityMatch(current.activeNotificationSender, sender) ||
                            !current.activeNotificationPackage.equals(packageName, ignoreCase = true))

            val updatedList = (listOf(notif) + current.pendingNotifications.filter {
                (!ContactResolver.doesIdentityMatch(it.sender, sender) || !it.packageName.equals(packageName, ignoreCase = true)) &&
                        (now - it.timestamp) < 300_000L // Keep recent 5 mins
            }).take(10)

            current.copy(
                pendingNotifications = updatedList,
                activeNotificationSender = sender,
                activeNotificationConversationTitle = conversationTitle,
                activeNotificationKey = notificationKey,
                activeNotificationText = message,
                activeNotificationPackage = packageName,
                activeNotificationTimestamp = now,
                pendingContactDisambiguation = if (targetChanged) null else current.pendingContactDisambiguation,
                pendingClarification = if (targetChanged) null else current.pendingClarification,
                lastUpdateTime = now
            )
        }
        Log.d(TAG, "Active notification context updated: sender='$sender', conv='$conversationTitle', pkg='$packageName'")
    }

    fun clearActiveNotificationContext() {
        _context.update {
            it.copy(
                activeNotificationSender = null,
                activeNotificationConversationTitle = null,
                activeNotificationKey = null,
                activeNotificationText = null,
                activeNotificationPackage = null,
                activeNotificationTimestamp = 0L
            )
        }
        Log.d(TAG, "Cleared active notification target context")
    }

    fun invalidateStaleNotificationIfTargetChanged(newTarget: String) {
        val clean = newTarget.trim()
        if (clean.isBlank()) return
        val current = _context.value
        val activeSender = current.activeNotificationSender ?: return
        val activeConv = current.activeNotificationConversationTitle ?: activeSender
        if (!ContactResolver.doesIdentityMatch(activeSender, clean) &&
            !ContactResolver.doesIdentityMatch(activeConv, clean)
        ) {
            Log.i(TAG, "[WA_CONTEXT_INVALIDATED] Invalidating stale notification target '$activeSender' because new target is '$clean'")
            clearActiveNotificationContext()
        }
    }

    fun markNotificationAnnounced(sender: String) {
        _context.update { current ->
            val updated = current.pendingNotifications.map {
                if (it.sender.equals(sender, ignoreCase = true) && it.readState == NotificationReadState.NEW) {
                    it.copy(readState = NotificationReadState.ANNOUNCED_BUT_NOT_READ)
                } else it
            }
            current.copy(pendingNotifications = updated)
        }
    }

    fun markNotificationRead(sender: String? = null) {
        _context.update { current ->
            val updated = current.pendingNotifications.map {
                if (sender == null || it.sender.contains(sender, ignoreCase = true) || sender.contains(it.sender, ignoreCase = true)) {
                    it.copy(readState = NotificationReadState.READ_BY_USER)
                } else it
            }
            current.copy(pendingNotifications = updated)
        }
    }

    fun getPendingNotifications(sender: String? = null): List<PendingNotificationContext> {
        val list = _context.value.pendingNotifications
        if (sender.isNullOrBlank()) return list
        return list.filter { it.sender.contains(sender, ignoreCase = true) || sender.contains(it.sender, ignoreCase = true) }
    }

    fun getUnreadNotifications(): List<PendingNotificationContext> {
        return _context.value.pendingNotifications.filter { it.readState != NotificationReadState.READ_BY_USER }
    }

    /**
     * Attention & Intent Gate: Distinguishes between speech directed to AFFY vs speech directed to another person.
     */
    fun classifySpeechAttention(
        rawSpeech: String,
        normalized: String,
        isAffySpeaking: Boolean = false,
        hasWakeWord: Boolean = false,
        isResponseWindow: Boolean = false,
        isContinuous: Boolean = false
    ): SpeechAttentionCategory {
        val clean = normalized.lowercase().trim()
        val rawLower = rawSpeech.lowercase().trim()

        // 1. Explicit addressing to AFFY
        if (hasWakeWord || clean.startsWith("affy") || clean.startsWith("hey affy") ||
            clean.startsWith("suno affy") || clean.startsWith("affy bhai") || clean.startsWith("boss ") ||
            clean.endsWith(" affy") || clean.contains(" affy ")) {
            return if (isAffySpeaking) SpeechAttentionCategory.INTERRUPTION_OF_AFFY else SpeechAttentionCategory.DIRECTED_TO_AFFY
        }

        // 2. Immediate user interruption while AFFY is speaking
        if (isAffySpeaking) {
            val isDirectInterruption = clean.startsWith("chup") || clean.startsWith("stop") ||
                    clean.startsWith("ruk") || clean.startsWith("ruko") || clean.startsWith("wait") ||
                    clean.startsWith("nahi") || clean.startsWith("suno") || clean.startsWith("ek minute")
            if (isDirectInterruption) {
                return SpeechAttentionCategory.INTERRUPTION_OF_AFFY
            }
        }

        // 3. Explicit device/assistant command phrasing
        val isExplicitCommand = clean.contains("torch") || clean.contains("flash") ||
                clean.contains("wifi") || clean.contains("data on") || clean.contains("data off") ||
                clean.contains("bluetooth") || clean.contains("kholo") || clean.contains("open ") ||
                clean.contains("chalao") || clean.contains("play ") || clean.contains("bhejo") ||
                clean.contains("call ") || clean.contains("time kitna") || clean.contains("battery") ||
                clean.contains("screen off") || clean.contains("screen lock") || clean.contains("screen wake") ||
                clean.contains("notification parho") || clean.contains("notification sunao") ||
                clean.contains("parh kar suna") || clean.contains("suna do") || clean.contains("parh do")

        // 4. In active response window or follow-up turn
        if (isResponseWindow || isContinuous) {
            // Conversational affirmative/follow-up answers to AFFY
            val isFollowUpAnswer = clean == "haan" || clean == "suna do" || clean == "sunao" ||
                    clean == "parho" || clean == "parh do" || clean == "nahi" || clean == "no" ||
                    clean == "theek hai" || clean == "ok" || clean == "cancel" || clean == "yes" ||
                    clean.startsWith("reply ") || clean.startsWith("bolo ") || clean.startsWith("bhejo ") ||
                    clean.contains("pehla") || clean.contains("doosra") || clean.contains("teesra")

            if (isFollowUpAnswer) {
                return SpeechAttentionCategory.FOLLOW_UP_TO_AFFY
            }

            // Explicit help request
            if (clean.contains("ye setting mil nahi rahi") || clean.contains("ye kaise hoga") ||
                clean.contains("kahan se hoga") || clean.contains("help") || clean.contains("madad")) {
                return SpeechAttentionCategory.EXPLICIT_HELP_REQUEST
            }
        }

        // 5. Human-to-Human Conversation Patterns (Talking to another person)
        // Phrases addressed to other humans (e.g., "Kal tum office kab ja rahe ho?", "Usko keh do main 10 min me aa raha hoon", "Yaar mujhe ye file bhej dena")
        val isHumanToHuman = (clean.contains("tum ") || clean.contains("aap ") || clean.contains("usko ") ||
                clean.contains("unko ") || clean.contains("yaar ") || clean.contains("bhai ") || clean.contains("dost ")) &&
                (clean.contains("kab ja rahe") || clean.contains("aa rahe ho") || clean.contains("keh do") ||
                        clean.contains("bol dena") || clean.contains("bata dena") || clean.contains("bhej dena") ||
                        clean.contains("khana kha") || clean.contains("kahan ho") || clean.contains("chalo") ||
                        clean.contains("baat suno") || clean.contains("office"))

        if (isHumanToHuman && !clean.contains("affy")) {
            return SpeechAttentionCategory.LIKELY_DIRECTED_TO_OTHER_PERSON
        }

        if (isExplicitCommand) {
            return SpeechAttentionCategory.DIRECTED_TO_AFFY
        }

        // In continuous window, check if user asks for help
        if ((isContinuous || isResponseWindow) && (clean.contains("kaise") || clean.contains("kya karoon") || clean.contains("batao"))) {
            return SpeechAttentionCategory.EXPLICIT_HELP_REQUEST
        }

        // Background / Ambiguous
        return if (clean.length < 4) SpeechAttentionCategory.BACKGROUND_CONVERSATION else SpeechAttentionCategory.AMBIGUOUS
    }

    /**
     * Resolves pronouns like "iski", "iska", "isko", "unka", "inhe", "unhe", "woh", "pehla wala"
     * to the active context sender or recent conversation target.
     */
    fun resolveContextualRecipient(utterance: String): String? {
        val lower = utterance.lowercase().trim()
        val current = _context.value
        val now = System.currentTimeMillis()

        // Check if context is fresh (within 2 minutes)
        val isNotifFresh = (now - current.activeNotificationTimestamp) < 120_000L
        if (isNotifFresh && !current.activeNotificationSender.isNullOrBlank()) {
            val pronounPatterns = listOf(
                "iski", "iska", "isko", "ise", "isay", "unka", "unki", "unko", "inhe", "unhe", "inhein",
                "unhein", "woh", "uska", "uski", "usko", "use", "usay", "is bande", "us bande", "is shakhs",
                "ye wala", "pehla wala", "latest", "recent", "last", "him", "her", "them",
                "sender", "notification", "reply"
            )
            if (lower.isBlank() || pronounPatterns.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") || lower.contains(" $it ") }) {
                Log.d(TAG, "Resolved contextual pronoun in '$utterance' -> '${current.activeNotificationSender}'")
                return current.activeNotificationSender
            }
        }

        return null
    }

    fun clearContext() {
        reset()
    }

    private var activeTaskWorkflow: TaskWorkflow? = null

    fun getActiveTaskWorkflow(): TaskWorkflow? = activeTaskWorkflow

    fun setActiveTaskWorkflow(workflow: TaskWorkflow?) {
        activeTaskWorkflow = workflow
    }

    fun reset() {
        _context.value = ConversationalContext()
        activeTaskWorkflow = null
        AffyEmotionEngine.getInstance().reset()
    }
}
