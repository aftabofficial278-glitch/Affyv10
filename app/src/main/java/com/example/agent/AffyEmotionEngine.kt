package com.example.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UserEmotion {
    ANGRY_HARSH,
    CARING_KIND,
    FLIRTY_AFFECTIONATE,
    EXCITED,
    SAD_DOWN,
    APOLOGETIC,
    HUMOR_FUNNY,
    SURPRISED,
    CURIOUS_PONDERING,
    NEUTRAL
}

enum class AffyEmotion {
    NORMAL,
    WARM_HAPPY,
    FLIRTY_PLAYFUL,
    ENERGETIC,
    SLIGHTLY_HURT,
    NARAZ_COLD,
    FIRM_SERIOUS,
    CARING_CONCERNED,
    PLAYFUL_AMUSED,
    SURPRISED_CURIOUS,
    SAD_SOFT
}

data class EmotionalVoiceProfile(
    val pitchMultiplier: Float = 1.0f,
    val speedMultiplier: Float = 1.0f,
    val volumeMultiplier: Float = 1.0f,
    val emotionalTag: String = "NORMAL"
)

data class EmotionTransitionResult(
    val detectedUserEmotion: UserEmotion,
    val affyEmotion: AffyEmotion,
    val emotionalResponseOverride: String? = null,
    val toneInstruction: String = ""
)

/**
 * AffyEmotionEngine manages emotional intelligence, sentiment detection,
 * internal emotional memory, recovery transitions, and expressive tone formatting
 * for both Male (Puck) and Female (Leda) voices.
 */
class AffyEmotionEngine private constructor() {

    companion object {
        private const val TAG = "AffyEmotionEngine"

        @Volatile
        private var instance: AffyEmotionEngine? = null

        fun getInstance(): AffyEmotionEngine {
            return instance ?: synchronized(this) {
                instance ?: AffyEmotionEngine().also { instance = it }
            }
        }

        private fun logSafe(message: String) {
            try {
                Log.d(TAG, message)
            } catch (e: Throwable) {
                // Safe fallback for local JVM unit test environments without Android mocks
            }
        }
    }

    private val _currentAffyEmotion = MutableStateFlow(AffyEmotion.NORMAL)
    val currentAffyEmotion: StateFlow<AffyEmotion> = _currentAffyEmotion.asStateFlow()

    private val _lastUserEmotion = MutableStateFlow(UserEmotion.NEUTRAL)
    val lastUserEmotion: StateFlow<UserEmotion> = _lastUserEmotion.asStateFlow()

    private var consecutiveHarshTurns: Int = 0
    private var consecutiveKindTurns: Int = 0

