package com.example.agent

import android.util.Log
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.AffyPlan

enum class AffyIntentCategory {
    GREETING,
    CASUAL_CONVERSATION,
    CREATOR_CONTEXT,
    FOLLOW_UP,
    ACKNOWLEDGEMENT,
    QUESTION,
    EMOTIONAL_CONVERSATION,
    SIMPLE_DEVICE_COMMAND,
    MULTI_STEP_TASK,
    NOTIFICATION_CONTEXT,
    WHATSAPP_CONTEXT,
    GMAIL_CONTEXT,
    UNKNOWN
}

data class AffyIntentClassification(
    val category: AffyIntentCategory,
    val matchedPlan: AffyPlan? = null,
    val immediateSpeech: String? = null,
    val needsGemini: Boolean = false,
    val requiresThinkingState: Boolean = false,
    val explanation: String = ""
)

object AffyIntentRouter {

    private const val TAG = "AffyIntentRouter"

    /**
     * Primary intent classifier before Gemini.
     * Takes raw speech, normalized speech, and active conversation state.
     * Classifies into:
     * - GREETING
     * - CASUAL_CONVERSATION
     * - FOLLOW_UP
     * - ACKNOWLEDGEMENT
     * - QUESTION
     * - EMOTIONAL_CONVERSATION
     * - SIMPLE_DEVICE_COMMAND
     * - MULTI_STEP_TASK
     * - NOTIFICATION_CONTEXT
     * - WHATSAPP_CONTEXT
     * - GMAIL_CONTEXT
     * - UNKNOWN
     */
    fun classify(
        rawSpeech: String,
        normalizedSpeech: String,
        lastActiveApp: String? = null,
        lastSearchQuery: String? = null,
        isContinuousActive: Boolean = false
    ): AffyIntentClassification {
        val clean = normalizedSpeech.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()
        val rawLower = rawSpeech.lowercase().trim().replace(Regex("[.,!?;:]"), "").trim()

        // 0. STOP / CANCEL (Immediate barge-in & cancellation)
        if (isStopOrCancel(clean) || isStopOrCancel(rawLower)) {
            val stopPlan = AffyPlan(
                intent = ActionTypes.STOP_CANCEL,
                speechResponse = "Theek hai boss, stop kar diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.STOP_CANCEL))
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.SIMPLE_DEVICE_COMMAND,
                matchedPlan = stopPlan,
                immediateSpeech = stopPlan.speechResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Emergency STOP/CANCEL command"
            )
        }

        // 0.2 FAST ACTION COMMAND CHECK (Ensures actionable commands like "Sorry Affy, flashlight on kardo" or "Shukriya WhatsApp kholo" execute immediately without being blocked by emotional override)
        val fastActionPlan = AffyFastCommandRouter.tryFastMatch(normalizedSpeech)
        if (fastActionPlan != null && fastActionPlan.actions.isNotEmpty()) {
            AffyEmotionEngine.getInstance().processUserInput(rawSpeech, isActionCommand = true)
            return AffyIntentClassification(
                category = when {
                    isWhatsAppQuery(clean) -> AffyIntentCategory.WHATSAPP_CONTEXT
                    isGmailQuery(clean) -> AffyIntentCategory.GMAIL_CONTEXT
                    isNotificationQuery(clean) -> AffyIntentCategory.NOTIFICATION_CONTEXT
                    else -> AffyIntentCategory.SIMPLE_DEVICE_COMMAND
                },
                matchedPlan = fastActionPlan,
                immediateSpeech = fastActionPlan.speechResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Direct fast action match: ${fastActionPlan.intent}"
            )
        }

        // 0.5 CREATOR CONTEXT & IDENTITY (Routes directly to Gemini with low latency and without heavy THINKING state)
        if (isCreatorContextQuery(clean, rawLower)) {
            return AffyIntentClassification(
                category = AffyIntentCategory.CREATOR_CONTEXT,
                matchedPlan = null,
                needsGemini = true,
                requiresThinkingState = false,
                explanation = "Creator / Aftab contextual query"
            )
        }

        // 1. GREETINGS (Instant response, NO Gemini, NO action planning, NO THINKING)
        val greetingResponse = matchGreeting(clean, rawLower)
        if (greetingResponse != null) {
            val plan = AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = greetingResponse,
                actions = emptyList()
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.GREETING,
                matchedPlan = plan,
                immediateSpeech = greetingResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Direct greeting match"
            )
        }

