package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiGenerateRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "role") val role: String? = null,
    @Json(name = "parts") val parts: List<GeminiPart>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "temperature") val temperature: Float? = 0.4f,
    @Json(name = "topP") val topP: Float? = 0.95f,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null,
    @Json(name = "responseMimeType") val responseMimeType: String? = null,
    @Json(name = "responseModalities") val responseModalities: List<String>? = null,
    @Json(name = "speechConfig") val speechConfig: GeminiSpeechConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiSpeechConfig(
    @Json(name = "voiceConfig") val voiceConfig: GeminiVoiceConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiVoiceConfig(
    @Json(name = "prebuiltVoiceConfig") val prebuiltVoiceConfig: GeminiPrebuiltVoiceConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiPrebuiltVoiceConfig(
    @Json(name = "voiceName") val voiceName: String = "Puck"
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>? = null,
    @Json(name = "promptFeedback") val promptFeedback: GeminiPromptFeedback? = null,
    @Json(name = "error") val error: GeminiError? = null
) {
    /**
     * Safely extracts text from candidates[0].content.parts[].
     * Aggregates all non-null text parts or extracts the first non-empty text part.
     * Returns null if no valid text parts are present.
     */
    fun extractFirstText(): String? {
        val parts = candidates?.firstOrNull()?.content?.parts ?: return null
        val combinedText = parts.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }.joinToString("\n").trim()
        return combinedText.ifBlank { null }
    }

    /**
     * Checks if response was blocked or finished due to safety/recitation/other reasons.
     */
    fun getBlockOrFinishReason(): String? {
        val blockReason = promptFeedback?.blockReason
        if (!blockReason.isNullOrBlank()) {
            return "Prompt blocked ($blockReason)"
        }
        val firstCandidate = candidates?.firstOrNull()
        val finishReason = firstCandidate?.finishReason
        if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") {
            return "Candidate finished with reason: $finishReason"
        }
        return null
    }

    /**
     * Accurately categorizes and diagnoses why a text response could not be extracted.
     */
    fun getDetailedDiagnosis(): String {
        if (error != null) {
            val codeStr = if (error.code != null) " (code ${error.code})" else ""
            return error.message?.takeIf { it.isNotBlank() } ?: "API Error response$codeStr"
        }
        val blockOrFinish = getBlockOrFinishReason()
        if (blockOrFinish != null) {
            return blockOrFinish
        }
        if (candidates == null) {
            return "Missing 'candidates' field in response"
        }
        if (candidates.isEmpty()) {
            return "Empty 'candidates' array returned by model"
        }
        val firstCandidate = candidates.first()
        val finish = firstCandidate.finishReason
        if (finish == "MAX_TOKENS") {
            val content = firstCandidate.content
            if (content == null || content.parts == null || content.parts.isEmpty() || !content.parts.any { !it.text.isNullOrBlank() }) {
                return "Token limit reached before content generated (finishReason: MAX_TOKENS)"
            }
        }
        if (firstCandidate.content == null) {
            val finishStr = finish ?: "unknown"
            return "Missing 'content' in candidate (finishReason: $finishStr)"
        }
        if (firstCandidate.content.parts == null) {
            val finishStr = finish ?: "unknown"
            return "Missing 'parts' in candidate content (finishReason: $finishStr)"
        }
        if (firstCandidate.content.parts.isEmpty()) {
            val finishStr = finish ?: "unknown"
            return "Empty 'parts' in candidate content (finishReason: $finishStr)"
        }
        val hasText = firstCandidate.content.parts.any { !it.text.isNullOrBlank() }
        if (!hasText) {
            return "No text part found in candidate parts"
        }
        return "Non-text response received"
    }
}

@JsonClass(generateAdapter = true)
data class GeminiPromptFeedback(
    @Json(name = "blockReason") val blockReason: String? = null,
    @Json(name = "safetyRatings") val safetyRatings: List<GeminiSafetyRating>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiSafetyRating(
    @Json(name = "category") val category: String? = null,
    @Json(name = "probability") val probability: String? = null,
    @Json(name = "blocked") val blocked: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent? = null,
    @Json(name = "finishReason") val finishReason: String? = null,
    @Json(name = "safetyRatings") val safetyRatings: List<GeminiSafetyRating>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiError(
    @Json(name = "code") val code: Int? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiListModelsResponse(
    @Json(name = "models") val models: List<GeminiModelInfo>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiModelInfo(
    @Json(name = "name") val name: String = "",
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "supportedGenerationMethods") val supportedGenerationMethods: List<String>? = null
)
