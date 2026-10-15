package com.example.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val timeFormatted: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()),
    val category: String, // VOICE, WHATSAPP_INCOMING, WHATSAPP_OUTGOING, COMMAND, NOTIFICATION, GMAIL
    val input: String = "",
    val normalizedCommand: String = "",
    val selectedIntent: String = "",
    val selectedPackage: String = "",
    val selectedWhatsAppInstance: String = "",
    val action: String = "",
    val verificationResult: String = "", // VERIFIED, UNVERIFIED, FAILED
    val failureReason: String? = null,
    val latencyMs: Long = 0L,
    val voiceProvider: String? = null
) {
    val isSuccess: Boolean get() = verificationResult == "VERIFIED"
    val stage: String get() = action
    val details: String get() = input
}

data class LatencyBreakdown(
    val sttMs: Long = 0L,
    val routerMs: Long = 0L,
    val geminiMs: Long = 0L,
    val actionMs: Long = 0L,
    val ttsMs: Long = 0L,
    val totalMs: Long = 0L
) {
    fun toFormattedString(): String =
        "STT: $sttMs ms\nRouter: $routerMs ms\nGemini: $geminiMs ms\nAction: $actionMs ms\nTTS: $ttsMs ms\nTotal: $totalMs ms"
}

object AffyDiagnostics {
    private const val TAG = "AffyDiagnostics"
    private const val MAX_LOGS = 100

    private val _logs = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticEntry>> = _logs.asStateFlow()

    private val _activeVoiceProvider = MutableStateFlow("INITIALIZING")
    val activeVoiceProvider: StateFlow<String> = _activeVoiceProvider.asStateFlow()
    val currentVoiceProvider: StateFlow<String> = _activeVoiceProvider.asStateFlow()

    private val _lastWhatsAppStatus = MutableStateFlow<String>("IDLE")
    val lastWhatsAppStatus: StateFlow<String> = _lastWhatsAppStatus.asStateFlow()

    private val _lastLatencyBreakdown = MutableStateFlow(LatencyBreakdown())
    val lastLatencyBreakdown: StateFlow<LatencyBreakdown> = _lastLatencyBreakdown.asStateFlow()

    // Latency tracking markers
    @Volatile private var timeSttStart = 0L
    @Volatile private var timeSttResult = 0L
    @Volatile private var timeRouterStart = 0L
    @Volatile private var timeRouterResult = 0L
    @Volatile private var timeGeminiRequestStart = 0L
    @Volatile private var timeGeminiResponseReceived = 0L
    @Volatile private var timeActionStart = 0L
    @Volatile private var timeActionEnd = 0L
    @Volatile private var timeTtsStart = 0L
    @Volatile private var timeTtsEnd = 0L

    fun updateVoiceProvider(provider: String) {
        _activeVoiceProvider.value = provider
        Log.i(TAG, "Voice Provider: $provider")
    }

    fun markSttStart() {
        timeSttStart = System.currentTimeMillis()
        timeSttResult = 0L
        timeRouterStart = 0L
        timeRouterResult = 0L
        timeGeminiRequestStart = 0L
        timeGeminiResponseReceived = 0L
        timeActionStart = 0L
        timeActionEnd = 0L
        timeTtsStart = 0L
        timeTtsEnd = 0L
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = "STT_START",
                input = "Listening started",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markSttResult(recognizedText: String) {
        val now = System.currentTimeMillis()
        timeSttResult = now
        val latency = if (timeSttStart > 0) now - timeSttStart else 0L
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = "STT_RESULT",
                input = sanitize(recognizedText),
                latencyMs = latency,
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markRouterStart() {
        timeRouterStart = System.currentTimeMillis()
    }

    fun markRouterResult(category: String, isFastPath: Boolean) {
        val now = System.currentTimeMillis()
        timeRouterResult = now
        val latency = if (timeRouterStart > 0) now - timeRouterStart else 0L
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "ROUTER_RESULT",
                selectedIntent = category,
                input = if (isFastPath) "LOCAL_FAST_PATH" else "GEMINI_REASONING_PATH",
                latencyMs = latency,
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markIntentDetected(intent: String, isFastPath: Boolean) {
        val now = System.currentTimeMillis()
        val latencyFromStt = if (timeSttResult > 0) now - timeSttResult else 0L
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "INTENT_DETECTED",
                selectedIntent = intent,
                input = if (isFastPath) "LOCAL_FAST_PATH" else "GEMINI_REASONING_PATH",
                latencyMs = latencyFromStt,
                verificationResult = "VERIFIED"
            )
        )
    }

    // Structured Pipeline Logging (Mandatory Diagnostic Format)
    fun logPipelineIntent(targetApp: String, requestedAction: String, query: String) {
        Log.i("AffyPipeline", "INTENT: targetApp=$targetApp | requestedAction=$requestedAction | query=$query")
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "PIPELINE_INTENT",
                selectedPackage = targetApp,
                selectedIntent = requestedAction,
                input = "query=$query",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun logPipelinePlan(step: String) {
        Log.i("AffyPipeline", "PLAN: step=$step")
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "PIPELINE_PLAN",
                input = step,
                verificationResult = "VERIFIED"
            )
        )
    }