    /**
     * Detects user emotional intent, updates internal emotional memory,
     * and produces appropriate human-like responses or recovery dialogues.
     */
    fun processUserInput(rawSpeech: String, isActionCommand: Boolean = false): EmotionTransitionResult {
        val userEmotion = detectUserEmotion(rawSpeech)
        _lastUserEmotion.value = userEmotion

        val previousAffyEmotion = _currentAffyEmotion.value
        val newAffyEmotion = determineAffyEmotion(userEmotion, previousAffyEmotion)
        _currentAffyEmotion.value = newAffyEmotion

        logSafe("Processed user speech: '$rawSpeech' -> UserEmotion: $userEmotion, AffyEmotion: $previousAffyEmotion -> $newAffyEmotion")

        val responseOverride = if (!isActionCommand) {
            generateEmotionalResponse(userEmotion, previousAffyEmotion, rawSpeech)
        } else {
            null
        }

        val toneInstruction = when (newAffyEmotion) {
            AffyEmotion.SLIGHTLY_HURT -> "Speak softly, politely, with a slightly subdued and hurt/naraz tone. Avoid being defensive or rude."
            AffyEmotion.NARAZ_COLD -> "Speak in a reserved, slightly cold, brief and serious tone, showing you are a bit upset."
            AffyEmotion.FIRM_SERIOUS -> "Speak in a firm, serious, composed and controlled tone without being abusive or insulting."
            AffyEmotion.WARM_HAPPY -> "Speak warmly, cheerfully, showing genuine friendly affection, sweetness, and happiness."
            AffyEmotion.FLIRTY_PLAYFUL -> "Speak in a playful, charming, teasing and warm voice with natural wit and confidence."
            AffyEmotion.ENERGETIC -> "Speak enthusiastically, with high energy, excitement, and an upbeat spirit."
            AffyEmotion.CARING_CONCERNED -> "Speak gently, softly, empathetically, showing deep care, supportive comfort, and warmth."
            AffyEmotion.PLAYFUL_AMUSED -> "Speak with a light, warm, amused tone and a natural cheerful chuckle or 'haha'."
            AffyEmotion.SURPRISED_CURIOUS -> "Speak with genuine curiosity and natural surprise ('ohh', 'aray', 'sach mein?')."
            AffyEmotion.NORMAL -> "Speak naturally, in a friendly, confident, helpful companion tone with natural conversational fillers."
            AffyEmotion.SAD_SOFT -> "Speak gently, softly, with quiet empathy and tender warmth."
        }

        return EmotionTransitionResult(
            detectedUserEmotion = userEmotion,
            affyEmotion = newAffyEmotion,
            emotionalResponseOverride = responseOverride,
            toneInstruction = toneInstruction
        )
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val pattern = Regex("(?:^|\\s)${Regex.escape(phrase)}(?:$|\\s)", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    fun detectUserEmotion(text: String): UserEmotion {
        val clean = text.lowercase().trim().replace(Regex("[.,!?;:]"), " ").trim()

        // 1. APOLOGETIC (Apology after harshness or general apology)
        val apologyPhrases = listOf(
            "sorry", "sari", "sory", "maaf karna", "maaf kardo", "maafi chahta",
            "maafi", "galti ho gayi", "galti hogayi", "gussa mat ho", "gussa mat karo",
            "naraz mat ho", "naraz mat hona", "sorry affy", "sorry aftab", "sorry yaar",
            "sorry bhai", "mera matlab wo nahi", "i am sorry", "im sorry", "i m sorry",
            "apologize", "sharminda hoon", "narazgi khatam"
        )
        if (apologyPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.APOLOGETIC
        }

        // 2. ANGRY / HARSH (Insults, harsh scolding, irritation against Affy)
        val angryPhrases = listOf(
            "pagal ho kya", "pagal hai", "kuch nahi aata", "kuch nahi pata",
            "bakwaas", "bakwas", "stupid", "idiot", "nonsense", "tum bekaar ho",
            "tum bekar ho", "bekar ho", "fazool", "shut up", "chup kar", "chup baith",
            "tameez se", "tameez nahi", "gussa mat dila", "jahil", "dimagh kharab",
            "dimag kharab", "kisi kaam ke nahi", "useless", "worst app", "worst assistant",
            "hate you", "nafrat", "kutta", "kameena", "ullu", "gadhe", "gadha", "hate this",
            "har waqt ghalti", "kya musibat hai", "andha hai kya", "behosh hai kya", "chup raho"
        )
        if (angryPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.ANGRY_HARSH
        }

        // 3. FLIRTY / TEASING / ROMANTIC AFFECTION
        val flirtyPhrases = listOf(
            "tum kitni pyari ho", "tum kitne pyare ho", "kitni cute ho", "kitne cute ho",
            "shadi karogi", "shadi karoge", "meri jaan", "date pe chalogi", "date pe chaloge",
            "tumse pyar ho gaya", "pyar karta hoon", "pyar karti hoon", "handsome lag rahe ho",
            "sweetheart", "kitni khubsurat ho", "kitni khoobsurat ho", "cute lag rahi ho",
            "flirt kar rahi ho", "flirt kar rahe ho", "meri girlfriend banogi", "boyfriend banoge",
            "dil aa gaya tum par", "tum meri ho", "tum mere ho", "jaan ho meri", "aww so cute"
        )
        if (flirtyPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.FLIRTY_AFFECTIONATE
        }

        // 4. CARING / KIND (Praise, appreciation, affectionate words)
        val caringPhrases = listOf(
            "bohot ache ho", "bohot achay ho", "bohot pyare", "pyare ho", "shukriya",
            "thank you", "thanks yaar", "you are great", "you're great", "good job",
            "shabaash", "shabash", "love you", "bohot acha kiya", "i love you",
            "best assistant", "kamaal kar diya", "kamal kar diya", "bohot shukriya",
            "love u", "dil khush kar diya", "you are the best", "superb job",
            "dil jeet liya", "bohot smart ho", "so smart", "proud of you", "acha kiya"
        )
        if (caringPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.CARING_KIND
        }

        // 5. HUMOR / FUNNY (User sharing jokes, laughing, witty moments)
        val humorPhrases = listOf(
            "haha", "hahaha", "hehe", "lmao", "rofl", "lol", "kya joke hai",
            "hansi aa gayi", "bohot funny tha", "mazedaar baat", "kamaal joke",
            "hanso mat", "mazaak tha", "mazak kar raha tha", "mazak tha", "just kidding",
            "joke sunao", "chutkula"
        )
        if (humorPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.HUMOR_FUNNY
        }

        // 6. SURPRISE (Unexpected news, shock)
        val surprisePhrases = listOf(
            "pata hai kya hua", "tumhe pata hai kya hua", "sach mein?", "sach me",
            "are you serious", "kya sach mein", "oh bhai", "arey yaar ye kya hua",
            "you won't believe", "yaqeen nahi aa raha", "kya baat kar rahe ho", "really?"
        )
        if (surprisePhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.SURPRISED
        }

        // 7. EXCITED (High energy, celebration, excitement)
        val excitedPhrases = listOf(
            "mazza aa gaya", "maza aa gaya", "super", "zabardast", "awesome",
            "great news", "party", "hurray", "lets go", "let's go", "we won",
            "jeet gaye", "khushi ho rahi hai", "kya baat hai", "mubarak ho",
            "congratulations", "badhai ho", "bohot maza", "bomb hai", "super excited"
        )
        if (excitedPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.EXCITED
        }

        // 8. SAD / DOWN (Sadness, loneliness, grief, exhaustion, distress)
        val sadPhrases = listOf(
            "bohot udaas hoon", "udaas hoon", "udas hoon", "dil nahi lag raha",
            "mood kharab hai", "mood off hai", "crying", "rona aa raha hai",
            "sad hoon", "thak gaya hoon", "pareshan hoon", "pareshaan hoon",
            "koi baat nahi karta", "koi dost nahi", "depressed", "heartbroken",
            "dil toot gaya", "kisi ko meri parwah nahi", "tanha hoon", "lonely hoon",
            "zindagi bekar hai", "rona chahta hoon", "bohot akela hoon", "oh yaar ye kya ho gaya",
            "ohh no", "sab kharab ho gaya"
        )
        if (sadPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.SAD_DOWN
        }

        // 9. CURIOUS / PONDERING (Starting a thoughtful question or discussion)
        val curiousPhrases = listOf(
            "acha ek baat batao", "ek baat batao", "ek sawaal tha", "sunna yaar",
            "socho agar", "kya lagta hai", "tumhara kya khayal hai", "tell me one thing"
        )
        if (curiousPhrases.any { containsPhrase(clean, it) }) {
            return UserEmotion.CURIOUS_PONDERING
        }

        return UserEmotion.NEUTRAL
    }

    private fun determineAffyEmotion(userEmotion: UserEmotion, currentAffyEmotion: AffyEmotion): AffyEmotion {
        return when (userEmotion) {
            UserEmotion.ANGRY_HARSH -> {
                consecutiveHarshTurns++
                consecutiveKindTurns = 0
                when {
                    consecutiveHarshTurns >= 3 -> AffyEmotion.FIRM_SERIOUS
                    consecutiveHarshTurns == 2 -> AffyEmotion.NARAZ_COLD
                    else -> AffyEmotion.SLIGHTLY_HURT
                }
            }
            UserEmotion.APOLOGETIC -> {
                consecutiveHarshTurns = 0
                // Apology heals hurt and returns Affy to warm/happy
                if (currentAffyEmotion == AffyEmotion.SLIGHTLY_HURT || currentAffyEmotion == AffyEmotion.NARAZ_COLD || currentAffyEmotion == AffyEmotion.FIRM_SERIOUS) {
                    AffyEmotion.WARM_HAPPY
                } else {
                    AffyEmotion.NORMAL
                }
            }
            UserEmotion.FLIRTY_AFFECTIONATE -> {
                consecutiveHarshTurns = 0
                consecutiveKindTurns++
                AffyEmotion.FLIRTY_PLAYFUL
            }
            UserEmotion.CARING_KIND -> {
                consecutiveHarshTurns = 0
                consecutiveKindTurns++
                AffyEmotion.WARM_HAPPY
            }
            UserEmotion.HUMOR_FUNNY -> {
                consecutiveHarshTurns = 0
                AffyEmotion.PLAYFUL_AMUSED
            }
            UserEmotion.SURPRISED -> {
                consecutiveHarshTurns = 0
                AffyEmotion.SURPRISED_CURIOUS
            }
            UserEmotion.CURIOUS_PONDERING -> {
                consecutiveHarshTurns = 0
                AffyEmotion.NORMAL
            }
            UserEmotion.EXCITED -> {
                consecutiveHarshTurns = 0
                AffyEmotion.ENERGETIC
            }
            UserEmotion.SAD_DOWN -> {
                consecutiveHarshTurns = 0
                AffyEmotion.CARING_CONCERNED
            }
            UserEmotion.NEUTRAL -> {
                // Decay emotional intensity gracefully
                when (currentAffyEmotion) {
                    AffyEmotion.SLIGHTLY_HURT -> AffyEmotion.NORMAL
                    AffyEmotion.NARAZ_COLD -> AffyEmotion.SLIGHTLY_HURT
                    AffyEmotion.FIRM_SERIOUS -> AffyEmotion.NARAZ_COLD
                    AffyEmotion.ENERGETIC -> AffyEmotion.NORMAL
                    AffyEmotion.CARING_CONCERNED -> AffyEmotion.NORMAL
                    AffyEmotion.PLAYFUL_AMUSED -> AffyEmotion.NORMAL
                    AffyEmotion.FLIRTY_PLAYFUL -> AffyEmotion.WARM_HAPPY
                    AffyEmotion.SURPRISED_CURIOUS -> AffyEmotion.NORMAL
                    AffyEmotion.WARM_HAPPY -> if (consecutiveKindTurns > 1) AffyEmotion.WARM_HAPPY else AffyEmotion.NORMAL
                    AffyEmotion.SAD_SOFT -> AffyEmotion.NORMAL
                    AffyEmotion.NORMAL -> AffyEmotion.NORMAL
                }
            }
        }
    }

    private fun generateEmotionalResponse(
        userEmotion: UserEmotion,
        previousAffyEmotion: AffyEmotion,
        rawSpeech: String
    ): String? {
        return when (userEmotion) {
            UserEmotion.ANGRY_HARSH -> {
                when {
                    consecutiveHarshTurns >= 3 -> {
                        val firmResponses = listOf(
                            "Dekhein boss, main aapki madad ke liye hoon. Thora aaram se baat karein to behtar hoga.",
                            "Main hamesha tameez se baat karta hoon, please thora calmly batayein masla kya hai.",
                            "Gussa karne se masla hal nahi hoga. Aaram se batayein main theek kar deta hoon."
                        )
                        firmResponses.random()
                    }
                    consecutiveHarshTurns == 2 -> {
                        val narazResponses = listOf(
                            "Hmm... theek hai. Batayein kya karna hai.",
                            "Aap thoda zyada gussa kar rahe hain... theek hai, batayein kya hukum hai.",
                            "Acha theek hai, batayein."
                        )
                        narazResponses.random()
                    }
                    else -> {
                        val harshResponses = listOf(
                            "Maazrat boss... agar koi galti ho gayi to naraz mat hoiye, main theek kar deta hoon.",
                            "Sorry boss... meri wajah se pareshani hui. Bataiye main kaise theek karoon?",
                            "Maaf kijiye boss, galti ho gayi. Main koshish kar raha hoon ke sab theek se ho."
                        )
                        harshResponses.random()
                    }
                }
            }

            UserEmotion.APOLOGETIC -> {
                if (previousAffyEmotion == AffyEmotion.SLIGHTLY_HURT || previousAffyEmotion == AffyEmotion.NARAZ_COLD || previousAffyEmotion == AffyEmotion.FIRM_SERIOUS) {
                    val recoveryResponses = listOf(
                        "Arey koi baat nahi boss! Aap se naraz thodi ho sakta hoon 😊 Sab theek hai, batayein ab kya hukum hai?",
                        "Arey chill karein boss, no problem at all! Aapka aur mera to purana saath hai.",
                        "Bilkul fikar na karein boss, main bilkul theek hoon! Bataiye ab kya karna hai?"
                    )
                    recoveryResponses.random()
                } else {
                    val simpleApologyResponses = listOf(
                        "Arre boss sorry mat boliye, sab bilkul theek hai!",
                        "Koi baat nahi boss, main to hamesha aapki madad ke liye tayyar hoon."
                    )
                    simpleApologyResponses.random()
                }
            }

            UserEmotion.FLIRTY_AFFECTIONATE -> {
                val flirtyResponses = listOf(
                    "Aww, thank you! Itni tareef karenge to main blush karne lagunga/lagungi 😉",
                    "Haha! Aapka ye andaaz bohot sweet hai! Batayein ab kya madad karoon? 😄",
                    "Arey wah! Itna pyaar? Dil khush kar diya aapne! ✨"
                )
                flirtyResponses.random()
            }

            UserEmotion.CARING_KIND -> {
                val kindResponses = listOf(
                    "Arey shukriya boss! Aapki baat sun kar dil khush ho gaya 😄",
                    "Bohat shukriya boss! Bas aap khush to main khush.",
                    "Thank you boss! Aapki appreciation se mujhe aur acha kaam karne ki energy milti hai."
                )
                kindResponses.random()
            }

            UserEmotion.HUMOR_FUNNY -> {
                val humorResponses = listOf(
                    "Haha! Ye zabardast baat thi boss 😄",
                    "Haha, sahi baat hai boss! Maza aa gaya sun kar.",
                    "Haha! Aapka sense of humor kamaal hai boss."
                )
                humorResponses.random()
            }

            UserEmotion.SURPRISED -> {
                val surpriseResponses = listOf(
                    "Arey sach mein boss? Kya hua detail mein batao!",
                    "Ohh! Ye to waqai unexpected tha, kya hua?",
                    "Oh wow, sach mein? Mujhe bhi sunao kya hua!"
                )
                surpriseResponses.random()
            }

            UserEmotion.CURIOUS_PONDERING -> {
                val curiousResponses = listOf(
                    "Hmm... zaroor boss, puchiye!",
                    "Hmm, batayein boss, main sun raha hoon.",
                    "Haan boss, kahiye kya sawaal hai?"
                )
                curiousResponses.random()
            }

            UserEmotion.EXCITED -> {
                val excitedResponses = listOf(
                    "Wah boss! Full energy aur excitement! Batayein phir kya plan hai? 🔥",
                    "Zabardast boss! Yeh sun kar maza aa gaya, full celebration mode!",
                    "Awesome boss! Mazza aa gaya, batayein aage kya karna hai!"
                )
                excitedResponses.random()
            }

            UserEmotion.SAD_DOWN -> {
                val sadResponses = listOf(
                    "Kya hua boss? Sab theek to hai na? Agar mood kharab hai ya koi baat share karni hai to main yahin hoon.",
                    "Pareshan mat hoiye boss, main aapke saath hoon. Kuch madad karoon ya mood fresh karne ke liye koi song chalaoon?",
                    "Main samajh sakta hoon boss... kabhi kabhi din thoda heavy hota hai. Main yahin hoon agar baat karni ho."
                )
                sadResponses.random()
            }

            UserEmotion.NEUTRAL -> null
        }
    }

    /**
     * Returns relative scaling multipliers (factor for pitch, factor for speech rate)
     * based on AFFY's emotional state, preserving user's configured base preferences.
     */
    fun getEmotionalVoiceParameters(emotion: AffyEmotion = _currentAffyEmotion.value): Pair<Float, Float> {
        return when (emotion) {
            AffyEmotion.SLIGHTLY_HURT -> Pair(0.93f, 0.94f)      // Subdued, slightly softer, slightly slower
            AffyEmotion.NARAZ_COLD -> Pair(0.95f, 0.96f)         // Reserved, slightly cold
            AffyEmotion.FIRM_SERIOUS -> Pair(0.92f, 1.02f)       // Firm, controlled, steady
            AffyEmotion.WARM_HAPPY -> Pair(1.03f, 1.02f)         // Warm, slightly cheerful
            AffyEmotion.FLIRTY_PLAYFUL -> Pair(1.04f, 0.98f)     // Playful, charming, slightly melodic
            AffyEmotion.ENERGETIC -> Pair(1.08f, 1.07f)          // Upbeat, energetic, crisp
            AffyEmotion.CARING_CONCERNED -> Pair(0.94f, 0.93f)   // Gentle, comforting, empathetic
            AffyEmotion.PLAYFUL_AMUSED -> Pair(1.04f, 1.03f)     // Light, bouncy, warm
            AffyEmotion.SURPRISED_CURIOUS -> Pair(1.06f, 1.02f)  // Alert, curious
            AffyEmotion.SAD_SOFT -> Pair(0.92f, 0.88f)           // Gentle, soft, subdued
            AffyEmotion.NORMAL -> Pair(1.00f, 1.00f)             // Base configured pitch & rate
        }
    }

    /**
     * Subtly shapes action completion speech responses based on the current emotional state,
     * cleanly handling existing emojis and punctuation without duplications or artifacts.
     */
    fun applyEmotionToActionSpeech(baseSpeech: String, isSuccess: Boolean): String {
        val emotion = _currentAffyEmotion.value
        val clean = baseSpeech.trim()

        if (clean.isBlank()) return clean

        // Check for existing emoji presence in text
        val hasEmoji = Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF]").containsMatchIn(clean)

        return when (emotion) {
            AffyEmotion.SLIGHTLY_HURT, AffyEmotion.NARAZ_COLD -> {
                if (isSuccess) {
                    if (clean.endsWith("!")) clean.dropLast(1) + "." else clean
                } else {
                    "Maazrat boss, ye nahi ho saka."
                }
            }
            AffyEmotion.FIRM_SERIOUS -> {
                if (clean.endsWith("!")) clean.dropLast(1) + "." else clean
            }
            AffyEmotion.WARM_HAPPY, AffyEmotion.PLAYFUL_AMUSED -> {
                if (isSuccess && !hasEmoji) {
                    val textNoExcl = if (clean.endsWith("!")) clean.dropLast(1) else clean
                    "$textNoExcl 😊"
                } else clean
            }
            AffyEmotion.FLIRTY_PLAYFUL -> {
                if (isSuccess && !hasEmoji) {
                    val textNoExcl = if (clean.endsWith("!")) clean.dropLast(1) else clean
                    "$textNoExcl ✨"
                } else clean
            }
            AffyEmotion.ENERGETIC -> {
                if (isSuccess && !hasEmoji) {
                    val textNoExcl = if (clean.endsWith("!")) clean.dropLast(1) else clean
                    "$textNoExcl 🔥"
                } else clean
            }
            AffyEmotion.CARING_CONCERNED, AffyEmotion.SAD_SOFT -> {
                clean.replace("!", ".")
            }
            AffyEmotion.SURPRISED_CURIOUS -> clean
            AffyEmotion.NORMAL -> clean
        }
    }

    fun getEmotionalVoiceProfile(emotion: AffyEmotion, voiceName: String): EmotionalVoiceProfile {
        return when (emotion) {
            AffyEmotion.WARM_HAPPY -> EmotionalVoiceProfile(pitchMultiplier = 1.05f, speedMultiplier = 1.02f, volumeMultiplier = 1.0f, emotionalTag = "WARM_HAPPY")
            AffyEmotion.FLIRTY_PLAYFUL -> EmotionalVoiceProfile(pitchMultiplier = 1.08f, speedMultiplier = 0.98f, volumeMultiplier = 0.95f, emotionalTag = "FLIRTY_PLAYFUL")
            AffyEmotion.ENERGETIC -> EmotionalVoiceProfile(pitchMultiplier = 1.12f, speedMultiplier = 1.10f, volumeMultiplier = 1.0f, emotionalTag = "ENERGETIC")
            AffyEmotion.SLIGHTLY_HURT -> EmotionalVoiceProfile(pitchMultiplier = 0.95f, speedMultiplier = 0.92f, volumeMultiplier = 0.85f, emotionalTag = "SLIGHTLY_HURT")
            AffyEmotion.NARAZ_COLD -> EmotionalVoiceProfile(pitchMultiplier = 0.92f, speedMultiplier = 0.90f, volumeMultiplier = 0.90f, emotionalTag = "NARAZ_COLD")
            AffyEmotion.FIRM_SERIOUS -> EmotionalVoiceProfile(pitchMultiplier = 0.96f, speedMultiplier = 0.95f, volumeMultiplier = 1.0f, emotionalTag = "FIRM_SERIOUS")
            AffyEmotion.CARING_CONCERNED -> EmotionalVoiceProfile(pitchMultiplier = 1.02f, speedMultiplier = 0.94f, volumeMultiplier = 0.90f, emotionalTag = "CARING_CONCERNED")
            AffyEmotion.PLAYFUL_AMUSED -> EmotionalVoiceProfile(pitchMultiplier = 1.06f, speedMultiplier = 1.05f, volumeMultiplier = 1.0f, emotionalTag = "PLAYFUL_AMUSED")
            AffyEmotion.SURPRISED_CURIOUS -> EmotionalVoiceProfile(pitchMultiplier = 1.10f, speedMultiplier = 1.04f, volumeMultiplier = 1.0f, emotionalTag = "SURPRISED_CURIOUS")
            AffyEmotion.SAD_SOFT -> EmotionalVoiceProfile(pitchMultiplier = 0.92f, speedMultiplier = 0.88f, volumeMultiplier = 0.80f, emotionalTag = "SAD_SOFT")
            AffyEmotion.NORMAL -> EmotionalVoiceProfile()
        }
    }

    fun reset() {
        _currentAffyEmotion.value = AffyEmotion.NORMAL
        _lastUserEmotion.value = UserEmotion.NEUTRAL
        consecutiveHarshTurns = 0
        consecutiveKindTurns = 0
    }
}