        // 2. ACKNOWLEDGEMENT (Instant, NO Gemini, NO action planning)
        val ackResponse = matchAcknowledgement(clean)
        if (ackResponse != null) {
            val plan = AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = ackResponse,
                actions = emptyList()
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.ACKNOWLEDGEMENT,
                matchedPlan = plan,
                immediateSpeech = ackResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Acknowledgement match"
            )
        }

        // 3. EMOTIONAL CONVERSATION & EMOTION DETECTION (Instant human-like companion response, NO action planning)
        val emotionTransition = AffyEmotionEngine.getInstance().processUserInput(rawSpeech, isActionCommand = false)
        if (emotionTransition.emotionalResponseOverride != null) {
            val plan = AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = emotionTransition.emotionalResponseOverride,
                actions = emptyList()
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.EMOTIONAL_CONVERSATION,
                matchedPlan = plan,
                immediateSpeech = emotionTransition.emotionalResponseOverride,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Emotion transition response (User: ${emotionTransition.detectedUserEmotion} -> Affy: ${emotionTransition.affyEmotion})"
            )
        }

        val fallbackEmotionalResponse = matchEmotionalConversation(clean)
        if (fallbackEmotionalResponse != null) {
            val plan = AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = fallbackEmotionalResponse,
                actions = emptyList()
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.EMOTIONAL_CONVERSATION,
                matchedPlan = plan,
                immediateSpeech = fallbackEmotionalResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Empathetic companion response"
            )
        }

        // 4. CASUAL CONVERSATION (Instant human-like AI companion, NO action planning)
        val casualResponse = matchCasualConversation(clean)
        if (casualResponse != null) {
            val plan = AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = casualResponse,
                actions = emptyList()
            )
            return AffyIntentClassification(
                category = AffyIntentCategory.CASUAL_CONVERSATION,
                matchedPlan = plan,
                immediateSpeech = casualResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Casual conversation fast path"
            )
        }

        // 5. CONTEXTUAL FOLLOW-UP (When in continuous window or active app exists)
        val followUpPlan = matchFollowUp(clean, normalizedSpeech, lastActiveApp, lastSearchQuery, isContinuousActive)
        if (followUpPlan != null) {
            return AffyIntentClassification(
                category = AffyIntentCategory.FOLLOW_UP,
                matchedPlan = followUpPlan,
                immediateSpeech = followUpPlan.speechResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Contextual follow-up on app: $lastActiveApp"
            )
        }

        // 6. GMAIL CONTEXT
        if (isGmailQuery(clean)) {
            val plan = AffyFastCommandRouter.tryFastMatch(normalizedSpeech)
            if (plan != null) {
                return AffyIntentClassification(
                    category = AffyIntentCategory.GMAIL_CONTEXT,
                    matchedPlan = plan,
                    immediateSpeech = plan.speechResponse,
                    needsGemini = false,
                    requiresThinkingState = false,
                    explanation = "Local Gmail fast action"
                )
            }
        }

        // 7. NOTIFICATION CONTEXT
        if (isNotificationQuery(clean)) {
            val plan = AffyFastCommandRouter.tryFastMatch(normalizedSpeech)
            if (plan != null) {
                return AffyIntentClassification(
                    category = AffyIntentCategory.NOTIFICATION_CONTEXT,
                    matchedPlan = plan,
                    immediateSpeech = plan.speechResponse,
                    needsGemini = false,
                    requiresThinkingState = false,
                    explanation = "Notification query match"
                )
            }
        }

        // 8. WHATSAPP CONTEXT
        if (isWhatsAppQuery(clean)) {
            val plan = AffyFastCommandRouter.tryFastMatch(normalizedSpeech)
            if (plan != null) {
                return AffyIntentClassification(
                    category = AffyIntentCategory.WHATSAPP_CONTEXT,
                    matchedPlan = plan,
                    immediateSpeech = plan.speechResponse,
                    needsGemini = false,
                    requiresThinkingState = false,
                    explanation = "Local WhatsApp fast action"
                )
            }
        }

        // 9. SIMPLE DEVICE COMMAND (App open, Volume, Brightness, Flashlight, Navigation, etc.)
        val fastPlan = AffyFastCommandRouter.tryFastMatch(normalizedSpeech)
        if (fastPlan != null) {
            val isMultiStep = fastPlan.intent == ActionTypes.MULTI_STEP_APP_COMMAND
            return AffyIntentClassification(
                category = if (isMultiStep) AffyIntentCategory.MULTI_STEP_TASK else AffyIntentCategory.SIMPLE_DEVICE_COMMAND,
                matchedPlan = fastPlan,
                immediateSpeech = fastPlan.speechResponse,
                needsGemini = false,
                requiresThinkingState = false,
                explanation = "Fast local device command"
            )
        }

        // 10. MULTI-STEP TASK DETECTION (explicit conjunctions: "aur ... chalao", "search karke chalao")
        if (isMultiStepTaskPrompt(clean)) {
            return AffyIntentClassification(
                category = AffyIntentCategory.MULTI_STEP_TASK,
                matchedPlan = null,
                needsGemini = true,
                requiresThinkingState = true,
                explanation = "Multi-step complex task requiring agent planner"
            )
        }

        // 11. KNOWLEDGE QUESTION DETECTION
        if (isKnowledgeQuestion(clean)) {
            return AffyIntentClassification(
                category = AffyIntentCategory.QUESTION,
                matchedPlan = null,
                needsGemini = true,
                requiresThinkingState = true,
                explanation = "Knowledge or reasoning question"
            )
        }

        // 12. UNKNOWN / GENERAL FALLBACK
        return AffyIntentClassification(
            category = AffyIntentCategory.UNKNOWN,
            matchedPlan = null,
            needsGemini = true,
            requiresThinkingState = true,
            explanation = "Ambiguous speech, routing to Gemini reasoning"
        )
    }

    private fun isStopOrCancel(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t in listOf(
            "chup", "chup karo", "chup ho jao", "ruk jao", "ruko", "stop", "cancel",
            "bas karo", "band karo", "shant ho jao", "shut up", "pause", "pause karo",
            "affy chup", "affy ruk jao", "affy stop", "affy cancel", "aftab chup", "aftab ruk jao"
        )) return true

        return t.contains("ruk jao") || t.contains("chup karo") || t.contains("chup ho jao") ||
                t.contains("stop karo") || t.contains("cancel karo") || t.contains("bas karo")
    }

    private fun matchGreeting(clean: String, rawLower: String): String? {
        val t = clean
        if (t == "hello" || t == "hi" || t == "hey" ||
            t == "hello affy" || t == "hi affy" || t == "hey affy" ||
            t == "hello aftab" || t == "hi aftab" || t == "hey aftab" ||
            t == "affy" || t == "aftab" || t == "aftaab" ||
            t == "oye affy" || t == "suno affy" || t == "bhai affy" ||
            t == "oye aftab" || t == "suno aftab" || t == "bhai aftab" ||
            t == "effy" || t == "affi" || t == "afy" || t == "afi" ||
            (rawLower.startsWith("hello ") && rawLower.length <= 12) ||
            (rawLower.startsWith("hi ") && rawLower.length <= 8)) {
            return "Ji Boss bolo!"
        }

        val salamRegex = Regex("(?i)\\b(assalam(\\s*o?\\s*alaikum)?|salam|slam)\\b")
        if (salamRegex.containsMatchIn(t) && !t.contains("aslam")) {
            return "Walaikum Assalam boss! Kahiye kya hukum hai?"
        }

        if (t.contains("good morning") || t.contains("subah bakhair")) {
            return "Good morning boss! Aaj kya plan hai?"
        }

        if (t.contains("good night") || t.contains("shabba khair")) {
            return "Good night boss! Aaram karein."
        }

        if (t.contains("good afternoon") || t.contains("good evening")) {
            return "Good day boss! Kahiye kya hukum hai?"
        }

        return null
    }

    private fun matchAcknowledgement(clean: String): String? {
        if (clean in listOf("acha", "achha", "theek hai", "thik hai", "sahi hai", "ok", "okay",
                "haan", "haan ji", "haan boss", "hmm", "hmmm", "hmmmm", "wah", "waah",
                "bilkul", "samjha", "samajh gaya", "samjh gaya", "done", "great", "nice",
                "zabardast", "shukriya", "thanks", "thank you", "meherbani")) {
            return when {
                clean.contains("shukriya") || clean.contains("thank") -> "Koi baat nahi boss, mera kaam hi yahi hai."
                clean == "wah" || clean == "waah" || clean == "zabardast" || clean == "great" -> "Shukriya boss 😄"
                clean == "theek hai" || clean == "thik hai" -> "Ji Boss."
                clean == "sahi hai" || clean == "haan ye sahi hai" -> "Bilkul Boss."
                else -> "Ji Boss."
            }
        }
        if (clean == "haan ye sahi hai" || clean == "ye sahi hai" || clean == "ye theek hai") {
            return "Ji bilkul boss!"
        }
        return null
    }

    private fun matchEmotionalConversation(clean: String): String? {
        // "Yaar aaj mood ajeeb hai", "mood kharab hai", "mood ajeeb", "aaj mood ajeeb hai"
        if (clean.contains("mood ajeeb") || clean.contains("mood kharab") || clean.contains("mood off") ||
            clean.contains("mood theek nahi") || clean.contains("mood nahi hai")) {
            return "Arre boss kya hua? Sab theek to hai na? Mujhse share karein, ya mood fresh karne ke liye koi acha song lagaun?"
        }

        // "Yaar aaj bohat ajeeb din tha", "aaj ajeeb din tha", "din ajeeb"
        if (clean.contains("ajeeb din tha") || clean.contains("ajeeb din") || clean.contains("din kharab") ||
            clean.contains("bohat ajeeb din")) {
            return "Kyun boss, kya hua aaj? Din mein aisi kya ajeeb baat ho gayi?"
        }

        // "Main thoda pareshan hoon", "tension hai", "pareshan hoon", "bohat tension"
        if (clean.contains("pareshan hoon") || clean.contains("pareshaan") || clean.contains("tension hai") ||
            clean.contains("bohat tension") || clean.contains("dimagh kharab")) {
            return "Relax karein boss, har masle ka koi na koi hal nikal aata hai. Agar main kisi tarah aapka load kam kar sakta hoon to batayein."
        }

        // "Aaj bohat thak gaya hoon", "thak gaya", "exhausted hoon"
        if (clean.contains("thak gaya") || clean.contains("bohat thak") || clean.contains("thakan") ||
            clean.contains("tired hoon")) {
            return "Toh boss thora aaram karein! Chahein toh main phone silent kar doon ya koi relaxing music chala doon?"
        }

        // "Bore ho raha hoon", "bohat bore"
        if (clean.contains("bore ho raha") || clean.contains("boring din") || clean.contains("bohat bore")) {
            return "Toh phir YouTube pe kuch mazedaar lagayein ya koi mast song sunein?"
        }

        // "Aaj bohat khush hoon"
        if (clean.contains("bohat khush") || clean.contains("khush hoon") || clean.contains("maza aa gaya")) {
            return "Wah boss! Ye sun kar dil khush ho gaya. Kis khushi mein celebration chal rahi hai?"
        }

        return null
    }

    private fun matchCasualConversation(clean: String): String? {
        // Health / Wellness inquiry: "Kya haal hai", "kaise ho", "kese ho", "how are you", "sab theek"
        if (clean.contains("kaise ho") || clean.contains("kese ho") || clean.contains("kya haal") ||
            clean.contains("how are you") || clean.contains("how are u") || clean.contains("how r u") ||
            clean.contains("how do you do") || clean.contains("sab theek") || clean.contains("tabiyat kaisi") ||
            clean.contains("kya chal raha hai") || clean.contains("aur sunao") || clean.contains("aur batao") ||
            clean.contains("whats up") || clean.contains("what's up")) {
            return "Main bilkul fit hoon boss! Aap sunao, aapka din kaisa guzar raha hai?"
        }

        // "What are you doing", "Aaj kya kar rahe ho", "kya kar rahe ho"
        if (clean.contains("what are you doing") || clean.contains("what you doing") || clean.contains("what r u doing") ||
            clean.contains("aaj kya kar rahe ho") || clean.contains("kya kar rahe ho") || clean.contains("kya kar rahe") ||
            clean.contains("kya chal raha")) {
            return "Kuch khaas nahi boss, bas aapki commands ka wait kar raha hoon. Aap sunao aaj ka kya plan hai?"
        }

        // "Mujhse baat karo", "aao baat karte hain", "baat karo", "talk to me"
        if (clean.contains("mujhse baat karo") || clean.contains("baat karo") || clean.contains("baat karni hai") ||
            clean.contains("aao baat karein") || clean.contains("talk to me") || clean.contains("let's talk")) {
            return "Haan boss bilkul, main yahin hoon! Kahiye kis baare mein baat karni hai?"
        }

        // "Tumhe pata hai kya hua", "pata hai kya hua"
        if (clean.contains("pata hai kya hua") || clean.contains("tumhe pata hai kya hua") || clean.contains("kya hua pata hai")) {
            return "Arre kya hua boss? Mujhe bhi batao!"
        }

        // "Main tumse ek baat share karna chahta hoon", "ek baat share karni hai", "kuch share karna hai"
        if (clean.contains("share karna chahta") || clean.contains("share karni hai") || clean.contains("kuch share") ||
            clean.contains("ek baat share")) {
            return "Ji bilkul boss, zaroor share karein. Main poore dhyan se sun raha hoon."
        }

        // "Sun rahe ho affy", "sun rahe ho", "awaz aa rahi hai", "are you listening"
        if (clean.contains("sun rahe ho") || clean.contains("sun rahe") || clean.contains("are you listening") ||
            clean.contains("awaz aa rahi hai") || clean.contains("meri awaz") || clean.contains("can you hear me")) {
            return "Ji boss, bilkul sun raha hoon. Hukum karein."
        }

        // "Kahan ho", "kahan ghaeb ho", "where are you"
        if (clean.contains("kahan ho") || clean.contains("kahan ghaeb") || clean.contains("where are you")) {
            return "Yahin hoon boss aapke phone mein, hamesha ready!"
        }

        // "Tell me a joke", "Joke sunao", "latifa sunao"
        if (clean.contains("tell me a joke") || clean.contains("tell a joke") || clean.contains("say a joke") ||
            clean.contains("joke sunao") || clean.contains("latifa sunao") || clean.contains("koi joke") ||
            clean.contains("ek joke")) {
            return "Ek baar ek ustaad ne shagird se poocha: 'Batao sabse teez kya daurta hai?' Shagird bola: 'Sir, school ki chutti ki bell!' 😂"
        }

        // "Kaun ho tum", "apna naam batao", "who are you", "what is your name"
        if (clean.contains("kaun ho tum") || clean.contains("tum kaun ho") || clean.contains("who are you") ||
            clean.contains("naam kya hai") || clean.contains("what is your name") || clean.contains("whats your name")) {
            return "Main AFFY hoon boss, aapka personal AI companion aur smart assistant."
        }

        // "Who created you", "who made you", "kisne banaya"
        if (clean.contains("who made you") || clean.contains("who created you") || clean.contains("kisne banaya") ||
            clean.contains("tumhe kisne banaya") || clean.contains("who is your developer") || clean.contains("who is aftab")) {
            return "Mujhe Aftab ne develop kiya hai jo Pakistan se hain aur ideas ko real software mein build karna pasand karte hain."
        }

        // "Kya kar sakte ho", "what can you do"
        if (clean.contains("kya kar sakte ho") || clean.contains("what can you do") || clean == "help" || clean == "madad") {
            return "Boss, main apps khol sakta hoon, YouTube pe videos chala sakta hoon, WhatsApp messages bhej sakta hoon, volume aur phone settings control kar sakta hoon, aur aapse dosti se baatein kar sakta hoon."
        }

        // "I am good", "main theek hoon"
        if (clean.contains("main theek hoon") || clean.contains("main theek") || clean.contains("i am good") ||
            clean.contains("i am fine") || clean.contains("all good") || clean.contains("sab badhiya")) {
            return "Zabardast boss! Batayein aaj main aapke liye kya karoon?"
        }

        return null
    }

    private fun matchFollowUp(
        clean: String,
        normalized: String,
        lastActiveApp: String?,
        lastSearchQuery: String?,
        isContinuousActive: Boolean
    ): AffyPlan? {
        val app = lastActiveApp?.lowercase()

        // 1. YouTube or Media Follow-up (e.g. "Atif Aslam ka koi song", "Atif Aslam", "Arijit Singh", "Imran Khan speech")
        if (app != null && (app.contains("youtube") || app.contains("spotify") || app.contains("gaana"))) {
            // Check for result selection: "Pehla wala chalao", "ye wala", "first wala", "dusra wala"
            if (clean.contains("pehla wala") || clean.contains("ye wala") || clean.contains("first wala") ||
                clean.contains("pehli video") || clean.contains("isko chalao") || clean.contains("chalao isko") ||
                clean == "chalao" || clean == "play" || clean == "play karo") {
                return AffyPlan(
                    intent = ActionTypes.TAP_LIKE,
                    speechResponse = "Pehla item chala raha hoon boss.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.TAP_LIKE,
                            target = lastActiveApp,
                            text = "first_result"
                        )
                    )
                )
            }

            // Check if user gives a follow-up query:
            // "Atif Aslam ka koi song", "Atif Aslam", "Arijit Singh", "lofi song", "comedy video"
            val hasMusicKeywords = clean.contains("song") || clean.contains("gana") || clean.contains("gaana") ||
                    clean.contains("video") || clean.contains("music") || clean.contains("qawwali") ||
                    clean.contains("naat") || clean.contains("track") || clean.contains("podcast") ||
                    clean.contains("chalao") || clean.contains("play") || clean.contains("dhoondo") ||
                    clean.contains("search")

            // If user explicitly mentions a query or if in continuous mode right after opening app
            if (hasMusicKeywords || isContinuousActive && clean.length >= 3 && !clean.contains("kholo")) {
                var query = normalized
                    .replace(Regex("(?i)\\b(chalao|play|search|dhoondo|laga do|lagao|aur|phir|ab|koi|ek|wala|ka|ki|ke|par|pe)\\b"), " ")
                    .replace(Regex("(?i)\\b(youtube|spotify|affy|aftab|please)\\b"), " ")
                    .trim()

                if (query.isBlank()) {
                    query = normalized
                }

                if (query.isNotBlank() && query.length >= 2) {
                    return AffyPlan(
                        intent = ActionTypes.MULTI_STEP_APP_COMMAND,
                        speechResponse = "Haan, $lastActiveApp par $query play kar raha hoon.",
                        actions = listOf(
                            AffyActionItem(
                                type = ActionTypes.MULTI_STEP_APP_COMMAND,
                                target = lastActiveApp,
                                text = query,
                                value = "play"
                            )
                        )
                    )
                }
            }
        }

        // 2. Social Media reels scrolling follow-up
        if (clean.contains("next reel") || clean.contains("agla reel") || clean.contains("agli video") ||
            clean.contains("swipe up") || clean.contains("niche karo")) {
            return AffyPlan(
                intent = ActionTypes.SCROLL_DOWN,
                speechResponse = "Agli reel chala di.",
                actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_DOWN))
            )
        }
        if (clean.contains("previous reel") || clean.contains("pichla reel") || clean.contains("pichli video") ||
            clean.contains("swipe down") || clean.contains("upar karo")) {
            return AffyPlan(
                intent = ActionTypes.SCROLL_UP,
                speechResponse = "Pichli reel pe aa gaye.",
                actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_UP))
            )
        }

        return null
    }

    private fun isGmailQuery(clean: String): Boolean {
        return clean.contains("mail check") || clean.contains("email check") || clean.contains("koi mail aya") ||
                clean.contains("unread email") || clean.contains("unread emails") || clean.contains("kitne email") ||
                clean.contains("latest email") || clean.contains("email parho") || clean.contains("email parh ke")
    }

    private fun isNotificationQuery(clean: String): Boolean {
        return clean.contains("notification") || clean.contains("koi message aya") || clean.contains("kis ne message") ||
                clean.contains("notif dikhao") || clean.contains("message parho")
    }

    private fun isWhatsAppQuery(clean: String): Boolean {
        return clean.contains("whatsapp") && (clean.contains("message") || clean.contains("msg") ||
                clean.contains("bhejo") || clean.contains("karo") || clean.contains("likho"))
    }

    private fun isMultiStepTaskPrompt(clean: String): Boolean {
        return (clean.contains(" aur ") || clean.contains(" then ") || clean.contains(" karke ") || clean.contains(" and ")) &&
                (clean.contains("search") || clean.contains("kholo") || clean.contains("open") || clean.contains("chalao") || clean.contains("bhejo"))
    }

    private fun isCreatorContextQuery(clean: String, rawLower: String): Boolean {
        val s = "$clean $rawLower"
        val hasCreationInquiry = s.contains("kisne banaya") || s.contains("kis ne banaya") ||
                s.contains("kaun banaya") || s.contains("kon banaya") ||
                s.contains("who made you") || s.contains("who created you") || s.contains("who developed you") ||
                s.contains("who is your creator") || s.contains("who is your developer") ||
                s.contains("tumhara developer") || s.contains("tumhe kisne") || s.contains("tumhe kis ne") ||
                s.contains("apka developer") || s.contains("aapka developer") ||
                s.contains("who is behind you") || s.contains("who is behind affy") ||
                s.contains("developer kaun") || s.contains("developer kon") ||
                s.contains("creator kaun") || s.contains("creator kon") ||
                s.contains("creator ke bare") || s.contains("about your creator") ||
                s.contains("naam affy kyun") || s.contains("affy naam kese") ||
                s.contains("affy naam kaise") || s.contains("naam affy kisne") ||
                s.contains("why are you called affy") || s.contains("why is your name affy") ||
                s.contains("why your name is affy") || s.contains("affy naam kyu") ||
                s.contains("affy naam kyun") || s.contains("affy kyu rakha") ||
                s.contains("affy kyun rakha") || s.contains("kisne create") || s.contains("kis ne create")

        val hasAftabMention = s.contains("aftab") || s.contains("aftaab") || s.contains("avtab") || s.contains("آفتاب")

        return hasCreationInquiry || hasAftabMention
    }

    private fun isKnowledgeQuestion(clean: String): Boolean {
        val questionWords = listOf(
            "kya hai", "kaun hai", "kisne", "kab hua", "kahan hai", "kyun", "how to", "what is",
            "who is", "when did", "where is", "explain", "meaning of", "capital of", "prime minister of",
            "president of", "duniya ka", "tarikh", "history of"
        )
        return questionWords.any { clean.contains(it) }
    }
}