    fun logPipelineExecution(action: String, target: String, targetPackage: String, actionResult: String) {
        Log.i("AffyPipeline", "EXECUTION: action=$action | target=$target | targetPackage=$targetPackage | actionResult=$actionResult")
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "PIPELINE_EXECUTION",
                selectedIntent = action,
                selectedPackage = targetPackage,
                input = "target=$target | result=$actionResult",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun logPipelineVerification(expected: String, observed: String, verified: Boolean) {
        Log.i("AffyPipeline", "VERIFICATION: expected=$expected | observed=$observed | verified=$verified")
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "PIPELINE_VERIFICATION",
                input = "expected=$expected | observed=$observed",
                verificationResult = if (verified) "VERIFIED" else "FAILED"
            )
        )
    }

    fun logPipelineFinal(status: String, summary: String, fallbackReason: String? = null) {
        val fallbackText = if (fallbackReason != null) " | Fallback Reason: $fallbackReason" else ""
        Log.i("AffyPipeline", "FINAL: $status | $summary$fallbackText")
        log(
            DiagnosticEntry(
                category = "COMMAND",
                action = "PIPELINE_FINAL",
                input = "$status | $summary$fallbackText",
                verificationResult = if (status == "SUCCESS") "VERIFIED" else "FAILED",
                failureReason = fallbackReason
            )
        )
    }

    fun maskApiKey(key: String): String {
        val clean = key.trim()
        if (clean.isBlank()) return ""
        return if (clean.length >= 8) {
            "${clean.take(4)}...${clean.takeLast(4)}"
        } else {
            "****"
        }
    }

    fun markGeminiKeyLoaded(maskedKey: String) {
        log(
            DiagnosticEntry(
                category = "GEMINI",
                action = "GEMINI_KEY_LOADED",
                input = "Key: $maskedKey",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markGeminiModelSelected(model: String) {
        log(
            DiagnosticEntry(
                category = "GEMINI",
                action = "GEMINI_MODEL_SELECTED",
                input = "Model: $model",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markGeminiStage(
        stage: String, // GEMINI_REQUEST_STARTED, GEMINI_REQUEST_SUCCESS, GEMINI_HTTP_401, GEMINI_HTTP_403, GEMINI_HTTP_429, GEMINI_MODEL_UNAVAILABLE, GEMINI_NETWORK_TIMEOUT, GEMINI_SERVER_ERROR, GEMINI_PARSE_ERROR, GEMINI_FALLBACK_USED
        model: String,
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null,
        latencyMs: Long = 0L
    ) {
        log(
            DiagnosticEntry(
                category = "GEMINI",
                action = stage,
                input = "Model: $model | $details",
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason,
                latencyMs = latencyMs
            )
        )
    }

    fun markGeminiRequestStart(model: String) {
        timeGeminiRequestStart = System.currentTimeMillis()
        log(
            DiagnosticEntry(
                category = "GEMINI",
                action = "GEMINI_REQUEST_START",
                input = "Model: $model",
                verificationResult = "UNVERIFIED"
            )
        )
    }

    fun markGeminiResponseReceived(isSuccess: Boolean) {
        val now = System.currentTimeMillis()
        timeGeminiResponseReceived = now
        val latency = if (timeGeminiRequestStart > 0) now - timeGeminiRequestStart else 0L
        log(
            DiagnosticEntry(
                category = "GEMINI",
                action = "GEMINI_RESPONSE_RECEIVED",
                input = "Duration: ${latency}ms",
                latencyMs = latency,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED"
            )
        )
    }

    fun markActionStart(actionType: String, target: String?) {
        val now = System.currentTimeMillis()
        timeActionStart = now
        val commandToActionStartMs = if (timeSttResult > 0) now - timeSttResult else 0L
        log(
            DiagnosticEntry(
                category = "ACTION_EXECUTION",
                action = "ACTION_START",
                selectedIntent = actionType,
                input = "target=${target.orEmpty()}, cmd->act=${commandToActionStartMs}ms",
                latencyMs = commandToActionStartMs,
                verificationResult = "UNVERIFIED"
            )
        )
    }

    fun markActionVerified(actionType: String, isSuccess: Boolean) {
        val now = System.currentTimeMillis()
        timeActionEnd = now
        val actionLatency = if (timeActionStart > 0) now - timeActionStart else 0L
        log(
            DiagnosticEntry(
                category = "ACTION_EXECUTION",
                action = "ACTION_VERIFIED",
                selectedIntent = actionType,
                latencyMs = actionLatency,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED"
            )
        )
    }

    fun markTtsStart(provider: String) {
        val now = System.currentTimeMillis()
        timeTtsStart = now
        val wakeToResponseMs = if (timeSttStart > 0) now - timeSttStart else 0L
        val speechEndToResponseMs = if (timeSttResult > 0) now - timeSttResult else 0L
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = "TTS_START",
                voiceProvider = provider,
                input = "wake->response=${wakeToResponseMs}ms, speechEnd->response=${speechEndToResponseMs}ms",
                latencyMs = speechEndToResponseMs,
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markTtsStop() {
        val now = System.currentTimeMillis()
        timeTtsEnd = now
        computeAndPublishLatencyBreakdown()
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = "TTS_STOP",
                input = "Speech playback ended/stopped",
                verificationResult = "VERIFIED"
            )
        )
    }

    fun markBargeIn(interruptedSpeechSnippet: String) {
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = "VOICE_BARGE_IN",
                input = "TTS interrupted by user: ${sanitize(interruptedSpeechSnippet)}",
                verificationResult = "VERIFIED"
            )
        )
    }

    private fun computeAndPublishLatencyBreakdown() {
        val sttDuration = if (timeSttStart > 0 && timeSttResult > timeSttStart) timeSttResult - timeSttStart else 0L
        val routerDuration = if (timeRouterStart > 0 && timeRouterResult > timeRouterStart) timeRouterResult - timeRouterStart else 0L
        val geminiDuration = if (timeGeminiRequestStart > 0 && timeGeminiResponseReceived > timeGeminiRequestStart) timeGeminiResponseReceived - timeGeminiRequestStart else 0L
        val actionDuration = if (timeActionStart > 0 && timeActionEnd > timeActionStart) timeActionEnd - timeActionStart else 0L
        val ttsDuration = if (timeTtsStart > 0 && timeTtsEnd > timeTtsStart) timeTtsEnd - timeTtsStart else 0L
        val totalDuration = if (timeSttStart > 0 && timeTtsEnd > timeSttStart) {
            timeTtsEnd - timeSttStart
        } else if (timeSttResult > 0 && timeTtsStart > timeSttResult) {
            timeTtsStart - timeSttResult
        } else 0L

        val breakdown = LatencyBreakdown(
            sttMs = sttDuration,
            routerMs = routerDuration,
            geminiMs = geminiDuration,
            actionMs = actionDuration,
            ttsMs = ttsDuration,
            totalMs = totalDuration
        )
        _lastLatencyBreakdown.value = breakdown
        Log.i(TAG, "Latency Breakdown: STT=${sttDuration}ms, Router=${routerDuration}ms, Gemini=${geminiDuration}ms, Action=${actionDuration}ms, TTS=${ttsDuration}ms, Total=${totalDuration}ms")
    }

    private fun sanitize(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.length > 50) "${trimmed.take(50)}..." else trimmed
    }

    fun log(entry: DiagnosticEntry) {
        Log.d(TAG, "[${entry.category}] action=${entry.action}, verified=${entry.verificationResult}, pkg=${entry.selectedPackage}, latency=${entry.latencyMs}ms, failure=${entry.failureReason}")
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOGS)
        }
    }

    fun logAction(category: String, tag: String, message: String, isSuccess: Boolean) {
        log(
            DiagnosticEntry(
                category = category,
                action = tag,
                input = message,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED"
            )
        )
    }

    fun getWhatsAppStages(): List<DiagnosticEntry> = _logs.value.filter { it.category.startsWith("WHATSAPP") }

    fun getTtsLogs(): List<DiagnosticEntry> = _logs.value.filter { it.category == "VOICE" }

    fun getNotificationLogs(): List<DiagnosticEntry> = _logs.value.filter { it.category == "NOTIFICATION" }

    fun getContactLogs(): List<DiagnosticEntry> = _logs.value.filter { it.category == "CONTACT" }

    fun getConversationLogs(): List<DiagnosticEntry> = _logs.value.filter { it.category == "CONVERSATION" }

    fun logWhatsAppStage(
        stage: String, // NOTIFICATION_RECEIVED, MESSAGE_EXTRACTED, SENDER_RESOLVED, INSTANCE_RESOLVED, REPLY_GENERATED, SEND_ATTEMPT, SEND_SUCCESS, SEND_FAILED
        packageName: String,
        sender: String = "",
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null
    ) {
        _lastWhatsAppStatus.value = "$stage: $packageName ($sender)"
        log(
            DiagnosticEntry(
                category = "WHATSAPP_INCOMING",
                selectedWhatsAppInstance = packageName,
                selectedPackage = packageName,
                action = stage,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason,
                input = "Sender: $sender | $details"
            )
        )
    }

    fun logVoiceStage(
        stage: String, // VOICE_REQUEST, PRIMARY_ENGINE_SELECTED, PRIMARY_REQUEST_START, PRIMARY_REQUEST_RESULT, FALLBACK_TRIGGERED, FALLBACK_ENGINE_SELECTED, AUDIO_STARTED, AUDIO_COMPLETED, AUDIO_FAILED
        engine: String,
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null,
        latencyMs: Long = 0L
    ) {
        log(
            DiagnosticEntry(
                category = "VOICE",
                action = stage,
                voiceProvider = engine,
                input = details,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason,
                latencyMs = latencyMs
            )
        )
    }

    fun logNotificationStage(
        stage: String, // NOTIFICATION_RECEIVED, FILTER_RESULT, MESSAGE_EXTRACTED, ANNOUNCEMENT_CREATED, TTS_REQUESTED, TTS_ENGINE_SELECTED, AUDIO_STARTED, AUDIO_COMPLETED, AUDIO_FAILED
        packageName: String,
        sender: String = "",
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null
    ) {
        log(
            DiagnosticEntry(
                category = "NOTIFICATION",
                action = stage,
                selectedPackage = packageName,
                input = if (sender.isNotBlank()) "Sender: $sender | $details" else details,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason
            )
        )
    }

    fun logTaskStage(
        stage: String, // TASK_CREATED, TASK_STEP_STARTED, TASK_SCREEN_OBSERVED, TASK_TARGET_RESOLVED, TASK_ACTION_EXECUTED, TASK_STEP_VERIFIED, TASK_STEP_FAILED, TASK_RECOVERY_STARTED, TASK_INTERRUPTED, TASK_CANCELLED, TASK_COMPLETED, etc.
        taskId: String = "",
        action: String = "",
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null
    ) {
        // Strict privacy sanitization: never log PINs, passwords, OTPs, auth tokens, CVVs
        val safeDetails = sanitizeForDiagnostics(details)
        val safeFailureReason = failureReason?.let { sanitizeForDiagnostics(it) }

        log(
            DiagnosticEntry(
                category = "AUTONOMOUS_TASK",
                action = stage,
                selectedIntent = action,
                input = if (taskId.isNotBlank()) "[$taskId] $safeDetails" else safeDetails,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = safeFailureReason
            )
        )
    }

    fun sanitizeForDiagnostics(raw: String): String {
        return raw.replace(Regex("(?i)\\b(pin|password|otp|token|cvv|secret|credential)[:\\s]*[A-Za-z0-9@#\$%^&*!]{3,}\\b"), "[REDACTED_CREDENTIAL]")
            .replace(Regex("\\b\\d{4,8}\\b"), "[REDACTED_NUMBER]")
    }

    fun logScreenStage(
        stage: String, // SCREEN_CONTEXT_UPDATED, TARGET_FOUND, TARGET_NOT_FOUND, TARGET_CONFIDENCE, SCREEN_STATE_CHANGED, SCREEN_ANALYSIS_STARTED, SCREEN_ANALYSIS_FAILED, SCREEN_VERIFICATION
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null
    ) {
        log(
            DiagnosticEntry(
                category = "SCREEN_VISION",
                action = stage,
                input = details,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason
            )
        )
    }

    fun logContactStage(
        stage: String, // CONTACT_QUERY, CONTACT_CANDIDATES, CONTACT_NORMALIZED, CONTACT_UNIQUE_TARGETS, CONTACT_SELECTED, CALL_STARTED, CALL_VERIFIED, CALL_FAILED
        details: String = "",
        isSuccess: Boolean = true,
        failureReason: String? = null
    ) {
        log(
            DiagnosticEntry(
                category = "CONTACT",
                action = stage,
                input = details,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED",
                failureReason = failureReason
            )
        )
    }

    fun logConversationStage(
        stage: String, // LISTENING_STARTED, LISTENING_STOPPED, SPEECH_DETECTED, SPEECH_INTERRUPTED_TTS, SESSION_CONTINUED, SESSION_TIMEOUT, WAKE_WORD_MODE, STALE_RESPONSE_CANCELLED
        details: String = "",
        isSuccess: Boolean = true
    ) {
        log(
            DiagnosticEntry(
                category = "CONVERSATION",
                action = stage,
                input = details,
                verificationResult = if (isSuccess) "VERIFIED" else "FAILED"
            )
        )
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
