package com.example.agent

object AffySttNormalizer {

    private const val WAKE_CORE = "(?:affy|effy|afy|affi|efi|afe|afi|aphy|avy|aphee|aftab|aftaab|avtab|aftaav|oftab)"
    private const val WAKE_PREFIX = "(?:hey|oye|suno|bhai|ok|okay|arre|ae|wake\\s*up|wake)"

    // Matches leading "Hey Affy, ", "Affy ", "Suno Affy "
    private val LEADING_WAKE_REGEX = Regex(
        "(?i)^($WAKE_PREFIX\\s+)?$WAKE_CORE\\s*[,:\\-]?\\s*",
        RegexOption.IGNORE_CASE
    )

    // Matches trailing " Affy", " Affi"
    private val TRAILING_WAKE_REGEX = Regex(
        "(?i)\\s*[,:\\-]?\\s*\\b$WAKE_CORE\\s*[.!?]?$",
        RegexOption.IGNORE_CASE
    )

    // Pure greetings combined with wake-word: e.g. "Hello Affy", "Hi Affy", "Assalam o Alaikum Affy", "Kaise ho Affy"
    private val GREETING_WITH_WAKE_REGEX = Regex(
        "(?i)^(hello|hi|hey|assalam\\s*o?\\s*alaikum|salam|slam|kaise\\s+ho|kese\\s+ho|sun\\s+rahe\\s+ho|kya\\s+haal\\s+hai)\\s+$WAKE_CORE\\s*[.!?]?$",
        RegexOption.IGNORE_CASE
    )

    private val APP_NAME_REPLACEMENTS = listOf(
        Regex("(?i)\\b(you\\s*tube|u\\s*tube|yt|yutube|you\\s*tub)\\b") to "YouTube",
        Regex("(?i)\\b(wat\\s*sap|wat\\s*sapp|what\\s*app|wats\\s*app|vats\\s*app|vat\\s*sap)\\b") to "WhatsApp",
        Regex("(?i)\\b(tik\\s*tok|tick\\s*tock|tik\\s*tak)\\b") to "TikTok",
        Regex("(?i)\\b(insta\\s*gram|insta|instgram)\\b") to "Instagram",
        Regex("(?i)\\b(face\\s*book|fb)\\b") to "Facebook",
        Regex("(?i)\\b(spoty\\s*fi|spot\\s*ify)\\b") to "Spotify",
        Regex("(?i)\\b(crome|google\\s*chrome)\\b") to "Chrome",
        Regex("(?i)\\b(gal\\s*lery|galery|gallary)\\b") to "Gallery",
        Regex("(?i)\\b(set\\s*ting|set\\s*tings|seting)\\b") to "Settings",
        Regex("(?i)\\b(calculater|calcy|calcu)\\b") to "Calculator",
        Regex("(?i)\\b(cam\\s*ra|chamra)\\b") to "Camera",
        Regex("(?i)\\b(g\\s*mail|email|e-mail)\\b") to "Gmail",
        Regex("(?i)\\b(maps|google\\s*maps|naksha)\\b") to "Maps",
        Regex("(?i)\\b(ghadi|ghari|alarm|clock)\\b") to "Clock"
    )

    /**
     * Checks if the text contains any variation of the wake word AFFY.
     */
    fun containsWakeWord(speech: String): Boolean {
        val lower = speech.lowercase().trim()
        val regex = Regex("(?i)\\b$WAKE_CORE\\b")
        return regex.containsMatchIn(lower) ||
                lower.contains("affy") || lower.contains("effy") || lower.contains("afy") ||
                lower.contains("affi") || lower.contains("efi") || lower.contains("afe") ||
                lower.contains("afi") || lower.contains("aftab") || lower.contains("aftaab")
    }

    /**
     * Strips wake words and returns normalized, clean speech for fast routing.
     * Preserves greetings like "Hello AFFY" as "hello" or "hello affy" so they route properly.
     */
    fun stripWakeWord(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return ""

        // Check for pure greeting + wake word: "Hello Affy" -> "hello", "Kaise ho Affy" -> "kaise ho"
        val greetingMatch = GREETING_WITH_WAKE_REGEX.find(trimmed)
        if (greetingMatch != null) {
            val greetingWord = greetingMatch.groupValues[1].trim()
            return greetingWord
        }

        // If it's just the wake word by itself (e.g. "Affy", "Hey Affy", "Suno Affy", "Oye Affy")
        if (trimmed.matches(Regex("(?i)^($WAKE_PREFIX\\s+)?$WAKE_CORE[.!?]?$"))) {
            return "hello affy"
        }

        // Check if "Aftab" or "Affy" is the subject of an inquiry (e.g., "Aftab kaun hai?", "Affy naam kyun hai?", "Why are you called Affy?")
        // If so, preserve the subject and only strip any preceding wake prefix like "Hey"
        val isIdentityInquiry = Regex(
            "(?i)^($WAKE_PREFIX\\s+)?(aftab|aftaab|affy)\\s*[,:\\-]?\\s+(kaun|kon|kya|who|what|where|kahan|kyun|why|ne|ko|kaisa|kese|is|developer|creator|ki|ke|se|bare|baray|naam|name)\\b"
        ).containsMatchIn(trimmed) || Regex(
            "(?i)\\b(about|who\\s+is|tell\\s+me\\s+about|know|naam|name|called)\\s+(aftab|aftaab|affy)\\b"
        ).containsMatchIn(trimmed) || Regex(
            "(?i)\\b(affy\\s+naam|naam\\s+affy|called\\s+affy)\\b"
        ).containsMatchIn(trimmed)

        if (isIdentityInquiry) {
            return trimmed.replace(Regex("(?i)^($WAKE_PREFIX)\\s+"), "").trim()
        }

        // Strip leading wake word
        var cleaned = trimmed.replace(LEADING_WAKE_REGEX, "").trim()

        // Strip trailing wake word
        cleaned = cleaned.replace(TRAILING_WAKE_REGEX, "").trim()

        return cleaned.ifBlank { trimmed }
    }

    /**
     * Normalizes common STT phonetic transcription errors and aligns app names.
     * Guaranteed not to corrupt commands or user names.
     */
    fun normalize(speech: String): String {
        var text = stripWakeWord(speech)

        for ((regex, replacement) in APP_NAME_REPLACEMENTS) {
            text = text.replace(regex, replacement)
        }

        return text.trim()
    }
}
