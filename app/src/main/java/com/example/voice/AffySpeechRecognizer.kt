package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.AffySttNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AffySpeechRecognizer(
    private val context: Context,
    private val onResult: (spokenText: String) -> Unit,
    private val onPartialResult: (partialText: String) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onListeningStateChanged: (isListening: Boolean) -> Unit
) {
    var onSpeechStarted: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isContinuousWakeWordMode = false
    private var isFollowUpMode = false
    private var lastDeliveredText = ""
    private var lastDeliveredTime = 0L

    val isInFollowUpMode: Boolean get() = isFollowUpMode

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    init {
        runOnMain {
            initRecognizer()
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun initRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        speechRecognizer = null
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            } catch (e: Exception) {
                Log.w("AffySTT", "Error creating SpeechRecognizer: ${e.message}")
            }
        } else {
            Log.w("AffySTT", "Speech recognition is not available on this device")
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                if (!isContinuousWakeWordMode) {
                    onListeningStateChanged(true)
                }
                Log.d("AffySTT", "SpeechRecognizer ready (WakeWordMode: $isContinuousWakeWordMode)")
            }

            override fun onBeginningOfSpeech() {
                isListening = true
                if (!isContinuousWakeWordMode) {
                    onListeningStateChanged(true)
                    onSpeechStarted?.invoke()
                }
                Log.d("AffySTT", "Speech input begun (WakeWordMode: $isContinuousWakeWordMode)")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Only broadcast amplitude during active speech interaction, not silent wake-word standby
                if (!isContinuousWakeWordMode) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                    onAmplitudeChanged(normalized)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (!isContinuousWakeWordMode) {
                    onAmplitudeChanged(0.05f)
                }
            }

            override fun onError(error: Int) {
                val wasFollowUp = isFollowUpMode
                val wasContinuousWake = isContinuousWakeWordMode
                isListening = false
                if (!wasContinuousWake) {
                    onAmplitudeChanged(0.05f)
                }

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error in recognition"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    else -> "Recognition error: $error"
                }
                Log.d("AffySTT", "Recognition state: $errorMsg (error code: $error, wakeMode: $wasContinuousWake)")

                if (wasFollowUp) {
                    // In continuous hands-free session, re-arm listening seamlessly
                    runOnMain {
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                            initRecognizer()
                        }
                        startListening(isFollowUp = true)
                    }
                } else if (wasContinuousWake) {
                    runOnMain {
                        mainHandler.postDelayed({
                            if (isContinuousWakeWordMode) {
                                startWakeWordListening()
                            }
                        }, 1000L)
                    }
                } else {
                    isFollowUpMode = false
                    isContinuousWakeWordMode = false
                    onListeningStateChanged(false)
                }
            }

            override fun onResults(results: Bundle?) {
                val wasFollowUp = isFollowUpMode
                val wasContinuousWake = isContinuousWakeWordMode
                isListening = false
                isFollowUpMode = false
                isContinuousWakeWordMode = false
                if (!wasContinuousWake) {
                    onListeningStateChanged(false)
                    onAmplitudeChanged(0.05f)
                }

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val rawSpoken = matches?.firstOrNull()?.trim()

                if (!rawSpoken.isNullOrBlank()) {
                    AffyDiagnostics.markSttResult(rawSpoken)
                    val normalized = AffySttNormalizer.normalize(rawSpoken)
                    _lastRecognizedText.value = normalized
                    Log.i("AffySTT", "[4] STT Result: '$rawSpoken' -> Normalized: '$normalized' (WakeMode: $wasContinuousWake)")

                    val now = System.currentTimeMillis()
                    if (normalized == lastDeliveredText && now - lastDeliveredTime < 1200L) {
                        Log.d("AffySTT", "Debouncing duplicate STT result: $normalized")
                        if (wasFollowUp) {
                            runOnMain { startListening(isFollowUp = true) }
                        }
                        return
                    }
                    lastDeliveredText = normalized
                    lastDeliveredTime = now

                    if (wasContinuousWake) {
                        if (AffySttNormalizer.containsWakeWord(rawSpoken)) {
                            val commandAfterWakeWord = AffySttNormalizer.stripWakeWord(normalized)
                            if (commandAfterWakeWord.isNotBlank()) {
                                onResult(commandAfterWakeWord)
                            } else {
                                onResult("AFFY")
                            }
                        } else {
                            runOnMain {
                                mainHandler.postDelayed({
                                    if (isContinuousWakeWordMode) {
                                        startWakeWordListening()
                                    }
                                }, 1000L)
                            }
                        }
                    } else {
                        val cleaned = AffySttNormalizer.stripWakeWord(normalized).ifBlank { normalized }
                        onResult(cleaned)
                    }
                } else if (wasContinuousWake) {
                    runOnMain {
                        mainHandler.postDelayed({
                            if (isContinuousWakeWordMode) {
                                startWakeWordListening()
                            }
                        }, 1000L)
                    }
                } else if (wasFollowUp) {
                    runOnMain { startListening(isFollowUp = true) }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (isContinuousWakeWordMode) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    onSpeechStarted?.invoke()
                    onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun createSpeechIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Low-latency silence detection parameters for Live conversation responsiveness
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
            // Multilingual speech recognition support (Urdu, Hindi, Punjabi, Sindhi, Marathi, English)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(
                "ur-PK", "hi-IN", "pa-IN", "sd-PK", "mr-IN", "en-US", "en-IN"
            ))
        }
    }

    fun startListening(isFollowUp: Boolean = false) {
        runOnMain {
            AffyDiagnostics.markSttStart()
            stopListeningInternal()
            isFollowUpMode = isFollowUp
            isContinuousWakeWordMode = false

            if (speechRecognizer == null) {
                initRecognizer()
            }

            try {
                speechRecognizer?.startListening(createSpeechIntent())
                isListening = true
                onListeningStateChanged(true)
            } catch (e: Exception) {
                Log.e("AffySTT", "Error starting listening: ${e.message}")
                try {
                    initRecognizer()
                    speechRecognizer?.startListening(createSpeechIntent())
                    isListening = true
                    onListeningStateChanged(true)
                } catch (e2: Exception) {
                    Log.e("AffySTT", "Fatal error starting listening: ${e2.message}")
                    isListening = false
                    isFollowUpMode = false
                    onListeningStateChanged(false)
                }
            }
        }
    }

    fun startWakeWordListening() {
        runOnMain {
            stopListeningInternal()
            isContinuousWakeWordMode = true
            isFollowUpMode = false

            if (speechRecognizer == null) {
                initRecognizer()
            }

            try {
                speechRecognizer?.startListening(createSpeechIntent())
                isListening = true
            } catch (e: Exception) {
                Log.e("AffySTT", "Error starting wake-word listening: ${e.message}")
            }
        }
    }

    fun stopListening() {
        runOnMain {
            isFollowUpMode = false
            isContinuousWakeWordMode = false
            stopListeningInternal()
        }
    }

    private fun stopListeningInternal() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
            }
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e("AffySTT", "Error stopping listening: ${e.message}")
        }
        isListening = false
        onListeningStateChanged(false)
        onAmplitudeChanged(0.05f)
    }

    fun destroy() {
        runOnMain {
            isFollowUpMode = false
            isContinuousWakeWordMode = false
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e("AffySTT", "Error destroying speech recognizer: ${e.message}")
            }
            speechRecognizer = null
        }
    }
}
