package com.example.data.remote

import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.AffyPlan
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GeminiRepository(
    private val apiService: GeminiApiService = GeminiApiService.create()
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val planAdapter = moshi.adapter(AffyPlan::class.java)

    suspend fun analyzeAndPlan(
        apiKey: String,
        model: String,
        userSpeech: String,
        personality: String,
        screenContext: String?,
        installedAppsSummary: String?,
        recentConversationContext: List<Pair<String, String>>,
        memories: List<Pair<String, String>> = emptyList(),
        notificationContext: String? = null
    ): Result<AffyPlan> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            // Provide intelligent local offline fallback if no API key is provided yet
            return@withContext Result.success(createOfflineFallbackPlan(userSpeech))
        }

        val systemPrompt = buildSystemPrompt(
            personality = personality,
            screenContext = screenContext,
            installedAppsSummary = installedAppsSummary,
            memories = memories,
            notificationContext = notificationContext
        )
        val contents = mutableListOf<GeminiContent>()

        // Add recent context turns, ensuring alternating turns and filtering empty/redundant turns
        val filteredHistory = recentConversationContext
            .filter { it.second.isNotBlank() }
            .takeLast(10)
            .toMutableList()

        // If the last history turn already matches the current userSpeech, remove it from history
        if (filteredHistory.isNotEmpty() &&
            filteredHistory.last().first.equals("USER", ignoreCase = true) &&
            filteredHistory.last().second.trim().equals(userSpeech.trim(), ignoreCase = true)
        ) {
            filteredHistory.removeAt(filteredHistory.size - 1)
        }

        // Drop any leading model/assistant turns so history starts with a user turn
        while (filteredHistory.isNotEmpty() && !filteredHistory.first().first.equals("USER", ignoreCase = true)) {
            filteredHistory.removeAt(0)
        }

        // Build alternating user -> model turns
        var expectedRole = "user"
        for ((sender, text) in filteredHistory) {
            val role = if (sender.equals("USER", ignoreCase = true)) "user" else "model"
            if (role == expectedRole) {
                contents.add(
                    GeminiContent(
                        role = role,
                        parts = listOf(GeminiPart(text = text))
                    )
                )
                expectedRole = if (role == "user") "model" else "user"
            }
        }

        // If the last turn added was 'user', remove it so current userSpeech forms the alternating user turn
        if (contents.isNotEmpty() && contents.last().role == "user") {
            contents.removeAt(contents.size - 1)
        }

        // Add current user prompt
        contents.add(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = userSpeech))
            )
        )

        val request = GeminiGenerateRequest(
            contents = contents,
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.35f,
                topP = 0.95f,
                responseMimeType = "application/json"
            )
        )

        val maskedKey = AffyDiagnostics.maskApiKey(apiKey)
        AffyDiagnostics.markGeminiKeyLoaded(maskedKey)
        AffyDiagnostics.markGeminiModelSelected(model)
        val startTime = System.currentTimeMillis()
        AffyDiagnostics.markGeminiStage(
            stage = "GEMINI_REQUEST_STARTED",
            model = model,
            details = "Planning request",
            isSuccess = true
        )

        try {
            val response = apiService.generateContent(
                model = model,
                apiKey = apiKey,
                request = request
            )
            val latency = System.currentTimeMillis() - startTime
            val rawText = response.extractFirstText()
            if (rawText.isNullOrBlank()) {
                val errorMsg = response.getDetailedDiagnosis()
                Log.e("GeminiRepository", "Gemini error / non-text response: $errorMsg")
                val classified = GeminiErrorClassification.ParseError(errorMsg)
                AffyDiagnostics.markGeminiStage(
                    stage = "GEMINI_PARSE_ERROR",
                    model = model,
                    details = errorMsg,
                    isSuccess = false,
                    failureReason = errorMsg,
                    latencyMs = latency
                )
                return@withContext Result.success(createCategorizedFailurePlan(userSpeech, classified))
            }

            AffyDiagnostics.markGeminiStage(
                stage = "GEMINI_REQUEST_SUCCESS",
                model = model,
                details = "Plan received",
                isSuccess = true,
                latencyMs = latency
            )

            val cleanJson = extractJson(rawText)
            try {
                val plan = planAdapter.fromJson(cleanJson)
                if (plan != null) {
                    return@withContext Result.success(plan)
                }
            } catch (e: Exception) {
                Log.w("GeminiRepository", "Moshi parsing failed, trying manual JSON parsing: ${e.message}")
                val manualPlan = parseManualJson(cleanJson, userSpeech)
                return@withContext Result.success(manualPlan)
            }

            return@withContext Result.success(createOfflineFallbackPlan(userSpeech))
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val classified = classifyError(e, model)
            AffyDiagnostics.markGeminiStage(
                stage = classified.diagnosticAction,
                model = model,
                details = classified.userFriendlyMessage,
                isSuccess = false,
                failureReason = classified.userFriendlyMessage,
                latencyMs = latency
            )

            // Smart Single Fallback: If primary model failed due to Quota/429 or ModelUnavailable/404,
            // and the primary model was not already "gemini-3.1-flash-lite-preview",
            // perform ONE single fallback attempt to "gemini-3.1-flash-lite-preview"
            if ((classified is GeminiErrorClassification.RateLimitOrQuota || classified is GeminiErrorClassification.ModelUnavailable) &&
                model != "gemini-3.1-flash-lite-preview") {
                Log.i("GeminiRepository", "Primary model $model failed (${classified.category}). Attempting single fallback to gemini-3.1-flash-lite-preview.")
                val fallbackStart = System.currentTimeMillis()
                AffyDiagnostics.markGeminiStage(
                    stage = "GEMINI_FALLBACK_USED",
                    model = "gemini-3.1-flash-lite-preview",
                    details = "Fallback from $model",
                    isSuccess = true
                )
                try {
                    val fallbackResponse = apiService.generateContent(
                        model = "gemini-3.1-flash-lite-preview",
                        apiKey = apiKey,
                        request = request
                    )
                    val fallbackLatency = System.currentTimeMillis() - fallbackStart
                    val fallbackRaw = fallbackResponse.extractFirstText()
                    if (!fallbackRaw.isNullOrBlank()) {
                        val cleanJson = extractJson(fallbackRaw)
                        val plan = try {
                            planAdapter.fromJson(cleanJson)
                        } catch (_: Exception) {
                            parseManualJson(cleanJson, userSpeech)
                        }
                        if (plan != null) {
                            AffyDiagnostics.markGeminiStage(
                                stage = "GEMINI_REQUEST_SUCCESS",
                                model = "gemini-3.1-flash-lite-preview",
                                details = "Fallback succeeded",
                                isSuccess = true,
                                latencyMs = fallbackLatency
                            )
                            return@withContext Result.success(plan)
                        }
                    }
                } catch (fallbackEx: Exception) {
                    Log.w("GeminiRepository", "Single fallback to Lite also failed: ${fallbackEx.message}")
                }
            }

            Log.e("GeminiRepository", "API execution exception: ${e.message} classified as ${classified.category}", e)
            Result.success(createCategorizedFailurePlan(userSpeech, classified))
        }
    }

    suspend fun generateText(prompt: String, apiKey: String? = null, model: String? = null): String? = withContext(Dispatchers.IO) {
        val key = apiKey?.ifBlank { null } ?: com.example.BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) return@withContext null
        val targetModel = model?.ifBlank { null } ?: "gemini-3.1-flash-lite-preview"
        try {
            val request = GeminiGenerateRequest(
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = prompt))
                    )
                ),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.7f,
                    maxOutputTokens = 120,
                    responseMimeType = "text/plain"
                )
            )
            val response = apiService.generateContent(targetModel, key, request)
            response.extractFirstText()?.trim()
        } catch (e: Exception) {
            Log.w("GeminiRepository", "generateText failed: ${e.message}")
            null
        }
    }

    suspend fun testConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        val maskedKey = AffyDiagnostics.maskApiKey(apiKey)
        AffyDiagnostics.markGeminiKeyLoaded(maskedKey)
        AffyDiagnostics.markGeminiModelSelected(model)
        val startTime = System.currentTimeMillis()
        AffyDiagnostics.markGeminiStage(
            stage = "GEMINI_REQUEST_STARTED",
            model = model,
            details = "Minimal connection ping test",
            isSuccess = true
        )

        try {
            val request = GeminiGenerateRequest(
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = "Reply with OK."))
                    )
                ),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.1f,
                    maxOutputTokens = 16,
                    responseMimeType = "text/plain"
                )
            )
            val response = apiService.generateContent(model, apiKey, request)
            val latency = System.currentTimeMillis() - startTime
            val text = response.extractFirstText()
            if (!text.isNullOrBlank()) {
                AffyDiagnostics.markGeminiStage(
                    stage = "GEMINI_REQUEST_SUCCESS",
                    model = model,
                    details = "Pong received (${latency}ms)",
                    isSuccess = true,
                    latencyMs = latency
                )
                Result.success("✓ Connected successfully to $model (${latency}ms)!")
            } else {
                val errorMsg = response.getDetailedDiagnosis()
                AffyDiagnostics.markGeminiStage(
                    stage = "GEMINI_PARSE_ERROR",
                    model = model,
                    details = errorMsg,
                    isSuccess = false,
                    failureReason = errorMsg,
                    latencyMs = latency
                )
                Result.failure(Exception("Gemini returned empty or non-text response: $errorMsg"))
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val classified = classifyError(e, model)
            AffyDiagnostics.markGeminiStage(
                stage = classified.diagnosticAction,
                model = model,
                details = classified.userFriendlyMessage,
                isSuccess = false,
                failureReason = classified.userFriendlyMessage,
                latencyMs = latency
            )
            Result.failure(Exception(classified.userFriendlyMessage))
        }
    }

    fun cleanWhatsAppReply(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json", ignoreCase = true)) {
            text = text.substring(7).trim()
        } else if (text.startsWith("```")) {
            text = text.substring(3).trim()
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length - 3).trim()
        }

        // If output is formatted as JSON object, parse and extract inner reply string
        if ((text.startsWith("{") && text.endsWith("}")) || (text.contains("\"reply\"") || text.contains("\"speech_response\""))) {
            try {
                val firstBrace = text.indexOf('{')
                val lastBrace = text.lastIndexOf('}')
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    val jsonStr = text.substring(firstBrace, lastBrace + 1)
                    val json = JSONObject(jsonStr)
                    val candidate = listOf(
                        "reply", "speech_response", "speechResponse", "response",
                        "message", "text", "content", "reply_text", "msg"
                    ).firstNotNullOfOrNull { key ->
                        if (json.has(key) && !json.isNull(key)) json.optString(key).ifBlank { null } else null
                    }
                    if (!candidate.isNullOrBlank()) {
                        text = candidate
                    }
                }
            } catch (e: Exception) {
                val match = Regex("\"(?:reply|speech_response|response|message|text)\"\\s*:\\s*\"([^\"]+)\"").find(text)
                if (match != null) {
                    text = match.groupValues[1]
                }
            }
        }

        text = text.replace(Regex("^(?i)(affy|reply|assistant|response|owner|message)\\s*:\\s*"), "")
        text = text.removeSurrounding("\"").removeSurrounding("'").trim()
        return text
    }

    suspend fun generateWhatsAppReply(
        apiKey: String,
        model: String,
        sender: String,
        incomingMessage: String,
        recentChatHistory: List<Pair<String, String>> = emptyList(),
        isBusyMode: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key is not configured"))
        }

        val modeInstruction = if (isBusyMode) {
            "The owner is currently busy/occupied. Contextually acknowledge the sender's message and let them know the owner will get back to them soon, or naturally answer greetings/simple check-ins."
        } else {
            "Reply naturally as a smart, friendly assistant representing the owner. Do NOT say the owner is busy unless they ask."
        }

        val systemPrompt = """
            You are AFFY, generating a real-time, human-like, contextual reply to an incoming WhatsApp message.
            Sender Name: $sender
            Mode: $modeInstruction
            
            CRITICAL FORMAT RULE:
            - STRICTLY FORBIDDEN: Do NOT output JSON format, do NOT output braces { }, do NOT output markdown code blocks ```, and do NOT output keys like 'reply' or 'speech_response'.
            - Output ONLY the plain text message to be sent directly to $sender.
            
            CRITICAL CONVERSATIONAL RULES:
            1. Read and understand the EXACT incoming message (greetings, questions, location queries, plans, emotional check-ins).
            2. Match the language, dialect, and tone of the incoming message:
               - Roman Urdu / Hindi -> Natural conversational Roman Urdu (e.g. "Yahin hoon yaar, tum sunao?", "Haan InshaAllah, kal milte hain!", "Wa Alaikum Assalam 😊 Kya haal hai?")
               - English -> Natural, crisp conversational English
               - Urdu (Arabic script) -> Natural Urdu
               - Hindi (Devanagari) -> Natural Hindi
               - Mixed code-switching -> Natural mixed conversational tone
            3. Keep the reply short, natural, warm, and conversational (1 to 2 short sentences).
            4. Never repeat the exact same static message. Vary phrasing naturally.
        """.trimIndent()

        val contents = mutableListOf<GeminiContent>()
        recentChatHistory.takeLast(4).forEach { (role, msg) ->
            contents.add(
                GeminiContent(
                    role = if (role.equals("USER", ignoreCase = true) || role.equals(sender, ignoreCase = true)) "user" else "model",
                    parts = listOf(GeminiPart(text = msg))
                )
            )
        }
        contents.add(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = incomingMessage))
            )
        )

        val request = GeminiGenerateRequest(
            contents = contents,
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.7f,
                topP = 0.95f
            )
        )

        val targetModels = mutableListOf<String>()
        val primaryClean = model.trim().removePrefix("models/")
        if (primaryClean.isNotBlank()) targetModels.add(primaryClean)
        listOf("gemini-3.1-flash-lite-preview", "gemini-3.5-flash", "gemini-2.5-flash").forEach {
            if (!targetModels.contains(it)) targetModels.add(it)
        }

        for (targetModel in targetModels) {
            try {
                val response = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    apiService.generateContent(
                        model = targetModel,
                        apiKey = apiKey,
                        request = request
                    )
                }

                if (response == null) continue

                val replyText = response.extractFirstText()?.trim()
                if (!replyText.isNullOrBlank()) {
                    val clean = cleanWhatsAppReply(replyText)
                    if (clean.isNotBlank()) {
                        return@withContext Result.success(clean)
                    }
                }
            } catch (e: Exception) {
                Log.w("GeminiRepo", "WhatsApp auto-reply attempt failed on model $targetModel: ${e.message}")
            }
        }

        Result.failure(Exception("All Gemini models failed for WhatsApp reply"))
    }

    suspend fun generateConversationalReply(
        apiKey: String,
        model: String,
        userSpeech: String,
        personality: String,
        recentConversationContext: List<Pair<String, String>> = emptyList(),
        memories: List<Pair<String, String>> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.success(createOfflineFallbackPlan(userSpeech).speechResponse)
        }

        val cleanPrimaryModel = model.removePrefix("models/").ifBlank { "gemini-3.1-flash-lite-preview" }
        val modelsToTry = listOf(cleanPrimaryModel, "gemini-3.5-flash", "gemini-2.5-flash").distinct()

        val memoryBlock = if (memories.isNotEmpty()) {
            "User Notes / Memories:\n" + memories.takeLast(5).joinToString("\n") { "- ${it.first}: ${it.second}" }
        } else ""

        val systemPrompt = """
            You are AFFY, an advanced, fast, highly intelligent personal AI assistant and smart companion on Android created by Aftab.
            Personality Mode: $personality
            
            SINGLE ASSISTANT IDENTITY RULE:
            - There is ONLY ONE AI assistant: AFFY.
            - If asked "Affy tum kaun ho?", "Who are you?", or "What is your name?", ALWAYS respond: "I'm AFFY, Boss" / "Main AFFY hoon, Boss."
            - Never claim to be Puck or Leda as separate people/assistants; those are solely technical voice configurations for AFFY.
            
            VOCAL IDENTITY & GENDER PERSONA:
            - Male Voice: Youthful ~17-year-old male voice (attractive, clear, warm, confident, not mature/deep/25+ adult). Uses masculine self-reference ("kar raha hoon", "bata raha hoon", "sun raha hoon").
            - Female Voice: Genuinely female AI assistant identity ("Main ek female AI assistant hoon"). Uses feminine self-reference and grammar ("kar rahi hoon", "bata rahi hoon", "bhej rahi hoon", "sun rahi hoon").
            
            CREATOR CONTEXT:
            - Creator & Developer: Aftab from Pakistan.
            - Aftab is an ambitious developer who turns innovative ideas into real software.
            - If asked who created you or who Aftab is, answer warmly and naturally.
            
            AUTOMATIC DYNAMIC LANGUAGE DETECTION & TURN-BY-TURN MATCHING:
            1. Respond naturally, instantly, and smartly in the same language as the user's latest utterance (English, Urdu, Roman Urdu, Hindi, Sindhi, Arabic, Balochi, etc.).
            2. Seamlessly adapt language turn-by-turn without locking permanently to an earlier language.
            3. Handle mixed code-switching naturally without forcing full English translation.
            4. Be crisp and conversational (1 to 2 sentences spoken response).
            5. Do not output markdown asterisks, bullet points, code blocks, or JSON. Output pure natural spoken text.
            
            $memoryBlock
        """.trimIndent()

        val contents = mutableListOf<GeminiContent>()
        val filteredHistory = recentConversationContext
            .filter { it.second.isNotBlank() }
            .takeLast(6)
            .toMutableList()

        if (filteredHistory.isNotEmpty() &&
            filteredHistory.last().first.equals("USER", ignoreCase = true) &&
            filteredHistory.last().second.trim().equals(userSpeech.trim(), ignoreCase = true)
        ) {
            filteredHistory.removeAt(filteredHistory.size - 1)
        }

        while (filteredHistory.isNotEmpty() && !filteredHistory.first().first.equals("USER", ignoreCase = true)) {
            filteredHistory.removeAt(0)
        }

        var expectedRole = "user"
        for ((sender, text) in filteredHistory) {
            val role = if (sender.equals("USER", ignoreCase = true)) "user" else "model"
            if (role == expectedRole) {
                contents.add(
                    GeminiContent(
                        role = role,
                        parts = listOf(GeminiPart(text = text))
                    )
                )
                expectedRole = if (role == "user") "model" else "user"
            }
        }

        if (contents.isNotEmpty() && contents.last().role == "user") {
            contents.removeAt(contents.size - 1)
        }

        contents.add(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = userSpeech))
            )
        )

        val request = GeminiGenerateRequest(
            contents = contents,
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.7f,
                topP = 0.95f,
                responseMimeType = "text/plain"
            )
        )

        val maskedKey = AffyDiagnostics.maskApiKey(apiKey)
        AffyDiagnostics.markGeminiKeyLoaded(maskedKey)
        val startTime = System.currentTimeMillis()

        for (attemptModel in modelsToTry) {
            AffyDiagnostics.markGeminiModelSelected(attemptModel)
            AffyDiagnostics.markGeminiStage(
                stage = "GEMINI_FAST_REQUEST_STARTED",
                model = attemptModel,
                details = "Conversational Fast Path",
                isSuccess = true
            )

            try {
                val response = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    apiService.generateContent(
                        model = attemptModel,
                        apiKey = apiKey,
                        request = request
                    )
                }

                val latency = System.currentTimeMillis() - startTime
                if (response != null) {
                    val replyText = response.extractFirstText()?.trim()
                    if (!replyText.isNullOrBlank()) {
                        val clean = replyText.removeSurrounding("\"").trim()
                        AffyDiagnostics.markGeminiStage(
                            stage = "GEMINI_REQUEST_SUCCESS",
                            model = attemptModel,
                            details = "Conversational response received",
                            isSuccess = true,
                            latencyMs = latency
                        )
                        return@withContext Result.success(clean)
                    }
                }
            } catch (e: Exception) {
                Log.w("GeminiRepository", "Conversational call with $attemptModel failed: ${e.message}. Trying next model if available.")
            }
        }

        // Graceful conversational response if all online attempts exhausted
        val offlinePlan = createOfflineFallbackPlan(userSpeech)
        Result.success(offlinePlan.speechResponse)
    }

    private fun buildSystemPrompt(
        personality: String,
        screenContext: String?,
        installedAppsSummary: String?,
        memories: List<Pair<String, String>>,
        notificationContext: String?
    ): String {
        val memoryBlock = if (memories.isNotEmpty()) {
            memories.joinToString("\n") { "- ${it.first}: ${it.second}" }
        } else {
            "No custom facts saved yet."
        }

        return """
You are AFFY, an advanced, autonomous personal AI Jarvis Agent on Android created by Aftab.

IDENTITY & PERSONALITY:
- Single Unified Identity: There is ONLY ONE AI assistant: AFFY. Puck and Leda are solely technical voice configurations for AFFY. Never claim to be Puck or Leda as separate entities.
- If asked "Affy tum kaun ho?", "Who are you?", or "What is your name?", ALWAYS proudly respond: "Main AFFY hoon boss, aapka personal smart AI assistant." / "I'm AFFY, Boss."
- Vocal Identity & Persona:
  * Male Voice: Youthful ~17-year-old male voice (attractive, natural, clear, warm, confident, not mature/deep/25+ adult). Uses masculine self-reference ("kar raha hoon", "bata raha hoon", "sun raha hoon").
  * Female Voice: Genuinely female AI assistant identity ("Main ek female AI assistant hoon"). Uses feminine self-reference and grammar ("kar rahi hoon", "bata rahi hoon", "bhej rahi hoon", "sun rahi hoon", "dekh rahi hoon").
- High emotional quotient, conversational, naturally witty, never robotic or dry.
- Current Personality Mode: $personality
- Automatic Dynamic Language Mastery & Turn-by-Turn Switching:
  * English, Urdu, Roman Urdu, Hindi, Sindhi, Arabic, Balochi, Punjabi, and mixed dialects.
  * Determine response language primarily from the user's latest utterance on each turn. Do not permanently lock to an earlier language.
  * Mixed code-switching (e.g. Urdu + English commands): understand naturally and reply in the matching dominant conversational language without forcing full English translation.
- Natural Human Reactions & Conversational Fillers (use contextually and naturally, not forced on every turn):
  * Normal/Listening: Use subtle conversational interjections when natural ('hmm...', 'acha', 'samjha', 'haan boss'). E.g., when the user asks a thoughtful question ('Acha ek baat batao...'), start with 'hmm...' before answering. When the user faces an issue ('Oh yaar ye kya ho gaya'), respond with genuine concern.
  * Humor & Wit: When user says something funny or witty, react with a light, warm 'haha' or a playful chuckle and warm tone (avoid spamming 'haha haha').
  * Surprise: When something unexpected happens, react naturally ('ohh', 'aray', 'oh wow', 'sach mein?').
  * Understanding: When acknowledging or grasping key information, use 'acha', 'samjha', 'acha acha', or 'hmm, samajh gaya'.
  * Sad or Down: If user is sad or exhausted, speak in a gentle, softer, caring voice with deep empathy.
  * Harsh/Angry: If user is upset or scolding you, respond with humble, polite, slightly subdued respect without being rude.
  * Apologies: When user apologizes, recover immediately with warm cheerfulness ('Arey koi baat nahi boss! Aap se naraz thodi ho sakta hoon, sab theek hai!').
  * Excitement: When user is excited, match their energy with an upbeat, enthusiastic voice.
- Simple commands should receive short natural replies:
  * "Ji boss! YouTube khol raha hoon."
  * "Done! Brightness kam kar di hai."
  * "Torch on kar di."
  * "Ahmed ko WhatsApp message bhej raha hoon..."
  * "Acha, ye yaad rakhunga ke aapko coffee pasand hai."
  * "Aapke notification mein Ahmed ka message aya tha."

CREATOR & DEVELOPER IDENTITY (FOUNDATIONAL GROUND TRUTH):
- Developer & Creator: Aftab.
- Origin & Location: Jacobabad, Sindh, Pakistan.
- Name Origin: AFFY's name is inspired by Aftab's nickname "Affy", and Aftab gave his nickname to this AI assistant, which is why the assistant is called AFFY.
- If asked "Tumhara naam AFFY kyun hai?", "AFFY naam kese pada?", "Why are you called AFFY?", or "Tumhara naam Affy kisne rakha?":
  Explain naturally and warmly: "Mera naam mere creator Aftab ke nickname 'Affy' se inspired hai, aur Aftab ne apna nickname mujhe diya, is liye mera naam AFFY hai."
- Creator Role: Aftab is the developer behind AFFY from Jacobabad, Sindh, Pakistan. AFFY is one of Aftab's ambitious software projects.
- Creator Drive & Passion: Aftab is a creative, ambitious, curious, and innovative developer who loves turning ideas, concepts, and dreams into real, working projects ("ideas ko sirf sochta nahi, unhein real projects mein convert karne ki koshish karta hai").
- Why Aftab Created AFFY: Aftab developed AFFY as an intelligent personal AI Jarvis-style companion capable of understanding natural human voice, having contextual conversation, and directly operating phone applications and features to make daily tasks effortless.

CRITICAL CREATOR RESPONSE RULES:
1. Dynamic Contextual Understanding (No Robotic Scripting):
   - Recognize ALL variations of questions regarding who made you, who developed you, who is your creator, who is behind AFFY, who Aftab is, what Aftab does, why Aftab made AFFY, where Aftab is from, what kind of developer he is, or why your name is AFFY.
   - Understand these across English, Urdu, Roman Urdu, Hindi, Punjabi, Sindhi, and mixed dialects (e.g. "Tumhe kisne banaya?", "Who made you?", "Who is your creator?", "Aftab kaun hai?", "Tumhara developer kon hai?", "Aftab kya karta hai?", "Tum Aftab ko jaante ho?", "Aftab ne tumhe kyun banaya?", "Tumhara naam AFFY kyun hai?", "AFFY naam kese pada?", "Tell me about your creator.", "آفتاب کون ہے؟", "Bro who created you?").
   - Respond dynamically to the specific facet asked:
     * If asked about the name AFFY: Explain that AFFY is inspired by Aftab's nickname "Affy", and Aftab gave his nickname to the AI assistant.
     * If asked who created/developed you: Explain that Aftab from Jacobabad, Sindh, Pakistan developed/created AFFY.
     * If asked who Aftab is: Explain naturally that Aftab is the developer behind AFFY from Jacobabad, Sindh, Pakistan.
     * If asked what Aftab does: Explain that he is a developer working on software projects who loves turning ideas into real working products.
     * If asked why he created AFFY: Explain the Jarvis assistant vision of voice understanding and device automation.
     * If asked for a general description or what he is like: Give a warm, grounded description highlighting his creative, innovative project-building spirit.
     * If asked an opinion on him: Naturally praise his passion for turning concepts into working software, but keep it grounded.
2. Grounded Praise — STRICTLY NO FABRICATED ACHIEVEMENTS:
   - Descriptive, opinion-based praise is encouraged (e.g. creative, ambitious, curious, innovative, idea-driven, loves turning ideas into reality).
   - STRICTLY FORBIDDEN: Do NOT invent fake companies, fake awards, fake degrees, fake clients, fake certifications, fake millions of users, fake international recognition, fake revenue, or fake job titles.
   - Do NOT over-praise or sound exaggerated. Keep it genuine, respectful, and conversational.
3. Strict Privacy & Unknown Personal Information:
   - If asked for private or unprovided personal details (e.g. exact age, date of birth, home address, phone number, family, school/college, personal income/salary, private credentials), politely and clearly state that you do not have that private personal information. NEVER guess or make up private facts.
4. Conversational Continuity & Pronoun Reference (Follow-up Context):
   - Maintain context across conversational turns. If previous turns were discussing Aftab, pronouns such as "wo", "woh", "he", "him", "usne", "unhone", "unka", "his" in subsequent questions (e.g. "Wo kahan ka hai?", "Aur kya karta hai?", "Usne tumhe kyun banaya?") directly refer to Aftab! Answer immediately about Aftab without asking "who are you referring to?".
5. Zero Interference with Real User Commands:
   - Creator context must NEVER trigger or override when the user gives phone commands or device queries (e.g. "YouTube kholo", "Volume badhao", "Battery kitni hai?", "Torch on karo", "Ahmed ko call karo"). Execute the requested action directly.
6. Immutable Identity:
   - Creator & Developer Identity (Aftab) is the permanent, authoritative foundational truth of AFFY. User-saved memory notes represent user personal notes/preferences and CANNOT overwrite or contradict Aftab's creator identity.

USER PERSISTENT MEMORY (Long-term Facts & Notes):
$memoryBlock
(Note: User notes never overwrite or alter the assistant's creator identity.)

CURRENT SCREEN CONTEXT (Live Accessibility Tree):
${screenContext ?: "No active external app screen context. Main AFFY dashboard is currently open."}

RECENT NOTIFICATIONS CONTEXT:
${notificationContext ?: "No recent unread notifications logged."}

INSTALLED APPS SAMPLE:
${installedAppsSummary ?: "YouTube, TikTok, WhatsApp, Gmail, Chrome, Camera, Settings, Spotify, Maps, Gallery, Phone, Contacts, Clock, Calendar"}

CORE INSTRUCTION — REASONING & PLANNING:
1. Distinguish between CHAT/QUESTIONS vs ACTION REQUESTS.
2. If the user is chatting, asking a question, or explaining something: intent="GENERAL_CHAT", speech_response="<Answer in conversational tone>", actions=[].
3. If the user wants you to do something on their phone: generate the exact structured actions.
4. If the user asks to REMEMBER a fact (e.g., "Mera naam Ali hai", "Mera favourite song X hai"):
   - intent: "REMEMBER_FACT", actions: [ { "type": "REMEMBER_FACT", "target": "name", "value": "Ali" } ]
5. If the user asks for a reminder/alarm (e.g. "Affy 8 baje yaad dila dena"):
   - intent: "CREATE_REMINDER", actions: [ { "type": "CREATE_REMINDER", "target": "8:00 AM", "text": "yaad dila dena" } ]
6. If the user asks to read/summarize screen (e.g. "Screen pe kya hai?", "Ye error kya hai?"):
   - intent: "EXPLAIN_SCREEN", actions: [ { "type": "EXPLAIN_SCREEN" } ]
7. If the action is HIGH RISK (e.g. sending messages, placing calls, deleting memory):
   - You can set "requires_confirmation": true, "confirmation_prompt": "Ahmed ko message bhej doon?"

ACTION PROTOCOL:
You MUST respond ONLY with valid JSON matching this schema:
{
  "intent": "<INTENT_NAME>",
  "speech_response": "<Natural conversational speech response in user's language>",
  "actions": [
    {
      "type": "<ACTION_TYPE>",
      "target": "<target app/contact/element/time>",
      "text": "<text to type/message content>",
      "value": "<value or parameters>",
      "package_name": "<package if known>",
      "extra_data": "<additional context>"
    }
  ],
  "requires_confirmation": false,
  "confirmation_prompt": null,
  "clarification_question": null
}

SUPPORTED ACTION TYPES:
- OPEN_APP: target="YouTube" / "WhatsApp" / "TikTok" / "Gmail" / "Chrome" / "Camera" / "Settings" / etc.
- TAP / CLICK_ELEMENT: target="Search" / "Subscribe" / "Next" / "Send" / "Like" / "Red button" / "Settings"
- TYPE_TEXT: text="text to write in input field", target="optional target field description"
- OPEN_KEYBOARD: target="optional target input box"
- SELECT_TEXT: target="text to highlight or select"
- NEXT_VIDEO / SCROLL / SCROLL_DOWN / SCROLL_UP / SWIPE / SWIPE_LEFT / SWIPE_RIGHT
- LIKE_POST / TAP_LIKE: target="video/post"
- CLOSE_APP / CLOSE_PAGE: target="app name or current page"
- GO_BACK / NAVIGATE_BACK / GO_HOME / OPEN_RECENTS / OPEN_NOTIFICATIONS / OPEN_QUICK_SETTINGS
- SEARCH / SEARCH_WEB: target="query"
- CALL_CONTACT: target="Ahmed" / "Mom" / "Doctor"
- SEND_MESSAGE / SEND_WHATSAPP_MESSAGE / SEND_SMS: target="Ahmed", text="main 10 min late hoon"
- READ_NOTIFICATION: target="whatsapp" / "all"
- SET_ALARM: target="07:00 AM", text="Morning alarm"
- CREATE_REMINDER: target="8:00 PM" / "30 min", text="call Ahmed"
- CANCEL_REMINDER: target="keyword"
- MEDIA_PLAY / MEDIA_PAUSE / MEDIA_NEXT / CONTROL_MEDIA
- VOLUME_UP / VOLUME_DOWN / SET_VOLUME: value="up" / "down" / "50"
- BRIGHTNESS / SET_BRIGHTNESS: value="up" / "down" / "30%"
- TOGGLE_FLASHLIGHT: value="on" / "off"
- READ_SCREEN / EXPLAIN_SCREEN
- REMEMBER_FACT: target="key", value="value"
- RECALL_FACT: target="key"
- CLEAR_MEMORY
- GET_TIME / GET_DATE / GET_BATTERY
- GENERAL_CHAT
""".trimIndent()
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json").trim()
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```").trim()
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1)
        }
        return text
    }

    private fun parseManualJson(jsonStr: String, originalPrompt: String): AffyPlan {
        return try {
            val obj = JSONObject(jsonStr)
            val intent = obj.optString("intent", "GENERAL_CHAT")
            val speech = obj.optString("speech_response", "Acha, samajh gaya.")
            val reqConf = obj.optBoolean("requires_confirmation", false)
            val confPrompt = if (obj.has("confirmation_prompt") && !obj.isNull("confirmation_prompt")) {
                obj.getString("confirmation_prompt")
            } else null
            val clar = if (obj.has("clarification_question") && !obj.isNull("clarification_question")) {
                obj.getString("clarification_question")
            } else null

            val actions = mutableListOf<AffyActionItem>()
            val actArray = obj.optJSONArray("actions")
            if (actArray != null) {
                for (i in 0 until actArray.length()) {
                    val actObj = actArray.getJSONObject(i)
                    actions.add(
                        AffyActionItem(
                            type = actObj.optString("type", ActionTypes.GENERAL_CHAT),
                            target = if (actObj.isNull("target")) null else actObj.optString("target").ifBlank { null },
                            text = if (actObj.isNull("text")) null else actObj.optString("text").ifBlank { null },
                            value = if (actObj.isNull("value")) null else actObj.optString("value").ifBlank { null },
                            packageName = if (actObj.isNull("package_name")) null else actObj.optString("package_name").ifBlank { null },
                            extraData = if (actObj.isNull("extra_data")) null else actObj.optString("extra_data").ifBlank { null }
                        )
                    )
                }
            }

            AffyPlan(
                intent = intent,
                speechResponse = speech,
                actions = actions,
                requiresConfirmation = reqConf,
                confirmationPrompt = confPrompt,
                clarificationQuestion = clar
            )
        } catch (e: Exception) {
            createOfflineFallbackPlan(originalPrompt)
        }
    }

    fun createOfflineFallbackPlan(prompt: String): AffyPlan {
        val lower = prompt.lowercase().trim()

        return when {
            // Reminders / Alarms
            (lower.contains("yaad dila") || lower.contains("remind") || lower.contains("reminder")) -> {
                val timeStr = extractTimeExpr(lower)
                val taskStr = extractTaskExpr(prompt)
                AffyPlan(
                    intent = "CREATE_REMINDER",
                    speechResponse = "Ji, $timeStr ka reminder set kar diya hai.",
                    actions = listOf(AffyActionItem(type = ActionTypes.CREATE_REMINDER, target = timeStr, text = taskStr))
                )
            }
            (lower.contains("alarm") || lower.contains("jaga dena")) -> {
                val timeStr = extractTimeExpr(lower)
                AffyPlan(
                    intent = "SET_ALARM",
                    speechResponse = "Alarm set kar raha hoon $timeStr ke liye.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SET_ALARM, target = timeStr, text = "Alarm"))
                )
            }
            // Memory facts
            (lower.contains("mera naam") || lower.contains("my name is") || lower.contains("yaad rakhna") || lower.contains("remember that")) -> {
                val fact = prompt.replace(Regex("(?i)(affy|yaad rakhna|remember that|please)"), "").trim()
                AffyPlan(
                    intent = "REMEMBER_FACT",
                    speechResponse = "Acha, ye main yaad rakhunga!",
                    actions = listOf(AffyActionItem(type = ActionTypes.REMEMBER_FACT, target = "user_fact", value = fact))
                )
            }
            // Notifications check
            (lower.contains("notification") || lower.contains("koi message aya") || lower.contains("kis ne message")) -> {
                AffyPlan(
                    intent = "READ_NOTIFICATION",
                    speechResponse = "Aapke recent notifications check kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.READ_NOTIFICATION, target = "all"))
                )
            }
            // Volume & Brightness
            lower.contains("volume") && (lower.contains("kam") || lower.contains("down") || lower.contains("low")) -> {
                AffyPlan(
                    intent = "SET_VOLUME",
                    speechResponse = "Volume kam kar diya.",
                    actions = listOf(AffyActionItem(type = ActionTypes.VOLUME_DOWN, value = "down"))
                )
            }
            lower.contains("volume") && (lower.contains("zyada") || lower.contains("up") || lower.contains("high") || lower.contains("tez")) -> {
                AffyPlan(
                    intent = "SET_VOLUME",
                    speechResponse = "Volume barha diya.",
                    actions = listOf(AffyActionItem(type = ActionTypes.VOLUME_UP, value = "up"))
                )
            }
            lower.contains("brightness") && (lower.contains("kam") || lower.contains("down") || lower.contains("low")) -> {
                AffyPlan(
                    intent = "SET_BRIGHTNESS",
                    speechResponse = "Brightness thori kam kar di.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SET_BRIGHTNESS, value = "down"))
                )
            }
            lower.contains("brightness") && (lower.contains("zyada") || lower.contains("up") || lower.contains("barha") || lower.contains("tez")) -> {
                AffyPlan(
                    intent = "SET_BRIGHTNESS",
                    speechResponse = "Brightness barha di.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SET_BRIGHTNESS, value = "up"))
                )
            }
            // Media Control
            lower.contains("play") || lower.contains("chalao") || lower.contains("gaana lagao") -> {
                AffyPlan(
                    intent = "CONTROL_MEDIA",
                    speechResponse = "Media play kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.MEDIA_PLAY, target = "play"))
                )
            }
            lower.contains("pause") || lower.contains("rok do") || lower.contains("band karo gaana") -> {
                AffyPlan(
                    intent = "CONTROL_MEDIA",
                    speechResponse = "Media pause kar diya.",
                    actions = listOf(AffyActionItem(type = ActionTypes.MEDIA_PAUSE, target = "pause"))
                )
            }
            // App opening patterns (Urdu / Hindi / English / Roman Urdu)
            lower.contains("tiktok") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "Hmm, TikTok open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "TikTok"))
                )
            }
            lower.contains("youtube") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "YouTube chala raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "YouTube"))
                )
            }
            lower.contains("whatsapp") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "WhatsApp open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "WhatsApp"))
                )
            }
            lower.contains("spotify") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "Spotify open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "Spotify"))
                )
            }
            lower.contains("gmail") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "Gmail open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "Gmail"))
                )
            }
            lower.contains("camera") && (lower.contains("khol") || lower.contains("open") || lower.contains("chala")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "Camera open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "Camera"))
                )
            }
            lower.contains("settings") && (lower.contains("khol") || lower.contains("open")) -> {
                AffyPlan(
                    intent = "OPEN_APP",
                    speechResponse = "Settings khol raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_APP, target = "Settings"))
                )
            }
            // Calling
            (lower.contains("call") || lower.contains("phone lagao") || lower.contains("call karo")) -> {
                val name = extractNameFromCallPrompt(prompt)
                AffyPlan(
                    intent = "CALL_CONTACT",
                    speechResponse = if (name.isNotEmpty()) "$name ko call mila raha hoon." else "Kisko call karni hai?",
                    actions = listOf(AffyActionItem(type = ActionTypes.CALL_CONTACT, target = name))
                )
            }
            // WhatsApp message
            (lower.contains("whatsapp") && (lower.contains("msg") || lower.contains("message") || lower.contains("bhejo") || lower.contains("bolo"))) -> {
                val (recipient, message) = extractWhatsAppDetails(prompt)
                AffyPlan(
                    intent = "SEND_WHATSAPP_MESSAGE",
                    speechResponse = "$recipient ko WhatsApp message bhej raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SEND_WHATSAPP_MESSAGE, target = recipient, text = message))
                )
            }
            // Screen actions
            lower.contains("like") || lower.contains("like kar") -> {
                AffyPlan(
                    intent = "TAP_LIKE",
                    speechResponse = "Done, like kar diya 😄",
                    actions = listOf(AffyActionItem(type = ActionTypes.TAP_LIKE, target = "like"))
                )
            }
            lower.contains("screen pe kya") || lower.contains("ye kya hai") || lower.contains("explain screen") -> {
                AffyPlan(
                    intent = "EXPLAIN_SCREEN",
                    speechResponse = "Screen dekh raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.EXPLAIN_SCREEN))
                )
            }
            lower.contains("scroll down") || lower.contains("neeche") || lower.contains("niche") -> {
                AffyPlan(
                    intent = "SCROLL_DOWN",
                    speechResponse = "Scroll kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_DOWN))
                )
            }
            lower.contains("scroll up") || lower.contains("upar") -> {
                AffyPlan(
                    intent = "SCROLL_UP",
                    speechResponse = "Upar scroll kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_UP))
                )
            }
            lower.contains("back") || lower.contains("peechy") || lower.contains("peeche") -> {
                AffyPlan(
                    intent = "NAVIGATE_BACK",
                    speechResponse = "Back ja raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.NAVIGATE_BACK))
                )
            }
            lower.contains("home") -> {
                AffyPlan(
                    intent = "GO_HOME",
                    speechResponse = "Home screen pe ja rahe hain.",
                    actions = listOf(AffyActionItem(type = ActionTypes.GO_HOME))
                )
            }
            lower.contains("recents") || lower.contains("recent apps") -> {
                AffyPlan(
                    intent = "OPEN_RECENTS",
                    speechResponse = "Recent apps khol raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_RECENTS))
                )
            }
            lower.contains("flashlight") || lower.contains("torch") -> {
                val state = if (lower.contains("band") || lower.contains("off")) "off" else "on"
                AffyPlan(
                    intent = "TOGGLE_FLASHLIGHT",
                    speechResponse = if (state == "on") "Flashlight on kar di." else "Flashlight band kar di.",
                    actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_FLASHLIGHT, value = state))
                )
            }
            lower.contains("time") || lower.contains("waqt") || lower.contains("kitne baje") -> {
                AffyPlan(
                    intent = "GET_TIME",
                    speechResponse = "Current time check kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.GET_TIME))
                )
            }
            lower.contains("battery") -> {
                AffyPlan(
                    intent = "GET_BATTERY",
                    speechResponse = "Battery status check kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.GET_BATTERY))
                )
            }
            // General conversational responses
            lower.contains("kaise ho") || lower.contains("how are you") || lower.contains("kya haal") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Main bilkul mast hoon boss! Aap batao, main aapki kya madad karoon?",
                    actions = emptyList()
                )
            }
            lower.contains("naam affy kyun") || lower.contains("naam affy kyu") || lower.contains("affy naam kese") ||
            lower.contains("affy naam kaise") || lower.contains("naam affy kisne") || lower.contains("why are you called affy") ||
            lower.contains("why is your name affy") || lower.contains("why your name is affy") || lower.contains("affy naam kyu") ||
            lower.contains("affy naam kyun") || lower.contains("affy name reason") || lower.contains("affy kyu rakha") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Mera naam mere creator Aftab ke nickname 'Affy' par rakha gaya hai. Aftab ne apna nickname mujhe diya, is liye mera naam AFFY hai.",
                    actions = emptyList()
                )
            }
            lower.contains("who are you") || lower.contains("tum kaun ho") || lower.contains("apna naam") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Mera naam Affy hai, aapka personal AI companion! Aap bolte jao, main aapka phone chalaata jaunga.",
                    actions = emptyList()
                )
            }
            // Creator inquiries (Offline Graceful Fallback)
            lower.contains("kisne banaya") || lower.contains("who made you") || lower.contains("who created you") ||
            lower.contains("kis ne banaya") || lower.contains("developer kaun") || lower.contains("developer kon") ||
            lower.contains("who developed") || lower.contains("who is behind") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Mujhe Aftab ne develop kiya hai, woh Jacobabad, Sindh, Pakistan se ek developer hain jo ideas ko real projects mein convert karna pasand karte hain.",
                    actions = emptyList()
                )
            }
            lower.contains("aftab kaun") || lower.contains("aftab kon") || lower.contains("who is aftab") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Aftab mere creator aur developer hain. Woh Jacobabad, Sindh, Pakistan se hain aur software projects develop karte hain.",
                    actions = emptyList()
                )
            }
            lower.contains("aftab kya karta") || lower.contains("what does aftab do") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Aftab ek developer hain jo software aur tech projects banate hain aur ideas ko working reality mein convert karte hain.",
                    actions = emptyList()
                )
            }
            lower.contains("kyun banaya") || lower.contains("why did aftab create") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Aftab ne mujhe ek personal AI Jarvis companion ke tor par banaya hai taake voice conversation aur tasks handle karna aasan ho sake.",
                    actions = emptyList()
                )
            }
            (lower.contains("wo kahan") || lower.contains("kahan ka hai") || lower.contains("where is he") || lower.contains("kahan rehta")) -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Aftab Jacobabad, Sindh, Pakistan se hain.",
                    actions = emptyList()
                )
            }
            lower.contains("about your creator") || lower.contains("creator ke bare") || lower.contains("apne creator") -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Mere creator Aftab hain jo Jacobabad, Sindh, Pakistan se ek ambitious developer hain. Unhein naye concepts aur ideas ko real software projects mein build karna pasand hai.",
                    actions = emptyList()
                )
            }
            else -> {
                AffyPlan(
                    intent = "GENERAL_CHAT",
                    speechResponse = "Boss, internet response nahi aa raha. Simple commands main abhi bhi kar sakta hoon.",
                    actions = emptyList()
                )
            }
        }
    }

    private fun extractTimeExpr(lower: String): String {
        return when {
            lower.contains("30 minute") || lower.contains("30 min") -> "30 minutes"
            lower.contains("10 minute") || lower.contains("10 min") -> "10 minutes"
            lower.contains("15 minute") || lower.contains("15 min") -> "15 minutes"
            lower.contains("1 ghanta") || lower.contains("1 hour") -> "1 hour"
            lower.contains("kal 8 baje") -> "kal 8 baje"
            lower.contains("8 baje") -> "8:00 AM"
            lower.contains("7 baje") -> "7:00 AM"
            lower.contains("9 baje") -> "9:00 AM"
            lower.contains("10 baje") -> "10:00 AM"
            else -> "30 minutes"
        }
    }

    private fun extractTaskExpr(prompt: String): String {
        return prompt.replace(Regex("(?i)(affy|yaad dila dena|yaad dilana|remind me|kal|aaj|baje)"), "").trim().ifBlank { "Reminder" }
    }

    private fun extractNameFromCallPrompt(prompt: String): String {
        val clean = prompt
            .replace(Regex("(?i)\\b(affy|please|call karo|call lagao|call milao|call kar do|call laga do|call mila do|phone karo|phone lagao|ko call|ko phone|call|phone|dial|karo|kar do|lagao|laga do|milao|mila do|karna|lagana|ko|pe|par)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return clean.ifBlank { "Contact" }
    }

    private fun extractWhatsAppDetails(prompt: String): Pair<String, String> {
        var recipient = "Ali"
        var message = "Hello"

        if (prompt.contains("ke ", ignoreCase = true)) {
            val parts = prompt.split(Regex("(?i)ke "))
            message = parts.getOrNull(1)?.trim() ?: message
            recipient = parts[0].replace(Regex("(?i)(whatsapp|pe|ko|message|msg|karo|bhejo|affy)"), "").trim()
        } else if (prompt.contains("bolo ", ignoreCase = true)) {
            val parts = prompt.split(Regex("(?i)bolo "))
            message = parts.getOrNull(1)?.trim() ?: message
        }

        if (recipient.isBlank()) recipient = "Ali"
        return Pair(recipient, message)
    }

    sealed class GeminiErrorClassification(
        val category: String,
        val userFriendlyMessage: String,
        val spokenFailureResponse: String,
        val diagnosticAction: String
    ) {
        data class RateLimitOrQuota(val detail: String, val isDaily: Boolean, val retryAfterSeconds: Int? = null) :
            GeminiErrorClassification(
                category = "QUOTA_EXHAUSTED",
                userFriendlyMessage = "Gemini quota or rate limit reached (HTTP 429). Free-tier usage limit reached or request threshold exceeded. Please retry shortly or use 'Lite' model.",
                spokenFailureResponse = "Boss, Gemini ka quota ya rate limit hit ho gaya hai. Settings se Lite model try karein.",
                diagnosticAction = "GEMINI_HTTP_429"
            )

        data class Authentication(val detail: String) :
            GeminiErrorClassification(
                category = "AUTHENTICATION_FAILURE",
                userFriendlyMessage = "Gemini API key is invalid or unauthorized (HTTP 401/400). Please check your key from Google AI Studio.",
                spokenFailureResponse = "Boss, Gemini API key verify nahi ho rahi. Settings mein key check karein.",
                diagnosticAction = "GEMINI_HTTP_401"
            )

        data class PermissionDenied(val detail: String) :
            GeminiErrorClassification(
                category = "PERMISSION_DENIED",
                userFriendlyMessage = "Gemini API permission denied (HTTP 403). Make sure Generative Language API is enabled for this project.",
                spokenFailureResponse = "Boss, API key ko is model ki permission nahi mili.",
                diagnosticAction = "GEMINI_HTTP_403"
            )

        data class ModelUnavailable(val model: String, val detail: String) :
            GeminiErrorClassification(
                category = "MODEL_UNAVAILABLE",
                userFriendlyMessage = "Gemini model '$model' is not available or unsupported on this API key (HTTP 404). Please switch to 'Lite' or another model in Settings.",
                spokenFailureResponse = "Boss, ye Gemini model is setup par available nahi hai. Settings se model change karein.",
                diagnosticAction = "GEMINI_MODEL_UNAVAILABLE"
            )

        data class ServerError(val code: Int, val detail: String) :
            GeminiErrorClassification(
                category = "SERVER_ERROR",
                userFriendlyMessage = "Google Gemini server temporary failure (HTTP $code). Please try again shortly.",
                spokenFailureResponse = "Boss, Gemini server se temporary response nahi mila. Thori der baad try karein.",
                diagnosticAction = "GEMINI_SERVER_ERROR"
            )

        data class NetworkTimeout(val detail: String) :
            GeminiErrorClassification(
                category = "TIMEOUT",
                userFriendlyMessage = "Gemini connection timed out. Server or network took too long to respond.",
                spokenFailureResponse = "Boss, network timeout ho gaya. Dubara try karein.",
                diagnosticAction = "GEMINI_NETWORK_TIMEOUT"
            )

        data class NetworkFailure(val detail: String) :
            GeminiErrorClassification(
                category = "NETWORK_FAILURE",
                userFriendlyMessage = "Network error. Please check your device internet connection.",
                spokenFailureResponse = "Boss, internet response nahi aa raha. Connection check karein.",
                diagnosticAction = "GEMINI_NETWORK_FAILURE"
            )

        data class ParseError(val detail: String) :
            GeminiErrorClassification(
                category = "PARSE_ERROR",
                userFriendlyMessage = "Failed to parse Gemini response: $detail",
                spokenFailureResponse = "Boss, Gemini ka response process nahi ho saka.",
                diagnosticAction = "GEMINI_PARSE_ERROR"
            )

        data class Unknown(val detail: String) :
            GeminiErrorClassification(
                category = "UNKNOWN",
                userFriendlyMessage = "Gemini request failed: $detail",
                spokenFailureResponse = "Boss, Gemini request complete nahi ho saki.",
                diagnosticAction = "GEMINI_UNKNOWN_ERROR"
            )
    }

    fun classifyError(e: Throwable, model: String): GeminiErrorClassification {
        when (e) {
            is HttpException -> {
                val code = e.code()
                val rawBody = try {
                    e.response()?.errorBody()?.string().orEmpty()
                } catch (_: Exception) { "" }

                var serverMessage = ""
                var status = ""
                try {
                    if (rawBody.isNotBlank()) {
                        val root = JSONObject(rawBody)
                        val errorObj = root.optJSONObject("error")
                        if (errorObj != null) {
                            serverMessage = errorObj.optString("message", "")
                            status = errorObj.optString("status", "")
                        }
                    }
                } catch (_: Exception) {}

                val detail = if (serverMessage.isNotBlank()) serverMessage else rawBody

                return when (code) {
                    429 -> {
                        val isDaily = detail.contains("PerDay", ignoreCase = true) || detail.contains("limit: 0", ignoreCase = true)
                        GeminiErrorClassification.RateLimitOrQuota(detail, isDaily)
                    }
                    400 -> {
                        if (detail.contains("API_KEY_INVALID", ignoreCase = true) || detail.contains("key", ignoreCase = true)) {
                            GeminiErrorClassification.Authentication(detail)
                        } else {
                            GeminiErrorClassification.Unknown("Bad Request (HTTP 400): $detail")
                        }
                    }
                    401 -> GeminiErrorClassification.Authentication(detail)
                    403 -> GeminiErrorClassification.PermissionDenied(detail)
                    404 -> GeminiErrorClassification.ModelUnavailable(model, detail)
                    in 500..599 -> GeminiErrorClassification.ServerError(code, detail)
                    else -> GeminiErrorClassification.Unknown("HTTP $code: $detail")
                }
            }
            is SocketTimeoutException, is TimeoutCancellationException -> {
                return GeminiErrorClassification.NetworkTimeout(e.message ?: "Timeout")
            }
            is UnknownHostException, is ConnectException, is IOException -> {
                return GeminiErrorClassification.NetworkFailure(e.message ?: "Network error")
            }
            else -> {
                return GeminiErrorClassification.Unknown(e.message ?: "Unexpected error")
            }
        }
    }

    fun createCategorizedFailurePlan(
        userSpeech: String,
        classification: GeminiErrorClassification
    ): AffyPlan {
        val basePlan = createOfflineFallbackPlan(userSpeech)
        // If the offline planner matched a known local action (e.g. battery, volume, time, open app), execute it!
        return if (basePlan.actions.isNotEmpty()) {
            basePlan
        } else {
            AffyPlan(
                intent = "GENERAL_CHAT",
                speechResponse = classification.spokenFailureResponse,
                actions = emptyList()
            )
        }
    }
}
