package com.example.voice

import android.content.Context
import android.util.Log
import com.example.agent.AffyDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TtsEngineState {
    INITIALIZING,
    READY,
    ERROR,
    SPEAKING
}

/**
 * Pure Gemini Voice Engine for AFFY.
 * Strictly uses Gemini Live / Gemini TTS audio synthesis.
 * All Android native TextToSpeech fallbacks have been removed to ensure zero robotic TTS interruptions.
 */
class AffyTextToSpeech(
    private val context: Context,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AffyTTS"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val geminiTtsService = GeminiTtsService(context) { isSpeaking ->
        if (isSpeaking) {
            _engineState.value = TtsEngineState.SPEAKING
        } else {
            _engineState.value = TtsEngineState.READY
        }
        onSpeakingStateChanged(isSpeaking)
    }

    private var currentApiKey: String = ""
    private var selectedVoiceName: String = GeminiTtsService.VOICE_PUCK_YOUNG_MALE

    private val _engineState = MutableStateFlow(TtsEngineState.READY)
    val engineState: StateFlow<TtsEngineState> = _engineState.asStateFlow()

    private var currentSpeed: Float = 1.05f
    private var currentPitch: Float = 0.95f
    private var preferredLanguage: String = "auto"

    @Volatile
    private var activeSessionId: Long = 0L

    init {
        val buildKey = com.example.BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotBlank() && !buildKey.contains("your_gemini_api_key", ignoreCase = true)) {
            currentApiKey = buildKey
            AffyDiagnostics.updateVoiceProvider("GEMINI_TTS (AFFY Male Voice)")
        }
    }

    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isNotBlank() && !trimmed.contains("your_gemini_api_key", ignoreCase = true)) {
            currentApiKey = trimmed
        } else {
            val buildKey = com.example.BuildConfig.GEMINI_API_KEY.trim()
            if (buildKey.isNotBlank() && !buildKey.contains("your_gemini_api_key", ignoreCase = true)) {
                currentApiKey = buildKey
            }
        }
        val voiceLabel = if (selectedVoiceName.equals(GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE, ignoreCase = true)) "AFFY Female Voice" else "AFFY Male Voice (17yo)"
        AffyDiagnostics.updateVoiceProvider("GEMINI_TTS ($voiceLabel)")
    }

    fun setVoiceName(voice: String) {
        val normalized = if (voice.equals(GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE, ignoreCase = true) || voice.equals("female", ignoreCase = true)) {
            GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE
        } else {
            GeminiTtsService.VOICE_PUCK_YOUNG_MALE
        }
        selectedVoiceName = normalized
        val voiceLabel = if (selectedVoiceName == GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE) "AFFY Female Voice" else "AFFY Male Voice (17yo)"
        AffyDiagnostics.updateVoiceProvider("GEMINI_TTS ($voiceLabel)")
    }

    fun updateConfig(speed: Float, pitch: Float, language: String) {
        currentSpeed = speed
        currentPitch = pitch
        preferredLanguage = language
    }

    fun speak(text: String, emotionTone: String? = null, onDone: (() -> Unit)? = null) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        val sessionId = ++activeSessionId
        stopCurrentPlaybackOnly()

        val voiceLabel = if (selectedVoiceName == GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE) "AFFY Female Voice" else "AFFY Male Voice (17yo)"
        AffyDiagnostics.markTtsStart(getCurrentVoiceProvider())
        AffyDiagnostics.logVoiceStage(
            stage = "PRIMARY_REQUEST_START",
            engine = "GEMINI_TTS ($voiceLabel)",
            details = if (!emotionTone.isNullOrBlank()) "[$emotionTone] $cleanText" else cleanText
        )

        scope.launch {
            val startTime = System.currentTimeMillis()
            val effectiveKey = currentApiKey.ifBlank { com.example.BuildConfig.GEMINI_API_KEY.trim() }

            val success = geminiTtsService.synthesizeAndPlay(
                text = cleanText,
                apiKey = effectiveKey,
                voiceName = selectedVoiceName,
                emotionTone = emotionTone,
                isActiveSession = { sessionId == activeSessionId },
                onDone = {
                    if (sessionId == activeSessionId) {
                        AffyDiagnostics.logVoiceStage(
                            stage = "AUDIO_COMPLETED",
                            engine = "GEMINI_TTS ($voiceLabel)"
                        )
                        _engineState.value = TtsEngineState.READY
                        onSpeakingStateChanged(false)
                        onDone?.invoke()
                    }
                }
            )

            if (sessionId != activeSessionId) return@launch

            if (success) {
                val latency = System.currentTimeMillis() - startTime
                AffyDiagnostics.logVoiceStage(
                    stage = "PRIMARY_REQUEST_RESULT",
                    engine = "GEMINI_TTS ($voiceLabel)",
                    details = "Synthesized in ${latency}ms",
                    isSuccess = true,
                    latencyMs = latency
                )
            } else {
                val latency = System.currentTimeMillis() - startTime
                Log.w(TAG, "Gemini $voiceLabel TTS synthesis unavailable or quota limited in ${latency}ms. Keeping audio silent without native TTS.")
                AffyDiagnostics.logVoiceStage(
                    stage = "PRIMARY_REQUEST_RESULT",
                    engine = "GEMINI_TTS ($voiceLabel)",
                    details = "Synthesis unavailable - Silenced to preserve Live Audio voice fidelity",
                    isSuccess = false,
                    failureReason = "SYNTHESIS_FAILED_OR_TIMEOUT",
                    latencyMs = latency
                )
                _engineState.value = TtsEngineState.READY
                onSpeakingStateChanged(false)
                onDone?.invoke()
            }
        }
    }

    fun testSpeech(phrase: String = "Hello boss! Main AFFY hoon, aapka personal AI assistant. Bataiye, kya hukam hai?", onComplete: (Boolean) -> Unit) {
        speak(phrase) {
            onComplete(true)
        }
    }

    fun getCurrentVoiceProvider(): String {
        val voiceLabel = if (selectedVoiceName == GeminiTtsService.VOICE_LEDA_YOUNG_FEMALE) "Female Voice" else "Male Voice (17yo)"
        return "Gemini AI Voice ($voiceLabel)"
    }

    private fun stopCurrentPlaybackOnly() {
        geminiTtsService.stop()
        _engineState.value = TtsEngineState.READY
    }

    fun stop() {
        activeSessionId++
        stopCurrentPlaybackOnly()
        onSpeakingStateChanged(false)
        AffyDiagnostics.markTtsStop()
        AffyDiagnostics.logVoiceStage(
            stage = "AUDIO_STOPPED",
            engine = "ALL",
            details = "Voice playback stopped"
        )
    }

    fun shutdown() {
        activeSessionId++
        geminiTtsService.stop()
        _engineState.value = TtsEngineState.INITIALIZING
    }
}
