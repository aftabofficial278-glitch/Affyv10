package com.example.agent

class AffyPersonalityEngine {

    fun formatResponse(baseResponse: String, isSuccess: Boolean, personalityMode: String): String {
        var text = baseResponse.trim()
        if (text.isBlank()) {
            return if (isSuccess) "Done boss 😄" else "Ouch, ye nahi ho saka."
        }

        // Apply emotional nuance
        text = AffyEmotionEngine.getInstance().applyEmotionToActionSpeech(text, isSuccess)

        // Keep simple command replies short and natural (e.g. "Haan, khol raha hoon.")
        if (isSimpleCommandReply(text)) {
            return text
        }

        // Natural conversational reaction touches for normal chat
        when (personalityMode.lowercase()) {
            "playful", "default" -> {
                // Keep it friendly, confident, warm vibe
                if (!isSuccess && !text.contains("masla") && !text.contains("sorry")) {
                    text = "$text Ek baar dobara try karte hain."
                }
            }
            "funny" -> {
                if (!isSuccess && !text.contains("😂") && !text.contains("😅")) {
                    text = "$text (Phone thora sa nakhre kar raha hai! 😅)"
                }
            }
            "calm" -> {
                text = text.replace(Regex("[!]+"), ".")
            }
        }
        return text
    }

    private fun isSimpleCommandReply(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("haan,") || lower.startsWith("done") ||
                lower.contains("khol raha hoon") || lower.contains("kar diya") ||
                lower.contains("bhej diya") || lower.contains("badha di")
    }

    fun getWaitingPhrase(userSpeech: String): String {
        val emotion = AffyEmotionEngine.getInstance().currentAffyEmotion.value
        if (emotion == AffyEmotion.SLIGHTLY_HURT || emotion == AffyEmotion.NARAZ_COLD) {
            return "Ji boss, abhi kar raha hoon..."
        }
        if (emotion == AffyEmotion.FIRM_SERIOUS) {
            return "Action execute kar raha hoon..."
        }
        if (emotion == AffyEmotion.ENERGETIC) {
            return "Full speed mein kar raha hoon boss!"
        }
        if (emotion == AffyEmotion.CARING_CONCERNED) {
            return "Main abhi theek kar raha hoon boss..."
        }
        if (emotion == AffyEmotion.FLIRTY_PLAYFUL) {
            return "Aapke liye to abhi foran kar deta hoon 😉"
        }
        val lower = userSpeech.lowercase()
        return when {
            lower.contains("khol") || lower.contains("open") -> "Haan, ek second..."
            lower.contains("call") || lower.contains("phone") -> "Contact check kar raha hoon..."
            lower.contains("like") -> "Screen pe Like button dhoond raha hoon..."
            lower.contains("whatsapp") || lower.contains("message") -> "Message prepare kar raha hoon..."
            else -> "Hmm samjha, ek second..."
        }
    }

    /**
     * Context-aware conversational prefix for general dialogue turns.
     * Uses reactions sparingly, naturally, and with variety.
     */
    fun getConversationalReaction(contextTag: String? = null): String {
        val emotion = AffyEmotionEngine.getInstance().currentAffyEmotion.value
        if (emotion == AffyEmotion.SLIGHTLY_HURT) {
            return "Ji boss, "
        }
        if (emotion == AffyEmotion.NARAZ_COLD) {
            return "Hmm... "
        }
        if (emotion == AffyEmotion.FIRM_SERIOUS) {
            return "Dekhein, "
        }
        if (emotion == AffyEmotion.WARM_HAPPY) {
            return listOf("Arey haan, ", "Bilkul, ", "Acha, ")[(0..2).random()]
        }
        if (emotion == AffyEmotion.FLIRTY_PLAYFUL) {
            return listOf("Aww, ", "Haha! ", "Arey wah, ")[(0..2).random()]
        }
        if (emotion == AffyEmotion.ENERGETIC) {
            return listOf("Bilkul boss! ", "Wah, ", "Zabardast! ")[(0..2).random()]
        }
        if (emotion == AffyEmotion.CARING_CONCERNED) {
            return listOf("Hmm... ", "Main samajh sakta hoon, ", "Arey, ")[(0..2).random()]
        }
        if (emotion == AffyEmotion.PLAYFUL_AMUSED) {
            return listOf("Haha! ", "Haha bilkul, ", "Arey haha, ", "Hehe, ")[(0..3).random()]
        }
        if (emotion == AffyEmotion.SURPRISED_CURIOUS) {
            return listOf("Ohh, ", "Aray, ", "Oh wow, ", "Really? ")[(0..3).random()]
        }
        return when (contextTag?.lowercase()) {
            "affirmative" -> listOf("Haan, ", "Theek hai, ")[(0..1).random()]
            "understanding" -> listOf("Hmm samjha, ", "Acha samjha, ", "Samjha, ")[(0..2).random()]
            "acknowledgement" -> listOf("Acha, ", "Acha acha, ", "Hmm, ", "Oh, acha, ")[(0..3).random()]
            "surprise" -> listOf("Ohh, ", "Aray, ", "Oh wow, ", "Really? ")[(0..3).random()]
            "humor" -> listOf("Haha, ", "Haha! ", "Hehe, ")[(0..2).random()]
            "curious" -> "Hmm... "
            "concern" -> "Uff, "
            "disappointment" -> "Ohh no... "
            "affection" -> "Aww, "
            else -> ""
        }
    }
}
